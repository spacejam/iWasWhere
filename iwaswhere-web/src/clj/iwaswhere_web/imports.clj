(ns iwaswhere-web.imports
  "This namespace does imports, for example of photos."
  (:require [clojure.pprint :as pp]
            [iwaswhere-web.files :as f]
            [iwaswhere-web.migrations :as m]
            [iwaswhere-web.specs :as specs]
            [clj-time.coerce :as c]
            [clj-time.core :as t]
            [cheshire.core :as cc]
            [matthiasn.systems-toolbox.component :as st]
            [me.raynes.fs :as fs]
            [clj-time.format :as tf]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [iwaswhere-web.utils.misc :as u])
  (:import [com.drew.imaging ImageMetadataReader]))

(defn dms-to-dd
  "Converts DMS (degree, minute, second) to DD (decimal degree) format. Returns
   nil when not all 3 groups dm, m, and s are contained in coord string. Result
   negative when coord in Western or Southern Hemisphere according to ref
   argument."
  [exif coord-key ref-key]
  (let [coord (get exif coord-key)
        ref (get exif ref-key)]
    (when coord
      (let [matcher (re-matcher #"(\d{1,3})° (\d{1,2})' (\d{1,2}\.?\d+?)" coord)
            [_dms d m s] (re-find matcher)]
        (when (and d m s)
          (let [d (read-string d)
                m (read-string m)
                s (read-string s)
                dd (float (+ d (/ m 60) (/ s 3600)))]
            (if (contains? #{"W" "S"} ref)
              (- dd)
              dd)))))))

(defn add-filename-offset
  "Add the last three digits of the filename to the timestamp in order to avoid
   collisions when photos were taken in burst mode on the iPhone, as
   unfortunately the photo timestamp is only precise to the second and the
   capture rate for iPhone 6s is 10fps."
  [millis filename]
  (let [filename-number (re-find #"\d{3}(?=\.)" filename)
        offset (if filename-number (Integer. filename-number) 0)]
    (+ millis offset)))

(defn extract-gps-ts
  "Converts concatenated GPS timestamp strings into milliseconds since epoch.
  Example from iPhone 6s camera app:
    'GPS Date Stamp' '2016:03:30'
    'GPS Time-Stamp' '20:07:57.00 UTC'"
  [ts filename]
  (let [f (tf/formatter "yyyy:MM:dd HH:mm:ss.SS z")]
    (when (and (seq ts)
               (re-matches
                 #"\d{4}:\d{2}:\d{2} \d{2}:\d{2}:\d{2}\.\d{2} [A-Z]{1,5}" ts))
      (add-filename-offset (c/to-long (tf/parse f ts)) filename))))

(defn extract-ts
  "Converts 'Date/Time' exif tag into milliseconds since epoch. Assumes local
   timezone at the time of importing a photo, which may or may not be accurate.
   In case of iPhone, this is only relevant for screenshots, media saved to
   local camera roll or when no GPS available, such as in the case of being in
   an aircraft (airplane mode). This is unfortunate, why would they not just use
   UTC all the time?"
  [ts filename]
  (let [dtz (t/default-time-zone)
        f (tf/formatter "yyyy:MM:dd HH:mm:ss" dtz)]
    (when (and (seq ts)
               (re-matches #"\d{4}:\d{2}:\d{2} \d{2}:\d{2}:\d{2}" ts))
      (add-filename-offset (c/to-long (tf/parse f ts)) filename))))

(defn extract-from-tag
  "Creates map for a single Exif directory.
  Borrowed from: https://github.com/joshuamiller/exif-processor"
  [tag]
  (into {} (map #(hash-map (.getTagName %) (.getDescription %)) tag)))

(defn extract-exif
  [file]
  (let [metadata (ImageMetadataReader/readMetadata file)
        exif-directories (.getDirectories metadata)
        tags (map #(.getTags %) exif-directories)
        exif (into {} (map extract-from-tag tags))]
    exif))

(defn import-image
  "Takes an image file (as a java.io.InputStream or java.io.File) and extracts
   exif information into a map.
   Borrowed and modified from: https://github.com/joshuamiller/exif-processor"
  [file]
  (let [filename (.getName file)
        rel-path (.getPath file)
        exif (extract-exif file)
        timestamp (or
                    (extract-gps-ts (str (get exif "GPS Date Stamp") " "
                                         (get exif "GPS Time-Stamp")) filename)
                    (extract-ts (str (get exif "Date/Time")) filename)
                    (extract-ts (str (get exif "Date/Time Original")) filename)
                    (st/now))
        target-filename (str timestamp "-" filename)]
    (fs/rename rel-path (str f/data-path "/images/" target-filename))
    {:timestamp timestamp
     :latitude  (dms-to-dd exif "GPS Latitude" "GPS Latitude Ref")
     :longitude (dms-to-dd exif "GPS Longitude" "GPS Longitude Ref")
     :img-file  target-filename
     :tags      #{"#photo" "#import"}}))

(defn import-video
  "Takes an video file (as a java.io.InputStream or java.io.File) creates entry
   from it."
  [file]
  (let [filename (.getName file)
        rel-path (.getPath file)
        timestamp (.lastModified file)
        target-filename (str timestamp "-" filename)]
    (fs/rename rel-path (str f/data-path "/videos/" target-filename))
    {:timestamp  timestamp
     :video-file target-filename
     :tags       #{"#video" "#import"}}))

(defn import-audio
  "Takes an audio file (as a java.io.InputStream or java.io.File) creates entry
   from it."
  [audio-file]
  (let [filename (.getName audio-file)
        ts-str (subs filename 0 15)
        f (tf/formatter "yyyyMMdd HHmmss")
        timestamp (.lastModified audio-file)
        rel-path (.getPath audio-file)
        target-filename (s/replace (str timestamp "-" filename) " " "_")]
    (fs/rename rel-path (str f/data-path "/audio/" target-filename))
    {:timestamp  (c/to-long (tf/parse f ts-str))
     :audio-file target-filename
     :tags       #{"#audio" "#import"}}))

(defn import-media
  "Imports photos from respective directory."
  [{:keys [put-fn msg-meta]}]
  (let [files (file-seq (io/file (str f/data-path "/import")))]
    (log/info "importing media files")
    (doseq [file (f/filter-by-name files specs/media-file-regex)]
      (let [filename (.getName file)]
        (log/info "Trying to import " filename)
        (try (let [[_ file-type] (re-find #"^.*\.([a-z0-9]{3})$" filename)
                   file-info (case file-type
                               "m4v" (import-video file)
                               "m4a" (import-audio file)
                               (import-image file))]
               (when file-info
                 (put-fn (with-meta [:entry/import file-info] msg-meta))))
             (catch Exception ex (log/error (str "Error while importing "
                                                 filename) ex)))))
    {:emit-msg [:cmd/schedule-new
                {:timeout 5000 :message (with-meta [:search/refresh] msg-meta)}]}))

(defn import-visits-fn
  [rdr put-fn msg-meta filename]
  (try
    (let [lines (line-seq rdr)]
      (doseq [line lines]
        (let [raw-visit (cc/parse-string line #(keyword (s/replace % "_" "-")))
              {:keys [arrival-ts departure-ts]} (u/visit-timestamps raw-visit)
              dur (when departure-ts
                    (-> (- departure-ts arrival-ts)
                        (/ 6000)
                        (Math/floor)
                        (/ 10)))
              visit (merge raw-visit
                           {:timestamp arrival-ts
                            :md        (if dur
                                         (str "Duration: " dur "m #visit")
                                         "No departure recorded #visit")
                            :tags      #{"#visit" "#import"}})]
          (if-not (neg? (:timestamp visit))
            (put-fn (with-meta [:entry/import visit] msg-meta))
            (log/warn "negative timestamp?" visit)))))
    (catch Exception ex (log/error "Error while importing " filename ex))))

(defn import-geo
  "Imports geo data from respective directory.
  For now, only pays attention to visits."
  [{:keys [put-fn msg-meta]}]
  (let [files (file-seq (io/file (str f/data-path "/geo-import")))]
    (log/info "importing photos")
    (doseq [file (f/filter-by-name files #"visits.json" #_#"[-0-9]+.(json)")]
      (let [filename (.getName file)]
        (log/info "Trying to import " filename)
        (import-visits-fn (io/reader file) put-fn msg-meta filename)))))

(defn import-weight-csv-fn
  [rdr put-fn msg-meta filename]
  (try
    (let [lines (line-seq rdr)]
      (doseq [line (drop 1 lines)]
        (let [parse-double #(when (seq %) (Double/parseDouble %))
              parsed (s/split (s/replace line "\"" "") #",")
              w (parse-double (second parsed))
              lbm (parse-double (get parsed 3))
              fat (parse-double (get parsed 2))
              dtz (t/default-time-zone)
              f (tf/formatter "yyyy-MM-dd h:mm a" dtz)
              entry {:timestamp    (c/to-long (tf/parse f (first parsed)))
                     :md           (str "current #weight: " w
                                        (when lbm (str " lbm: " lbm "%")))
                     :tags         #{"#weight"}
                     :measurements {:weight {:value w
                                             :unit  :kg
                                             :lbm   lbm
                                             :fat   fat}}}]
          (put-fn (with-meta [:entry/import entry] msg-meta)))))
    (catch Exception ex (log/error "Error while importing " filename ex))))

(defn import-weight-csv
  "Imports weights csv data from respective directory."
  [{:keys [put-fn msg-meta]}]
  (let [files (file-seq (io/file (str f/data-path "/import")))]
    (log/info "importing measurements")
    (doseq [file (f/filter-by-name files #"withings.csv")]
      (let [filename (.getName file)]
        (log/info "Trying to import" filename)
        (import-weight-csv-fn (io/reader file) put-fn msg-meta filename)))
    {:emit-msg [:search/refresh]}))

(defn update-audio-tag
  [entry]
  (if (:audio-file entry)
    (-> entry
        (update-in [:tags] conj "#audio")
        (update-in [:md] str " #audio"))
    entry))

(defn import-text-entries-fn
  [rdr put-fn msg-meta filename]
  (try (let [lines (line-seq rdr)]
         (doseq [line lines]
           (when (seq line)
             (let [set-linked
                   (fn [entry]
                     (assoc-in entry [:linked-entries]
                               (when-let [linked (:linked-timestamp entry)]
                                 #{linked})))
                   entry
                   (-> (cc/parse-string line #(keyword (s/replace % "_" "-")))
                       (m/add-tags-mentions)
                       (update-audio-tag)
                       (update-in [:timestamp] u/double-ts-to-long)
                       (update-in [:linked-timestamp] u/double-ts-to-long))
                   entry (if (:linked-timestamp entry)
                           (set-linked entry)
                           (update-in entry [:tags] conj "#import"))]
               (put-fn (with-meta [:entry/import entry] msg-meta))))))
       (catch Exception ex (log/error (str "Error while importing "
                                           filename) ex))))

(defn import-text-entries
  "Imports text entries from phone."
  [{:keys [put-fn msg-meta]}]
  (let [files (file-seq (io/file (str f/data-path "/geo-import")))]
    (log/info "importing photos")
    (doseq [file (f/filter-by-name files #"text-entries.json")]
      (let [filename (.getName file)]
        (log/info "Trying to import " filename)
        (import-text-entries-fn (io/reader file) put-fn msg-meta filename)))
    {:emit-msg [:search/refresh]}))

(defn cmp-map
  "Generates component map for imports-cmp."
  [cmp-id]
  {:cmp-id      cmp-id
   :handler-map {:import/photos import-media
                 :import/geo    import-geo
                 :import/weight import-weight-csv
                 :import/phone  import-text-entries}})
