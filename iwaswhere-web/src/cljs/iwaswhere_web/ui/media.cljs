(ns iwaswhere-web.ui.media
  (:require [clojure.string :as s]))

(defn image-view
  "Renders image view. Used resized and properly rotated image endpoint
   when JPEG file requested."
  [entry query-params]
  (when-let [file (:img-file entry)]
    (let [path (str "/photos/" file)
          resized (if (s/includes? path ".JPG")
                    (str "/photos2/" file query-params)
                    path)]
      [:a {:href path :target "_blank"}
       [:img {:src resized}]])))

(defn audioplayer-view
  "Renders audio player view."
  [entry]
  (when-let [audio-file (:audio-file entry)]
    [:audio {:controls true :preload "none"}
     [:source {:src (str "/audio/" audio-file) :type "audio/mp4"}]]))

(defn audioplayer
  "Renders automatically starting audio player."
  [audio-file autoplay loop id]
  [:audio {:autoPlay autoplay :loop loop :id id}
   [:source {:src audio-file :type "audio/mp4"}]])

(defn videoplayer-view
  "Renders video player view."
  [entry]
  (when-let [video-file (:video-file entry)]
    [:video {:controls true :preload "none"}
     [:source {:src (str "/videos/" video-file) :type "video/mp4"}]]))
