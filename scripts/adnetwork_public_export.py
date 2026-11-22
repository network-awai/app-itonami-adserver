#!/usr/bin/env python3
"""Write the public, static export of adnetwork-scout's advertiser observations.

Owner decision 2026-11-18 (AWAI-3335): scout's read surface is a static public
export committed to this public repository and read from
raw.githubusercontent.com. No server answers requests for it.

Only these columns of `cloud_itonami.adnetwork_advertiser` are published:
network, advertiser_key, advertiser_name, evidence_url, observed_at, kind.
`notes`, `raw_json`, `record_id`, `ingested_at` and everything the outreach
tick writes stay private. A row that fails a check (an e-mail-like value, a
non-https or credentialed evidence URL, a timestamp without a zone, an
unexpected shape) is withheld, not repaired; the export says how many.

Read-only against the catalog. The token is resolved by the shared
`datalake_catalog` module (env or Keychain) and is never printed.

Needs an interpreter with pyiceberg (the coordinator's scratch venv:
pyiceberg 0.12.0, python3.12). The token must carry
`R2 Data Catalog: Edit` + `Workers R2 Storage: Edit`, stored as env
`CF_CATALOG_TOKEN` -> Keychain `service=gftd.cf` / `account=API_TOKEN`.
Wrangler's OAuth session is NOT enough: it passes `/v1/config` and fails
the storage face with 401, so it is not a substitute.

  python3 scripts/adnetwork_public_export.py --check                     # readiness probe, no catalog read
  python3 scripts/adnetwork_public_export.py --out public/adnetwork-scout
  python3 scripts/adnetwork_public_export.py --rows rows.json --out DIR   # offline
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import os
import re
import subprocess
import sys

MODULE_NAME = "datalake_catalog"

# The shared `datalake_catalog` module lives in the com-junkawasaki/root
# checkout. The current layout is ~/github/repos/com-junkawasaki/root/scripts
# (verified 2026-11-23); the old ~/github/com-junkawasaki/root/scripts is
# kept as a fallback so a host still on that layout keeps working. An
# explicit --root-scripts always wins.
ROOT_SCRIPTS_CANDIDATES = [
    os.path.expanduser("~/github/repos/com-junkawasaki/root/scripts"),
    os.path.expanduser("~/github/com-junkawasaki/root/scripts"),
]

SCHEMA = "awai.adnetwork-scout.public-export.v1"
CATALOG = ("cloud_itonami", "adnetwork_advertiser")
FIELDS = ["network", "advertiserKey", "advertiserName", "evidenceUrl", "observedAt", "kind"]
SOURCE_COLUMNS = {
    "network": "network",
    "advertiserKey": "advertiser_key",
    "advertiserName": "advertiser_name",
    "evidenceUrl": "evidence_url",
    "observedAt": "observed_at",
    "kind": "kind",
}
FILE = "advertisers.v1.json"

NETWORK = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
KEY = re.compile(r"^(?=.{3,253}$)[a-z0-9-]+(\.[a-z0-9-]+)+(/[A-Za-z0-9._~-]+)*$")
KIND = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
URL = re.compile(r"^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[^\s@]*)?$")
STAMP = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$")


def public_row(row: dict) -> dict | None:
    """Project one catalog row to the public shape, or None to withhold it."""
    out = {k: row.get(src) for k, src in SOURCE_COLUMNS.items()}
    for v in out.values():
        if v is not None and not isinstance(v, str):
            return None
        if isinstance(v, str) and "@" in v:
            return None
    if not (out["network"] and NETWORK.match(out["network"])):
        return None
    if not (out["advertiserKey"] and KEY.match(out["advertiserKey"])):
        return None
    if not (out["evidenceUrl"] and URL.match(out["evidenceUrl"])):
        return None
    if not (out["observedAt"] and STAMP.match(out["observedAt"])):
        return None
    if out["kind"] is not None and not KIND.match(out["kind"]):
        return None
    name = out["advertiserName"]
    if name is not None:
        name = name.strip()
        if not name or len(name) > 200 or any(ord(c) < 32 for c in name):
            return None
        out["advertiserName"] = name
    return out


def build(rows: list[dict], snapshot_id: str | None, generated_at: str) -> dict:
    kept, withheld = [], 0
    seen = set()
    for row in rows:
        pub = public_row(row)
        if pub is None:
            withheld += 1
            continue
        ident = tuple(pub[k] or "" for k in FIELDS)
        if ident in seen:
            continue
        seen.add(ident)
        kept.append(pub)
    kept.sort(key=lambda r: (r["network"], r["advertiserKey"], r["kind"] or "", r["evidenceUrl"], r["observedAt"]))
    return {
        "schema": SCHEMA,
        "source": {"profile": "adnetwork-scout",
                   "catalog": ".".join(CATALOG),
                   "snapshotId": snapshot_id},
        "generatedAt": generated_at,
        "fields": FIELDS,
        "count": len(kept),
        "withheld": withheld,
        "advertisers": kept,
    }


def encode(export: dict) -> bytes:
    return (json.dumps(export, ensure_ascii=False, indent=1, sort_keys=False) + "\n").encode()


def write(export: dict, out_dir: str) -> str:
    os.makedirs(out_dir, exist_ok=True)
    body = encode(export)
    digest = hashlib.sha256(body).hexdigest()
    with open(os.path.join(out_dir, FILE), "wb") as f:
        f.write(body)
    with open(os.path.join(out_dir, FILE + ".sha256"), "w") as f:
        f.write(f"{digest}  {FILE}\n")
    return digest


def resolve_root_scripts(explicit: str | None = None, candidates=None) -> str:
    """Pick the directory holding `datalake_catalog.py`.

    An explicit --root-scripts is authoritative. Otherwise the first
    candidate that actually contains the module wins; when no candidate
    does, the first candidate is returned so the import error can name
    the expected location in the current layout.
    """
    if explicit:
        return explicit
    cands = ROOT_SCRIPTS_CANDIDATES if candidates is None else list(candidates)
    for cand in cands:
        if os.path.isfile(os.path.join(cand, MODULE_NAME + ".py")):
            return cand
    return cands[0]


def _missing_module_message(root_scripts: str) -> str:
    expected = os.path.join(root_scripts, MODULE_NAME + ".py")
    return (
        f"missing module '{MODULE_NAME}': expected at {expected}. "
        f"Pass --root-scripts DIR pointing at the directory that contains "
        f"{MODULE_NAME}.py (default, in order: {', '.join(ROOT_SCRIPTS_CANDIDATES)})."
    )


def _load_catalog_module(root_scripts: str):
    """Load datalake_catalog from exactly <root_scripts>/datalake_catalog.py.

    Loaded by file location (not sys.path search) so a long-lived process
    never silently reuses a stale copy from another directory.
    """
    path = os.path.join(root_scripts, MODULE_NAME + ".py")
    if not os.path.isfile(path):
        print(_missing_module_message(root_scripts), file=sys.stderr)
        sys.exit(2)
    spec = importlib.util.spec_from_file_location(MODULE_NAME, path)
    if spec is None or spec.loader is None:  # pragma: no cover - unreadable file
        print(_missing_module_message(root_scripts), file=sys.stderr)
        sys.exit(2)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def catalog_rows(root_scripts: str) -> tuple[list[dict], str | None]:
    mod = _load_catalog_module(root_scripts)
    table = mod.connect().load_table(CATALOG)
    snap = table.current_snapshot()
    return table.scan().to_arrow().to_pylist(), (str(snap.snapshot_id) if snap else None)


def _token_presence() -> str | None:
    """Return the token source name if present, else None. Never returns the value.

    Sources, in order: env CF_CATALOG_TOKEN, then Keychain item existence
    (service=gftd.cf, account=API_TOKEN) checked without reading the value.
    No other fallback (scout stop-rule: on a 403, stop, never try another
    credential).
    """
    if os.environ.get("CF_CATALOG_TOKEN", "").strip():
        return "env:CF_CATALOG_TOKEN"
    try:
        out = subprocess.run(
            ["security", "find-generic-password", "-s", "gftd.cf", "-a", "API_TOKEN"],
            capture_output=True, text=True, timeout=30,
        )
        if out.returncode == 0:
            return "keychain:service=gftd.cf,account=API_TOKEN"
    except Exception:
        pass
    return None


def check_readiness(root_scripts: str) -> int:
    """Readiness probe: module + token presence only. No catalog read, ever.

    Stops at token resolution. While the token is absent it exits 2 and
    states that the catalog read is BLOCKED; it never attempts a table
    read and never prints a token value.
    """
    path = os.path.join(root_scripts, MODULE_NAME + ".py")
    if not os.path.isfile(path):
        print(f"READINESS module=missing expected={path}")
        print("READINESS token=UNKNOWN catalog_read=BLOCKED (module missing)")
        return 2
    # Load the module to prove importability (top-level only; never connect()).
    _load_catalog_module(root_scripts)
    module_state = "found"
    source = _token_presence()
    if source is None:
        print(f"READINESS module={module_state} token=absent source=none catalog_read=BLOCKED")
        print("READINESS blocked: no catalog table read attempted.")
        print("READINESS next_step: set env CF_CATALOG_TOKEN, or store a Cloudflare API token")
        print("  ('R2 Data Catalog: Edit' + 'Workers R2 Storage: Edit') in Keychain")
        print("  service=gftd.cf account=API_TOKEN. Wrangler OAuth is NOT enough.")
        return 2
    print(f"READINESS module={module_state} token=present source={source} catalog_read=NOT_ATTEMPTED")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", help="export directory (not needed with --check)")
    ap.add_argument("--rows", help="offline: a JSON array of catalog rows instead of reading the catalog")
    ap.add_argument(
        "--root-scripts", default=None,
        help=f"directory containing {MODULE_NAME}.py (default: first existing of "
        f"{', '.join(ROOT_SCRIPTS_CANDIDATES)})",
    )
    ap.add_argument(
        "--check", action="store_true",
        help="readiness probe: report module + token presence only; no catalog read",
    )
    args = ap.parse_args(argv)
    root_scripts = resolve_root_scripts(args.root_scripts)
    if args.check:
        return check_readiness(root_scripts)
    if not args.out:
        ap.error("--out is required unless --check is given")
    if args.rows:
        with open(args.rows) as f:
            rows, snap = json.load(f), None
    else:
        rows, snap = catalog_rows(root_scripts)
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    export = build(rows, snap, now)
    digest = write(export, args.out)
    print(f"EXPORT\trows={len(rows)}\tcount={export['count']}\twithheld={export['withheld']}\tsha256={digest}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
