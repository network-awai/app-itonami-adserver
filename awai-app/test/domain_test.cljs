(ns domain-test
  (:require [koukoku.domain :as d] [clojure.string :as str] ["node:test" :refer [test]] ["node:assert/strict" :as a] ["node:fs" :as fs]
            ["node:crypto" :as crypto]
            ["../../vendor/assets/kotoba-client-runtime.js" :as runtime]))

(defn fixture [f] (js/JSON.parse (fs/readFileSync (str "test/fixtures/" f) "utf8")))
(def inventory-live (fixture "inventory-20261118.json"))
(def free-live (fixture "free-status-20261118.json"))
(def profile (js/JSON.parse (fs/readFileSync "profiles/paired-profile.json" "utf8")))
(defn clone [x] (js/JSON.parse (js/JSON.stringify x)))
(defn reason [f] (try (f) nil (catch :default e (:reason (ex-data e)))))
(defn sha [file] (-> (crypto/createHash "sha256") (.update (fs/readFileSync file)) (.digest "hex")))

(test "bundled profile is murakumo-adnetwork and matches the source files in this repository"
  (fn []
    (a/deepEqual (d/profile-problems profile) [])
    (let [dir (str "../" (get-in profile [:provenance :path])) srcs (get-in profile [:provenance :sources])]
      (doseq [f ["profile.yaml" "itonami.edn" "cron/jobs.json"]]
        (a/equal (sha (str dir f)) (get srcs f) (str "source drift: " f)))
      (a/equal (get-in profile [:profile "profile.yaml"]) (fs/readFileSync (str dir "profile.yaml") "utf8"))
      (a/equal (get-in profile [:profile "itonami.edn"]) (fs/readFileSync (str dir "itonami.edn") "utf8")))
    (a/ok (str/includes? (d/profile-description profile) "propose-only"))
    (let [p (clone profile)] (aset (aget p "profile") "id" "adnetwork-scout") (a/ok (some #{"profile-id"} (d/profile-problems p))))
    (let [p (clone profile)] (aset (aget p "profile") "profile.yaml" "description: 'publishes ads'") (a/ok (some #{"authority"} (d/profile-problems p))))
    (let [p (clone profile)] (aset (aget p "profile") "itonami.edn" "{:profile/id \"murakumo-adnetwork\" :runtime {:engine :hermes}}") (a/ok (some #{"runtime"} (d/profile-problems p))))))

(test "descriptor passes the canonical runtime and derives connect-src for one origin"
  (fn [] (a/deepEqual (vec (runtime/clientRuntimeProblems (d/descriptor) #js[d/capability d/records-capability] #js["tauri://localhost"])) [])
    (a/equal (runtime/clientRuntimeConnectSrc (d/descriptor) #js[d/capability d/records-capability] #js[] #js["tauri://localhost"])
             "connect-src https://murakumo.cloud")))

(test "CSP in tauri.conf.json and index.html equals the derived connect-src"
  (fn [] (let [conf (js/JSON.parse (fs/readFileSync "src-tauri/tauri.conf.json" "utf8"))
               html (fs/readFileSync "public/index.html" "utf8")
               csp (get-in conf [:app :security :csp])]
    (a/ok (str/includes? csp "connect-src https://murakumo.cloud ipc: http://ipc.localhost;"))
    (a/ok (str/includes? html (str "content=\"" csp "\""))))))

(test "only the two declared reads match; refresh, POST and other paths do not"
  (fn [] (let [[inv free] d/operations]
    (a/ok (runtime/clientEndpointAllowsRequest (:endpoint inv) (d/inventory-url) "GET"))
    (a/ok (runtime/clientEndpointAllowsRequest (:endpoint free) (d/free-status-url) "GET"))
    (a/equal (runtime/clientEndpointAllowsRequest (:endpoint inv) (str (d/inventory-url) "?refresh=1") "GET") false)
    (a/equal (runtime/clientEndpointAllowsRequest (:endpoint inv) (d/inventory-url) "POST") false)
    (a/equal (runtime/clientEndpointAllowsRequest (:endpoint free) "https://murakumo.cloud/api/v1/free/impression" "GET") false)
    (a/equal (runtime/clientEndpointAllowsRequest (:endpoint free) "https://murakumo.cloud/api/v1/free/funding" "GET") false)
    (a/equal (runtime/clientEndpointAllowsRequest (:endpoint inv) "https://ads.x402.nexus/campaigns" "GET") false))))

(test "live (2026-11-18): inventory unmeasured, free tier house-sponsored, verdict unknown"
  (fn [] (let [i (d/inventory inventory-live) f (d/free-status free-live) v (d/funding i f)]
    (a/equal (:measured i) false) (a/equal (:campaigns i) nil) (a/equal (:funding-source i) "house")
    (a/equal (:funding-source f) "house-sponsorship") (a/equal (:advertiser-funded f) 0)
    (a/equal (:verdict v) "unknown")
    (a/deepEqual (:reasons v) ["inventory-unmeasured" "inventory-stale" "no-advertiser-funding"])
    (a/deepEqual (d/funding nil nil) {:verdict "not-read" :reasons ["inventory-not-read" "free-status-not-read"]})
    (a/deepEqual (:reasons (d/funding nil f)) ["inventory-not-read" "no-advertiser-funding"]))))

(defn measured-inventory [active total]
  (let [b (clone inventory-live)]
    (aset b "measured" true) (aset b "stale" false) (aset b "ageMs" 60000)
    (aset b "campaigns" total) (aset b "active" active)
    (aset b "fundingSource" (if (pos? active) "advertiser" "house"))
    (when (pos? active) (aset b "placements" #js["free-tier" "go-sidebar"]))
    (d/inventory b)))
(defn funded-free [n]
  (let [b (clone free-live)] (aset b "advertiserFunded" n) (aset b "fundingSource" (if (pos? n) "advertiser" "house-sponsorship"))
    (aset b "impressionsPerUnit" (when (pos? n) 3)) (d/free-status b)))

(test "negative controls: advertiser-funded needs both sources, fresh and agreeing"
  (fn []
    (a/deepEqual (d/funding (measured-inventory 1 2) (funded-free 1)) {:verdict "advertiser-funded" :reasons []})
    (a/equal (:verdict (d/funding (measured-inventory 0 0) (funded-free 0))) "house-only")
    (a/equal (:verdict (d/funding (measured-inventory 1 2) nil)) "unknown")
    (a/deepEqual (:reasons (d/funding (measured-inventory 1 2) (funded-free 0))) ["no-advertiser-funding" "sources-disagree"])
    (a/equal (:verdict (d/funding (measured-inventory 0 0) (funded-free 2))) "unknown")
    (let [stale (clone inventory-live)]
      (aset stale "measured" true) (aset stale "stale" true) (aset stale "campaigns" 1) (aset stale "active" 1) (aset stale "fundingSource" "advertiser")
      (a/ok (some #{"inventory-stale"} (:reasons (d/funding (d/inventory stale) (funded-free 1)))))
      (a/equal (:verdict (d/funding (d/inventory stale) (funded-free 1))) "unknown"))))

(test "malformed or contradictory responses are refused, never displayed"
  (fn []
    (a/equal (reason #(d/inventory #js{})) "inventory-shape")
    (let [b (clone inventory-live)] (aset b "fundingSource" "google") (a/equal (reason #(d/inventory b)) "inventory-shape"))
    (let [b (clone inventory-live)] (aset b "measured" "yes") (a/equal (reason #(d/inventory b)) "inventory-shape"))
    (let [b (clone inventory-live)] (aset b "measured" true) (a/equal (reason #(d/inventory b)) "inventory-measured-without-counts"))
    (let [b (clone inventory-live)] (aset b "campaigns" 1) (aset b "active" 2) (a/equal (reason #(d/inventory b)) "inventory-counts"))
    (let [b (clone inventory-live)] (aset b "placements" #js["<script>" 1]) (a/equal (reason #(d/inventory b)) "inventory-shape"))
    (a/equal (reason #(d/free-status #js{:error "x"})) "free-status-shape")
    (let [b (clone free-live)] (aset b "placement" "go-sidebar") (a/equal (reason #(d/free-status b)) "free-status-shape"))
    (let [b (clone free-live)] (aset b "advertiserFunded" -1) (a/equal (reason #(d/free-status b)) "free-status-shape"))
    (let [b (clone free-live)] (aset b "fundingSource" "advertiser") (a/equal (reason #(d/free-status b)) "free-status-funding"))
    (let [b (clone free-live)] (aset b "advertiserFunded" 3) (a/equal (reason #(d/free-status b)) "free-status-funding"))))

;; ---- AWAI-420 recording screens -------------------------------------------------
;; Each block below proves read / write / persist (reload) for one screen.

(def stamp "2026-11-23T09:00:00Z")
(defn example-application []
  {:id "app-1" :applicant "Yamada" :contact "yamada@example.jp" :note "first" :created-at stamp})
(defn example-receipt []
  {:id "rcpt-1" :issued-to "Yamada" :amount-micros 2000000 :memo "memo" :issued-at stamp})
(defn example-impression []
  {:id "evt-1" :kind "impression" :at stamp :campaign-id "cmp-1" :advertiser "did:key:z1" :placement "go-sidebar"})
(defn example-deposit []
  {:id "evt-9" :kind "deposit" :at stamp :payer "Yamada" :amount-micros 500000})
(defn reloaded [store] (d/parse-store (d/serialize-store store)))

(test "records: two reads plus four browser-action writes, no new connect origin"
  (fn []
    (a/equal (count d/operations) 6)
    (let [writes (vec (filter #(= d/records-capability (:capability %)) d/operations))]
      (a/deepEqual (mapv :operation writes)
                   ["record-application" "set-review-state" "record-receipt" "record-ledger-event"])
      (doseq [w writes]
        (a/equal (:consent w) "each-operation")
        (a/equal (:operation w) (get-in w [:endpoint :browser-action]))
        (a/equal (runtime/clientEndpointAllowsRequest (:endpoint w) "https://local.invalid/x" "GET") false)))
    (a/equal (get-in (d/descriptor) [:local-state :adapter]) "localstorage")
    (a/equal (runtime/clientRuntimeConnectSrc (d/descriptor) #js[d/capability d/records-capability] #js[] #js["tauri://localhost"])
             "connect-src https://murakumo.cloud")))

(test "application: read/write/persist round trip, duplicate and smuggled state refused"
  (fn []
    (let [s (d/add-application (d/empty-store) (example-application))]
      (a/equal (count (d/list-applications s)) 1)
      (a/equal (:review-state (first (d/list-applications s))) "received")
      (let [back (reloaded s)]
        (a/deepEqual (d/list-applications back) (d/list-applications s))
        (a/equal (:created-at (first (d/list-applications back))) stamp)))
    (a/equal (reason #(d/add-application (d/add-application (d/empty-store) (example-application)) (example-application)))
             "duplicate-application-id")
    (a/equal (reason #(d/add-application (d/empty-store) (assoc (example-application) :applicant ""))) "application-shape")
    (a/equal (reason #(d/add-application (d/empty-store) (assoc (example-application) :created-at "yesterday"))) "application-shape")
    (a/equal (reason #(d/add-application (d/empty-store) (assoc (example-application) :review-state "approved"))) "application-shape")
    (a/equal (reason #(d/add-application (d/empty-store) (assoc (example-application) :extra "x"))) "application-shape")))

(test "review: fixed enum (received/under-review/approved/rejected) with guarded transitions, no free text"
  (fn []
    (a/deepEqual d/review-states #{"received" "under-review" "approved" "rejected"})
    (let [s0 (d/add-application (d/empty-store) (example-application))
          state-of (fn [s] (:review-state (first (d/list-applications s))))]
      (a/ok (d/review-allowed? "received" "under-review"))
      (a/ok (not (d/review-allowed? "received" "approved")))
      (a/ok (d/review-allowed? "under-review" "approved"))
      (a/ok (d/review-allowed? "under-review" "received"))
      (a/ok (d/review-allowed? "rejected" "received"))
      (a/ok (not (d/review-allowed? "approved" "received")))
      (a/equal (state-of (d/transition-review s0 "app-1" "under-review")) "under-review")
      (a/equal (state-of (-> s0 (d/transition-review "app-1" "under-review") (d/transition-review "app-1" "approved"))) "approved")
      (a/equal (state-of (d/transition-review s0 "app-1" "rejected")) "rejected")
      (a/equal (reason #(d/transition-review s0 "app-1" "approved")) "review-transition")
      (a/equal (reason #(d/transition-review s0 "app-1" "maybe")) "review-state-unknown")
      (a/equal (reason #(d/transition-review s0 "app-1" "")) "review-state-unknown")
      (a/equal (reason #(d/transition-review s0 "ghost" "under-review")) "application-not-found")
      (let [terminal (d/transition-review (d/transition-review s0 "app-1" "under-review") "app-1" "approved")]
        (doseq [to ["received" "under-review" "rejected" "approved"]]
          (a/equal (reason #(d/transition-review terminal "app-1" to)) "review-transition"))
        (a/deepEqual (d/list-applications (reloaded terminal)) (d/list-applications terminal))))
    (a/equal (reason #(d/parse-store (d/serialize-store (d/add-application (d/empty-store) (example-application)))))
             nil)
    (let [tampered (js/JSON.parse (d/serialize-store (d/add-application (d/empty-store) (example-application))))]
      (aset (aget (aget tampered "applications") 0) "review-state" "maybe")
      (a/equal (reason #(d/parse-store (js/JSON.stringify tampered))) "review-state-unknown"))))

(test "receipt: read/write/persist, issuance metadata only"
  (fn []
    (let [s (d/add-receipt (d/empty-store) (example-receipt))]
      (a/equal (count (d/list-receipts s)) 1)
      (a/deepEqual (d/list-receipts (reloaded s)) (d/list-receipts s)))
    (a/equal (reason #(d/add-receipt (d/add-receipt (d/empty-store) (example-receipt)) (example-receipt)))
             "duplicate-receipt-id")
    (a/equal (reason #(d/add-receipt (d/empty-store) (assoc (example-receipt) :amount-micros 0))) "receipt-shape")
    (a/equal (reason #(d/add-receipt (d/empty-store) (assoc (example-receipt) :amount-micros -5))) "receipt-shape")
    (a/equal (reason #(d/add-receipt (d/empty-store) (assoc (example-receipt) :issued-to ""))) "receipt-shape")
    (a/equal (reason #(d/add-receipt (d/empty-store) (assoc (example-receipt) :issued-at "not-a-stamp"))) "receipt-shape")))

(test "ledger: read/write/persist, duplicate event id refused and the refusal is the visible text"
  (fn []
    (let [s (-> (d/empty-store)
                (d/record-ledger-event (example-impression))
                (d/record-ledger-event (assoc (example-impression) :id "evt-2" :kind "click"))
                (d/record-ledger-event (example-deposit)))]
      (a/equal (count (d/list-ledger s)) 3)
      (a/deepEqual (d/list-ledger (reloaded s)) (d/list-ledger s))
      (a/equal (reason #(d/record-ledger-event s (example-impression))) "duplicate-event-id")
      (a/equal (reason #(d/record-ledger-event s (assoc (example-deposit) :amount-micros 1))) "duplicate-event-id")
      (a/equal (d/duplicate-event-refusal "evt-1") "二重登録拒否: イベント evt-1 は既に記録されています。"))
    (a/equal (reason #(d/record-ledger-event (d/empty-store) (assoc (example-impression) :kind "view"))) "ledger-kind-unknown")
    (a/equal (reason #(d/record-ledger-event (d/empty-store) (assoc (example-impression) :placement ""))) "ledger-entry-shape")
    (a/equal (reason #(d/record-ledger-event (d/empty-store) (assoc (example-deposit) :amount-micros 0))) "ledger-entry-shape")
    (a/equal (reason #(d/record-ledger-event (d/empty-store) (dissoc (example-impression) :campaign-id))) "ledger-entry-shape")
    (a/equal (reason #(d/parse-store "{not json")) "records-parse")
    (a/equal (reason #(d/parse-store (d/serialize-store (d/record-ledger-event (d/empty-store) (example-impression))))) nil)))

(test "app wires the dedup refusal to an on-screen element (ledger-refusal)"
  (fn [] (let [src (fs/readFileSync "src/koukoku/app.cljs" "utf8")]
    (a/ok (str/includes? src "ledger-refusal"))
    (a/ok (str/includes? src "duplicate-event-refusal"))
    (a/ok (str/includes? src "localStorage"))
    (a/ok (str/includes? src "browserAdapters")))))
