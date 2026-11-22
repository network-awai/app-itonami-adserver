(ns scout.app
  (:require [scout.domain :as d] ["@awai/ui" :as ui] [clojure.string :as str]
            [scout.guest-port :as port] ["@awai/profile-text$default" :as profile-text]))

(defonce state (atom {:locale "ja" :notice "" :export nil :sha256 nil :busy false :read-at nil}))
(def node ui/node)
(declare render!)

(def copy
  {:ja {:title "Koukoku Scout" :lead "広告主を探す bot が集めた記録を、公開されている export から確かめます。"
        :bot "組になる bot" :description "役割" :runtime "実行" :cron "定期実行" :enabled "有効" :disabled "停止" :limits "bot の制約"
        :authority "この app は公開 export を読むだけです。営業の連絡や申込は送りません。"
        :read "公開 export を取得" :language "言語"
        :consent-note "取得のたびに確認画面が開きます。拒否すると何も送信しません。自動更新はしません。"
        :endpoint "接続先" :not-published "export はまだ公開されていません（404）。件数は 0 ではなく不明です。"
        :summary "export の中身" :rows "記録" :advertisers "広告主（ネットワーク別に数える）" :withheld "公開条件を満たさず除いた記録"
        :generated "作成" :fresh "新しい" :stale "古い（8 日超）" :newest "最新の観測" :snapshot "snapshot" :digest "sha256"
        :networks "ネットワーク別" :list "記録" :evidence "出所" :unknown "不明" :none "なし"
        :denied "取得を中止しました。何も送信していません。" :failed "取得できません: " :fetched "取得しました。"
        :refused "export の形が正しくないため表示しません: "
        :profile-invalid "同梱の bot profile を確認できないため、表示を停止しました。"
        :read-at "最終取得" :origin "出典"}
   :en {:title "Koukoku Scout" :lead "Check what the advertiser-scouting bot collected, from its public export."
        :bot "Paired bot" :description "Role" :runtime "Runtime" :cron "Schedules" :enabled "enabled" :disabled "disabled" :limits "Bot limits"
        :authority "This app only reads the public export. It sends no outreach and no applications."
        :read "Read public export" :language "Language"
        :consent-note "Each read opens an approval dialog. Denying sends nothing. Nothing refreshes automatically."
        :endpoint "Endpoint" :not-published "The export is not published yet (404). The count is unknown, not zero."
        :summary "Export contents" :rows "Records" :advertisers "Advertisers (counted per network)" :withheld "Records withheld by the publishing rules"
        :generated "Generated" :fresh "fresh" :stale "stale (over 8 days)" :newest "Newest observation" :snapshot "Snapshot" :digest "sha256"
        :networks "By network" :list "Records" :evidence "Source" :unknown "unknown" :none "none"
        :denied "Cancelled. Nothing was sent." :failed "Could not read: " :fetched "Read complete."
        :refused "The export is malformed, so it is not shown: "
        :profile-invalid "The bundled bot profile could not be verified, so the view is stopped."
        :read-at "Last read" :origin "Source"}})

(defn t [k] (get-in copy [(:locale @state) k]))
(defn or-unknown [x] (if (nil? x) (t :unknown) (str x)))
(defn time-text [ms] (if ms (.format (js/Intl.DateTimeFormat. (:locale @state) #js{:dateStyle "medium" :timeStyle "short"}) (js/Date. ms)) (t :unknown)))

(def profile (js/JSON.parse profile-text))
(def profile-ok (empty? (d/profile-problems profile)))

(defn hex [buf] (str/join (map #(.padStart (.toString % 16) 2 "0") (js/Array.from (js/Uint8Array. buf)))))

;; restricted-wasm-v1: the guest reaches the host broker only through the port.
;; The host bridges UTF-8 text; the digest is taken over its UTF-8 bytes, which
;; equal the file's bytes for the exporter's BOM-less UTF-8 JSON.
(defn bounded-text [m]
  (try (.encode (js/TextEncoder.) (port/http-text m d/max-response-bytes))
       (catch :default e
         (throw (if (= 404 (:status (ex-data e))) (ex-info "not-published" {:reason "not-published"}) e)))))

(defn notice! [s] (swap! state assoc :notice s) (render!))

(defn message [e]
  (let [reason (:reason (ex-data e))]
    (cond
      (re-find #"not approved" (str (.-message e))) (t :denied)
      (= "not-published" reason) (t :not-published)
      (= "export-row" reason) (str (t :refused) "row " (:row (ex-data e)) " " (:problem (ex-data e)))
      reason (str (t :refused) reason)
      :else (str (t :failed) (.-message e)))))

(defn read! []
  (swap! state assoc :busy true) (render!)
  (-> (port/invoke #js{:capability d/capability :service d/service :operation "read-public-export"}
               #js{:input (d/export-url) :init #js{:method "GET"}})
      (.then bounded-text)
      (.then (fn [buf]
               (.then (js/crypto.subtle.digest "SHA-256" buf)
                      (fn [digest]
                        (let [ex (d/public-export (js/JSON.parse (.decode (js/TextDecoder.) buf)))]
                          (swap! state assoc :export ex :sha256 (hex digest) :read-at (js/Date.now))
                          (notice! (t :fetched)))))))
      (.catch (fn [e] (swap! state assoc :export nil :sha256 nil) (notice! (message e))))
      (.finally (fn [] (swap! state assoc :busy false) (render!)))))

(defn profile-card []
  (let [p (:profile profile)]
    (ui/card
     (ui/heading 2 (str (t :bot) ": " (:id p)))
     [:dl
      [:dt (t :description)] [:dd {:id "bot-description"} (or-unknown (d/profile-description profile))]
      [:dt (t :runtime)] [:dd "itonami-agent / itonami scheduler"]
      [:dt (t :cron)] [:dd [:ul {:class "plain"} (map (fn [j] [:li (str (:name j) " — " (:schedule j) " (" (t (if (:enabled j) :enabled :disabled)) ")")]) (:cron p))]]
      [:dt (t :limits)] [:dd [:ul {:class "plain" :id "limits"} (map (fn [l] [:li (str/replace l #"^- " "")]) (:authority p))]]]
     [:p {:id "authority"} (t :authority)]
     [:p {:class "awai-caption awai-muted"} (str (t :origin) ": " (get-in profile [:provenance :repository]) " " (get-in profile [:provenance :path]) " " (subs (get-in profile [:provenance :revision]) 0 12))])))

(defn export-card []
  (when-let [ex (:export @state)]
    (let [s (d/summary ex (js/Date.now))]
      (ui/card
       (ui/heading 2 (t :summary))
       [:dl {:id "summary"}
        [:dt (t :rows)] [:dd (str (:rows s))]
        [:dt (t :advertisers)] [:dd (str (:advertisers s))]
        [:dt (t :withheld)] [:dd (str (:withheld s))]
        [:dt (t :generated)] [:dd {:data-stale (str (:stale s))} (str (:generated-at ex) " (" (t (if (:stale s) :stale :fresh)) ")")]
        [:dt (t :newest)] [:dd (or-unknown (:newest s))]
        [:dt (t :snapshot)] [:dd (or-unknown (:snapshot-id ex))]
        [:dt (t :digest)] [:dd {:id "digest" :class "mono"} (:sha256 @state)]]
       (ui/heading 3 (t :networks))
       (if (seq (:networks s))
         [:ul {:id "networks"} (map (fn [n] [:li (str (:network n) ": " (:advertisers n) " / " (:rows n))]) (:networks s))]
         [:p (t :none)])
       (ui/heading 3 (t :list))
       [:ul {:id "rows" :class "rows"}
        (map (fn [r] [:li [:strong (:advertiser-key r)] (when (:advertiser-name r) (str " — " (:advertiser-name r)))
                      [:br] [:span {:class "awai-caption awai-muted"} (str (:network r) " · " (or (:kind r) (t :unknown)) " · " (:observed-at r))]
                      [:br] [:span {:class "awai-caption mono"} (str (t :evidence) ": " (:evidence-url r))]])
             (take 200 (:rows ex)))]))))

(defn render! []
  (let [root (.getElementById js/document "app") busy (or (:busy @state) (not (port/connected?)))]
    (set! (.. js/document -documentElement -lang) (:locale @state))
    (.replaceChildren root
     (node [:div {:class "awai-ui awai-container"}
            [:header {:class "app-header"} (ui/heading 1 (t :title))
             (ui/field {:id "language" :label (t :language)}
               [:select {:value (:locale @state) :on-change (fn [e] (swap! state assoc :locale (.. e -target -value)) (render!))}
                [:option {:value "ja" :selected (= "ja" (:locale @state))} "日本語"] [:option {:value "en" :selected (= "en" (:locale @state))} "English"]])]
            (ui/status (:notice @state) {:id "notice"})
            [:main {:id "main"}
             (if-not profile-ok
               [:p {:id "profile-invalid"} (t :profile-invalid)]
               (ui/stack
                [:p {:class "lead"} (t :lead)]
                (profile-card)
                [:div {:class "actions"}
                 (ui/button (t :read) {:attrs {:id "read-export" :disabled busy :on-click read!}})]
                [:p {:class "awai-caption awai-muted"} (str (t :endpoint) ": " (d/export-url) " — " (t :consent-note))]
                (when (:read-at @state) [:p {:class "awai-caption awai-muted"} (str (t :read-at) ": " (time-text (:read-at @state)))])
                (export-card)))]]))))

(port/listen! render!)
(render!)
