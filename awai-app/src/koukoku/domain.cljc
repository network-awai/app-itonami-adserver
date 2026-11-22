(ns koukoku.domain
  "Pure, fail-closed domain for the murakumo-adnetwork bot's public surface.
  Runs in the browser (Squint) and on a JVM host. No I/O here."
  (:require [clojure.string :as str]))

(def profile-id "murakumo-adnetwork")
(def murakumo-origin "https://murakumo.cloud")
(def inventory-path "/api/v1/ads/inventory")
(def free-status-path "/api/v1/free/status")
(def capability "adnetwork-read")
(def service "murakumo-ads")
(def max-response-bytes 65536)

;; Each read is its own operation with its own trusted user choice. Both
;; routes are exact paths with no query: `?refresh=1` would make the server
;; sync the registry on our behalf, which this read-only app must not trigger.
;; restricted-wasm-v1 (KOTOBA-159): every operation carries the disclosure the
;; consent dialog shows first. Values are written for the user and never
;; derived from the payload. Nothing is persisted, so withdrawal is "do not
;; allow" on the next dialog.
(def disclosure-recipient
  "Legal name and country of the murakumo.cloud operator. Owner input (KOTOBA-159)."
  {:recipient "TBD" :country "JP"})

(defn- disclosure [purpose]
  (merge disclosure-recipient
         {:data ["IP アドレス" "通信の時刻"] :special-care false :purpose purpose :frequency "one-time"
          :withdrawal "許可は保存されません。取得のたびに確認画面で選べます。"}))

(def operations
  [{:capability capability :service service :operation "read-inventory" :consent "each-operation"
    :data-categories ["request-ip"]
    :disclosure (disclosure "広告 bot の広告在庫の公開データを murakumo.cloud から読み、この端末で資金を判定するため。")
    :endpoint {:origin murakumo-origin :routes [{:method "GET" :path inventory-path :query-params []}]}}
   {:capability capability :service service :operation "read-free-status" :consent "each-operation"
    :data-categories ["request-ip"]
    :disclosure (disclosure "無料枠の資金の公開データを murakumo.cloud から読み、この端末で資金を判定するため。")
    :endpoint {:origin murakumo-origin :routes [{:method "GET" :path free-status-path :query-params []}]}}])

(defn descriptor []
  {:schema "kotoba.app.client-runtime.v1" :target "browser" :profile "restricted-wasm-v1" :entry "index.html"
   :local-state {:adapter "memory" :schema "itonami.koukoku.local.v1" :version 1}
   :workers [] :operations operations})

(defn inventory-url [] (str murakumo-origin inventory-path))
(defn free-status-url [] (str murakumo-origin free-status-path))

(defn- match? [re s] (and (string? s) (some? (re-matches re s))))
(defn- int-value? [x] #?(:clj (integer? x) :cljs (js/Number.isSafeInteger x)))
(defn- array-value? [x] #?(:clj (sequential? x) :cljs (js/Array.isArray x)))
(defn- object-value? [x] #?(:clj (map? x) :cljs (and (some? x) (= "object" (js/typeof x)) (not (js/Array.isArray x)))))
(defn- bool? [x] (or (true? x) (false? x)))
(defn- nat-int? [x] (and (int-value? x) (>= x 0)))
(defn- short-text? [x n] (and (string? x) (not (str/blank? x)) (<= (count x) n)))
(defn- opt [pred x] (or (nil? x) (pred x)))
(defn- refuse [reason] (throw (ex-info reason {:reason reason})))

;; ---- bundled paired profile -------------------------------------------------

(defn profile-problems
  "The bundle is a copy of hermes/profiles/murakumo-adnetwork in this repository.
  Refuse it unless it names that profile, records a source revision and digests,
  declares the itonami agent runtime, and still says propose-only."
  [doc]
  (let [p (:profile doc) srcs (get-in doc [:provenance :sources])
        edn (get p "itonami.edn") yaml (get p "profile.yaml")]
    (cond-> []
      (not= "awai.koukoku.paired-profile.v1" (:schema doc)) (conj "schema")
      (not (match? #"[0-9a-f]{40}" (get-in doc [:provenance :revision]))) (conj "provenance")
      (not (and (object-value? srcs)
                (every? #(match? #"[0-9a-f]{64}" (get srcs %)) ["profile.yaml" "itonami.edn" "cron/jobs.json"]))) (conj "source-digests")
      (not= profile-id (:id p)) (conj "profile-id")
      (not (and (string? edn) (str/includes? edn (str ":profile/id \"" profile-id "\""))
                (str/includes? edn ":engine :itonami-agent"))) (conj "runtime")
      (not (and (string? yaml) (str/includes? yaml "propose-only"))) (conj "authority")
      (not (and (array-value? (:cron p)) (every? #(and (short-text? (:name %) 120) (bool? (:enabled %))) (:cron p)))) (conj "cron"))))

(defn profile-description [doc]
  (let [yaml (get-in doc [:profile "profile.yaml"])
        m (re-find #"description: '([^']*)'" (str yaml))]
    (second m)))

;; ---- GET /api/v1/ads/inventory ----------------------------------------------

(def inventory-funding #{"advertiser" "listed" "house"})

(defn inventory
  "Shape from cloud-murakumo.adnetwork/inventory-report. `measured false` means
  no campaign count may be read off the report, so we carry it as unmeasured."
  [body]
  (let [net (:network body) placements (:placements body)]
    (when-not (and (object-value? body) (bool? (:measured body)) (bool? (:stale body))
                   (opt nat-int? (:ageMs body)) (opt nat-int? (:campaigns body)) (opt nat-int? (:active body))
                   (nat-int? (:listed body)) (contains? inventory-funding (:fundingSource body))
                   (object-value? net) (bool? (:configured net)) (opt #(short-text? % 300) (:source net))
                   (opt object-value? (:lastSync body))
                   (array-value? placements) (<= (count placements) 50)
                   (every? #(short-text? % 80) placements))
      (refuse "inventory-shape"))
    (when (and (true? (:measured body)) (or (nil? (:campaigns body)) (nil? (:active body))))
      (refuse "inventory-measured-without-counts"))
    (when (and (some? (:active body)) (some? (:campaigns body)) (> (:active body) (:campaigns body)))
      (refuse "inventory-counts"))
    {:measured (:measured body) :stale (:stale body) :age-ms (:ageMs body)
     :configured (:configured net) :last-sync-at (get-in body [:lastSync :at])
     :campaigns (:campaigns body) :active (:active body) :listed (:listed body)
     :placements (vec placements) :funding-source (:fundingSource body)}))

;; ---- GET /api/v1/free/status --------------------------------------------------

(def free-funding #{"unavailable" "advertiser" "house-sponsorship" "none"})

(defn free-status
  "Shape from cloud-murakumo.free-tier/availability. `advertiser` funding needs
  at least one funded campaign; house sponsorship or none needs zero."
  [body]
  (when-not (and (object-value? body) (= "free-tier" (:placement body))
                 (bool? (:available body)) (bool? (:sponsored body)) (bool? (:storeReady body))
                 (contains? free-funding (:fundingSource body))
                 (nat-int? (:advertiserFunded body)) (opt nat-int? (:impressionsPerUnit body))
                 (nat-int? (:unitPriceMicros body)) (nat-int? (:dailyUnitCap body))
                 (opt #(short-text? % 120) (:sponsoredBy body)) (opt #(short-text? % 120) (:policyProblem body)))
    (refuse "free-status-shape"))
  (let [fs (:fundingSource body) n (:advertiserFunded body)]
    (when (or (and (= "advertiser" fs) (zero? n))
              (and (contains? #{"house-sponsorship" "none"} fs) (pos? n)))
      (refuse "free-status-funding")))
  {:available (:available body) :funding-source (:fundingSource body)
   :advertiser-funded (:advertiserFunded body) :sponsored-by (:sponsoredBy body)
   :unit-price-micros (:unitPriceMicros body) :daily-unit-cap (:dailyUnitCap body)
   :impressions-per-unit (:impressionsPerUnit body)})

;; ---- funding verdict ----------------------------------------------------------

(defn funding
  "Fail closed. `advertiser-funded` needs a measured, fresh inventory with an
  active campaign AND a free-tier status that counts a funded campaign.
  `house-only` needs both sources read and agreeing on zero. Anything else,
  including an unmeasured inventory, is unknown."
  [inv free]
  (let [reasons (cond-> []
                  (nil? inv) (conj "inventory-not-read")
                  (nil? free) (conj "free-status-not-read")
                  (and inv (not (:measured inv))) (conj "inventory-unmeasured")
                  (and inv (:stale inv)) (conj "inventory-stale")
                  (and free (not (:available free))) (conj "free-tier-unavailable")
                  (and free (zero? (:advertiser-funded free))) (conj "no-advertiser-funding"))
        measured (and inv (:measured inv) (not (:stale inv)))
        inv-paid (and measured (pos? (:active inv)) (= "advertiser" (:funding-source inv)))
        free-paid (and free (= "advertiser" (:funding-source free)) (pos? (:advertiser-funded free)))]
    (cond
      (and (nil? inv) (nil? free)) {:verdict "not-read" :reasons reasons}
      (and inv-paid free-paid) {:verdict "advertiser-funded" :reasons []}
      (and measured free (not inv-paid) (not free-paid)) {:verdict "house-only" :reasons reasons}
      (and measured free (not= inv-paid free-paid)) {:verdict "unknown" :reasons (conj reasons "sources-disagree")}
      :else {:verdict "unknown" :reasons reasons})))
