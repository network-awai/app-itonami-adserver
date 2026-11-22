(ns restricted-test
  "restricted-wasm-v1 (KOTOBA-159). Runs after the build: it checks dist/."
  (:require [scout.domain :as d] [scout.guest-port :as port] [clojure.string :as str]
            ["node:test" :refer [test]] ["node:assert/strict" :as a] ["node:fs" :as fs] ["node:crypto" :as crypto]
            ["../../vendor/assets/kotoba-client-runtime.js" :as runtime]))

(defn sha [bytes] (-> (crypto/createHash "sha256") (.update bytes) (.digest "hex")))
(defn csp-hash [text] (str "'sha256-" (-> (crypto/createHash "sha256") (.update text "utf8") (.digest "base64")) "'"))
(defn between [s start end] (let [i (+ (.indexOf s start) (count start))] (.slice s i (.indexOf s end i))))
(def host-data
  (let [text (fs/readFileSync "dist/host-data.js" "utf8")]
    (js/JSON.parse (.slice text (count "export default ") (- (count text) 2)))))
(def guest (:guest host-data))
(def assert-throws (aget a "throws"))
(def conf-csp (get-in (js/JSON.parse (fs/readFileSync "src-tauri/tauri.conf.json" "utf8")) [:app :security :csp]))
(defn directive [csp name] (some #(when (str/starts-with? % (str name " ")) %) (map str/trim (.split csp ";"))))

(test "the descriptor is restricted-wasm-v1 and every operation has a complete disclosure"
  (fn []
    (a/equal (:profile (d/descriptor)) "restricted-wasm-v1")
    (a/equal (:adapter (get (d/descriptor) "local-state")) "memory")
    (a/deepEqual (vec (runtime/clientRuntimeProblems (d/descriptor) #js[d/capability] #js["tauri://localhost"])) [])
    (doseq [op (:operations (d/descriptor))]
      (a/deepEqual (vec (runtime/clientDisclosureProblems (:disclosure op))) [] (:operation op))
      (a/notEqual (:recipient (:disclosure op)) "TBD" "the recipient is owner input, not a placeholder")
      (a/equal (:withdrawal (:disclosure op)) "設定 › 許可した送信")
      (when (not= "JP" (:country (:disclosure op)))
        (a/ok (str/ends-with? (:foreign-info-url (:disclosure op)) "/foreign-info.html#us") "the app's own foreign-information page, section #us")))
    (a/deepEqual (:operations (:descriptor host-data)) (js/JSON.parse (js/JSON.stringify (:operations (d/descriptor)))))))

(test "the page CSP is the one in tauri.conf.json and admits only the preamble and the guest by hash"
  (fn []
    (let [html (fs/readFileSync "dist/index.html" "utf8")
          preamble (second (.exec (js/RegExp. "^const GUEST_PREAMBLE = '<script>([^'\\\\]*)</script>';$" "m") (fs/readFileSync "vendor/assets/kotoba-client-runtime.js" "utf8")))
          script (between guest "<script type=\"module\">" "</script>")
          style (between guest "<style>" "</style>")]
      (a/ok (str/includes? html (str "content=\"" conf-csp "\"")))
      (a/equal (directive conf-csp "script-src") (str "script-src 'self' " (csp-hash preamble) " " (csp-hash script)))
      (a/equal (directive conf-csp "style-src") (str "style-src 'self' " (csp-hash style)))
      (a/equal (directive conf-csp "connect-src") (str (runtime/clientRuntimeConnectSrc (d/descriptor) #js[d/capability] #js[] #js["tauri://localhost"]) " ipc: http://ipc.localhost"))
      (a/equal (directive conf-csp "connect-src") "connect-src https://raw.githubusercontent.com ipc: http://ipc.localhost")
      (a/equal (directive conf-csp "frame-src") "frame-src 'none'")
      (a/equal (directive conf-csp "default-src") "default-src 'none'"))))

(defn ^:async pinned-guest []
    (let [decl (:artifact host-data) bytes (.encode (js/TextEncoder.) guest)
          ok (js-await (runtime/verifyClientArtifact bytes decl))]
      (a/ok (re-matches #"[0-9a-f]{40}" (:revision decl)))
      (a/equal (:sha256 ok) (sha bytes))
      (aset bytes 0 (bit-xor (aget bytes 0) 1))
      (js-await (a/rejects (runtime/verifyClientArtifact bytes decl) #"digest mismatch"))))
(test "the guest is one pinned document: it verifies, and one changed byte is refused" pinned-guest)

(test "the host serves the byte-exact pinned runtime, and the guest carries none of it"
  (fn []
    (let [pins (js/JSON.parse (fs/readFileSync "vendor/PINS.json" "utf8"))]
      (a/equal (:revision pins) "a1b90e38c9e5334facfa993062e237299c70de10")
      (a/equal (sha (fs/readFileSync "dist/vendor/kotoba-client-runtime.js")) (get-in pins [:files "kotoba-client-runtime.js"]))
      (a/equal (get-in pins [:files "kotoba-client-runtime.js"]) "d6f67c058c5c40fc56fe500648dc79704a2eee02d4af75fc17898aad1f560a75")
      (a/equal (sha (fs/readFileSync "dist/vendor/kotoba-client-consent.css")) (get-in pins [:files "kotoba-client-consent.css"])))
    (a/ok (str/includes? (fs/readFileSync "dist/host.js" "utf8") "mountRestrictedClientApp"))
    (a/equal (str/includes? guest "createClientOperationBroker") false)
    (a/equal (str/includes? guest "<script src") false)
    (a/equal (str/includes? guest "<link") false)))

(defn ^:async port-bounds []
    (a/equal (port/http-text #js{:status 200 :content "{}"} 10) "{}")
    (assert-throws (fn [] (port/http-text #js{:status 404 :content ""} 10)) #"HTTP 404")
    (a/equal (:status (ex-data (try (port/http-text #js{:status 404 :content ""} 10) (catch :default e e)))) 404)
    (assert-throws (fn [] (port/http-text #js{:status 200 :content "あいう"} 8)) #"too large")
    (assert-throws (fn [] (port/http-text #js{:status 200} 8)) #"not text")
    (a/equal (port/connected?) false)
    (js-await (a/rejects (port/invoke #js{} #js{}) #"host-not-connected")))
(test "guest port: a bridged response is bounded by status and bytes" port-bounds)

(test "the 設定 › 許可した送信 screen says the app stores no permission"
  (fn []
    (a/ok (str/includes? guest "この app は許可を保存しません。送るたびに確認します。保存している許可はありません。"))))

(test "the foreign-information page has the #us section Legal requires"
  (fn []
    (let [html (fs/readFileSync "site/foreign-info.html" "utf8")]
      (a/ok (str/includes? html "<section id=\"us\">"))
      (a/ok (str/includes? html "https://www.ppc.go.jp/files/pdf/USA_report.pdf"))
      (a/ok (str/includes? html "https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement"))
      (a/ok (str/includes? html "EEA・英国の利用者については GitHub B.V. も管理者とされています"))
      (a/ok (str/includes? html "Kotoba Labs と送り先の間に、個人情報の取扱いに関する契約はありません")))))
