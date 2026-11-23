#!/usr/bin/env bash
# run_export_with_1password.sh -- load tokens into env for ONE child command.
#
# Paths (A) and (B) share this helper; only the catalog-token source differs:
#   (A) OP_SERVICE_ACCOUNT_TOKEN is read from macOS Keychain when the caller
#       has not already set it, so `op read` runs non-interactively.
#   (B) CF_CATALOG_TOKEN is read with `op read` when the caller has not
#       already set it.
# Values are never printed, never echoed, and never written to a file.
# Shell tracing must never be enabled in this script (it would leak values).
#
# Usage (canonical, forwards to the exporter):
#   scripts/run_export_with_1password.sh --check
#   scripts/run_export_with_1password.sh --out DIR        # waits for GO; do not run yet
# Explicit equivalent (any command):
#   scripts/run_export_with_1password.sh -- python3 scripts/adnetwork_public_export.py --check
# With no leading "--", arguments starting with "-" are forwarded to the
# exporter; otherwise the remaining args are executed as the child command.
set -u

OP_REFERENCE="op://gftdcojp/gftd.cloudflare/API_TOKEN"
KEYCHAIN_SERVICE="awai.cf"
KEYCHAIN_ACCOUNT="OP_SERVICE_ACCOUNT_TOKEN"
INJECTED_SA=0
INJECTED_CF=0

cleanup() {
    if [ "$INJECTED_SA" = "1" ]; then
        unset OP_SERVICE_ACCOUNT_TOKEN
    fi
    if [ "$INJECTED_CF" = "1" ]; then
        unset CF_CATALOG_TOKEN
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

usage() {
    printf '%s\n' "usage: $0 --check" >&2
    printf '%s\n' "       $0 --out DIR" >&2
    printf '%s\n' "       $0 -- <command> [args...]" >&2
}

if [ "$#" -eq 0 ]; then
    usage
    exit 2
fi

# --- 1. Service-account token (path A) -----------------------------------
if [ -z "${OP_SERVICE_ACCOUNT_TOKEN:-}" ]; then
    SA_TOKEN="$(security find-generic-password -s "$KEYCHAIN_SERVICE" -a "$KEYCHAIN_ACCOUNT" -w 2>/dev/null)"
    SA_RC=$?
    if [ "$SA_RC" -eq 0 ] && [ -n "$SA_TOKEN" ]; then
        OP_SERVICE_ACCOUNT_TOKEN="$SA_TOKEN"
        export OP_SERVICE_ACCOUNT_TOKEN
        INJECTED_SA=1
        unset SA_TOKEN
    else
        unset SA_TOKEN
    fi
fi

# --- 2. Catalog token (path B reuses this helper) -------------------------
if [ -z "${CF_CATALOG_TOKEN:-}" ]; then
    if ! command -v op >/dev/null 2>&1; then
        printf '%s\n' "op_error: op binary missing: op CLI が見つかりません (PATH を確認してください)" >&2
        exit 2
    fi
    # Combined capture: on success this is the token; on failure it is the
    # error text, which is only classified below and never printed.
    OP_OUT="$(op read "$OP_REFERENCE" 2>&1)"
    OP_RC=$?
    if [ "$OP_RC" -eq 0 ] && [ -n "$OP_OUT" ]; then
        CF_CATALOG_TOKEN="$OP_OUT"
        export CF_CATALOG_TOKEN
        INJECTED_CF=1
        unset OP_OUT
    else
        LOWER_OUT="$(printf '%s' "$OP_OUT" | tr '[:upper:]' '[:lower:]')"
        unset OP_OUT
        case "$LOWER_OUT" in
            *"authorization timeout"*)
                printf '%s\n' "authorization timeout: 承認が必要" >&2
                printf '%s\n' "承認が必要です: unlock 1Password for CLI approval, or set OP_SERVICE_ACCOUNT_TOKEN via Keychain (service=awai.cf account=OP_SERVICE_ACCOUNT_TOKEN) or env for read-only access to vault gftdcojp." >&2
                ;;
            *"item"*"not found"*|*"item"*"does not exist"*|*"does not exist"*"item"*)
                printf '%s\n' "op_error: item not found: ${OP_REFERENCE} が見つかりません" >&2
                ;;
            *"vault"*|*"access"*"denied"*|*"no access"*)
                printf '%s\n' "op_error: vault access denied: vault gftdcojp へのアクセスが不足しています" >&2
                ;;
            *)
                printf '%s\n' "op_error: op read failed: op read ${OP_REFERENCE} failed (see 1Password CLI; token never printed)" >&2
                ;;
        esac
        unset LOWER_OUT
        exit 2
    fi
fi

# --- 3. Exec the child -----------------------------------------------------
# `exec` replaces this process with the child, so injected vars exist only in
# the child's environment and die with it; the trap above unsets them on the
# early exits and signal paths that can still run in this process.
if [ "${1:-}" = "--" ]; then
    shift
    if [ "$#" -eq 0 ]; then
        usage
        exit 2
    fi
    exec "$@"
fi

case "${1:-}" in
    -*)
        SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")" && pwd)"
        exec python3 "$SCRIPT_DIR/adnetwork_public_export.py" "$@"
        ;;
    *)
        exec "$@"
        ;;
esac
