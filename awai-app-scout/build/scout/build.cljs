(ns scout.build
 "restricted-wasm-v1 build (KOTOBA-159). dist/ is the trusted host shell
 (index.html, host.js, the byte-exact canonical runtime in vendor/) plus
 host-data.js, which carries the descriptor and one self-contained guest
 document pinned by SHA-256. The host mounts the guest with
 mountRestrictedClientApp in an opaque-origin sandbox. The srcdoc guest
 inherits the page CSP, so the page admits the runtime's preamble and the
 guest's one inline script and style by hash, and nothing else inline.
 Set WRITE_CSP=1 to write the derived CSP into src-tauri/tauri.conf.json."
 (:require ["node:fs" :as fs] ["node:path" :as path] ["node:crypto" :as crypto] ["node:child_process" :as cp]
           ["esbuild" :as esbuild] ["../../../vendor/assets/kotoba-client-runtime.js" :as runtime] [scout.domain :as d]))
(def app-id "network.itonami.koukokuscout")
(def label "Koukoku Scout")
(defn sha [bytes] (-> (crypto/createHash "sha256") (.update bytes) (.digest "hex")))
(defn csp-hash [text] (str "'sha256-" (-> (crypto/createHash "sha256") (.update text "utf8") (.digest "base64")) "'"))
(defn fill [template k v] (.join (.split template (str "{{" k "}}")) v))
(def pins (js/JSON.parse (fs/readFileSync "vendor/PINS.json" "utf8")))
(doseq [[file digest] (get pins "files")] (when-not (= digest (sha (fs/readFileSync (str "vendor/assets/" file)))) (throw (js/Error. (str "canonical source pin drift: " file)))))
(def ui-pin (js/JSON.parse (fs/readFileSync "vendor/awai-ui-pin.json" "utf8")))
(when-not (= (get ui-pin "sha256") (sha (fs/readFileSync (get ui-pin "tarball")))) (throw (js/Error. "Awai UI tarball drift")))
(def ui-integrity (js/JSON.parse (fs/readFileSync "node_modules/@awai/ui/dist/integrity.json" "utf8")))
(doseq [[file digest] (get ui-integrity "files")] (when-not (= digest (sha (fs/readFileSync (str "node_modules/@awai/ui/" file)))) (throw (js/Error. (str "Awai UI package drift: " file)))))

;; The guest: one inline module script and one inline style, nothing fetched.
(def script
  (let [out (esbuild/buildSync #js{:entryPoints #js[".agent-output/compiled/scout/app.mjs"] :write false :bundle true :format "esm" :platform "browser" :target #js["es2022"]
                                   :alias #js{"@awai/profile-text" (path/resolve "profiles/paired-profile.json")} :loader #js{".json" "text"} :charset "utf8" :minify true})
        text (.-text (aget (.-outputFiles out) 0))]
    (when (.test (js/RegExp. "</script|<!--" "i") text) (throw (js/Error. "the guest script contains </script or <!--")))
    (when (.includes text "createClientOperationBroker") (throw (js/Error. "the canonical runtime must stay in the host, not the guest")))
    text))
;; The sandboxed guest has font-src 'none' (it inherits default-src 'none'), so
;; the Awai UI web font is dropped and the stack falls back to Noto Sans JP / system-ui.
(def style
  (let [ui (.replace (fs/readFileSync "node_modules/@awai/ui/dist/awai-ui.css" "utf8") (js/RegExp. "@font-face\\{[^}]*\\}" "g") "")
        text (str ui "\n" (fs/readFileSync "public/app.css" "utf8"))]
    (when (or (.test (js/RegExp. "</style" "i") text) (.includes text "url(")) (throw (js/Error. "the guest style must be inline-only")))
    text))
(def guest (-> (fs/readFileSync "public/guest.html" "utf8") (fill "STYLE" style) (fill "SCRIPT" script)))
(def guest-bytes (.encode (js/TextEncoder.) guest))
(when (> (.-length guest) 8388608) (throw (js/Error. "the guest exceeds the 8 MiB mount budget")))
(def revision
  (or (.. js/process -env -ITONAMI_SOURCE_COMMIT)
      (.trim (.toString (cp/execFileSync "git" #js["rev-parse" "HEAD"])))))
(when-not (re-matches #"[0-9a-f]{40}" revision) (throw (js/Error. "the guest is pinned to a 40-hex source commit")))
(def artifact {:revision revision :sha256 (sha guest-bytes) :max-bytes (.-length guest-bytes)})

(def preamble
  (let [m (.exec (js/RegExp. "^const GUEST_PREAMBLE = '<script>([^'\\\\]*)</script>';$" "m") (fs/readFileSync "vendor/assets/kotoba-client-runtime.js" "utf8"))]
    (when-not m (throw (js/Error. "the runtime has no GUEST_PREAMBLE this build can hash")))
    (second m)))
(def descriptor (d/descriptor))
(def capabilities (vec (distinct (map #(:capability %) (:operations descriptor)))))
(let [problems (runtime/clientRuntimeProblems descriptor capabilities #js["tauri://localhost"])]
  (when (seq problems) (throw (js/Error. (str "canonical client runtime refused descriptor: " (js/JSON.stringify problems))))))
(def csp
  (str "default-src 'none'; script-src 'self' " (csp-hash preamble) " " (csp-hash script) "; style-src 'self' " (csp-hash style)
       "; img-src 'self' data:; " (runtime/clientRuntimeConnectSrc descriptor capabilities #js[] #js["tauri://localhost"])
       " ipc: http://ipc.localhost; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'"))

(def conf-path "src-tauri/tauri.conf.json")
(let [text (fs/readFileSync conf-path "utf8") conf (js/JSON.parse text)]
  (when-not (= csp (get-in conf [:app :security :csp]))
    (if (= "1" (.. js/process -env -WRITE_CSP))
      (do (aset (get-in conf [:app :security]) "csp" csp) (fs/writeFileSync conf-path (str (js/JSON.stringify conf nil 2) "\n")))
      (throw (js/Error. (str conf-path " csp is not the derived policy; rebuild with WRITE_CSP=1:\n" csp))))))

(fs/rmSync "dist" #js{:recursive true :force true})
(fs/mkdirSync "dist/vendor" #js{:recursive true})
(fs/writeFileSync "dist/index.html" (fill (fs/readFileSync "public/index.html" "utf8") "CSP" csp))
(doseq [[src dst] [["public/host.js" "dist/host.js"] ["public/host.css" "dist/host.css"]
                   ["vendor/assets/kotoba-client-runtime.js" "dist/vendor/kotoba-client-runtime.js"]
                   ["vendor/assets/kotoba-client-consent.css" "dist/vendor/kotoba-client-consent.css"]]]
  (fs/copyFileSync src dst))
(fs/writeFileSync "dist/host-data.js" (str "export default " (js/JSON.stringify {:descriptor descriptor :artifact artifact :guest guest} nil 1) ";\n"))
(def files (into {} (map (fn [f] [f (sha (fs/readFileSync (str "dist/" f)))]) (filter (fn [f] (.isFile (fs/statSync (str "dist/" f)))) (sort (vec (fs/readdirSync "dist" #js{:recursive true})))))))
(fs/writeFileSync "dist/release.json"
  (js/JSON.stringify {:schema "awai.app.artifact.v1" :id app-id :version "0.1.0" :tauri "3.0.0-alpha.6" :profile (:profile descriptor)
                      :runtimePins pins :guest artifact :files files} nil 2))
(js/console.log (str "Built " label " (restricted-wasm-v1): host shell, canonical runtime and a pinned guest " (:sha256 artifact)))
