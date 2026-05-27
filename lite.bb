#!/usr/bin/env bb
;; lite.bb — standalone lite-mode diagnostic tool for ClojureScript projects
;;
;; Usage: bb /path/to/lite.bb <command> [args...]
;;   validate                        list browser builds in shadow-cljs.edn
;;   build <label> <build> [cmd…]    compile → target/lite-diag/<label>/
;;   check [label]                   analyze build slot (default: latest)
;;   diff  <a> <b>                   compare two build slots
;;
;; build injects all diagnostic options via --config-merge — no shadow-cljs.edn changes needed.
;; The cmd prefix is how to invoke shadow-cljs in this project (default: npx shadow-cljs).
;;
;; Example:
;;   bb /path/to/lite.bb build latest app clojure -M:demo:shadow

(ns lite
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.java.shell :as jsh]
            [clojure.set :as set]
            [clojure.string :as str]))

;; ---- constants ----

(def +banned-fns+
  {"set"         "(reduce conj #{} coll)"
   "hash-map"    "{k v ...} literal"
   "array-map"   "{k v ...} literal"
   "sorted-map"  "avoid sorted-map"
   "sorted-set"  "avoid sorted-set"
   "zipmap"      "(into {} (map vector ks vs))"
   "frequencies" "inline with reduce"
   "group-by"    "inline with reduce"
   "doseq"       "(run! fn coll)"
   "for"         "(map fn coll)"
   "ex-info"     "js/Error. + (set! (.-data e) data)"
   "pr-str"      "custom serializer or js/console.log"
   "prn"         "js/console.log"
   "println"     "js/console.log (str x)"
   "vec"         "(or (:key m) []) or [] literal"})

(def +artifact-banned+
  {"cljs$core$set$$"         "set"
   "cljs$core$hash_map$$"    "hash-map"
   "cljs$core$array_map$$"   "array-map"
   "cljs$core$sorted_map$$"  "sorted-map"
   "cljs$core$sorted_set$$"  "sorted-set"
   "cljs$core$zipmap$$"      "zipmap"
   "cljs$core$frequencies$$" "frequencies"
   "cljs$core$group_by$$"    "group-by"
   "cljs$core$doseq$$"       "doseq"
   "cljs$core$ex_info$$"     "ex-info"
   "cljs$core$pr_str$$"      "pr-str"
   "cljs$core$prn$$"         "prn"
   "cljs$core$println$$"     "println"})

(def +dirty-pattern+ (re-pattern "Persistent|ChunkedSeq|ChunkedCons"))

(def +diag-compiler-options+
  {:lite-mode       true
   :elide-to-string true
   :pseudo-names    true
   :source-map      true})

(def +measure-compiler-options+
  {:lite-mode       true
   :elide-to-string true})

;; ---- helpers ----

(defn size-kb [path]
  (str (int (/ (fs/size path) 1024)) "k"))

(defn read-shadow-config []
  (when (fs/exists? "shadow-cljs.edn")
    (read-string (slurp "shadow-cljs.edn"))))

(defn browser-builds []
  (when-let [cfg (read-shadow-config)]
    (->> (get cfg :builds {})
         (filter (fn [[_ b]] (= :browser (:target b))))
         (sort-by key))))

(defn extract-names [js]
  (->> (re-seq (re-pattern "\\$[a-zA-Z0-9$]+\\$\\$") js)
       (into (sorted-set))))

(defn lite-kondo
  "Run clj-kondo over files listed in the source map, return banned-fn violations."
  [map-path]
  (when (and map-path (fs/exists? map-path))
    (let [m     (json/parse-string (slurp map-path))
          srcs  (get-in m ["sections" 0 "map" "sources"])
          files (->> srcs
                     (map (fn [s] (str "src/" s)))
                     (filter fs/exists?)
                     vec)]
      (when (seq files)
        (let [args   (into ["clj-kondo"
                            "--config" "{:output {:analysis true :format :json}}"
                            "--lint"] files)
              result (apply jsh/sh args)
              data   (json/parse-string (:out result) true)]
          (->> (get-in data [:analysis :var-usages])
               (filter (fn [{:keys [to name]}]
                         (and (contains? #{"cljs.core" "clojure.core"} (str to))
                              (contains? +banned-fns+ (str name)))))
               (map (fn [{:keys [filename row col name]}]
                      {:file    (str/replace (str filename)
                                             (re-pattern "^.*/src/") "src/")
                       :line    row
                       :col     col
                       :fn-name (str name)}))
               (sort-by (juxt :file :line))))))))

;; ---- commands ----

(defn check-path [tool]
  (let [result (jsh/sh "which" tool)]
    (if (zero? (:exit result))
      (println (str "  " tool "  ✓  " (str/trim (:out result))))
      (println (str "  " tool "  MISSING")))))

(defn validate! []
  (println "PATH:")
  (run! check-path ["clj-kondo" "brotli"])
  (println)
  (if-not (fs/exists? "shadow-cljs.edn")
    (do (println "ERROR: No shadow-cljs.edn found in current directory.")
        (System/exit 1))
    (let [builds (browser-builds)]
      (if (empty? builds)
        (do (println "No :browser target builds found in shadow-cljs.edn.")
            (System/exit 1))
        (do (println "Available builds:")
            (doseq [[k b] builds]
              (let [mod  (-> b :modules first key name)
                    opts (get-in b [:release :compiler-options])]
                (println (str "  " (name k) "  (module: " mod ")"
                              (when (:lite-mode opts) "  :lite-mode ✓")
                              (when (:elide-to-string opts) "  :elide-to-string ✓")))))
            (println)
            (println "Usage: bb lite.bb build <label> <build-name> [cmd-prefix…]")
            (println "  cmd-prefix defaults to: clojure -M -m shadow.cljs.devtools.cli")
            (println "  example:  bb lite.bb build latest app clojure -M:demo:shadow"))))))

(defn run-build! [label compiler-options build-name cmd-prefix]
  (when-not (fs/exists? "shadow-cljs.edn")
    (println "No shadow-cljs.edn found.")
    (System/exit 1))
  (let [slot-dir  (str "target/lite-diag/" label)
        merge-cfg {:compiler-options compiler-options
                   :output-dir       slot-dir}
        prefix    (if (seq cmd-prefix) (vec cmd-prefix) ["npx" "shadow-cljs"])
        cmd       (conj prefix "release" build-name "--config-merge" (pr-str merge-cfg))]
    (println (str "Building " build-name " → " slot-dir " …"))
    (fs/create-dirs slot-dir)
    (apply proc/shell cmd)
    (doseq [js (fs/glob slot-dir "*.js")]
      (jsh/sh "brotli" "-9" "-f" (str js) "-o" (str js ".br")))
    (when-let [js (first (fs/glob slot-dir "*.js"))]
      (let [br (str js ".br")]
        (print (str "Done: " (size-kb (str js))))
        (when (fs/exists? br) (print (str "  " (size-kb br) " brotli")))
        (println)))))

(defn build-diagnostic! [label build-name cmd-prefix]
  (run-build! label +diag-compiler-options+ build-name cmd-prefix))

(defn build-measure! [label build-name cmd-prefix]
  (run-build! label +measure-compiler-options+ build-name cmd-prefix))

(defn analyze-slot
  "Returns analysis results for a slot, or exits if no artifact found."
  [label]
  (let [slot-dir (str "target/lite-diag/" label)
        js-path  (first (fs/glob slot-dir "*.js"))
        map-path (first (fs/glob slot-dir "*.js.map"))]
    (when-not (and js-path (fs/exists? js-path))
      (println (str "No artifact in slot '" label "' — run 'build " label " <build-name>' first."))
      (System/exit 1))
    (let [js    (slurp (str js-path))
          names (extract-names js)
          br    (str js-path ".br")]
      {:js-path      (str js-path)
       :br-path      (when (fs/exists? br) br)
       :names        names
       :leaked       (->> names (filter (fn [n] (re-find +dirty-pattern+ n))) sort)
       :found-banned (->> +artifact-banned+
                          (filter (fn [[k _]] (str/includes? js k)))
                          (into (sorted-map)))
       :violations   (lite-kondo (when map-path (str map-path)))})))

(defn print-check [label {:keys [js-path br-path names leaked found-banned violations]}]
  (println (str "\n=== " label " ==="))
  (print (str "Size: " (size-kb js-path)))
  (when br-path (print (str " | " (size-kb br-path) " brotli")))
  (println "\n")
  (if (empty? leaked)
    (println "CLEAN — no Persistent/Chunked types\n")
    (do (println (str "DIRTY — " (count leaked) " leaked type(s):"))
        (run! (fn [n] (println (str "  " n))) leaked)
        (println)))
  (when (seq found-banned)
    (println "Banned functions in artifact:")
    (doseq [[k fn-name] found-banned]
      (println (str "  " k))
      (println (str "    → replace with: " (get +banned-fns+ fn-name "?"))))
    (println))
  (when (seq violations)
    (println "Source violations (clj-kondo):")
    (doseq [[file hits] (->> violations (group-by :file) (sort-by key))]
      (println (str "  " file))
      (doseq [{:keys [line col fn-name]} hits]
        (println (str "    :" line ":" col "  (" fn-name ")  → "
                      (get +banned-fns+ fn-name "?")))))
    (println))
  (println (str "Names: " (count names))))

(defn check! [label]
  (print-check label (analyze-slot label)))

(defn diff! [label-a label-b]
  (when-not (and label-a label-b)
    (println "Usage: bb lite.bb diff <a> <b>")
    (System/exit 1))
  (let [a (analyze-slot label-a)
        b (analyze-slot label-b)]
    (println (str "\n=== diff: " label-a " → " label-b " ===\n"))
    (let [sa (fs/size (:js-path a))
          sb (fs/size (:js-path b))
          d  (- sb sa)]
      (print (str "JS: " (size-kb (:js-path a)) " → " (size-kb (:js-path b))
                  " (" (if (neg? d) "-" "+") (int (/ (Math/abs d) 1024)) "k)"))
      (when (and (:br-path a) (:br-path b))
        (let [bra (fs/size (:br-path a))
              brb (fs/size (:br-path b))
              bd  (- brb bra)]
          (print (str " | brotli: " (size-kb (:br-path a)) " → " (size-kb (:br-path b))
                      " (" (if (neg? bd) "-" "+") (int (/ (Math/abs bd) 1024)) "k)"))))
      (println))
    (let [la (set (:leaked a))
          lb (set (:leaked b))]
      (cond
        (and (empty? la) (empty? lb)) (println "Leaked: CLEAN (both)")
        (empty? lb)                   (println "Leaked: DIRTY -> CLEAN")
        (empty? la)                   (println "Leaked: CLEAN -> DIRTY")
        :else
        (do (println "Leaked: still dirty")
            (run! (fn [n] (println (str "  FIXED: " n))) (sort (set/difference la lb)))
            (run! (fn [n] (println (str "  NEW:   " n))) (sort (set/difference lb la)))))
      (println))
    (let [ba    (into #{} (keys (:found-banned a)))
          bb    (into #{} (keys (:found-banned b)))
          fixed (set/difference ba bb)
          added (set/difference bb ba)]
      (when (or (seq fixed) (seq added))
        (println "Banned functions:")
        (run! (fn [k] (println (str "  FIXED: " k))) (sort fixed))
        (run! (fn [k] (println (str "  NEW:   " k))) (sort added))
        (println)))
    (let [na  (:names a)
          nb  (:names b)
          rm  (set/difference na nb)
          add (set/difference nb na)]
      (println (str "Names: " (count na) " -> " (count nb)
                    " | -" (count rm) " +" (count add)))
      (when (and (seq rm) (<= (count rm) 20))
        (println "  Removed:")
        (run! (fn [n] (println (str "    " n))) (sort rm)))
      (when (and (seq add) (<= (count add) 20))
        (println "  Added:")
        (run! (fn [n] (println (str "    " n))) (sort add))))))

;; ---- dispatch ----

(defn parse-build-args [args cmd-name]
  (let [[label build-name & cmd-prefix] args]
    (when-not build-name
      (println (str "Usage: bb lite.bb " cmd-name " <label> <build-name> [cmd-prefix…]"))
      (println "Run 'validate' to see available builds.")
      (System/exit 1))
    [(or label "latest") build-name cmd-prefix]))

(let [[cmd & args] *command-line-args*]
  (case cmd
    "validate"         (validate!)
    "build-diagnostic" (let [[label build-name cmd-prefix] (parse-build-args args "build-diagnostic")]
                         (build-diagnostic! label build-name cmd-prefix))
    "build-measure"    (let [[label build-name cmd-prefix] (parse-build-args args "build-measure")]
                         (build-measure! label build-name cmd-prefix))
    "check"            (check! (or (first args) "latest"))
    "diff"             (diff! (first args) (second args))
    (println "Usage: bb lite.bb <command> [args...]
  validate                                list available browser builds
  build-diagnostic <label> <build> [cmd…] compile with pseudo-names+source-map → target/lite-diag/<label>/
  build-measure    <label> <build> [cmd…] compile production-equivalent → target/lite-diag/<label>/
  check [label]                           analyze diagnostic slot (default: latest)
  diff  <a> <b>                           compare two measure slots by size")))
