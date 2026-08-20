(ns llm-proxy.time
  "Wall-clock boundary. Dynamic binding is for synchronous leaf-code tests;
  runtime resources capture explicit clock functions for asynchronous work.")

(def ^:dynamic *now-ms*
  (fn [] (System/currentTimeMillis)))

(defn now-ms []
  (*now-ms*))
