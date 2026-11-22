# Koukoku

Follow the workspace AGENTS.md, including the Kotoba end-user apps contract.

- The common domain is `.cljc` (`src/koukoku/domain.cljc`), and the UI is CLJS through Squint with the pinned `@awai/ui`. Tauri v3 is a thin host. Do not fork the UI components or the consent logic.
- Every external request goes through the canonical client runtime broker with `each-operation` consent. Add a route only by declaring it in `koukoku.domain/operations`. Keep the CSP `connect-src` equal to `clientRuntimeConnectSrc` of the descriptor.
- The funding verdict stays fail-closed: an unmeasured or stale inventory is never read as "no campaigns", and a shape that cannot be verified is refused rather than shown.
- `profiles/paired-profile.json` is a copy of `../hermes/profiles/murakumo-adnetwork`. Regenerate it from those files and update the recorded revision and digests; do not edit fields by hand.
- The app does not submit ads, serve, bill, launch or reconfigure the bot.
- On-device recording (AWAI-420, owner approval 2026-11-23): four browser-action operations (`record-application`, `set-review-state`, `record-receipt`, `record-ledger-event`) write to local-state (`localstorage`, schema `itonami.koukoku.local.v1` v2). Each write opens its own approval dialog and touches no network. Receipt and deposit entries keep issuance metadata only; nothing is submitted, billed or settled, and no currency is fixed (AWAI-323 still undecided). The review state machine is the fixed enum `received/under-review/approved/rejected` with guarded transitions (`koukoku.domain/review-transitions`); there is no free-text status. The Murakumo-side review bot (AWAI-495) is not part of this app.
- Keep source, build, device, distribution and live-operation evidence separate, and record unknowns as unknown.
