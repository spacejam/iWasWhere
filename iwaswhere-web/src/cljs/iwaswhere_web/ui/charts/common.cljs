(ns iwaswhere-web.ui.charts.common
  (:require [clojure.string :as s]
            [iwaswhere-web.utils.parse :as up]))

(defn line-points
  [indexed mapper]
  (let [point-strings (map mapper indexed)]
    (s/join " " point-strings)))

(defn path
  "Renders path with the given path description attribute."
  [d]
  [:path {:stroke "rgba(200,200,200,0.5)" :stroke-width 1 :d d}])

(defn weekend?
  [date-string]
  (let [day-of-week (.weekday (js/moment. date-string))]
    (not (and (pos? day-of-week) (< day-of-week 6)))))

(defn weekend-class
  [cls v]
  (str cls (when (weekend? (:date-string v)) "-weekend")))

(defn chart-title
  [title]
  [:text {:x           300
          :y           32
          :stroke      "none"
          :fill        "#AAA"
          :text-anchor :middle
          :style       {:font-weight :bold
                        :font-size   24}}
   title])

(defn mouse-leave-fn
  "Creates event handler that removes the keys required for the info div
   when leaving an element, such as a bar or circle in an SVG chart."
  [local v]
  (fn [_ev]
    (when (= v (:mouse-over @local))
      (swap! local (fn [state] (-> state
                                   (dissoc :mouse-over)
                                   (dissoc :mouse-pos)))))))

(defn mouse-enter-fn
  "Creates event handler for mouse-enter events on elements in a chart.
   Takes a local atom and the value associated with the chart element.
   Returns function which detects the mouse position from the event and
   replaces :mouse-over key in local atom with v and :mouse-pos with the
   mouse position in the event"
  [local v]
  (fn [ev]
    (let [mouse-pos {:x (.-pageX ev) :y (.-pageY ev)}
          update-fn (fn [state v]
                      (-> state
                          (assoc-in [:mouse-over] v)
                          (assoc-in [:mouse-pos] mouse-pos)))]
      (swap! local update-fn v)
      (.setTimeout js/window (mouse-leave-fn local v) 5000))))

(defn info-div-pos
  "Determines position for info div in chart, depending on position on page.
   Avoids going so low or far to the right on the page that the div would be
   cut off."
  [snapshot]
  (let [mouse-pos (:mouse-pos snapshot)
        mouse-x (:x mouse-pos)
        page-w (.-scrollWidth (.-body js/document))
        page-h (.-scrollHeight (.-body js/document))]
    {:style {:top  (min (:y mouse-pos) (- page-h 80))
             :left (if (< (- page-w mouse-x) 120)
                     (- mouse-x 100)
                     (+ mouse-x 20))}}))

(defn open-day-fn
  "Return on-click function for chart elements which then triggers opening
   a new tab with the associated day in a new tab on the right side of the
   split view."
  [v put-fn]
  (fn [_ev]
    (put-fn [:search/add {:tab-group :right
                          :query     (up/parse-search (:date-string v))}])))

(defn bg-bars
  "Renders invisible bars of maximum height. Allows mouse-over effect in entire
   column, rather than only where visible bars are."
  [indexed local chart-h k]
  [:g
   (for [[idx v] indexed]
     ^{:key (str "bgbar" (:date-string v) idx k)}
     [:g {:on-mouse-enter (mouse-enter-fn local v)
          :on-mouse-leave (mouse-leave-fn local v)}
      [:rect.bar-bg {:x      (* 10 idx)
                     :y      0
                     :width  9
                     :height chart-h}]])])
