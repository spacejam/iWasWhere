(ns iwaswhere-web.utils.misc
  (:require [clojure.string :as s]
            [clojure.set :as set]
            [iwaswhere-web.specs :as specs]))

(defn duration-string
  "Format duration string from seconds."
  [seconds]
  (let [hours (int (/ seconds 3600))
        seconds (rem seconds 3600)
        min (int (/ seconds 60))
        sec (rem seconds 60)]
    (s/trim
      (str (when (pos? hours) (str hours "h "))
           (when (pos? min) (str min "m "))
           (when (and (not (pos? hours)) (pos? sec)) (str sec "s"))))))

(defn visit-duration
  "Formats duration string."
  [entry]
  (let [arrival-ts (:arrival-timestamp entry)
        depart-ts (:departure-timestamp entry)
        secs (when (and arrival-ts depart-ts)
              (let [dur (- depart-ts arrival-ts)]
                (Math/floor (if (int? dur) (/ dur 1000) dur))))]
    (when (and secs (< secs 99999999))
      (str ", " (duration-string secs)))))

(defn pvt-filter
  "Filter for entries considered private."
  [cfg]
  (fn
    [entry]
    (let [tags (set (map s/lower-case (:tags entry)))
          private-tags (:pvt-hashtags cfg)
          hashtags (:hashtags cfg)
          only-pvt-tags (set/difference private-tags hashtags)
          matched (set/intersection tags only-pvt-tags)]
      (empty? matched))))

(defn suggestions
  "Renders suggestions for hashtags or mentions if either occurs before the
   current caret position. It does so by getting the selection from the DOM API,
   which can be used to determine the position and a string before that
   position, then finding either a hashtag or mention fragment right at the end
   of that substring. For these, auto-suggestions are displayed, which are
   entities that begin with the tag fragment before the caret position. When any
   of the suggestions are clicked, the fragment will be replaced with the
   clicked item."
  [key-prefix filtered-tags current-tag tag-replace-fn css-class]
  (when (seq filtered-tags)
    [:div.suggestions
     [:div.suggestions-list
      (for [tag filtered-tags]
        ^{:key (str key-prefix tag)}
        [:div {:on-click #(tag-replace-fn current-tag tag)}
         [:span {:class css-class} tag]])]]))

(defn double-ts-to-long
  [ts]
  (when (and ts (number? ts))
    (long (* ts 1000))))

(defn visit-timestamps
  "Parse arrival and departure timestamp as milliseconds since epoch."
  [entry]
  (let [departure-ts (let [ms (double-ts-to-long (:departure-timestamp entry))]
                       (when (specs/possible-timestamp? ms) ms))]
    {:arrival-ts  (double-ts-to-long (:arrival-timestamp entry))
     :departure-ts departure-ts}))

(defn find-missing-entry
  "Gets entry from entries-map for specified timestamp. Retrieves entry if it
   doesn't exist locally."
  [entries-map put-fn]
  (fn [ts]
    (let [entry (get entries-map ts)]
      (or entry
          (let [missing-entry {:timestamp ts}]
            (put-fn [:entry/find missing-entry])
            missing-entry)))))
