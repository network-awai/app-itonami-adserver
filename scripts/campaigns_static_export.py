#!/usr/bin/env python3
"""Write the static, public campaign list for the adserver Worker.

Owner decision 2026-11-23: the campaign list is published as static files
generated at BUILD time, never request-time dynamic generation in the Worker.
The Worker serves the same embedded content at `GET /campaigns` and
`GET /catalog` (200 + Deprecation/Sunset/Link headers, rework of the 410
pointer) until pullers migrate to the static URLs.

Source of truth: `CAMPAIGNS_JSON` in `wrangler.jsonc` (managed by
cloud-itonami's governor, a different repo). This script parses that same
source, so static and Worker can never diverge by hand.

Outputs (in --out, default `public/`):
  campaigns.json          the registry, public creative + bid + targeting + budget
  campaigns.json.sha256   "<sha256>  campaigns.json\\n"  (content digest)
  catalog.json            count + slots + billing, derived at build time
  catalog.json.sha256     "<sha256>  catalog.json\\n"

Publish convention (same as the scout export): merge = publish. The files are
committed to this public repo and read from raw.githubusercontent.com:

  https://raw.githubusercontent.com/network-awai/app-itonami-adserver/main/public/campaigns.json
  https://raw.githubusercontent.com/network-awai/app-itonami-adserver/main/public/catalog.json

No HTML view: this repo has no static site home (public/ is data-only, read
via raw.githubusercontent.com), so an HTML copy would only go stale. Readers
render from the JSON.

  python3 scripts/campaigns_static_export.py --out public
  python3 scripts/campaigns_static_export.py --rows rows.json --out DIR   # offline fixture
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import sys

SCHEMA = "awai.adserver.campaigns.v1"
CATALOG_SCHEMA = "awai.adserver.catalog.v1"
SOURCE_VAR = "CAMPAIGNS_JSON"
SOURCE_FILE = "wrangler.jsonc"
STATIC_BASE = "https://raw.githubusercontent.com/network-awai/app-itonami-adserver/main/public"
CAMPAIGNS_FILE = "campaigns.json"
CATALOG_FILE = "catalog.json"
BILLING = "USDC on Base via x402 prepaid deposits"
STATUSES = {"active", "paused"}


def _strip_jsonc_comments(text: str) -> str:
    """Remove // comments outside of strings (minimal JSONC)."""
    out, i, n = [], 0, len(text)
    in_str, esc = False, False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
            i += 1
        elif c == '"':
            in_str = True
            out.append(c)
            i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def read_wrangler_registry(path: str) -> tuple[list[dict], str]:
    """Parse CAMPAIGNS_JSON out of wrangler.jsonc. Returns (rows, inputs_sha256)."""
    with open(path) as f:
        raw_text = f.read()
    cfg = json.loads(_strip_jsonc_comments(raw_text))
    raw = cfg.get("vars", {}).get(SOURCE_VAR)
    if not isinstance(raw, str):
        raise ValueError(f"{SOURCE_FILE}: vars.{SOURCE_VAR} missing or not a string")
    rows = json.loads(raw)
    if not isinstance(rows, list):
        raise ValueError(f"{SOURCE_VAR} is not a JSON array")
    inputs_sha256 = hashlib.sha256(raw.encode()).hexdigest()
    return rows, inputs_sha256


def public_row(row: dict) -> dict | None:
    """Project one registry row to the public shape, or None to withhold it."""
    if not isinstance(row, dict):
        return None
    for v in row.values():
        if isinstance(v, str) and "@" in v:
            return None
    cid = row.get("id")
    if not isinstance(cid, str) or not cid.strip():
        return None
    status = row.get("status")
    if status is not None and status not in STATUSES:
        return None
    for key in ("bid", "creative", "targeting", "budget"):
        val = row.get(key)
        if val is not None and not isinstance(val, dict):
            return None
    return row


def build(rows: list[dict], generated_at: str, inputs_sha256: str) -> dict:
    kept, withheld = [], 0
    seen = set()
    for row in rows:
        pub = public_row(row)
        if pub is None:
            withheld += 1
            continue
        cid = pub["id"]
        if cid in seen:
            continue
        seen.add(cid)
        kept.append(pub)
    kept.sort(key=lambda r: r["id"])
    return {
        "schema": SCHEMA,
        "source": {"file": SOURCE_FILE, "var": SOURCE_VAR,
                   "inputsSha256": inputs_sha256},
        "generatedAt": generated_at,
        "count": len(kept),
        "withheld": withheld,
        "campaigns": kept,
    }


def build_catalog(export: dict, generated_at: str) -> dict:
    slots = sorted({
        p for c in export["campaigns"]
        for p in (c.get("targeting") or {}).get("placements") or []
    })
    return {
        "schema": CATALOG_SCHEMA,
        "source": {"file": SOURCE_FILE, "var": SOURCE_VAR,
                   "inputsSha256": export["source"]["inputsSha256"]},
        "generatedAt": generated_at,
        "campaigns": export["count"],
        "slots": slots,
        "billing": BILLING,
        "registry": f"{STATIC_BASE}/{CAMPAIGNS_FILE}",
        "campaignsUrl": f"{STATIC_BASE}/{CAMPAIGNS_FILE}",
        "catalogUrl": f"{STATIC_BASE}/{CATALOG_FILE}",
    }


def encode(export: dict) -> bytes:
    return (json.dumps(export, ensure_ascii=False, indent=1, sort_keys=False) + "\n").encode()


def _write_file(export: dict, out_dir: str, filename: str) -> str:
    body = encode(export)
    digest = hashlib.sha256(body).hexdigest()
    with open(os.path.join(out_dir, filename), "wb") as f:
        f.write(body)
    with open(os.path.join(out_dir, filename + ".sha256"), "w") as f:
        f.write(f"{digest}  {filename}\n")
    return digest


def write(export: dict, catalog: dict, out_dir: str) -> tuple[str, str]:
    os.makedirs(out_dir, exist_ok=True)
    d1 = _write_file(export, out_dir, CAMPAIGNS_FILE)
    d2 = _write_file(catalog, out_dir, CATALOG_FILE)
    return d1, d2


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="public")
    ap.add_argument("--wrangler", default=SOURCE_FILE)
    ap.add_argument("--rows", help="offline: a JSON array of registry rows instead of wrangler.jsonc")
    ap.add_argument("--generated-at", help="pin generatedAt (deterministic tests)")
    args = ap.parse_args(argv)
    if args.rows:
        with open(args.rows, "rb") as f:
            raw_bytes = f.read()
        rows = json.loads(raw_bytes.decode())
        if not isinstance(rows, list):
            print("REFUSED: --rows must be a JSON array", file=sys.stderr)
            return 2
        inputs_sha256 = hashlib.sha256(raw_bytes).hexdigest()
        source_note = f"rows fixture {args.rows}"
    else:
        try:
            rows, inputs_sha256 = read_wrangler_registry(args.wrangler)
        except (OSError, ValueError, json.JSONDecodeError) as e:
            print(f"REFUSED: cannot read registry from {args.wrangler}: {e}", file=sys.stderr)
            return 2
        source_note = f"{args.wrangler} vars.{SOURCE_VAR}"
    now = args.generated_at or dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    export = build(rows, now, inputs_sha256)
    catalog = build_catalog(export, now)
    d1, d2 = write(export, catalog, args.out)
    print(f"EXPORT\trows={len(rows)}\tcount={export['count']}\twithheld={export['withheld']}"
          f"\tcampaigns_sha256={d1}\tcatalog_sha256={d2}\tgeneratedAt={now}\tsource={source_note}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
