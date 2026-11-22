(ns scout.domain
  "Pure, fail-closed domain for the adnetwork-scout bot's public export.
  Runs in the browser (Squint) and on a JVM host. No I/O here.

  The export is a static file committed to the public repository
  network-awai/app-itonami-adserver and served by raw.githubusercontent.com
  (owner decision 2026-11-18, AWAI-3335). Its rules mirror
  scripts/adnetwork_public_export.py; a row the exporter would withhold is
  refused here too."
  (:require [clojure.string :as str]))

(def profile-id "adnetwork-scout")
(def export-origin "https://raw.githubusercontent.com")
(def export-path "/network-awai/app-itonami-adserver/main/public/adnetwork-scout/advertisers.v1.json")
(def capability "adnetwork-scout-read")
(def service "adnetwork-scout-export")
;; restricted-wasm-v1: mountRestrictedClientApp bridges at most 1 MiB of text
;; to the guest, so the export is bounded to 1 MiB (was 2 MiB, KOTOBA-159).
(def max-response-bytes 1048576)
(def max-rows 10000)
(def export-schema "awai.adnetwork-scout.public-export.v1")
(def fields ["network" "advertiserKey" "advertiserName" "evidenceUrl" "observedAt" "kind"])

;; One read, one exact path, no query, no redirects.
;; restricted-wasm-v1 (KOTOBA-159): the disclosure the consent dialog shows
;; first (Legal, 2026-11-22). raw.githubusercontent.com is served by GitHub,
;; Inc. (United States). foreign-info-url is the app's own foreign-information
;; page, section #us (site/foreign-info.html), not the PPC list page. The app
;; stores no permission; the withdrawal place is the app's 設定 › 許可した送信
;; screen, which says so.
(def foreign-info-url
  "Where site/foreign-info.html is published (owner decision, KOTOBA-159)."
  "TBD")

(def disclosure
  {:recipient "GitHub, Inc." :country "US" :country-name "アメリカ合衆国"
   :foreign-info-url foreign-info-url
   :data ["端末の IP アドレス" "送信の時刻" "ブラウザの種類と版"] :special-care false
   :purpose "公開された広告主の一覧を GitHub から読み、この端末で表示するため" :frequency "one-time"
   :withdrawal "設定 › 許可した送信"})

(def operations
  [{:capability capability :service service :operation "read-public-export" :consent "each-operation"
    :data-categories ["request-ip"] :disclosure disclosure
    :endpoint {:origin export-origin :routes [{:method "GET" :path export-path :query-params []}]}}])

(defn descriptor []
  {:schema "kotoba.app.client-runtime.v1" :target "browser" :profile "restricted-wasm-v1" :entry "index.html"
   :local-state {:adapter "memory" :schema "itonami.koukoku-scout.local.v1" :version 1}
   :workers [] :operations operations})

(defn export-url [] (str export-origin export-path))

(defn- match? [re s] (and (string? s) (some? (re-matches re s))))
(defn- int-value? [x] #?(:clj (integer? x) :cljs (js/Number.isSafeInteger x)))
(defn- array-value? [x] #?(:clj (sequential? x) :cljs (js/Array.isArray x)))
(defn- object-value? [x] #?(:clj (map? x) :cljs (and (some? x) (= "object" (js/typeof x)) (not (js/Array.isArray x)))))
(defn- bool? [x] (or (true? x) (false? x)))
(defn- nat-int? [x] (and (int-value? x) (>= x 0)))
(defn- short-text? [x n] (and (string? x) (not (str/blank? x)) (<= (count x) n)))
(defn- obj-keys [x] #?(:clj (set (map name (keys x))) :cljs (set (js/Object.keys x))))
(defn- refuse
  ([reason] (throw (ex-info reason {:reason reason})))
  ([reason data] (throw (ex-info reason (assoc data :reason reason)))))

;; ---- bundled paired profile -------------------------------------------------

(defn profile-problems
  "The bundle is a copy of hermes/profiles/adnetwork-scout in this repository.
  Refuse it unless it names that profile, records a source revision and digests,
  declares the itonami agent runtime, and its SOUL still says outreach sends nothing."
  [doc]
  (let [p (:profile doc) srcs (get-in doc [:provenance :sources])
        edn (get p "itonami.edn") soul (get p "authority")]
    (cond-> []
      (not= "awai.koukoku-scout.paired-profile.v1" (:schema doc)) (conj "schema")
      (not (match? #"[0-9a-f]{40}" (get-in doc [:provenance :revision]))) (conj "provenance")
      (not (and (object-value? srcs)
                (every? #(match? #"[0-9a-f]{64}" (get srcs %)) ["profile.yaml" "itonami.edn" "SOUL.md" "cron/jobs.json"]))) (conj "source-digests")
      (not= profile-id (:id p)) (conj "profile-id")
      (not (and (string? edn) (str/includes? edn (str ":profile/id \"" profile-id "\""))
                (str/includes? edn ":engine :itonami-agent"))) (conj "runtime")
      (not (and (array-value? soul) (some #(and (string? %) (str/includes? % "nothing is ever sent")) soul))) (conj "authority")
      (not (and (array-value? (:cron p)) (every? #(and (short-text? (:name %) 120) (bool? (:enabled %))) (:cron p)))) (conj "cron"))))

(defn profile-description [doc]
  (second (re-find #"description: ([^\n]+)" (str (get-in doc [:profile "profile.yaml"])))))

;; ---- public export --------------------------------------------------------------

(def ^:private network-re #"[a-z0-9][a-z0-9._-]{0,63}")
(def ^:private key-re #"(?=.{3,253}$)[a-z0-9-]+(\.[a-z0-9-]+)+(/[A-Za-z0-9._~-]+)*")
(def ^:private kind-re #"[a-z0-9][a-z0-9-]{0,63}")
(def ^:private url-re #"https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[^\s@]*)?")
(def ^:private stamp-re #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})")

(defn- no-at? [x] (or (nil? x) (and (string? x) (not (str/includes? x "@")))))

(defn row-problem
  "nil when the row is publishable, otherwise the first failing check."
  [r]
  (cond
    (not (object-value? r)) "row-shape"
    (not= (set fields) (obj-keys r)) "row-fields"
    (not (every? #(no-at? (get r (keyword %))) fields)) "row-email-like"
    (not (match? network-re (:network r))) "row-network"
    (not (match? key-re (:advertiserKey r))) "row-advertiser-key"
    (not (match? url-re (:evidenceUrl r))) "row-evidence-url"
    (not (match? stamp-re (:observedAt r))) "row-observed-at"
    (not (or (nil? (:kind r)) (match? kind-re (:kind r)))) "row-kind"
    (not (or (nil? (:advertiserName r))
             (and (short-text? (:advertiserName r) 200) (= (:advertiserName r) (str/trim (:advertiserName r)))
                  (not (re-find #"[\x00-\x1f]" (:advertiserName r)))))) "row-advertiser-name"
    :else nil))

(defn public-export
  "Validate the whole file. Any bad row refuses the export: the exporter
  already withheld what it could not publish, so a bad row here means the
  file is not what the exporter wrote."
  [body]
  (let [src (:source body) rows (:advertisers body)]
    (when-not (and (object-value? body) (= export-schema (:schema body))
                   (object-value? src) (= profile-id (:profile src))
                   (= "cloud_itonami.adnetwork_advertiser" (:catalog src))
                   (or (nil? (:snapshotId src)) (match? #"[0-9]{1,20}" (:snapshotId src)))
                   (match? stamp-re (:generatedAt body))
                   (array-value? (:fields body)) (= fields (vec (:fields body)))
                   (nat-int? (:count body)) (nat-int? (:withheld body))
                   (array-value? rows) (<= (count rows) max-rows))
      (refuse "export-shape"))
    (when-not (= (:count body) (count rows)) (refuse "export-count"))
    (doseq [[i r] (map-indexed vector rows)]
      (when-let [p (row-problem r)] (refuse "export-row" {:row i :problem p})))
    {:generated-at (:generatedAt body) :snapshot-id (:snapshotId src)
     :count (:count body) :withheld (:withheld body)
     :rows (mapv (fn [r] {:network (:network r) :advertiser-key (:advertiserKey r)
                          :advertiser-name (:advertiserName r) :evidence-url (:evidenceUrl r)
                          :observed-at (:observedAt r) :kind (:kind r)}) rows)}))

(defn- parse-ms [stamp]
  #?(:clj (.toEpochMilli (java.time.Instant/from (.parse java.time.format.DateTimeFormatter/ISO_OFFSET_DATE_TIME stamp)))
     :cljs (let [ms (js/Date.parse stamp)] (when (js/Number.isFinite ms) ms))))

(defn summary
  "Counts the user can check: rows, distinct advertisers, per network, and the
  newest observation (compared as instants, not strings). `age-ms` is relative
  to `now-ms`; older than 8 days is stale (collect is expected at least weekly)."
  [ex now-ms]
  (let [rows (:rows ex)
        by-net (->> rows (group-by :network)
                    (map (fn [[n rs]] {:network n :rows (count rs) :advertisers (count (set (map :advertiser-key rs)))}))
                    (sort-by (juxt (comp - :advertisers) :network)) vec)
        newest (when (seq rows) (apply max-key #(or (parse-ms (:observed-at %)) 0) rows))
        gen-ms (parse-ms (:generated-at ex))
        age (when gen-ms (- now-ms gen-ms))]
    {:rows (count rows)
     :advertisers (count (set (map (juxt :network :advertiser-key) rows)))
     :networks by-net
     :newest (:observed-at newest)
     :withheld (:withheld ex)
     :age-ms age
     :stale (or (nil? age) (> age (* 8 24 3600 1000)))}))
