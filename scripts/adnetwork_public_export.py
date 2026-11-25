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
1Password is a third source: `op read "op://gftdcojp/gftd.cloudflare/API_TOKEN"`
injected at runtime as `CF_CATALOG_TOKEN=$(op read ...)`. The token value is
never printed, echoed, written to a file, or put in an exception message.

Needs an interpreter with pyiceberg (the coordinator's scratch venv:
pyiceberg 0.12.0, python3.12). The token must carry
`R2 Data Catalog: Edit` + `Workers R2 Storage: Edit`, stored as env
`CF_CATALOG_TOKEN` -> Keychain `service=awai.cf` / `account=CLOUDFLARE_CATALOG_TOKEN`
-> 1Password `op://gftdcojp/gftd.cloudflare/API_TOKEN`.
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
import urllib.error
import urllib.request

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

# 1Password item: title `gftd.cloudflare/API_TOKEN`, vault `gftdcojp`.
OP_REFERENCE = "op://gftdcojp/gftd.cloudflare/API_TOKEN"
KEYCHAIN_SERVICE = "awai.cf"
KEYCHAIN_ACCOUNT = "CLOUDFLARE_CATALOG_TOKEN"

REQUIRED_PERMISSIONS = ("R2 Data Catalog: Edit", "Workers R2 Storage: Edit")
VERIFY_URL = "https://api.cloudflare.com/client/v4/user/tokens/verify"
TOKEN_DETAIL_URL = "https://api.cloudflare.com/client/v4/user/tokens/{token_id}"

AUTH_TIMEOUT_MESSAGE = (
    "READINESS authorization_timeout: 承認が必要 "
    "(authorization timeout: 承認が必要 — "
    "1Password CLI の承認・ロック解除が必要です。"
    "サービスアカウント OP_SERVICE_ACCOUNT_TOKEN の利用を推奨します)"
)

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


def _keychain_token() -> str | None:
    """Read the Keychain token (never printed). None when absent."""
    try:
        out = subprocess.run(
            ["security", "find-generic-password", "-s", KEYCHAIN_SERVICE, "-a", KEYCHAIN_ACCOUNT, "-w"],
            capture_output=True, text=True, timeout=30,
        )
        if out.returncode == 0:
            tok = (out.stdout or "").strip()
            if tok:
                return tok
    except Exception:
        pass
    return None


def _op_token() -> tuple[str | None, str | None]:
    """Read the 1Password item via `op read`. Returns (token, error_kind).

    error_kind is None on success, else one of: authorization_timeout,
    op_missing, op_item_not_found, op_vault_access, op_error. The token
    value is never included in error text and never printed.
    """
    try:
        out = subprocess.run(
            ["op", "read", OP_REFERENCE],
            capture_output=True, text=True, timeout=30,
        )
    except FileNotFoundError:
        return None, "op_missing"
    except OSError:
        return None, "op_missing"
    except Exception:
        return None, "op_error"
    if out.returncode == 0:
        tok = (out.stdout or "").strip()
        if tok:
            return tok, None
        return None, "op_error"
    combined = f"{out.stdout or ''}\n{out.stderr or ''}".lower()
    if "authorization timeout" in combined:
        return None, "authorization_timeout"
    if "item" in combined and ("not found" in combined or "does not exist" in combined):
        return None, "op_item_not_found"
    if "vault" in combined or ("access" in combined and ("denied" in combined or "no access" in combined)):
        return None, "op_vault_access"
    return None, "op_error"


def _resolve_token() -> tuple[str | None, str | None, str | None]:
    """Resolve the catalog token: env -> Keychain -w -> 1Password.

    Returns (token, source, error_kind). `token` and `source` are None
    when no source yields a value; `error_kind` then names the 1Password
    failure (or is None when 1Password was never reached). The token
    value must never be printed by callers.
    """
    env_tok = os.environ.get("CF_CATALOG_TOKEN", "").strip()
    if env_tok:
        return env_tok, "env:CF_CATALOG_TOKEN", None
    kc = _keychain_token()
    if kc:
        return kc, f"keychain:service={KEYCHAIN_SERVICE},account={KEYCHAIN_ACCOUNT}", None
    op_tok, err = _op_token()
    if op_tok:
        return op_tok, f"1password:{OP_REFERENCE}", None
    if err == "authorization_timeout":
        return None, "1password:authorization_timeout", err
    if err in ("op_missing", "op_item_not_found", "op_vault_access", "op_error"):
        return None, f"1password:{err}", err
    return None, None, err


def catalog_rows(root_scripts: str) -> tuple[list[dict], str | None]:
    mod = _load_catalog_module(root_scripts)
    token, _source, err_kind = _resolve_token()
    if token is None:
        if err_kind == "authorization_timeout":
            print("READINESS token=absent source=1password:authorization_timeout catalog_read=BLOCKED", file=sys.stderr)
            print(AUTH_TIMEOUT_MESSAGE, file=sys.stderr)
        elif err_kind == "op_missing":
            print("READINESS token=absent source=1password:op_missing catalog_read=BLOCKED", file=sys.stderr)
            print("READINESS op_error: op binary missing: op CLI が見つかりません (PATH を確認してください)", file=sys.stderr)
        elif err_kind == "op_item_not_found":
            print("READINESS token=absent source=1password:op_item_not_found catalog_read=BLOCKED", file=sys.stderr)
            print(f"READINESS op_error: item not found: {OP_REFERENCE} が見つかりません", file=sys.stderr)
        elif err_kind == "op_vault_access":
            print("READINESS token=absent source=1password:op_vault_access catalog_read=BLOCKED", file=sys.stderr)
            print("READINESS op_error: vault access denied: vault gftdcojp へのアクセスが不足しています", file=sys.stderr)
        else:
            print("READINESS token=absent source=none catalog_read=BLOCKED", file=sys.stderr)
            print("READINESS blocked: no catalog table read attempted.", file=sys.stderr)
            print("READINESS next_step: set env CF_CATALOG_TOKEN, or store a Cloudflare API token", file=sys.stderr)
            print("  ('R2 Data Catalog: Edit' + 'Workers R2 Storage: Edit') in Keychain", file=sys.stderr)
            print(f"  service={KEYCHAIN_SERVICE} account={KEYCHAIN_ACCOUNT}, or use 1Password {OP_REFERENCE}", file=sys.stderr)
            print("  with OP_SERVICE_ACCOUNT_TOKEN set. Wrangler OAuth is NOT enough.", file=sys.stderr)
        sys.exit(2)
    table = mod.connect(token=token).load_table(CATALOG)
    snap = table.current_snapshot()
    return table.scan().to_arrow().to_pylist(), (str(snap.snapshot_id) if snap else None)


def _token_presence() -> str | None:
    """Return the token source name if present, else None. Never returns the value.

    Sources, in order: env CF_CATALOG_TOKEN, then Keychain (service=awai.cf,
    account=CLOUDFLARE_CATALOG_TOKEN, read with -w), then 1Password `op read` of the
    configured reference. No other fallback (scout stop-rule: on a 403,
    stop, never try another credential).
    """
    token, source, _err = _resolve_token()
    if token:
        return source
    return None


def _scrub(value: str, token: str) -> str:
    if token and token in value:
        return value.replace(token, "[REDACTED]")
    return value


def _http_get_json(url: str, token: str) -> tuple[int | None, dict | None, str | None]:
    """GET `url` with `Authorization: Bearer <token>`; stdlib only.

    Returns (http_status, parsed_json_or_None, raw_text_or_None). On a
    network error http_status is None and raw_text carries a sanitized
    reason (token scrubbed). Never raises: callers decide BLOCKED.
    """
    req = urllib.request.Request(
        url,
        headers={"Authorization": f"Bearer {token}", "Accept": "application/json"},
        method="GET",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            status = getattr(resp, "status", None)
            if status is None:
                try:
                    status = resp.getcode()
                except Exception:
                    status = None
            raw = resp.read()
            if isinstance(raw, bytes):
                text = raw.decode("utf-8", errors="replace")
            else:
                text = str(raw)
            try:
                data = json.loads(text) if text.strip() else None
            except Exception:
                data = None
            try:
                status_int = int(status) if status is not None else None
            except Exception:
                status_int = None
            return status_int, data if isinstance(data, dict) else None, _scrub(text, token)
    except urllib.error.HTTPError as e:
        try:
            raw = e.read()
        except Exception:
            raw = b""
        if isinstance(raw, bytes):
            text = raw.decode("utf-8", errors="replace")
        else:
            text = str(raw or "")
        try:
            data = json.loads(text) if text.strip() else None
        except Exception:
            data = None
        return e.code, data if isinstance(data, dict) else None, _scrub(text, token)
    except Exception as e:  # network error / timeout / DNS
        reason = getattr(e, "reason", e)
        msg = str(reason)
        return None, None, _scrub(msg, token)


def _api_lines(data: dict | None) -> list[str]:
    """Format API errors/messages verbatim as code=... message=... lines."""
    lines: list[str] = []
    if not isinstance(data, dict):
        return lines
    for err in data.get("errors") or []:
        if isinstance(err, dict):
            lines.append(f"code={err.get('code')} message={err.get('message')}")
        else:
            lines.append(str(err))
    for msg in data.get("messages") or []:
        if isinstance(msg, dict):
            lines.append(f"code={msg.get('code')} message={msg.get('message')}")
        else:
            lines.append(str(msg))
    return lines


def _print_next_step_recreate() -> None:
    print(f"READINESS required_permissions={list(REQUIRED_PERMISSIONS)}")
    print("READINESS next_step: owner recreates the Cloudflare API token with")
    print("  'R2 Data Catalog: Edit' + 'Workers R2 Storage: Edit' and stores it in")
    print(f"  env CF_CATALOG_TOKEN, Keychain service={KEYCHAIN_SERVICE} account={KEYCHAIN_ACCOUNT},")
    print(f"  or 1Password {OP_REFERENCE}. Wrangler OAuth is NOT enough (metadata-only, fails storage face with 401).")


def check_readiness(root_scripts: str) -> int:
    """Readiness probe: module + token presence + token verify. No catalog read, ever.

    Token resolution order: env CF_CATALOG_TOKEN -> Keychain -w ->
    1Password `op read`. When a token is present, `--check` verifies it
    via `GET /user/tokens/verify` (Bearer, token never printed) and,
    when verify yields an id, via `GET /user/tokens/{id}` to list
    `permission_groups[].name` and diff against the two required names.
    Never attempts a table read and never prints a token value.
    """
    path = os.path.join(root_scripts, MODULE_NAME + ".py")
    if not os.path.isfile(path):
        print(f"READINESS module=missing expected={path}")
        print("READINESS token=UNKNOWN catalog_read=BLOCKED (module missing)")
        return 2
    # Load the module to prove importability (top-level only; never connect()).
    _load_catalog_module(root_scripts)
    module_state = "found"
    token, source, err_kind = _resolve_token()
    if token is None:
        if err_kind == "authorization_timeout":
            print(f"READINESS module={module_state} token=absent source=1password:authorization_timeout catalog_read=BLOCKED")
            print(AUTH_TIMEOUT_MESSAGE)
            print("READINESS next_step: unlock 1Password for CLI approval, or set OP_SERVICE_ACCOUNT_TOKEN")
            print("  (Keychain service=awai.cf account=OP_SERVICE_ACCOUNT_TOKEN, or env)")
            print(f"  for read-only access to vault gftdcojp, then re-run with CF_CATALOG_TOKEN=$(op read \"{OP_REFERENCE}\")")
            return 2
        if err_kind == "op_missing":
            print(f"READINESS module={module_state} token=absent source=1password:op_missing catalog_read=BLOCKED")
            print("READINESS op_error: op binary missing: op CLI が見つかりません (PATH を確認してください)")
            print("READINESS next_step: install 1Password CLI, or set env CF_CATALOG_TOKEN / Keychain instead")
            return 2
        if err_kind == "op_item_not_found":
            print(f"READINESS module={module_state} token=absent source=1password:op_item_not_found catalog_read=BLOCKED")
            print(f"READINESS op_error: item not found: {OP_REFERENCE} が見つかりません")
            print(f"READINESS next_step: check vault gftdcojp / item gftd.cloudflare/API_TOKEN, or set env CF_CATALOG_TOKEN / Keychain instead")
            return 2
        if err_kind == "op_vault_access":
            print(f"READINESS module={module_state} token=absent source=1password:op_vault_access catalog_read=BLOCKED")
            print("READINESS op_error: vault access denied: vault gftdcojp へのアクセスが不足しています")
            print("READINESS next_step: grant the service account read-only access to vault gftdcojp via OP_SERVICE_ACCOUNT_TOKEN (Keychain service=awai.cf account=OP_SERVICE_ACCOUNT_TOKEN), or set env CF_CATALOG_TOKEN / Keychain service=awai.cf account=CLOUDFLARE_CATALOG_TOKEN instead")
            return 2
        # Generic absence (no usable source; op_error or never reached).
        disp_source = source or "none"
        print(f"READINESS module={module_state} token=absent source={disp_source} catalog_read=BLOCKED")
        print("READINESS blocked: no catalog table read attempted.")
        print("READINESS next_step: set env CF_CATALOG_TOKEN, or store a Cloudflare API token")
        print("  ('R2 Data Catalog: Edit' + 'Workers R2 Storage: Edit') in Keychain")
        print(f"  service={KEYCHAIN_SERVICE} account={KEYCHAIN_ACCOUNT}, or 1Password {OP_REFERENCE}")
        print("  with OP_SERVICE_ACCOUNT_TOKEN set. Wrangler OAuth is NOT enough.")
        return 2

    print(f"READINESS module={module_state} token=present source={source}")

    # --- verify the token (never printed) ---
    status, data, raw = _http_get_json(VERIFY_URL, token)
    result = data.get("result") if isinstance(data, dict) else None
    verify_success = data.get("success") if isinstance(data, dict) else None
    verify_status = result.get("status") if isinstance(result, dict) else None
    if status is None:
        print("READINESS verify_status=unknown verify_success=false catalog_read=BLOCKED")
        print(f"READINESS verify_error: {raw or 'network error'}")
        _print_next_step_recreate()
        return 2
    success_str = "true" if verify_success is True else "false"
    disp_status = verify_status or "unknown"
    if not (200 <= status < 300) or verify_success is not True or verify_status != "active":
        print(f"READINESS verify_status={disp_status} verify_success={success_str} catalog_read=BLOCKED")
        api_lines = _api_lines(data)
        if api_lines:
            for line in api_lines:
                print(f"READINESS api_error: {_scrub(line, token)}")
        elif raw and raw.strip():
            print(f"READINESS api_error: {_scrub(raw.strip(), token)}")
        else:
            print(f"READINESS api_error: http_status={status}")
        _print_next_step_recreate()
        return 2

    print(f"READINESS verify_status={verify_status} verify_success=true")

    # --- permission detail via /user/tokens/{id} (names only, never the token) ---
    token_id = result.get("id") if isinstance(result, dict) else None
    if not token_id:
        print("READINESS permissions_unknown=true catalog_read=BLOCKED")
        _print_next_step_recreate()
        return 2
    d_status, d_data, d_raw = _http_get_json(TOKEN_DETAIL_URL.format(token_id=token_id), token)
    if d_status is None:
        print("READINESS permissions_detail_unavailable=true catalog_read=BLOCKED")
        print(f"READINESS detail_error: {d_raw or 'network error'}")
        _print_next_step_recreate()
        return 2
    if not (200 <= d_status < 300) or not isinstance(d_data, dict) or d_data.get("success") is not True:
        print("READINESS permissions_detail_unavailable=true catalog_read=BLOCKED")
        api_lines = _api_lines(d_data)
        if api_lines:
            for line in api_lines:
                print(f"READINESS api_error: {_scrub(line, token)}")
        elif d_raw and d_raw.strip():
            print(f"READINESS api_error: {_scrub(d_raw.strip(), token)}")
        else:
            print(f"READINESS api_error: http_status={d_status}")
        _print_next_step_recreate()
        return 2
    d_result = d_data.get("result") or {}
    groups = d_result.get("permission_groups") or [] if isinstance(d_result, dict) else []
    names = [g.get("name") for g in groups if isinstance(g, dict) and g.get("name")]
    missing = [p for p in REQUIRED_PERMISSIONS if p not in names]
    if missing:
        print(f"READINESS permissions_missing={missing} catalog_read=BLOCKED")
        print(f"READINESS required_permissions={list(REQUIRED_PERMISSIONS)}")
        print(f"READINESS permission_groups={names}")
        print("READINESS next_step: owner recreates the Cloudflare API token with both required permissions.")
        print("  If absent the catalog still 403s. Wrangler OAuth is NOT enough.")
        return 2
    print("READINESS permissions_ok=true catalog_read=NOT_ATTEMPTED")
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
