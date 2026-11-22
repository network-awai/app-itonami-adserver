(ns koukoku.guest-port
  "restricted-wasm-v1 guest side of mountRestrictedClientApp (KOTOBA-159).
  The host hands over one MessagePort in a `kotoba.client.host.v1` message.
  Every operation goes through it to the host's canonical broker, which opens
  the consent dialog in the trusted host document. This guest has no network
  (connect-src 'none') and never sees the broker, eligibility or transport.")

(def host-protocol "kotoba.client.host.v1")
(def operation-protocol "kotoba.client.operation.v1")
(defonce port (atom nil))
(def pending (js/Map.))
(defonce counter (atom 0))

(defn connected? [] (some? @port))

(defn- settle [e]
  (let [m (.-data e) id (when m (.-id m)) p (.get pending id)]
    (when p
      (.delete pending id)
      (if (true? (.-ok m)) ((.-resolve p) m) ((.-reject p) (js/Error. (str (.-error m))))))))

(defn listen!
  "Accept the first host port, from the parent window only, then call on-connect."
  [on-connect]
  (.addEventListener js/window "message"
    (fn [e]
      (let [m (.-data e) ports (.-ports e)]
        (when (and (nil? @port) (= (.-source e) (.-parent js/window)) (some? m)
                   (= host-protocol (.-protocol m)) (= 1 (.-length ports)))
          (let [p (aget ports 0)]
            (set! (.-onmessage p) settle)
            (reset! port p)
            (on-connect)))))))

(defn invoke
  "Ask the host broker for one operation. An HTTP read resolves to {status, content}."
  [selector payload]
  (if-let [p @port]
    (js/Promise. (fn [resolve reject]
                   (let [id (str "op-" (swap! counter inc))]
                     (.set pending id #js{:resolve resolve :reject reject})
                     (.postMessage p #js{:protocol operation-protocol :id id :selector selector :payload payload}))))
    (js/Promise.reject (js/Error. "host-not-connected"))))

(defn http-text
  "The host bridges the status and at most 1 MiB of UTF-8 text, with no
  headers. Refuse a non-2xx status or more than max-bytes."
  [m max-bytes]
  (let [status (.-status m) content (.-content m)]
    (cond
      (not (string? content)) (throw (js/Error. "response is not text"))
      (not (and (>= status 200) (< status 300))) (throw (ex-info (str "HTTP " status) {:status status}))
      (> (.-byteLength (.encode (js/TextEncoder.) content)) max-bytes) (throw (js/Error. "response too large"))
      :else content)))
