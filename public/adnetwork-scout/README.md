# adnetwork-scout public export

`advertisers.v1.json` (with `advertisers.v1.json.sha256`) is the static, public read surface of the `adnetwork-scout` bot (owner decision 2026-11-18, AWAI-3335). Koukoku Scout (`awai-app-scout/`) reads it from `https://raw.githubusercontent.com/network-awai/app-itonami-adserver/main/public/adnetwork-scout/advertisers.v1.json`.

**Not published yet.** The first export is blocked: the catalog token returned 403 on 2026-11-18 (AWAI-3341), and the renewed token is not stored on this host yet. Run the readiness probe below before attempting the export — it fails closed while the token is absent.

Public columns are only `network`, `advertiserKey`, `advertiserName`, `evidenceUrl`, `observedAt` and `kind`. `notes`, `raw_json`, `record_id`, `ingested_at` and outreach records stay private. Rows that fail the checks are withheld and counted in `withheld`.

## Prerequisites

1. **A venv with pyiceberg** (the exporter reads the catalog through it). Example used by the coordinator: pyiceberg 0.12.0, python3.12. Reuse an existing scratch venv; do not commit anything from it. The offline `--rows` mode needs no venv.
2. **The shared `datalake_catalog` module** from the com-junkawasaki/root checkout. Current layout: `~/github/repos/com-junkawasaki/root/scripts` (the old `~/github/com-junkawasaki/root/scripts` still works as a fallback; pass `--root-scripts DIR` to override).
3. **A Cloudflare API token** with `R2 Data Catalog: Edit` + `Workers R2 Storage: Edit`, stored in exactly one of these two places (resolved in this order):
   - env var `CF_CATALOG_TOKEN`, or
   - macOS Keychain item `service=gftd.cf` / `account=API_TOKEN`.

   Wrangler's OAuth session is **not** enough: it passes `GET /v1/config` but fails the storage face with 401, so the exporter never falls back to it. The token is never printed.

## Readiness (run before every export)

```bash
python3 scripts/adnetwork_public_export.py --check
```

Reports module + token presence only (names only, never values) and never touches the catalog: while the token is absent it exits 2 and states `catalog_read=BLOCKED`. Stop there — on a 403 or a missing token, do not try another credential (the scout stop-rule); store the renewed token first, then re-run.

## Publish (a reviewed PR, never an automatic push)

```bash
python3 scripts/adnetwork_public_export.py --out public/adnetwork-scout   # needs pyiceberg + the catalog token above
git diff --stat public/adnetwork-scout                                    # review the rows before committing
python3 scripts/adnetwork_public_export.py --rows rows.json --out DIR     # offline: no catalog needed
```

Open a PR with the two files, review the rows (in particular, no e-mail-like values), and merge. Merging to `main` publishes them.
