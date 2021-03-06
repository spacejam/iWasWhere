(ns iwaswhere-web.ui.charts.custom-fields
  (:require [reagent.core :as rc]
            [iwaswhere-web.ui.charts.common :as cc]))

(defn mouse-leave-fn
  "Creates event handler that removes the keys required for the info div
   when leaving an element, such as a bar or circle in an SVG chart."
  [local v]
  (fn [_ev]
    (when (= v (:mouse-over @local))
      (swap! local (fn [state] (-> state
                                   (dissoc :mouse-over)
                                   (dissoc :mouse-over-path)
                                   (dissoc :mouse-pos)))))))

(defn mouse-enter-fn
  "Creates event handler for mouse-enter events on elements in a chart.
   Takes a local atom and the value associated with the chart element.
   Returns function which detects the mouse position from the event and
   replaces :mouse-over key in local atom with v and :mouse-pos with the
   mouse position in the event. Also sets path that specifies where to find
   the data of the moused over element."
  [local day-stats path]
  (fn [ev]
    (let [mouse-pos {:x (.-pageX ev) :y (.-pageY ev)}
          update-fn (fn [state day-stats]
                      (-> state
                          (assoc-in [:mouse-over] day-stats)
                          (assoc-in [:mouse-over-path] path)
                          (assoc-in [:mouse-pos] mouse-pos)))]
      (swap! local update-fn day-stats)
      (.setTimeout js/window (mouse-leave-fn local day-stats) 7500))))

(defn linechart-row
  "Draws line chart, for example for weight or LBM."
  [indexed local put-fn cfg]
  (let [{:keys [path chart-h y-start cls]} cfg
        vals (filter second (map (fn [[k v]] [k (get-in v path)]) indexed))
        max-val (or (apply max (map second vals)) 10)
        min-val (or (apply min (map second vals)) 1)
        y-scale (/ chart-h (- max-val min-val))
        mapper (fn [[idx v]]
                 (let [x (+ 5 (* 10 idx))
                       y (- (+ chart-h y-start) (* y-scale (- v min-val)))]
                   (str x "," y)))
        points (cc/line-points vals mapper)]
    [:g {:class cls}
     [:g
      [:polyline {:points points}]
      (for [[idx day] (filter #(get-in (second %) path) indexed)]
        (let [w (get-in day path)
              mouse-enter-fn (mouse-enter-fn local day path)
              mouse-leave-fn (mouse-leave-fn local day )
              cy (- (+ chart-h y-start) (* y-scale (- w min-val)))]
          ^{:key (str path idx)}
          [:circle {:cx             (+ (* 10 idx) 5)
                    :cy             cy
                    :r              4
                    :on-mouse-enter mouse-enter-fn
                    :on-mouse-leave mouse-leave-fn}]))]]))

(defn barchart-row
  "Renders bars."
  [indexed local put-fn cfg]
  (let [{:keys [path chart-h y-start threshold]} cfg]
    [:g
     (for [[idx day] indexed]
       (let [y-end (+ chart-h y-start)
             max-val (apply max (map (fn [[_idx v]] (get-in v path)) indexed))
             y-scale (/ chart-h (or max-val 1))
             v (get-in day path)
             h (if (pos? v) (* y-scale v) (* y-scale threshold))
             mouse-enter-fn (mouse-enter-fn local day path)
             mouse-leave-fn (mouse-leave-fn local day)
             threshold-reached? (>= v threshold)]
         (when (pos? max-val)
           ^{:key (str path idx)}
           [:rect {:x              (* 10 idx)
                   :on-click       (cc/open-day-fn day put-fn)
                   :y              (- y-end h)
                   :width          9
                   :height         h
                   :class          (if (pos? v)
                                     (if threshold-reached?
                                       (cc/weekend-class "done" day)
                                       (cc/weekend-class "backlog" day))
                                     (cc/weekend-class "failed" day))
                   :on-mouse-enter mouse-enter-fn
                   :on-mouse-leave mouse-leave-fn}])))]))

(defn custom-fields-chart
  "Draws chart for daily activities vs weight. Weight is a line chart with
   circles for each value, activites are represented as bars. On mouse-over
   on top of bars or circles, a small info div next to the hovered item is
   shown."
  [stats chart-h put-fn options]
  (let [local (rc/atom {})]
    (fn [stats chart-h put-fn options]
      (let [indexed (map-indexed (fn [idx [k v]] [idx v]) stats)]
        [:div
         [:svg
          {:viewBox (str "0 0 600 " chart-h)}
          [cc/chart-title "custom fields"]
          [cc/bg-bars indexed local chart-h :custom]
          (for [[k row-cfg] (:custom-field-charts options)]
            (if (= :barchart (:type row-cfg))
              ^{:key (str :custom-fields-barchart (:path row-cfg))}
              [barchart-row indexed local put-fn row-cfg]
              ^{:key (str :custom-fields-linechart (:path row-cfg))}
              [linechart-row indexed local put-fn row-cfg]))]
         (when-let [mouse-over (:mouse-over @local)]
           (let [path (:mouse-over-path @local)
                 v (get-in mouse-over path)]
             [:div.mouse-over-info (cc/info-div-pos @local)
              [:div (:date-string mouse-over)]
              (when path
                [:div [:strong (first path)] ": " v])]))]))))
