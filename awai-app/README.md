# Koukoku

Koukoku is an Awai app (Tauri v3, Android first) paired one-to-one with the `murakumo-adnetwork` bot profile in this repository. The bot measures murakumo.cloud's first-party ad network and proposes one bounded improvement per tick. The app lets a person check, on their own device, whether that ad network is actually funded by advertisers.

Tracking: [AWAI-3260](https://linear.app/kotobalabs/issue/AWAI-3260) (this unit), parent [AWAI-3241](https://linear.app/kotobalabs/issue/AWAI-3241), plan [AWAI-420](https://linear.app/kotobalabs/issue/AWAI-420). The layout follows Rodo ([AWAI-3146](https://linear.app/kotobalabs/issue/AWAI-3146)).

## What is paired

- **Bot profile**: [`profiles/paired-profile.json`](profiles/paired-profile.json) holds `../hermes/profiles/murakumo-adnetwork/profile.yaml` and `itonami.edn` verbatim, plus the cron job names and schedules, with the source revision and the sha256 of each source file. The tests fail if the source files change without the bundle being regenerated. The profile declares `:engine :itonami-agent` and is propose-only: it never submits, publishes, serves, bills or sends outreach.
- **App**: shows the profile and reads two public, credential-free routes on `https://murakumo.cloud`:
  - `GET /api/v1/ads/inventory`: the cached campaign registry, whether it was measured, and who funds a placement (`cloud-murakumo.adnetwork/inventory-report`).
  - `GET /api/v1/free/status`: the ad-funded free inference lane and how many campaigns fund it (`cloud-murakumo.free-tier/availability`).

Every read goes through the canonical `kotoba-client-runtime.js` broker and opens its own approval dialog. Denying sends nothing. Nothing refreshes in the background. The app never sends `?refresh=1` (that would make the server sync on the app's behalf).

## On-device recording (AWAI-420, owner approval 2026-11-23)

Four local-only recording screens, all through the same broker as browser-action operations (no fetch, so no new network origin): 申請 `record-application`, 審査 `set-review-state`, 領収 `record-receipt`, イベント・入金記録 `record-ledger-event`. Each write opens its own approval dialog; denying writes nothing. Records persist in local-state (`localstorage`, schema `itonami.koukoku.local.v1` v2) and are re-read on reload (`koukoku.domain/parse-store`), which re-runs the validators. Review state is the fixed enum `received/under-review/approved/rejected` with guarded transitions and no free-text status (see `koukoku.domain/review-transitions`). Ledger event ids are unique: a repeated id is refused with 「二重登録拒否」. Recording moves no money: receipt and deposit entries keep issuance metadata only, no currency is fixed (AWAI-323 undecided), and the Murakumo-side review bot (AWAI-495) is not part of this app.

## The funding verdict is fail-closed

- `advertiser-funded` only when the inventory is measured and fresh, has an active campaign with `fundingSource: advertiser`, **and** the free tier counts at least one advertiser-funded campaign.
- `house-only` when both are read, the inventory is measured and fresh, and neither shows advertiser money.
- `unknown` otherwise, with reasons: inventory unmeasured or stale, a source not read, or the two sources disagreeing. An unmeasured inventory is never read as "0 campaigns".
- A response whose shape or counts cannot be checked (wrong types, unknown funding source, `active > campaigns`, `advertiser` funding with 0 campaigns) is refused and not displayed.

State on 2026-11-18 (fixtures in `test/fixtures/`): the inventory is `measured: false, stale: true` and the free tier is `house-sponsorship` with `advertiserFunded: 0`, so the verdict is `unknown`.

## Build and test

Node 26/npm, pinned Squint 0.16.214, esbuild 0.28.2 and Tauri CLI 3.0.0-alpha.6. Run from `awai-app/`.

```sh
npm ci
npm test          # domain tests on real captured fixtures, with negative controls and a profile drift check
npm run build     # static files in dist/, with dist/release.json digests
```

The build fails if the canonical runtime or consent CSS differs from `vendor/PINS.json` (kotoba-lang/kotoba `91ed14c`), or if the Awai UI tarball or its package files differ from `vendor/awai-ui-pin.json`. These are the same pinned files as Rodo.

Android needs SDK 35+, NDK 27.0.12077973 and JDK 21, with `ANDROID_HOME`, `NDK_HOME` and `JAVA_HOME` set:

```sh
npm run build:android        # arm64 debug APK
npm run build:android:x86    # x86_64 debug APK (in a fresh checkout the first Android build can fail on
                             # 'PluginManager'; Tauri generates tauri.settings.gradle during it — run it again)
```

The APK is a debug test artifact. It is not a signed production or OTA release, and it is not enrolled in Itonami Store.

## Boundaries

- The app origin is static only: no SSR, BFF, app-origin API or inference proxy. The Tauri host exposes only `awai_host_info`.
- The CSP `connect-src` allows exactly `https://murakumo.cloud`, which equals what the runtime derives from the descriptor (checked in the tests).
- The app does not edit any profile store and does not launch or reconfigure the bot.
- The ad-server Worker at the repository root is separate and unchanged. Moving its request-time logic toward the static-origin contract is tracked in AWAI-3241.
- Verification evidence lives under `.agent-output/` and in the Linear issue. See [docs/verification.md](docs/verification.md).
