# adserver

**First-party ad-server Worker** for our own ad network — the advertising twin
of `nexus-x402`. Publishers (shinshi / isekai / …) call `/serve` at each
placement; this runs the `kotoba-lang/adnet` auction over the campaign registry
and returns the winning creative, or the **x402-promoting house ad**;
impressions/clicks POST to `/event` and accrue against the advertiser's prepaid
USDC balance. Advertisers fund campaigns with x402 USDC deposits (verified
on-chain via `treasury` over a keyless Base RPC). Ad income flows in our own
currency instead of a third-party network's payout.

Design: superproject ADR-2607093500. The pure decision is `adnet.core/serve` +
`adnet.billing/accrue` (vendored); the worker only adds HTTP + on-chain top-up
verification. `cloud-itonami` operates the campaign lifecycle (create/pause via
its ops-LLM ⊣ CertGovernor loop); campaigns arrive here as `CAMPAIGNS_JSON`.

## Endpoints

```
GET  /serve?slot=&tier=&format=&geo=   → {kind:paid|house, creative, campaign-id}
POST /event {campaign-id, kind, placement}  → {charge-micros, event}
POST /topup {tx}                        → verify an advertiser's USDC deposit on-chain
GET  /campaigns                         → 200, deprecated (see below); successor = static file
GET  /catalog                           → 200, deprecated (see below); successor = static file
GET  /health
```

`/event` + `/topup` keep their existing behaviour: nothing is recorded (the
accrual store is unbound by design; real charging is an AWAI-420 stage — see
`docs/worker-inventory.md`).

## Campaign registry (`CAMPAIGNS_JSON`, no keys)

```json
[{"id":"cmp-1","advertiser":"did:key:z…",
  "creative":{"type":"image","image-url":"https://…","click-url":"https://…"},
  "bid":{"model":"cpm","usd":"4.00"},
  "targeting":{"tier":"adult","formats":["rectangle"],"placements":["scene-detail"]},
  "budget":{"total-usd":"50.00","spent-micros":0},"status":"active"}]
```

`ADNET_TREASURY_ADDR` (advertiser USDC deposits) via `wrangler secret`.

## Static campaign list (owner decision 2026-11-23)

The list is a static build artifact, never request-time generation. The single
source is `CAMPAIGNS_JSON` in `wrangler.jsonc`; `scripts/campaigns_static_export.py`
parses it at build time and writes `public/campaigns.json` (+ `.sha256`) and
`public/catalog.json` (+ `.sha256`), each embedding `generatedAt` and the
sha256 of the input registry. Merge = publish (same convention as the scout
export): after merging, readers fetch

- `https://raw.githubusercontent.com/network-awai/app-itonami-adserver/main/public/campaigns.json`
- `https://raw.githubusercontent.com/network-awai/app-itonami-adserver/main/public/catalog.json`

with a digest check against the `.sha256` sidecar. No HTML view: this repo has
no static-site home (`public/` is data-only), so an HTML copy would go stale.
The Worker embeds those bytes at build time
(`scripts/embed_static_json.py` → `src/adserver/static_content.cljs`, run by
`npm run build` before shadow-cljs) and serves them at `GET /campaigns` /
`GET /catalog` with status 200, the static content plus the envelope fields
`service`/`ok`/`generated-at`, and headers `Deprecation: true`,
`Sunset: Thu, 31 Dec 2026 00:00:00 GMT` (2026-12-31),
`Link: <static URL>; rel="successor-version"`.

```bash
python3 scripts/campaigns_static_export.py --out public   # regenerate from wrangler.jsonc
python3 scripts/embed_static_json.py                      # re-embed into the Worker bundle
git diff --stat public src/adserver/static_content.cljs   # review before committing
```

**murakumo puller migration:** `cloud-murakumo.adnetwork` still pulls
`https://ads.x402.nexus/campaigns` on a cron. That route keeps answering 200
with the same body, so the puller keeps working; it migrates to the static
`campaigns.json` URL above in a separate PR. Only after that migration may
these routes return 410.

## Awai app (Koukoku)

[`awai-app/`](awai-app/README.md) is the Awai app paired one-to-one with the
`murakumo-adnetwork` bot profile: a Tauri v3 app (Android first) that reads
murakumo.cloud's public ad inventory and free-tier funding, each read behind
its own consent, and shows a fail-closed funding verdict. It is read-only and
separate from this Worker. Tracking: AWAI-3260.

## Awai app (Koukoku Scout) and the scout export

[`awai-app-scout/`](awai-app-scout/README.md) is the Awai app paired with the
`adnetwork-scout` bot profile. It reads the bot's static public export
([`public/adnetwork-scout/`](public/adnetwork-scout/README.md), written by
`scripts/adnetwork_public_export.py`) behind its own consent. It is read-only.
The export is not published yet (catalog token 403 on 2026-11-18).
Tracking: AWAI-3335.

## Dev

```bash
npm test           # portable suite on kbb, then the compiled Worker: node test runner + HTTP surface (worker_http_test.cljk)
kbb -M:lint
npm run build      # scripts/cljk-mirror.cljk -> .cljk-build/, then shadow-cljs release worker -> dist/worker.js
npm run deploy     # wrangler deploy -> https://ads.x402.nexus
```

Sources are `.cljk` (ADR-2609111500); shadow-cljs reads the git-ignored mirror
`scripts/cljk-mirror.cljk` derives from `cljk-origin.edn`.

## Live (2026-09-16, domain restored 2026-11-23)

`https://ads.x402.nexus` (cloud-kotoba Cloudflare account, zone `x402.nexus`;
single canonical hostname, `custom_domain` route in `wrangler.jsonc`).
`CAMPAIGNS_JSON` is `[]`: **the registry is empty by fact, not by omission** —
no advertiser has funded a campaign. `/serve` therefore answers the house ad,
and the static list answers `count 0`. The first publisher wired to this host is
murakumo.cloud (`cloud-murakumo.adnetwork`: pulls the static `campaigns.json`
into its KV on a cron, serves from that cache, reports `/api/v1/ads/inventory`).

Apache-2.0.
