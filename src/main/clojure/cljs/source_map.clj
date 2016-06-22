(ns cljs.source-map
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.data.json :as json]
            [clojure.set :as set]
            [cljs.source-map.base64-vlq :as base64-vlq]))

;; =============================================================================
;; All source map code in the file assumes the following in memory
;; representation of source map data.
;;
;; { file-name[String]
;;   { line[Integer]
;;     { col[Integer]
;;       [{ :gline ..., :gcol ..., :name ...}] } }
;;
;; The outer level is a sorted map where the entries are file name and
;; sorted map of line information, the keys are strings. The line
;; information is represented as a sorted map of of column
;; information, the keys are integers. The column information is a
;; sorted map where the keys are integers and values are a vector of
;; maps - these maps have the keys :gline and :gcol for the generated
;; line and column.  A :name key may be present if available.
;;
;; This representation simplifies merging ClojureScript source map
;; information with source map information generated by Google Closure
;; Compiler optimization. We can now trivially create the merged map
;; by using :gline and :gcol in the ClojureScript source map data to
;; extract final :gline and :gcol from the Google Closure source map.

;; -----------------------------------------------------------------------------
;; Utilities

(defn indexed-sources
  "Take a seq of source file names and return a map from
   file number to integer index."
  [sources]
  (->> sources
    (map-indexed (fn [a b] [a b]))
    (reduce (fn [m [i v]] (assoc m v i)) {})))

(defn source-compare
  "Take a seq of source file names and return a comparator
   that can be used to construct a sorted map."
  [sources]
  (let [sources (indexed-sources sources)]
    (fn [a b] (compare (sources a) (sources b)))))

;; -----------------------------------------------------------------------------
;; Decoding

(defn seg->map
  "Take a source map segment represented as a vector
   and return a map."
  [seg source-map]
  (let [[gcol source line col name] seg]
   {:gcol   gcol
    :source (nth (:sources source-map) source)
    :line   line
    :col    col
    :name   (when-let [name (-> seg meta :name)]
              (nth (:names source-map) name))}))

(defn seg-combine
  "Combine a source map segment vector and a relative
   source map segment vector and combine them to get
   an absolute segment posititon information as a vector."
  [seg relseg]
  (let [[gcol source line col name] seg
        [rgcol rsource rline rcol rname] relseg
        nseg [(+ gcol rgcol)
              (+ (or source 0) rsource)
              (+ (or line 0) rline)
              (+ (or col 0) rcol)
              (+ (or name 0) rname)]]
    (if name
      (with-meta nseg {:name (+ name rname)})
      nseg)))

(defn update-reverse-result
  "Helper for decode-reverse. Take a source map and update it
  based on a segment map."
  [result segmap gline]
  (let [{:keys [gcol source line col name]} segmap
        d {:gline gline
           :gcol gcol}
        d (if name (assoc d :name name) d)]
    (update-in result [source]
      (fnil (fn [m]
              (update-in m [line]
                (fnil (fn [m]
                        (update-in m [col]
                          (fnil (fn [v] (conj v d))
                            [])))
                      (sorted-map))))
            (sorted-map)))))

(defn decode-reverse
  "Convert a v3 source map JSON object into a nested sorted map 
   organized as file, line, and column. Note this source map
   maps from *original* source location to generated source location."
  ([source-map]
     (decode-reverse (:mappings source-map) source-map))
  ([mappings source-map]
     (let [{:keys [sources]} source-map
           relseg-init [0 0 0 0 0]
           lines (seq (string/split mappings #";"))]
       (loop [gline 0
              lines lines
              relseg relseg-init
              result (sorted-map-by (source-compare sources))]
         (if lines
           (let [line (first lines)
                 [result relseg]
                 (if (string/blank? line)
                   [result relseg]
                   (let [segs (seq (string/split line #","))]
                     (loop [segs segs relseg relseg result result]
                       (if segs
                         (let [seg (first segs)
                               nrelseg (seg-combine (base64-vlq/decode seg) relseg)]
                           (recur (next segs) nrelseg
                             (update-reverse-result result (seg->map nrelseg source-map) gline)))
                         [result relseg]))))]
             (recur (inc gline) (next lines) (assoc relseg 0 0) result))
           result)))))

(defn update-result
  "Helper for decode. Take a source map and update it based on a
  segment map."
  [result segmap gline]
  (let [{:keys [gcol source line col name]} segmap
        d {:line line
           :col col
           :source source}
        d (if name (assoc d :name name) d)]
    (update-in result [gline]
      (fnil (fn [m]
              (update-in m [gcol]
                (fnil #(conj % d) [])))
        (sorted-map)))))

(defn decode
  "Convert a v3 source map JSON object into a nested sorted map
   organized as file, line, and column. Note this source map
   maps from *generated* source location to original source
   location."
  ([source-map]
    (decode (:mappings source-map) source-map))
  ([mappings source-map]
    (let [relseg-init [0 0 0 0 0]
          lines (seq (string/split mappings #";"))]
      (loop [gline 0 lines lines relseg relseg-init result {}]
        (if lines
          (let [line (first lines)
                [result relseg]
                (if (string/blank? line)
                  [result relseg]
                  (let [segs (seq (string/split line #","))]
                    (loop [segs segs relseg relseg result result]
                      (if segs
                        (let [seg (first segs)
                              nrelseg (seg-combine (base64-vlq/decode seg) relseg)]
                          (recur (next segs) nrelseg
                            (update-result result (seg->map nrelseg source-map) gline)))
                        [result relseg]))))]
            (recur (inc gline) (next lines) (assoc relseg 0 0) result))
          result)))))

;; -----------------------------------------------------------------------------
;; Encoding

(defn lines->segs
  "Take a nested sorted map encoding line and column information
   for a file and return a vector of vectors of encoded segments.
   Each vector represents a line, and the internal vectors are segments
   representing the contents of the line."
  [lines]
  (let [relseg (atom [0 0 0 0 0])]
    (reduce
      (fn [segs cols]
        (swap! relseg
          (fn [[_ source line col name]]
            [0 source line col name]))
        (conj segs
          (reduce
            (fn [cols [gcol sidx line col name :as seg]]
              (let [offset (map - seg @relseg)]
                (swap! relseg
                  (fn [[_ _ _ _ lname]]
                    [gcol sidx line col (or name lname)]))
                (conj cols (base64-vlq/encode offset))))
            [] cols)))
      [] lines)))

(defn relativize-path
  "Relativize a path using :source-map-path if provided or the parent directory
   otherwise."
  [path {:keys [output-dir source-map-path source-map relpaths] :as opts}]
  (let [bare-munged-path
        (cond
          (re-find #"\.jar!/" path)
          (str (or source-map-path output-dir)
               (second (string/split path #"\.jar!")))
          :else
          (str (or source-map-path output-dir)
               "/" (get relpaths path)))]
    (cond
      source-map-path bare-munged-path
      :else
      (let [unrel-uri (-> bare-munged-path io/file .toURI)
            sm-parent-uri (-> source-map io/file .getAbsoluteFile .getParentFile .toURI)]
        (str (.relativize sm-parent-uri unrel-uri))))))

(defn encode*
  "Take an internal source map representation represented as nested
   sorted maps of file, line, column and return a v3 representation."
  [m opts]
  (let [lines (atom [[]])
        names->idx (atom {})
        name-idx (atom 0)
        preamble-lines (take (or (:preamble-line-count opts) 0) (repeat []))
        info->segv
        (fn [info source-idx line col]
          (let [segv [(:gcol info) source-idx line col]]
            (if-let [name (:name info)]
              (let [idx (if-let [idx (get @names->idx name)]
                          idx
                          (let [cidx @name-idx]
                            (swap! names->idx assoc name cidx)
                            (swap! name-idx inc)
                            cidx))]
                (conj segv idx))
              segv)))
        encode-cols
        (fn [infos source-idx line col]
          (doseq [info infos]
            (let [segv (info->segv info source-idx line col)
                  gline (:gline info)
                  lc (count @lines)]
              (if (> gline (dec lc))
                (swap! lines
                  (fn [lines]
                    (conj (into lines (repeat (dec (- gline (dec lc))) [])) [segv])))
                (swap! lines
                  (fn [lines]
                    (update-in lines [gline] conj segv)))))))]
    (doseq [[source-idx [_ lines]] (map-indexed (fn [i v] [i v]) m)]
      (doseq [[line cols] lines]
        (doseq [[col infos] cols]
          (encode-cols infos source-idx line col))))

    (cond-> {"version" 3
             "file" (:file opts)
             "sources" (into []
                             (let [paths (keys m)
                                   f (comp
                                      (if (true? (:source-map-timestamp opts))
                                        #(str % "?rel=" (System/currentTimeMillis))
                                        identity)
                                      (if (or (:output-dir opts)
                                              (:source-map-path opts))
                                        #(relativize-path % opts)
                                        #(last (string/split % #"/"))))]
                               (map f paths)))
             "lineCount" (:lines opts)
             "mappings" (->> (lines->segs (concat preamble-lines @lines))
                             (map #(string/join "," %))
                             (string/join ";"))
             "names" (into []
                           (map (set/map-invert @names->idx)
                                (range (count @names->idx))))}

            (:sources-content opts)
            (assoc "sourcesContent" (:sources-content opts)))))

(defn encode
  "Take an internal source map representation represented as nested
   sorted maps of file, line, column and return a source map v3 JSON
   string."
  [m opts]
  (let [source-map-file-contents (encode* m opts)]
    (if (true? (:source-map-pretty-print opts))
      (with-out-str
        (json/pprint
         source-map-file-contents
         :escape-slash false))
      (json/write-str source-map-file-contents))))

;; -----------------------------------------------------------------------------
;; Merging

(defn merge-source-maps
  "Merge an internal source map representation of a single
   ClojureScript file mapping original to generated with a
   second source map mapping original JS to generated JS.
   The is to support source maps that work through multiple
   compilation steps like Google Closure optimization passes."
  [cljs-map js-map]
  (loop [line-map-seq (seq cljs-map) new-lines (sorted-map)]
    (if line-map-seq
      (let [[line col-map] (first line-map-seq)
            new-cols
            (loop [col-map-seq (seq col-map) new-cols (sorted-map)]
              (if col-map-seq
                (let [[col infos] (first col-map-seq)]
                  (recur (next col-map-seq)
                    (assoc new-cols col
                      (reduce (fn [v {:keys [gline gcol]}]
                                (into v (get-in js-map [gline gcol])))
                        [] infos))))
                new-cols))]
        (recur (next line-map-seq)
          (assoc new-lines line new-cols)))
      new-lines)))

;; -----------------------------------------------------------------------------
;; Reverse Source Map Inversion

(defn invert-reverse-map
  "Given a ClojureScript to JavaScript source map, invert it. Useful when
   mapping JavaScript stack traces when environment support is unavailable."
  [reverse-map]
  (let [inverted (atom (sorted-map))]
    (doseq [[line columns] reverse-map]
      (doseq [[column column-info] columns]
        (doseq [{:keys [gline gcol name]} column-info]
          (swap! inverted update-in [gline]
            (fnil (fn [columns]
                    (update-in columns [column] (fnil conj [])
                      {:line line :col column :name name}))
              (sorted-map))))))
    @inverted))

(comment
  ;; INSTRUCTIONS:
  
  ;; switch into samples/hello
  ;; run repl to start clojure
  ;; build with
  
  (require '[cljs.closure :as cljsc])
  (cljsc/build "src"
    {:optimizations :simple
     :output-to "hello.js"
     :source-map "hello.js.map"
     :output-dir "out"})

  ;; load source map
  (def raw-source-map
    (json/read-str (slurp (io/file "hello.js.map")) :key-fn keyword))

  ;; test it out
  (first (decode-reverse raw-source-map))

  ;; decoded source map preserves file order
  (= (keys (decode-reverse raw-source-map)) (:sources raw-source-map))
  )
