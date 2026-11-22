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
  (fn [] (a/deepEqual (vec (runtime/clientRuntimeProblems (d/descriptor) #js[d/capability] #js["tauri://localhost"])) [])
    (a/equal (runtime/clientRuntimeConnectSrc (d/descriptor) #js[d/capability] #js[] #js["tauri://localhost"])
             "connect-src https://murakumo.cloud")))

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
