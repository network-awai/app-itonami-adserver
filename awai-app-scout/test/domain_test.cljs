(ns domain-test
  (:require [scout.domain :as d] [clojure.string :as str] ["node:test" :refer [test]] ["node:assert/strict" :as a] ["node:fs" :as fs]
            ["node:crypto" :as crypto]
            ["../../vendor/assets/kotoba-client-runtime.js" :as runtime]))

;; Synthetic: produced by scripts/adnetwork_public_export.py from made-up rows
;; (one of four withheld for an e-mail-like name). Not catalog data.
(def synthetic (js/JSON.parse (fs/readFileSync "test/fixtures/export-synthetic.json" "utf8")))
(def profile (js/JSON.parse (fs/readFileSync "profiles/paired-profile.json" "utf8")))
(defn clone [x] (js/JSON.parse (js/JSON.stringify x)))
(defn reason [f] (try (f) nil (catch :default e (let [x (ex-data e)] (if (:problem x) [(:reason x) (:problem x)] (:reason x))))))
(defn sha [file] (-> (crypto/createHash "sha256") (.update (fs/readFileSync file)) (.digest "hex")))
(defn with-row [f] (let [b (clone synthetic)] (f (aget (aget b "advertisers") 0)) b))

(test "bundled profile is adnetwork-scout and matches the source files in this repository"
  (fn []
    (a/deepEqual (d/profile-problems profile) [])
    (let [dir (str "../" (get-in profile [:provenance :path])) srcs (get-in profile [:provenance :sources])]
      (doseq [f ["profile.yaml" "itonami.edn" "SOUL.md" "cron/jobs.json"]]
        (a/equal (sha (str dir f)) (get srcs f) (str "source drift: " f)))
      (a/equal (get-in profile [:profile "profile.yaml"]) (fs/readFileSync (str dir "profile.yaml") "utf8"))
      (a/equal (get-in profile [:profile "itonami.edn"]) (fs/readFileSync (str dir "itonami.edn") "utf8"))
      (doseq [l (get-in profile [:profile "authority"])]
        (a/ok (str/includes? (fs/readFileSync (str dir "SOUL.md") "utf8") l) "authority line is verbatim from SOUL.md")))
    (let [p (clone profile)] (aset (aget p "profile") "id" "murakumo-adnetwork") (a/ok (some #{"profile-id"} (d/profile-problems p))))
    (let [p (clone profile)] (aset (aget p "profile") "authority" #js["sends outreach"]) (a/ok (some #{"authority"} (d/profile-problems p))))
    (let [p (clone profile)] (aset (aget p "profile") "itonami.edn" "{:profile/id \"adnetwork-scout\" :runtime {:engine :hermes}}") (a/ok (some #{"runtime"} (d/profile-problems p))))))

(test "descriptor passes the canonical runtime and derives connect-src for one origin"
  (fn [] (a/deepEqual (vec (runtime/clientRuntimeProblems (d/descriptor) #js[d/capability] #js["tauri://localhost"])) [])
    (a/equal (runtime/clientRuntimeConnectSrc (d/descriptor) #js[d/capability] #js[] #js["tauri://localhost"])
             "connect-src https://raw.githubusercontent.com")))

(test "only the export file matches; other repositories, branches, files, queries and POST do not"
  (fn [] (let [ep (:endpoint (first d/operations)) base "https://raw.githubusercontent.com/network-awai/app-itonami-adserver/"]
    (a/ok (runtime/clientEndpointAllowsRequest ep (d/export-url) "GET"))
    (a/equal (runtime/clientEndpointAllowsRequest ep (d/export-url) "POST") false)
    (a/equal (runtime/clientEndpointAllowsRequest ep (str (d/export-url) "?token=x") "GET") false)
    (a/equal (runtime/clientEndpointAllowsRequest ep (str base "main/README.md") "GET") false)
    (a/equal (runtime/clientEndpointAllowsRequest ep (str base "other/public/adnetwork-scout/advertisers.v1.json") "GET") false)
    (a/equal (runtime/clientEndpointAllowsRequest ep "https://raw.githubusercontent.com/someone/else/main/public/adnetwork-scout/advertisers.v1.json" "GET") false)
    (a/equal (runtime/clientEndpointAllowsRequest ep "https://murakumo.cloud/api/v1/ads/inventory" "GET") false))))

(test "exporter output is accepted, and the summary counts per network"
  (fn [] (let [ex (d/public-export synthetic)
               s (d/summary ex (js/Date.parse "2026-11-18T02:00:00Z"))]
    (a/equal (:count ex) 3) (a/equal (:withheld ex) 1)
    (a/deepEqual (mapv :advertiser-key (:rows ex)) ["brand-a.example" "brand-b.example" "brand-a.example"])
    (a/equal (:rows s) 3) (a/equal (:advertisers s) 3)
    (a/deepEqual (:networks s) [{:network "example-net" :rows 2 :advertisers 2} {:network "other-net" :rows 1 :advertisers 1}])
    (a/equal (:newest s) "2026-11-17T01:00:00Z" "newest compares instants, not strings")
    (a/equal (:stale s) false)
    (a/equal (:stale (d/summary ex (js/Date.parse "2026-11-27T02:00:00Z"))) true))))

(test "an export with private columns, bad rows or wrong counts is refused, never displayed"
  (fn []
    (a/equal (reason #(d/public-export #js{})) "export-shape")
    (a/equal (reason #(d/public-export (doto (clone synthetic) (aset "schema" "v2")))) "export-shape")
    (a/equal (reason #(d/public-export (doto (clone synthetic) (aset "count" 4)))) "export-count")
    (let [b (clone synthetic)] (aset (aget b "source") "profile" "murakumo-adnetwork") (a/equal (reason #(d/public-export b)) "export-shape"))
    (let [b (clone synthetic)] (aset b "fields" #js["network" "notes"]) (a/equal (reason #(d/public-export b)) "export-shape"))
    (a/deepEqual (reason #(d/public-export (with-row (fn [r] (aset r "notes" "private"))))) ["export-row" "row-fields"])
    (a/deepEqual (reason #(d/public-export (with-row (fn [r] (aset r "raw_json" "{}"))))) ["export-row" "row-fields"])
    (a/deepEqual (reason #(d/public-export (with-row (fn [r] (aset r "advertiserName" "a@b.example"))))) ["export-row" "row-email-like"])
    (a/deepEqual (reason #(d/public-export (with-row (fn [r] (aset r "evidenceUrl" "http://ads.example/x"))))) ["export-row" "row-evidence-url"])
    (a/deepEqual (reason #(d/public-export (with-row (fn [r] (aset r "evidenceUrl" "javascript:alert(1)"))))) ["export-row" "row-evidence-url"])
    (a/deepEqual (reason #(d/public-export (with-row (fn [r] (aset r "advertiserKey" "Not A Domain"))))) ["export-row" "row-advertiser-key"])
    (a/deepEqual (reason #(d/public-export (with-row (fn [r] (aset r "observedAt" "2026-11-16T09:00:00"))))) ["export-row" "row-observed-at"])
    (a/deepEqual (reason #(d/public-export (with-row (fn [r] (aset r "advertiserName" " padded "))))) ["export-row" "row-advertiser-name"])
    (a/deepEqual (reason #(d/public-export (with-row (fn [r] (aset r "kind" "Bad Kind"))))) ["export-row" "row-kind"])))
