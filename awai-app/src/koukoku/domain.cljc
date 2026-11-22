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

;; ---- own-ads recording screens (AWAI-420 write side) ------------------------
;; Local-only records. Recording is not submitting, serving, billing or
;; settling: these operations cause no fetch (browser-action endpoints), so
;; they add no network origin to the descriptor and the CSP connect-src is
;; unchanged. The owner approved building them on 2026-11-23. Fund movement
;; stays out of scope: receipt and deposit records keep issuance/deposit
;; metadata only, and no currency is fixed here (AWAI-323 stays undecided).
;; The Murakumo-side review bot (AWAI-495) is not touched.
(def records-capability "adnetwork-records")
(def records-service "koukoku-records")
(def records-origin "https://local.invalid")
(def records-data-categories ["local-record"])

;; Review state machine (審査). Fixed enum, guarded transitions, no free text:
;;   received     -> under-review | rejected
;;   under-review -> approved | rejected | received
;;   approved     -> (terminal)
;;   rejected     -> received
(def review-states #{"received" "under-review" "approved" "rejected"})
(def review-transitions
  {"received" #{"under-review" "rejected"}
   "under-review" #{"approved" "rejected" "received"}
   "approved" #{}
   "rejected" #{"received"}})

;; Each read is its own operation with its own trusted user choice. Both
;; routes are exact paths with no query: `?refresh=1` would make the server
;; sync the registry on our behalf, which this read-only app must not trigger.
;; One write per recording screen. Each is its own browser-action operation
;; with its own trusted user choice; the host handler validates with this
;; namespace, persists to local-state and returns plain data. No endpoint
;; ever leaves the device.
(def records-operations
  (mapv (fn [op]
          {:capability records-capability :service records-service :operation op
           :consent "each-operation" :data-categories records-data-categories
           :endpoint {:origin records-origin :browser-action op}})
        ["record-application" "set-review-state" "record-receipt" "record-ledger-event"]))

(def operations
  (vec (concat
        [{:capability capability :service service :operation "read-inventory" :consent "each-operation"
          :data-categories ["request-ip"]
          :endpoint {:origin murakumo-origin :routes [{:method "GET" :path inventory-path :query-params []}]}}
         {:capability capability :service service :operation "read-free-status" :consent "each-operation"
          :data-categories ["request-ip"]
          :endpoint {:origin murakumo-origin :routes [{:method "GET" :path free-status-path :query-params []}]}}]
        records-operations)))

(defn descriptor []
  {:schema "kotoba.app.client-runtime.v1" :target "browser" :profile "trusted-page-v1" :entry "index.html"
   :local-state {:adapter "localstorage" :schema "itonami.koukoku.local.v1" :version 2}
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

;; ---- recording store (申請 / 審査 / 領収 / イベント・入金記録) -------------------
;; Pure store transitions for the four write screens. The app serialises the
;; store with serialize-store into local-state (adapter "localstorage") and
;; rehydrates with parse-store on reload; both directions re-run the same
;; validators below, so persisted data cannot smuggle a free-text review
;; state or a duplicate id past a reload. Ids are unique per collection and,
;; for the ledger, across the whole ledger: a repeated event id is refused
;; with "duplicate-event-id" and its Japanese text is duplicate-event-refusal.

(def store-keys #{"applications" "receipts" "ledger"})
(def ledger-kinds #{"impression" "click" "deposit"})
(def ^:private id-re #"[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
(def ^:private stamp-re #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})")

(defn empty-store [] {:applications [] :receipts [] :ledger []})

(defn- obj-keys [x] (set (map name (keys x))))
(defn- valid-id? [x] (match? id-re x))
(defn- valid-stamp? [x] (match? stamp-re x))
(defn- short-or-nil? [x n] (or (nil? x) (short-text? x n)))

(defn duplicate-event-refusal
  "The text shown on screen when an event id is submitted twice. Single
  source in the domain so tests pin the visible string."
  [event-id] (str "二重登録拒否: イベント " event-id " は既に記録されています。"))

(defn- application-fields-ok? [a]
  (and (short-text? (:applicant a) 120) (short-text? (:contact a) 120)
       (short-or-nil? (:note a) 500) (valid-stamp? (:created-at a))))

(defn add-application
  "Record an application (申請). Creation takes no review state: the record
  starts at \"received\" and moves only via transition-review."
  [store input]
  (when-not (and (object-value? input)
                 (= #{"id" "applicant" "contact" "note" "created-at"} (obj-keys input))
                 (valid-id? (:id input)) (application-fields-ok? input))
    (refuse "application-shape"))
  (when (some #(= (:id %) (:id input)) (:applications store))
    (refuse "duplicate-application-id" {:id (:id input)}))
  (assoc store :applications
         (conj (:applications store)
               {:id (:id input) :applicant (:applicant input) :contact (:contact input)
                :note (:note input) :created-at (:created-at input)
                :review-state "received"})))

(defn review-allowed?
  "True when the fixed enum allows `from` -> `to`. Unknown states allow nothing."
  [from to] (contains? (get review-transitions from) to))

(defn transition-review
  "Move one application to `to`, guarded by review-transitions. Anything
  outside the table, including free-text states, is refused."
  [store application-id to]
  (when-not (contains? review-states to)
    (refuse "review-state-unknown" {:state to}))
  (when-not (some #(= (:id %) application-id) (:applications store))
    (refuse "application-not-found" {:id application-id}))
  (let [from (:review-state (first (filter #(= (:id %) application-id) (:applications store))))]
    (when-not (review-allowed? from to)
      (refuse "review-transition" {:from from :to to}))
    (assoc store :applications
           (vec (map (fn [a] (if (= (:id a) application-id) (assoc a :review-state to) a))
                     (:applications store))))))

(defn add-receipt
  "Record a receipt issuance (領収). Metadata only: this settles nothing
  and fixes no currency (AWAI-323 stays undecided)."
  [store input]
  (when-not (and (object-value? input)
                 (= #{"id" "issued-to" "amount-micros" "memo" "issued-at"} (obj-keys input))
                 (valid-id? (:id input)) (short-text? (:issued-to input) 120)
                 (and (int-value? (:amount-micros input)) (pos? (:amount-micros input)))
                 (short-or-nil? (:memo input) 500) (valid-stamp? (:issued-at input)))
    (refuse "receipt-shape"))
  (when (some #(= (:id %) (:id input)) (:receipts store))
    (refuse "duplicate-receipt-id" {:id (:id input)}))
  (assoc store :receipts
         (conj (:receipts store)
               {:id (:id input) :issued-to (:issued-to input) :amount-micros (:amount-micros input)
                :memo (:memo input) :issued-at (:issued-at input)})))

(defn- ledger-entry-ok? [e]
  (and (valid-id? (:id e)) (contains? ledger-kinds (:kind e)) (valid-stamp? (:at e))
       (case (:kind e)
         "impression" (and (= #{"id" "kind" "at" "campaign-id" "advertiser" "placement"} (obj-keys e))
                           (short-text? (:campaign-id e) 120) (short-text? (:advertiser e) 120)
                           (short-text? (:placement e) 120))
         "click" (and (= #{"id" "kind" "at" "campaign-id" "advertiser" "placement"} (obj-keys e))
                      (short-text? (:campaign-id e) 120) (short-text? (:advertiser e) 120)
                      (short-text? (:placement e) 120))
         "deposit" (and (= #{"id" "kind" "at" "payer" "amount-micros"} (obj-keys e))
                        (short-text? (:payer e) 120)
                        (and (int-value? (:amount-micros e)) (pos? (:amount-micros e))))
         false)))

(defn duplicate-event? [store event-id]
  (some #(= (:id %) event-id) (:ledger store)))

(defn record-ledger-event
  "Record an impression/click event or a deposit (イベント/入金記録).
  Impression/click entries mirror the adnet.billing event shape
  (campaign-id, advertiser, placement, kind, at). A repeated event id is
  refused with \"duplicate-event-id\"; the refusal text is
  duplicate-event-refusal."
  [store input]
  (when-not (object-value? input)
    (refuse "ledger-entry-shape"))
  (when-not (contains? ledger-kinds (:kind input))
    (refuse "ledger-kind-unknown" {:kind (:kind input)}))
  (when-not (ledger-entry-ok? input)
    (refuse "ledger-entry-shape"))
  (when (duplicate-event? store (:id input))
    (refuse "duplicate-event-id" {:id (:id input)}))
  (assoc store :ledger (conj (:ledger store) input)))

;; ---- read paths (each screen lists what was written before) -------------------

(defn list-applications [store] (vec (:applications store)))
(defn list-receipts [store] (vec (:receipts store)))
(defn list-ledger [store] (vec (:ledger store)))

;; ---- persistence round trip ---------------------------------------------------

(defn- valid-stored-application? [a]
  (and (object-value? a)
       (= #{"id" "applicant" "contact" "note" "created-at" "review-state"} (obj-keys a))
       (valid-id? (:id a)) (application-fields-ok? a) (contains? review-states (:review-state a))))

(defn- valid-stored-receipt? [r]
  (and (object-value? r)
       (= #{"id" "issued-to" "amount-micros" "memo" "issued-at"} (obj-keys r))
       (valid-id? (:id r)) (short-text? (:issued-to r) 120)
       (and (int-value? (:amount-micros r)) (pos? (:amount-micros r)))
       (short-or-nil? (:memo r) 500) (valid-stamp? (:issued-at r))))

(defn- unique-ids? [xs] (= (count xs) (count (set (map :id xs)))))

(defn- restore-review-state
  "Walk a re-created application from \"received\" to its stored state using
  only legal transitions (approved is reached via under-review)."
  [store application-id target]
  (cond
    (= "received" target) store
    (= "approved" target) (-> store
                              (transition-review application-id "under-review")
                              (transition-review application-id "approved"))
    :else (transition-review store application-id target)))

(defn validate-store
  "Full check of a store as held in memory: shapes, enum states and unique
  ids. Used before serialising and after parsing, so a tampered persisted
  store is refused instead of shown."
  [store]
  (when-not (and (object-value? store) (= store-keys (obj-keys store))
                 (array-value? (:applications store)) (array-value? (:receipts store))
                 (array-value? (:ledger store))
                 (every? valid-stored-application? (:applications store))
                 (every? valid-stored-receipt? (:receipts store))
                 (every? ledger-entry-ok? (:ledger store))
                 (unique-ids? (:applications store)) (unique-ids? (:receipts store))
                 (unique-ids? (:ledger store)))
    (refuse "records-shape"))
  store)

(defn serialize-store [store]
  (validate-store store)
  #?(:cljs (js/JSON.stringify (clj->js store))
     :clj (pr-str store)))

(defn parse-store
  "Re-create the client state from storage (the reload leg). Re-runs the
  add-* validators, so duplicate ids and off-enum review states in the
  persisted bytes are refused rather than re-read."
  [s]
  (let [raw #?(:cljs (try (js/JSON.parse s) (catch :default _ (refuse "records-parse")))
              :clj (try (read-string s) (catch Exception _ (refuse "records-parse"))))]
    (when-not (and (object-value? raw) (= store-keys (obj-keys raw))
                   (array-value? (:applications raw)) (array-value? (:receipts raw))
                   (array-value? (:ledger raw)))
      (refuse "records-shape"))
    (let [s0 (reduce (fn [st e] (record-ledger-event st e)) (empty-store) (:ledger raw))
          s1 (reduce (fn [st r] (add-receipt st r)) s0 (:receipts raw))
          s2 (reduce (fn [st a]
                       (let [st (add-application st (dissoc a :review-state))]
                        (restore-review-state st (:id a) (:review-state a))))
                     s1 (:applications raw))]
      (validate-store s2))))
