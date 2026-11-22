(ns koukoku.app
  (:require [koukoku.domain :as d] ["@awai/ui" :as ui] [clojure.string :as str]
            ["@awai/client-runtime" :as runtime] ["@awai/profile-text$default" :as profile-text]))

(defonce state (atom {:locale "ja" :notice "" :inventory nil :free nil :busy false :read-at nil
                      :records (d/empty-store) :records-error nil :ledger-refusal nil :kind "impression"}))
(def node ui/node)
(declare render!)

(def copy
  {:ja {:title "Koukoku" :lead "広告 bot の仕事を、手元で確かめる。広告主のお金で回っているかを、公開データから判定します。"
        :bot "組になる bot" :description "役割" :runtime "実行" :cron "定期実行" :enabled "有効" :disabled "停止"
        :authority "この bot は提案だけを行い、申込・配信・課金はしません。この app は公開データの読み取りに加え、この端末内だけの記録（申請・審査・領収・台帳）を行います。送信・課金・決済はしません。"
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
        :read-at "最終取得" :origin "出典"
        :records "端末内記録（申請・審査・領収・台帳）"
        :local-only "この記録はこの端末内だけに保存します。送信・課金・決済はしません。"
        :saved "記録しました。" :save-failed "記録できませんでした: "
        :records-error "保存済みの記録を読み込めません: " :empty "まだありません。"
        :application-id "申請 ID" :applicant "申込者" :contact "連絡先" :note "備考"
        :record-application "申請を記録" :applications "申請"
        :review "審査" :review-state "審査状態" :review-terminal "変更不可"
        :review-states {"received" "受付済" "under-review" "審査中" "approved" "承認済" "rejected" "不承認"}
        :receipt-id "領収番号" :issued-to "宛先" :amount "金額（micros）" :memo "摘要" :issued-at "発行日時"
        :record-receipt "領収を記録" :receipts "領収"
        :ledger-id "イベント ID" :kind "種別" :campaign "campaign" :advertiser "広告主" :placement "掲載枠"
        :payer "入金者" :at "時刻" :record-ledger "記録する" :ledger "イベント・入金記録"
        :kinds {"impression" "表示" "click" "クリック" "deposit" "入金"}}
   :en {:title "Koukoku" :lead "Check the ad bot's work on your own device. Public data tells whether advertiser money is funding it."
        :bot "Paired bot" :description "Role" :runtime "Runtime" :cron "Schedules" :enabled "enabled" :disabled "disabled"
        :authority "This bot only proposes; it never submits, serves or bills. This app also records on this device only (applications, reviews, receipts, ledger). It never submits, bills or settles."
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
        :read-at "Last read" :origin "Source"
        :records "On-device records (applications, reviews, receipts, ledger)"
        :local-only "Records stay on this device. Nothing is submitted, billed or settled."
        :saved "Recorded." :save-failed "Could not record: "
        :records-error "Could not read saved records: " :empty "None yet."
        :application-id "Application ID" :applicant "Applicant" :contact "Contact" :note "Note"
        :record-application "Record application" :applications "Applications"
        :review "Review" :review-state "Review state" :review-terminal "Terminal"
        :review-states {"received" "Received" "under-review" "Under review" "approved" "Approved" "rejected" "Rejected"}
        :receipt-id "Receipt ID" :issued-to "Issued to" :amount "Amount (micros)" :memo "Memo" :issued-at "Issued at"
        :record-receipt "Record receipt" :receipts "Receipts"
        :ledger-id "Event ID" :kind "Kind" :campaign "Campaign" :advertiser "Advertiser" :placement "Placement"
        :payer "Payer" :at "At" :record-ledger "Record" :ledger "Events and deposits"
        :kinds {"impression" "Impression" "click" "Click" "deposit" "Deposit"}}})

(defn t [k] (get-in copy [(:locale @state) k]))
(defn yes-no [b] (t (if b :yes :no)))
(defn or-unknown [x] (if (nil? x) (t :unknown) (str x)))
(defn time-text [ms] (if ms (.format (js/Intl.DateTimeFormat. (:locale @state) #js{:dateStyle "medium" :timeStyle "short"}) (js/Date. ms)) (t :unknown)))

(def profile (js/JSON.parse profile-text))
(def profile-ok (empty? (d/profile-problems profile)))

(def records-key "itonami.koukoku.records.v2")
(defn now-stamp [] (.toISOString (js/Date.)))

;; ---- on-device records: hydration, persistence, host adapters -----------------
;; Writes go through the canonical broker as browser-action operations: each
;; one opens its own approval dialog (each-operation), the trusted handler
;; validates with koukoku.domain, persists to local-state (localstorage)
;; and returns plain data. Denying writes nothing.

(defn persist! [store]
  (.setItem js/localStorage records-key (d/serialize-store store))
  (swap! state assoc :records store))

(defn hydrate-records! []
  (try
    (let [raw (.getItem js/localStorage records-key)]
      (when-not (nil? raw)
        (swap! state assoc :records (d/parse-store raw))))
    (catch :default e
      (swap! state assoc :records-error (or (:reason (ex-data e)) (.-message e))))))

(defn- host-fail [e]
  (throw (js/Error. (or (:reason (ex-data e)) (.-message e)))))

(defn record-application-handler [req]
  (let [p (:payload req)
        input {:id (:id p) :applicant (:applicant p) :contact (:contact p)
               :note (:note p) :created-at (:created-at p)}]
    (try
      (persist! (d/add-application (:records @state) input))
      #js{:ok true}
      (catch :default e (host-fail e)))))

(defn set-review-state-handler [req]
  (let [p (:payload req)]
    (try
      (persist! (d/transition-review (:records @state) (:id p) (:to p)))
      #js{:ok true}
      (catch :default e (host-fail e)))))

(defn record-receipt-handler [req]
  (let [p (:payload req)
        input {:id (:id p) :issued-to (:issued-to p) :amount-micros (:amount-micros p)
               :memo (:memo p) :issued-at (:issued-at p)}]
    (try
      (persist! (d/add-receipt (:records @state) input))
      #js{:ok true}
      (catch :default e (host-fail e)))))

(defn record-ledger-handler [req]
  (let [p (:payload req)
        input (if (= "deposit" (:kind p))
                {:id (:id p) :kind (:kind p) :at (:at p) :payer (:payer p) :amount-micros (:amount-micros p)}
                {:id (:id p) :kind (:kind p) :at (:at p) :campaign-id (:campaign-id p)
                 :advertiser (:advertiser p) :placement (:placement p)})]
    (try
      (persist! (d/record-ledger-event (:records @state) input))
      #js{:ok true}
      (catch :default e (host-fail e)))))

(def client
  (let [desc (d/descriptor)]
    (when (seq (runtime/clientRuntimeProblems desc #js[d/capability d/records-capability] js/location.origin))
      (throw (js/Error. "canonical client runtime refused descriptor")))
    (runtime/createClientOperationBroker
     #js{:runtime desc :requestedCapabilities #js[d/capability d/records-capability] :approvedOperations (:operations desc)
         :hostSupportedOperations (clj->js (map (fn [o] [(:capability o) (:service o) (:operation o)]) (:operations desc)))
         :browserAdapters #js{:koukoku-records #js{:record-application record-application-handler
                                                     :set-review-state set-review-state-handler
                                                     :record-receipt record-receipt-handler
                                                     :record-ledger-event record-ledger-handler}}
         :consentHost (runtime/createClientConsentHost)})))

(defn bounded-json [response]
  (if-not (.-ok response)
    (js/Promise.reject (js/Error. (str "HTTP " (.-status response))))
    (let [declared (js/Number (.get (.-headers response) "content-length"))]
      (if (and (js/Number.isFinite declared) (> declared d/max-response-bytes))
        (js/Promise.reject (js/Error. "response too large"))
        (.then (.text response) (fn [text] (if (> (count text) d/max-response-bytes) (throw (js/Error. "response too large")) (js/JSON.parse text))))))))

(defn notice! [s] (swap! state assoc :notice s) (render!))

(defn read! [operation url on-body]
  (swap! state assoc :busy true) (render!)
  (-> (.invoke client #js{:capability d/capability :service d/service :operation operation} #js{:input url :init #js{:method "GET"}})
      (.then bounded-json)
      (.then (fn [body] (on-body body) (swap! state assoc :read-at (js/Date.now)) (notice! (t :fetched))))
      (.catch (fn [e] (notice! (if (re-find #"not approved" (str (.-message e))) (t :denied) (str (t :failed) (or (:reason (ex-data e)) (.-message e)))))))
      (.finally (fn [] (swap! state assoc :busy false) (render!)))))

(defn read-inventory! [] (read! "read-inventory" (d/inventory-url) (fn [body] (swap! state assoc :inventory (d/inventory body)))))
(defn read-free! [] (read! "read-free-status" (d/free-status-url) (fn [body] (swap! state assoc :free (d/free-status body)))))

;; ---- record writes through the broker -----------------------------------------

(defn field-value [id]
  (let [el (.getElementById js/document id)] (if (nil? el) "" (str (.-value el)))))

(defn- opt-field [id]
  (let [v (field-value id)] (if (str/blank? v) nil v)))

(defn- amount-field [id]
  (let [n (js/parseInt (field-value id) 10)] (if (js/Number.isNaN n) 0 n)))

(defn save-failed! [e]
  (let [m (str (.-message e))]
    (notice! (cond
               (re-find #"not approved" m) (t :denied)
               (= m "duplicate-event-id") (d/duplicate-event-refusal (field-value "record-ledger-id"))
               :else (str (t :save-failed) m)))))

(defn ledger-failed! [e]
  (when (= (str (.-message e)) "duplicate-event-id")
    (swap! state assoc :ledger-refusal (d/duplicate-event-refusal (field-value "record-ledger-id"))))
  (save-failed! e))

(defn write! [operation payload on-fail]
  (swap! state assoc :busy true) (render!)
  (-> (.invoke client #js{:capability d/records-capability :service d/records-service :operation operation} payload)
      (.then (fn [_] (swap! state assoc :ledger-refusal nil) (notice! (t :saved))))
      (.catch (fn [e] (on-fail e)))
      (.finally (fn [] (swap! state assoc :busy false) (render!)))))

(defn submit-application! []
  (write! "record-application"
          #js{:id (field-value "record-application-id") :applicant (field-value "record-application-applicant")
              :contact (field-value "record-application-contact") :note (opt-field "record-application-note")
              :created-at (now-stamp)}
          save-failed!))

(defn submit-review! [application-id to]
  (write! "set-review-state" #js{:id application-id :to to} save-failed!))

(defn submit-receipt! []
  (write! "record-receipt"
          #js{:id (field-value "record-receipt-id") :issued-to (field-value "record-receipt-issued-to")
              :amount-micros (amount-field "record-receipt-amount") :memo (opt-field "record-receipt-memo")
              :issued-at (now-stamp)}
          save-failed!))

(defn submit-ledger! []
  (let [kind (:kind @state)]
    (write! "record-ledger-event"
            (if (= "deposit" kind)
              #js{:id (field-value "record-ledger-id") :kind kind :at (now-stamp)
                  :payer (field-value "record-ledger-payer") :amount-micros (amount-field "record-ledger-amount")}
              #js{:id (field-value "record-ledger-id") :kind kind :at (now-stamp)
                  :campaign-id (field-value "record-ledger-campaign") :advertiser (field-value "record-ledger-advertiser")
                  :placement (field-value "record-ledger-placement")})
            ledger-failed!)))

;; ---- cards --------------------------------------------------------------------

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

(defn text-field [id label]
  (ui/field {:id id :label label} [:input {:type "text" :id id}]))

(defn applications-card []
  (let [apps (d/list-applications (:records @state)) busy (:busy @state)]
    (ui/card
     (ui/heading 2 (t :applications))
     (text-field "record-application-id" (t :application-id))
     (text-field "record-application-applicant" (t :applicant))
     (text-field "record-application-contact" (t :contact))
     (text-field "record-application-note" (t :note))
     (ui/button (t :record-application) {:attrs {:id "record-application" :disabled busy :on-click submit-application!}})
     (if (seq apps)
       [:ul {:id "applications"}
        (map (fn [a] [:li (str (:id a) " — " (:applicant a) " — " (get (t :review-states) (:review-state a)))]) apps)]
       [:p {:id "applications"} (t :empty)]))))

(defn review-card []
  (let [apps (d/list-applications (:records @state)) busy (:busy @state)]
    (ui/card
     (ui/heading 2 (t :review))
     (if (seq apps)
       [:ul {:id "reviews"}
        (map (fn [a]
               (let [nexts (sort (get d/review-transitions (:review-state a)))]
                 [:li (str (:id a) " — " (get (t :review-states) (:review-state a)))
                  (if (seq nexts)
                    [:div {:class "actions"}
                     (map (fn [to]
                            (ui/button (get (t :review-states) to)
                                       {:attrs {:id (str "review-" (:id a) "-" to) :disabled busy
                                                :on-click (fn [] (submit-review! (:id a) to))}}))
                          nexts)]
                    [:span (t :review-terminal)])]))
             apps)]
       [:p {:id "reviews"} (t :empty)]))))

(defn receipts-card []
  (let [recs (d/list-receipts (:records @state)) busy (:busy @state)]
    (ui/card
     (ui/heading 2 (t :receipts))
     (text-field "record-receipt-id" (t :receipt-id))
     (text-field "record-receipt-issued-to" (t :issued-to))
     (text-field "record-receipt-amount" (t :amount))
     (text-field "record-receipt-memo" (t :memo))
     (ui/button (t :record-receipt) {:attrs {:id "record-receipt" :disabled busy :on-click submit-receipt!}})
     (if (seq recs)
       [:ul {:id "receipts"}
        (map (fn [r] [:li (str (:id r) " — " (:issued-to r) " — " (:amount-micros r) " " (:issued-at r))]) recs)]
       [:p {:id "receipts"} (t :empty)]))))

(defn ledger-card []
  (let [entries (d/list-ledger (:records @state)) busy (:busy @state)]
    (ui/card
     (ui/heading 2 (t :ledger))
     (text-field "record-ledger-id" (t :ledger-id))
     (ui/field {:id "record-ledger-kind" :label (t :kind)}
       [:select {:value (:kind @state) :on-change (fn [e] (swap! state assoc :kind (.. e -target -value)) (render!))}
        [:option {:value "impression" :selected (= "impression" (:kind @state))} (get (t :kinds) "impression")]
        [:option {:value "click" :selected (= "click" (:kind @state))} (get (t :kinds) "click")]
        [:option {:value "deposit" :selected (= "deposit" (:kind @state))} (get (t :kinds) "deposit")]])
     (text-field "record-ledger-campaign" (t :campaign))
     (text-field "record-ledger-advertiser" (t :advertiser))
     (text-field "record-ledger-placement" (t :placement))
     (text-field "record-ledger-payer" (t :payer))
     (text-field "record-ledger-amount" (t :amount))
     (ui/button (t :record-ledger) {:attrs {:id "record-ledger" :disabled busy :on-click submit-ledger!}})
     (when (:ledger-refusal @state) [:p {:id "ledger-refusal"} (:ledger-refusal @state)])
     (if (seq entries)
       [:ul {:id "ledger"}
        (map (fn [e] [:li (str (:id e) " — " (get (t :kinds) (:kind e)) " — " (:at e))]) entries)]
       [:p {:id "ledger"} (t :empty)]))))

(defn records-section []
  (ui/card
   (ui/heading 2 (t :records))
   [:p {:class "awai-caption awai-muted"} (t :local-only)]
   (when (:records-error @state)
     [:p {:id "records-error"} (str (t :records-error) (:records-error @state))])
   (applications-card)
   (review-card)
   (receipts-card)
   (ledger-card)))

(defn render! []
  (let [root (.getElementById js/document "app") busy (:busy @state)]
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
                (free-card)
                (records-section)))]]))))

(hydrate-records!)
(render!)
