(ns scout.build
 (:require ["node:fs" :as fs] ["node:path" :as path] ["node:crypto" :as crypto] ["esbuild" :as esbuild]))
(defn sha [bytes] (-> (crypto/createHash "sha256") (.update bytes) (.digest "hex")))
(def pins (js/JSON.parse (fs/readFileSync "vendor/PINS.json" "utf8")))
(doseq [[file digest] (get pins "files")] (when-not (= digest (sha (fs/readFileSync (str "vendor/assets/" file)))) (throw (js/Error. (str "canonical source pin drift: " file)))))
(def ui-pin (js/JSON.parse (fs/readFileSync "vendor/awai-ui-pin.json" "utf8")))
(when-not (= (get ui-pin "sha256") (sha (fs/readFileSync (get ui-pin "tarball")))) (throw (js/Error. "Awai UI tarball drift")))
(def ui-integrity (js/JSON.parse (fs/readFileSync "node_modules/@awai/ui/dist/integrity.json" "utf8")))
(doseq [[file digest] (get ui-integrity "files")] (when-not (= digest (sha (fs/readFileSync (str "node_modules/@awai/ui/" file)))) (throw (js/Error. (str "Awai UI package drift: " file)))))
(fs/rmSync "dist" #js{:recursive true :force true})
(fs/mkdirSync "dist/ui-assets" #js{:recursive true})
(doseq [[src dst] [["public/index.html" "dist/index.html"] ["public/app.css" "dist/app.css"] ["node_modules/@awai/ui/dist/awai-ui.css" "dist/awai-ui.css"] ["vendor/assets/kotoba-client-consent.css" "dist/kotoba-client-consent.css"]]] (fs/copyFileSync src dst))
(fs/cpSync "node_modules/@awai/ui/dist/ui-assets" "dist/ui-assets" #js{:recursive true})
(esbuild/buildSync #js{:entryPoints #js[".agent-output/compiled/scout/app.mjs"] :outfile "dist/app.js" :bundle true :format "esm" :platform "browser" :target #js["es2022"]
  :alias #js{"@awai/client-runtime" (path/resolve "vendor/assets/kotoba-client-runtime.js") "@awai/profile-text" (path/resolve "profiles/paired-profile.json")}
  :loader #js{".json" "text"} :minify true})
(def files (into {} (map (fn [f] [f (sha (fs/readFileSync (str "dist/" f)))]) (filter (fn [f] (.isFile (fs/statSync (str "dist/" f)))) (sort (vec (fs/readdirSync "dist" #js{:recursive true})))))))
(fs/writeFileSync "dist/release.json" (js/JSON.stringify {:schema "awai.app.artifact.v1" :id "network.itonami.koukokuscout" :version "0.1.0" :tauri "3.0.0-alpha.6" :files files} nil 2))
(js/console.log "Built static Koukoku Scout with canonical runtime and Awai UI package")
