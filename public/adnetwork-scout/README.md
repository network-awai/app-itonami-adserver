# adnetwork-scout public export

`advertisers.v1.json` (with `advertisers.v1.json.sha256`) is the static, public read surface of the `adnetwork-scout` bot (owner decision 2026-11-18, AWAI-3335). Koukoku Scout (`awai-app-scout/`) reads it from `https://raw.githubusercontent.com/network-awai/app-itonami-adserver/main/public/adnetwork-scout/advertisers.v1.json`.

**Not published yet.** The first export is blocked: the catalog token returned 403 on 2026-11-18 (AWAI-3341), and the renewed token is not stored on this host yet. Run the readiness probe below before attempting the export — it fails closed while the token is absent.

Public columns are only `network`, `advertiserKey`, `advertiserName`, `evidenceUrl`, `observedAt` and `kind`. `notes`, `raw_json`, `record_id`, `ingested_at` and outreach records stay private. Rows that fail the checks are withheld and counted in `withheld`.

## Prerequisites

1. **A venv with pyiceberg** (the exporter reads the catalog through it). Example used by the coordinator: pyiceberg 0.12.0, python3.12. Reuse an existing scratch venv; do not commit anything from it. The offline `--rows` mode needs no venv.
2. **The shared `datalake_catalog` module** from the com-junkawasaki/root checkout. Current layout: `~/github/repos/com-junkawasaki/root/scripts` (the old `~/github/com-junkawasaki/root/scripts` still works as a fallback; pass `--root-scripts DIR` to override).
3. **A Cloudflare API token** with `R2 Data Catalog: Edit` + `Workers R2 Storage: Edit`, stored in exactly one of these three places (resolved in this order):
   - env var `CF_CATALOG_TOKEN`, or
   - macOS Keychain item `service=gftd.cf` / `account=API_TOKEN` (read with `security find-generic-password ... -w`), or
   - 1Password item `gftd.cloudflare/API_TOKEN` in vault `gftdcojp`, read at runtime with `op read "op://gftdcojp/gftd.cloudflare/API_TOKEN"`.
   - There is no other fallback. If the catalog returns 403, stop — do not try another credential (the scout stop-rule); the owner recreates the token with both required permissions instead.

   Wrangler's OAuth session is **not** enough — never use it as a fallback: it passes `GET /v1/config` but fails the storage face with 401, so the exporter never falls back to it. The token is never printed.

## 1Password injection (no leakage)

The token is injected **at runtime only**, as an env var for that one command. Never `echo` it, never write it to a file, never include it in an exception or log line, and never enable `set -x` (a shell wrapper must not enable `set -x` either, or the value leaks into the trace output).

```bash
# Per-run approval: unlock 1Password for the CLI, then one command with the token in env.
CF_CATALOG_TOKEN=$(op read "op://gftdcojp/gftd.cloudflare/API_TOKEN") \
  python3 scripts/adnetwork_public_export.py --check

CF_CATALOG_TOKEN=$(op read "op://gftdcojp/gftd.cloudflare/API_TOKEN") \
  python3 scripts/adnetwork_public_export.py --out public/adnetwork-scout
```

The prefix assignment `VAR=$(op read ...)` sets the variable only for that command — the value is never echoed and never exported to the surrounding shell. Do not replace this with `export` followed by `echo "$CF_CATALOG_TOKEN"`.

Two approval paths exist for `op read`:

- **(a) Per-run unlock + CLI approval** — with the 1Password app unlocked, each non-interactive `op read` may still require approval. If the CLI cannot get it, it fails with `authorization timeout` (e.g. `error initializing client: authorization timeout`).
- **(b) Service account (recommended for automation)** — preset a service-account token for **read-only access to vault `gftdcojp`** and export it before the `op read`:

```bash
export OP_SERVICE_ACCOUNT_TOKEN=...  # service account with read-only vault gftdcojp access
CF_CATALOG_TOKEN=$(op read "op://gftdcojp/gftd.cloudflare/API_TOKEN") \
  python3 scripts/adnetwork_public_export.py --check
```

`OP_SERVICE_ACCOUNT_TOKEN` **must be set in the environment before `op read`** for the non-interactive path; the exporter itself never creates one and never falls back to wrangler OAuth.

### Keychain → env helper: `scripts/run_export_with_1password.sh`

The helper reads both tokens into the environment for **one child command only** and never prints, echoes, or writes them to a file. Paths (A) and (B) use the same helper; only the catalog-token source differs (`op read`).

Store the service-account token in Keychain under exactly this selector — any other service/account pair reads as absent:

```bash
# service=gftd.cf, account=OP_SERVICE_ACCOUNT_TOKEN
# The value is entered at the prompt; never put it on the command line.
security add-generic-password -s gftd.cf -a OP_SERVICE_ACCOUNT_TOKEN -w
# Overwrite an existing entry:
security add-generic-password -U -s gftd.cf -a OP_SERVICE_ACCOUNT_TOKEN -w
```

The helper reads it with `security find-generic-password -s gftd.cf -a OP_SERVICE_ACCOUNT_TOKEN -w` (only when `OP_SERVICE_ACCOUNT_TOKEN` is not already set in the caller's env — a preset is never overridden), reads the catalog token with `op read "op://gftdcojp/gftd.cloudflare/API_TOKEN"` into `CF_CATALOG_TOKEN` (only when not already set), and `exec`s the child with both in its environment. It traps `EXIT`/`INT`/`TERM` to `unset` any vars it injected, and the `exec` means the injected values exist only in the child process and die with it — nothing persists in the invoking shell after exit.

```bash
# (A) service-account token from Keychain + (B) catalog token via op read:
scripts/run_export_with_1password.sh --check
scripts/run_export_with_1password.sh --out public/adnetwork-scout   # waits for GO; do not run the real export yet
# Explicit equivalent (any command after --):
scripts/run_export_with_1password.sh -- python3 scripts/adnetwork_public_export.py --check
```

The real export (`--out`) waits for Organizer GO after the owner picks (A)/(B); until then this is code-prep only. If `op read` fails with `authorization timeout`, the helper prints `authorization timeout: 承認が必要` to stderr plus the service-account hint (unlock 1Password for CLI approval, or set `OP_SERVICE_ACCOUNT_TOKEN` via Keychain/env) and exits 2 without printing any value. Other `op` failures get distinct messages naming the cause (binary missing, item not found, vault access denied), still without the value. Stop-rule unchanged: never try another credential on 403, never fall back to wrangler OAuth.

Token-read failures report the cause without ever printing the value:

- `authorization timeout` → `--check` prints `READINESS token=absent source=1password:authorization_timeout catalog_read=BLOCKED` plus `READINESS authorization_timeout: 承認が必要` (1Password CLI の承認・ロック解除が必要です。サービスアカウント OP_SERVICE_ACCOUNT_TOKEN の利用を推奨します) and exits 2. The export path uses the same message.
- Other `op` failures get distinct messages naming the cause: binary missing (`source=1password:op_missing`, `op binary missing`), item not found (`source=1password:op_item_not_found`), and no vault access (`source=1password:op_vault_access`).

## Readiness (run before every export)

```bash
python3 scripts/adnetwork_public_export.py --check
```

Reports module + token presence only (names only, never values) and never touches the catalog **table**: `--check` never reads a table. While the token is absent it exits 2 and states `catalog_read=BLOCKED`. Stop there — on a 403 or a missing token, do not try another credential (the scout stop-rule); store the renewed token first, then re-run.

After the token is present, `--check` verifies it against the Cloudflare API (stdlib `urllib`, 30 s timeout, `Authorization: Bearer <token>`, token never printed):

1. `GET https://api.cloudflare.com/client/v4/user/tokens/verify` → prints `READINESS verify_status=... verify_success=...`. On any non-2xx, `success=false`, or `status != active`, it prints the API `errors`/`messages` verbatim (code + message, no token) and states `catalog_read=BLOCKED`, then exits 2.
2. When verify succeeds, `GET https://api.cloudflare.com/client/v4/user/tokens/{id}` (id from verify `result.id`) lists `permission_groups[].name` (names only, never the token) and diffs them against the two required names (`R2 Data Catalog: Edit`, `Workers R2 Storage: Edit`). It prints `READINESS permissions_ok=true` or `READINESS permissions_missing=[...]` with the full required list. If the detail fetch 403s, it prints that API error verbatim and blocks; if the detail fetch is otherwise unavailable, it still prints the verify status, the required list, and the next step: **owner recreates the token with both required permissions** (if a permission is absent the catalog still 403s). Wrangler OAuth is never a fallback.

A green `--check` prints `verify_status=active`, `verify_success=true`, `permissions_ok=true`, `catalog_read=NOT_ATTEMPTED` and exits 0.

## Publish (a reviewed PR, never an automatic push)

```bash
python3 scripts/adnetwork_public_export.py --out public/adnetwork-scout   # needs pyiceberg + the catalog token above
git diff --stat public/adnetwork-scout                                    # review the rows before committing
python3 scripts/adnetwork_public_export.py --rows rows.json --out DIR     # offline: no catalog needed
```

Open a PR with the two files, review the rows (in particular, no e-mail-like values), and merge. Merging to `main` publishes them.
