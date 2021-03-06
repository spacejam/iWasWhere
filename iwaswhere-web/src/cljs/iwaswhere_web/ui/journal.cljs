(ns iwaswhere-web.ui.journal
  (:require [iwaswhere-web.utils.misc :as u]
            [iwaswhere-web.ui.entry.entry :as e]
            [iwaswhere-web.utils.parse :as ps]
            [clojure.set :as set]))

(defn linked-filter-fn
  "Filter linked entries by search."
  [entries-map linked-filter put-fn]
  (fn [entry]
    (let [comments-mapper (u/find-missing-entry entries-map put-fn)
          comments (mapv comments-mapper (:comments entry))
          combined-tags (reduce #(set/union %1 (:tags %2)) (:tags entry)
                                comments)]
      (and (set/subset? (:tags linked-filter) combined-tags)
           (empty? (set/intersection (:not-tags linked-filter)
                                     combined-tags))))))

(defn linked-entries-view
  "Renders linked entries in right side column, filtered by local search."
  [linked-entries entries-map new-entries cfg put-fn local-cfg]
  (when linked-entries
    (let [query-id (:query-id local-cfg)
          on-input-fn #(put-fn [:linked-filter/set
                                {:search (ps/parse-search
                                           (.. % -target -innerText))
                                 :query-id query-id}])
          linked-entries (if (:show-pvt cfg)
                           linked-entries
                           (filter (u/pvt-filter cfg) linked-entries))
          linked-filter (query-id (:linked-filter cfg))
          filter-fn (linked-filter-fn entries-map linked-filter put-fn)
          linked-entries (filter filter-fn linked-entries)]
      [:div.journal-entries
       (when (query-id (:active cfg))
         [:div.linked-search-field {:content-editable true :on-input on-input-fn}
          (:search-text (query-id (:linked-filter cfg)))])
       (for [entry linked-entries]
         (when (and (not (:comment-for entry))
                    (or (:new-entry entry) (:show-context cfg)))
           ^{:key (str "linked-" (:timestamp entry))}
           [e/entry-with-comments
            entry cfg new-entries put-fn entries-map local-cfg false]))])))

(defn journal-view
  "Renders journal div, one entry per item, with map if geo data exists in the
   entry."
  [{:keys [observed put-fn]} local-cfg]
  (let [snapshot @observed
        cfg (merge (:cfg snapshot) (:options snapshot))
        query-id (:query-id local-cfg)
        entries (query-id (:results snapshot))
        entries-map (:entries-map snapshot)
        entries (map (fn [ts] (get entries-map ts)) entries)
        show-pvt? (:show-pvt cfg)
        filtered-entries (if show-pvt?
                           entries
                           (filter (u/pvt-filter cfg) entries))
        new-entries (:new-entries snapshot)
        show-context? (:show-context cfg)
        comments-w-entries? (not (:comments-standalone cfg))
        with-comments? (fn [entry] (and (or (and comments-w-entries?
                                                 (not (:comment-for entry)))
                                            (not comments-w-entries?))
                                        (or (:new-entry entry) show-context?)))
        active-id (-> snapshot :cfg :active query-id)
        active-entry (get entries-map active-id)
        linked-entries-set (into (sorted-set) (:linked-entries-list active-entry))
        linked-mapper (u/find-missing-entry entries-map put-fn)
        linked-entries (map linked-mapper linked-entries-set)]
    [:div.journal
     [:div.journal-entries
      (for [entry (filter #(not (contains? linked-entries-set (:timestamp %)))
                          filtered-entries)]
        (when (with-comments? entry)
          ^{:key (:timestamp entry)}
          [e/entry-with-comments
           entry cfg new-entries put-fn entries-map local-cfg false]))
      (when (seq entries)
        (let [show-more #(put-fn [:show/more {:query-id query-id}])]
          [:div.show-more {:on-click show-more :on-mouse-over show-more}
           [:span.show-more-btn [:span.fa.fa-plus-square] " show more"]]))]
     (when active-entry
       [linked-entries-view
        linked-entries entries-map new-entries cfg put-fn local-cfg])]))
