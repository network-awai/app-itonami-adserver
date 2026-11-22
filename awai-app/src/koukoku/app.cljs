(ns koukoku.app
  (:require [koukoku.domain :as d] ["@awai/ui" :as ui] [clojure.string :as str]
            [koukoku.guest-port :as port] ["@awai/profile-text$default" :as profile-text]))

(defonce state (atom {:locale "ja" :notice "" :inventory nil :free nil :busy false :read-at nil}))
(def node ui/node)
(declare render!)

(def copy
  {:ja {:title "Koukoku" :lead "広告 bot の仕事を、手元で確かめる。広告主のお金で回っているかを、公開データから判定します。"
        :bot "組になる bot" :description "役割" :runtime "実行" :cron "定期実行" :enabled "有効" :disabled "停止"
        :authority "この bot は提案だけを行い、申込・配信・課金はしません。この app も読み取りだけです。"
        :read-inventory "広告在庫を取得" :read-free "無料枠の資金を取得" :language "言語"
        :consent-note "取得のたびに確認画面が開きます。拒否すると何も送信しません。自動更新はしません。"
        :endpoint "接続先" :funding "資金の判定" :not-read "まだ取得していません。"
        :verdicts {"not-read" "未取得" "advertiser-funded" "広告主の資金で運用中" "house-only" "自社負担のみ（広告主の資金なし）" "unknown" "判定できません"}
        :reasons {"inventory-not-read" "広告在庫を未取得" "free-status-not-read" "無料枠を未取得"
                  "inventory-unmeasured" "広告在庫が計測されていない" "inventory-stale" "広告在庫の計測が古い"
                  "free-tier-unavailable" "無料枠が使えない" "no-advertiser-funding" "広告主の資金で動く campaign が 0 件"
                  "sources-disagree" "在庫と無料枠の結果が食い違う"}
        :inventory "広告在庫（/api/v1/ads/inventory）" :measured "計測" :yes "あり" :no "なし" :stale "古い"
        :campaigns "campaign（有効 / 全体）" :listed "掲載承認" :placements "掲載枠" :source "資金の出所" :last-sync "最終同期" :unknown "不明"
        :free "無料枠（/api/v1/free/status）" :available "利用可" :funded "広告主の資金で動く campaign" :sponsor "負担元"
        :unit "1 回の価格 / 1 日の上限" :per-unit "1 回に必要な表示数"
        :denied "取得を中止しました。何も送信していません。" :failed "取得できません: " :fetched "取得しました。"
        :profile-invalid "同梱の bot profile を確認できないため、表示を停止しました。"
        :read-at "最終取得" :origin "出典"}
   :en {:title "Koukoku" :lead "Check the ad bot's work on your own device. Public data tells whether advertiser money is funding it."
        :bot "Paired bot" :description "Role" :runtime "Runtime" :cron "Schedules" :enabled "enabled" :disabled "disabled"
        :authority "This bot only proposes; it never submits, serves or bills. This app only reads."
        :read-inventory "Read ad inventory" :read-free "Read free-tier funding" :language "Language"
        :consent-note "Each read opens an approval dialog. Denying sends nothing. Nothing refreshes automatically."
        :endpoint "Endpoint" :funding "Funding verdict" :not-read "Not read yet."
        :verdicts {"not-read" "Not read" "advertiser-funded" "Funded by advertisers" "house-only" "House-funded only (no advertiser money)" "unknown" "Cannot decide"}
        :reasons {"inventory-not-read" "inventory not read" "free-status-not-read" "free tier not read"
                  "inventory-unmeasured" "inventory not measured" "inventory-stale" "inventory measurement is stale"
                  "free-tier-unavailable" "free tier unavailable" "no-advertiser-funding" "0 advertiser-funded campaigns"
                  "sources-disagree" "inventory and free tier disagree"}
        :inventory "Ad inventory (/api/v1/ads/inventory)" :measured "Measured" :yes "yes" :no "no" :stale "stale"
        :campaigns "Campaigns (active / total)" :listed "Approved listings" :placements "Placements" :source "Funding source" :last-sync "Last sync" :unknown "unknown"
        :free "Free tier (/api/v1/free/status)" :available "Available" :funded "Advertiser-funded campaigns" :sponsor "Sponsored by"
        :unit "Unit price / daily cap" :per-unit "Impressions per unit"
        :denied "Cancelled. Nothing was sent." :failed "Could not read: " :fetched "Read complete."
        :profile-invalid "The bundled bot profile could not be verified, so the view is stopped."
        :read-at "Last read" :origin "Source"}})

(defn t [k] (get-in copy [(:locale @state) k]))
(defn yes-no [b] (t (if b :yes :no)))
(defn or-unknown [x] (if (nil? x) (t :unknown) (str x)))
(defn time-text [ms] (if ms (.format (js/Intl.DateTimeFormat. (:locale @state) #js{:dateStyle "medium" :timeStyle "short"}) (js/Date. ms)) (t :unknown)))

(def profile (js/JSON.parse profile-text))
(def profile-ok (empty? (d/profile-problems profile)))

;; restricted-wasm-v1: the guest reaches the host broker only through the port.
(defn bounded-json [m] (js/JSON.parse (port/http-text m d/max-response-bytes)))

(defn notice! [s] (swap! state assoc :notice s) (render!))

(defn read! [operation url on-body]
  (swap! state assoc :busy true) (render!)
  (-> (port/invoke #js{:capability d/capability :service d/service :operation operation} #js{:input url :init #js{:method "GET"}})
      (.then bounded-json)
      (.then (fn [body] (on-body body) (swap! state assoc :read-at (js/Date.now)) (notice! (t :fetched))))
      (.catch (fn [e] (notice! (if (re-find #"not approved" (str (.-message e))) (t :denied) (str (t :failed) (or (:reason (ex-data e)) (.-message e)))))))
      (.finally (fn [] (swap! state assoc :busy false) (render!)))))

(defn read-inventory! [] (read! "read-inventory" (d/inventory-url) (fn [body] (swap! state assoc :inventory (d/inventory body)))))
(defn read-free! [] (read! "read-free-status" (d/free-status-url) (fn [body] (swap! state assoc :free (d/free-status body)))))

(defn profile-card []
  (let [p (:profile profile)]
    (ui/card
     (ui/heading 2 (str (t :bot) ": " (:id p)))
     [:dl
      [:dt (t :description)] [:dd {:id "bot-description"} (or-unknown (d/profile-description profile))]
      [:dt (t :runtime)] [:dd "itonami-agent / itonami scheduler"]
      [:dt (t :cron)] [:dd [:ul {:class "plain"} (map (fn [j] [:li (str (:name j) " — " (:schedule j) " (" (t (if (:enabled j) :enabled :disabled)) ")")]) (:cron p))]]]
     [:p {:id "authority"} (t :authority)]
     [:p {:class "awai-caption awai-muted"} (str (t :origin) ": " (get-in profile [:provenance :repository]) " " (get-in profile [:provenance :path]) " " (subs (get-in profile [:provenance :revision]) 0 12))])))

(defn funding-card []
  (let [{:keys [inventory free]} @state f (d/funding inventory free)]
    (ui/card
     (ui/heading 2 (t :funding))
     [:p {:id "funding" :data-verdict (:verdict f)} (get (t :verdicts) (:verdict f))]
     (when (seq (:reasons f)) [:ul {:id "reasons"} (map (fn [r] [:li (get (t :reasons) r r)]) (:reasons f))])
     (when (:read-at @state) [:p {:class "awai-caption awai-muted"} (str (t :read-at) ": " (time-text (:read-at @state)))]))))

(defn inventory-card []
  (when-let [i (:inventory @state)]
    (ui/card
     (ui/heading 2 (t :inventory))
     [:dl {:id "inventory"}
      [:dt (t :measured)] [:dd (str (yes-no (:measured i)) (when (:stale i) (str " (" (t :stale) ")")))]
      [:dt (t :campaigns)] [:dd (str (or-unknown (:active i)) " / " (or-unknown (:campaigns i)))]
      [:dt (t :listed)] [:dd (str (:listed i))]
      [:dt (t :placements)] [:dd (if (seq (:placements i)) (str/join ", " (:placements i)) (t :no))]
      [:dt (t :source)] [:dd (:funding-source i)]
      [:dt (t :last-sync)] [:dd (or-unknown (:last-sync-at i))]])))

(defn free-card []
  (when-let [f (:free @state)]
    (ui/card
     (ui/heading 2 (t :free))
     [:dl {:id "free"}
      [:dt (t :available)] [:dd (yes-no (:available f))]
      [:dt (t :source)] [:dd (:funding-source f)]
      [:dt (t :funded)] [:dd (str (:advertiser-funded f))]
      [:dt (t :sponsor)] [:dd (or-unknown (:sponsored-by f))]
      [:dt (t :unit)] [:dd (str (:unit-price-micros f) " micros / " (:daily-unit-cap f))]
      [:dt (t :per-unit)] [:dd (or-unknown (:impressions-per-unit f))]])))

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
                 (ui/button (t :read-inventory) {:attrs {:id "read-inventory" :disabled busy :on-click read-inventory!}})
                 (ui/button (t :read-free) {:variant "tonal" :attrs {:id "read-free" :disabled busy :on-click read-free!}})]
                [:p {:class "awai-caption awai-muted"} (str (t :endpoint) ": " d/murakumo-origin " — " (t :consent-note))]
                (funding-card)
                (inventory-card)
                (free-card)))]]))))

(port/listen! render!)
(render!)
