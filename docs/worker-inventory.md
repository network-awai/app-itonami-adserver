# adserver Worker: request-time inventory (AWAI-3330, 2026-11-18)

This lists what the Cloudflare Worker in `src/adserver/worker.cljk` does when a request arrives. For each route it says which role the work belongs to under the shared Kotoba end-user apps contract (workspace `AGENTS.md`), and what a move would need. It changes no code and no deployment. A production cutover needs separate approval (AWAI-420 stages).

## Role first

The contract forbids request-time application logic on an **app origin**. It keeps **remote protocol services** as a separate role and does not ask that they move into the browser.

This Worker is not an app origin, because no end-user UI is served from it. Its callers are publishers (murakumo.cloud and other sites that place ads) and advertisers who top up funds. So it is a remote protocol service: the ad network's server side.

The question per route is therefore not "move everything to the browser". It is:
- whether the route needs a trusted server at all;
- if it does, whether it is honest about what it persists;
- and which parts a user-side app (such as Koukoku) can do directly against a public source, with consent for each operation.

## Routes

The dispatcher is `src/adserver/worker.cljk:156-171`. Bindings are `wrangler.jsonc`: the custom domain `ads.x402.nexus`, `CAMPAIGNS_JSON` as a plain var (now `[]`), and `ADNET_TREASURY_ADDR` as a secret or var.

| Route | Code | What it does at request time | State | Needs a trusted server? | Where it can live |
| -- | -- | -- | -- | -- | -- |
| `OPTIONS *` | `:160-164` | CORS preflight, `*` origin | none | no | Wherever the remaining routes live |
| `GET /serve?slot&tier&format&geo` | `:47-61` | Parses the registry and runs the auction (`adnet.core/serve`) | reads `CAMPAIGNS_JSON` | **no**: the auction is pure and the registry is public | Publisher side. murakumo.cloud already runs `kotoba-lang/adnet` against a cached registry. A browser page can run it too, from a static, digest-pinned `campaigns.json` |
| `POST /event {campaign-id, kind, placement}` | `:63-79` | Computes a charge (`adnet.billing/accrue`) | **nothing is persisted**: the comment at `:63-67` says the accrual store is "a deploy-time binding", and none is bound | **yes**, if charges are ever to count: an append-only ledger the advertiser can audit | A protocol service with a real store (D1/KV or a ledger). Until then this route only echoes a number. Do not treat it as billing. |
| `POST /topup {tx}` | `:81-113` | Reads a Base transaction receipt over a keyless RPC and checks the recipient and at least 3 confirmations | none (crediting is "the host's store step", `:84`, also unbound) | partly: verifying needs no secret (public chain). **Crediting** a balance needs a trusted ledger | Verification can run user-side, with consent per operation to the RPC origin. Crediting stays server-side with the ledger above |
| `GET /campaigns` | `:115-145` | Returns `CAMPAIGNS_JSON` as is; 503 with `count: null` when it is unreadable | reads config | **no**: it is static content | A static, digest-pinned file (`campaigns.json` plus its sha256) on any static origin. Lifecycle changes then become content releases, not config edits |
| `GET /catalog` | `:147-154` | Counts campaigns and lists slots | derived from config | no | Derived at build time next to `campaigns.json`, or computed by the reader |
| `GET /health` | `:170` | Liveness | none | no | Goes away with the Worker |
| anything else | `:171` | 200 with a pointer to `/catalog` | none | no | Should be 404. A 200 for unknown paths hides errors |

## Observed on 2026-11-18

- `dig +short ads.x402.nexus A` returned nothing, and `curl https://ads.x402.nexus/health` could not connect. The zone's nameservers (`x402.nexus`, Cloudflare) answer. The README still says the host is live (`README.md`, "Live (2026-09-16)"). This doc does not edit that section, because whether the domain should be restored is the owner's call.
- `GET https://murakumo.cloud/api/v1/ads/inventory` returned `lastSync: null`, `network.configured: true`, `network.source: null`, `measured: false`, `stale: true`.
  - murakumo.cloud is the publisher that pulls `/campaigns` from this host on a cron (README).
  - A sync that has never succeeded fits a host that does not resolve, but this is a correlation. The murakumo side was not inspected.
  - This is also why the Koukoku app's verdict is `unknown`.

## Suggested order (each needs its own issue and approval)

1. **Make the registry a static artifact.** Publish `campaigns.json` with its sha256 from this repo on a static origin. murakumo.cloud and user-side apps read it with digest checks. This removes the dependency behind the stale inventory without any request-time code. It is a deployment, so it needs approval.
2. **Run `/serve` on the publisher side only.** The auction is already shared code (`src/adnet/core.cljk`). Retire `GET /serve` on this host once no publisher calls it.
3. **Decide the ledger** before `/event` or `/topup` are described as billing. This is an AWAI-420 stage (real charging) and needs owner approval. Until then, both routes should say in their response that nothing is recorded.
4. **Retire the Worker or fix its domain.** This is a production change and needs separate approval.
5. **Serve the static URL's body first, then retire the Worker route.** The 2026-11-23 change shipped the 410 on `/campaigns` + `/catalog` before the static URL had a real body — a 410 order violation; nothing broke only because the murakumo puller's existing refusal path kept the cache (`cloud-murakumo/src/cloud_murakumo/adnetwork.cljk:42-97`, `adnetwork_http.cljk:5`) and live inventory stayed `lastSync: null, measured: false`. See "410 order violation" below.

## Not covered

- `cloud-itonami`'s campaign lifecycle (the governor that edits `CAMPAIGNS_JSON`) and murakumo.cloud's `cloud-murakumo.adnetwork` code. Both live in other repositories.
- Whether `CAMPAIGNS_JSON` in the deployed Worker equals the `[]` in `wrangler.jsonc`. The host is unreachable, and the Cloudflare account was not queried.

## Addendum 2026-11-23 — static list + domain fix (owner approved)

Steps 1 and 4 above were approved together on 2026-11-23:

- **Static list:** `scripts/campaigns_static_export.py` generates
  `public/campaigns.json` (+ `.sha256`) and `public/catalog.json` (+ `.sha256`)
  from `CAMPAIGNS_JSON` in `wrangler.jsonc` at build time. Worker
  `/campaigns` + `/catalog` now answer **410 Gone** with a pointer to the
  static URLs; `/serve`, `/event`, `/topup`, `/health` are unchanged.
  murakumo.cloud's cron must switch from `https://ads.x402.nexus/campaigns`
  to the static `campaigns.json` URL (README documents the migration).
- **Domain:** `ads.x402.nexus` did not resolve because the `adserver` Worker
  did not exist on any account (zone `x402.nexus` is on `cloud-kotoba`,
  account `4da88288dc30d9ee257f319d3c33ecf0`; only `nexus-x402` held the apex
  custom domain). The fix deploys this Worker to that account with the
  existing `custom_domain` route, restoring the single canonical hostname.
  No second/off-path setup was created.
- **Not done:** no ledger/billing change; `/event` + `/topup` still record
  nothing (AWAI-420 stage, separate approval). `/serve` was kept as-is per
  the owner scope (step 2 above was not part of this change).

## 410 order violation (2026-11-23)

The Addendum shipped the 410 on `/campaigns` + `/catalog` before the static URL had a real body — the opposite of the order in "Suggested order" above.

- **Why nothing broke:** the murakumo puller's refusal path already existed — `cloud-murakumo/src/cloud_murakumo/adnetwork.cljk:42-97` records a refusal and keeps the cache, and `adnetwork_http.cljk:5` handles the non-2xx — so live inventory stayed `lastSync: null, measured: false`.
- **Rule going forward:** serve the static URL's body first, retire the Worker route after.
