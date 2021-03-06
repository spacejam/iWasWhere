(ns iwaswhere-web.core
  "In this namespace, the individual components are initialized and wired
  together to form the backend system."
  (:gen-class)
  (:require [matthiasn.systems-toolbox.switchboard :as sb]
            [matthiasn.systems-toolbox-sente.server :as sente]
            [matthiasn.inspect-probe.probe :as probe]
            [iwaswhere-web.index :as idx]
            [iwaswhere-web.keepalive :as ka]
            [iwaswhere-web.specs]
            [clojure.tools.logging :as log]
            [clj-pid.core :as pid]
            [iwaswhere-web.store :as st]
            [iwaswhere-web.fulltext-search :as ft]
            [iwaswhere-web.upload :as up]
            [iwaswhere-web.imports :as i]))

(defonce switchboard (sb/component :server/switchboard))

(defn restart!
  "Starts or restarts system by asking switchboard to fire up the ws-cmp for
   serving the client side application and providing bi-directional
   communication with the client, plus the store and imports components.
   Then, routes messages to the store and imports components for which those
   have a handler function. Also route messages from imports to store component.
   Finally, sends all messages from store component to client via the ws
   component."
  [switchboard]
  (sb/send-mult-cmd
    switchboard
    [[:cmd/init-comp #{(sente/cmp-map :server/ws-cmp idx/sente-map)
                       (i/cmp-map :server/imports-cmp)
                       (st/cmp-map :server/store-cmp)
                       (up/cmp-map :server/upload-cmp)
                       (ft/cmp-map :server/ft-cmp)}]

     [:cmd/route {:from :server/ws-cmp
                  :to   #{:server/store-cmp
                          :server/imports-cmp}}]

     [:cmd/route {:from :server/imports-cmp
                  :to   :server/store-cmp}]

     [:cmd/route {:from :server/upload-cmp
                  :to   :server/store-cmp}]

     [:cmd/route {:from :server/store-cmp
                  :to   #{:server/ws-cmp
                          :server/ft-cmp}}]]))

(defn -main
  "Starts the application from command line, saves and logs process ID. The
   system that is fired up when restart! is called proceeds in core.async's
   thread pool. Since we don't want the application to exit when the current
   thread is out of work, we just put it to sleep."
  [& _args]
  (pid/save "iwaswhere.pid")
  (pid/delete-on-shutdown! "iwaswhere.pid")
  (log/info "Application started, PID" (pid/current))
  (restart! switchboard)
  (when (get (System/getenv) "PROBE")
    (probe/start! switchboard))
  (ka/restart-keepalive! switchboard)
  (Thread/sleep Long/MAX_VALUE))
