#!/usr/bin/env bb
;; lite.bb — standalone lite-mode diagnostic tool for ClojureScript projects
;;
;; Usage: bb /path/to/lite.bb <command> [args...]
;;   validate                                    list browser builds in shadow-cljs.edn
;;   build <label> <build> [--exp <n>] [cmd…]   compile both measure + diagnostic slots
;;   check [label]                               analyze diagnostic slot for label (default: advanced)
;;   diff  <a> <b>                               compare two labels: size from measure, analysis from diag
;;   report [label]                              post-DCE bytes per source from measure slot's report.html
;;
;; State is tracked in .cljs-lite-skill/latest.edn:
;;   {:latest 2
;;    1 {:before {:measure "before-1" :diag "diag-before-1"}
;;       :after  {:measure "after-1"  :diag "diag-after-1"}}
;;    2 {:advanced {:measure "advanced-2" :diag "diag-advanced-2"}}}
;;
;; Each build defaults to a new experiment (increments :latest). Pass --exp <n> to
;; add a label to an existing experiment instead.
;;
;; Example:
;;   bb ~/cljs-lite-skill/lite.bb build advanced app clojure -M:demo:shadow

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

(def +diag-build-opts
  '{:compiler-options {:lite-mode       true
                      :elide-to-string true
                      :pseudo-names    true
                      :source-map      true}})

(def +measure-build-opts
  '{:compiler-options {:lite-mode       true
                       :elide-to-string true}
    :build-hooks
    [(shadow.cljs.build-report/hook)]})

;; ---- state ----

(def state-file ".cljs-lite-skill/latest.edn")

(defn read-state []
  (if (fs/exists? state-file)
    (read-string (slurp state-file))
    {:latest 0}))

(defn write-state [state]
  (fs/create-dirs ".cljs-lite-skill")
  (spit state-file (pr-str state)))

(defn get-slots
  "Look up measure+diag slot names for label in experiment n (defaults to :latest)."
  ([label] (get-slots label nil))
  ([label exp-num]
   (let [state (read-state)
         n     (or exp-num (:latest state))
         slots (get-in state [n (keyword label)])]
     (when-not slots
       (println (str "ERROR: no slot for label '" label "' in experiment " n " — run 'build' first."))
       (System/exit 1))
     slots)))

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
                            "--lang" "cljs"
                            "--lint"] files)
              result (apply jsh/sh args)
              data   (json/parse-string (:out result) true)]
          (->> (get-in data [:analysis :var-usages])
               (filter (fn [{:keys [to name lang]}]
                         (and (not= (str lang) "clj")
                              (contains? #{"cljs.core" "clojure.core"} (str to))
                              (contains? +banned-fns+ (str name)))))
               (map (fn [{:keys [filename row col name]}]
                      {:file    (str/replace (str filename)
                                             (re-pattern "^.*/src/") "src/")
                       :line    row
                       :col     col
                       :fn-name (str name)}))
               (sort-by (juxt :file :line))))))))

;; ---- report parsing ----

(defn parse-report-edn [html-path]
  (let [html  (slurp html-path)
        tag   "<script type=\"shadow/build-report\">"
        start (.indexOf html tag)]
    (when (neg? start)
      (println (str "No shadow/build-report script tag found in " html-path))
      (System/exit 1))
    (let [end (.indexOf html "</script>" start)]
      (read-string (subs html (+ start (count tag)) end)))))

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
            (println "Usage: bb lite.bb build <label> <build-name> [--exp <n>] [cmd-prefix…]")
            (println "  cmd-prefix defaults to: clojure -M -m shadow.cljs.devtools.cli")
            (println "  example:  bb lite.bb build advanced app clojure -M:demo:shadow"))))))

(defn run-build! [slot-label build-opts build-name cmd-prefix]
  (when-not (fs/exists? "shadow-cljs.edn")
    (println "No shadow-cljs.edn found.")
    (System/exit 1))
  (let [slot-dir  (str ".cljs-lite-skill/" slot-label)
        merge-cfg (merge build-opts {:output-dir slot-dir})
        prefix    (if (seq cmd-prefix) (vec cmd-prefix) ["npx" "shadow-cljs"])
        cmd       (conj prefix "release" build-name "--config-merge" (pr-str merge-cfg))]
    (println (str "  Building → " slot-dir " …"))
    (fs/create-dirs slot-dir)
    (apply proc/shell cmd)
    (doseq [js (fs/glob slot-dir "*.js")]
      (jsh/sh "brotli" "-9" "-f" (str js) "-o" (str js ".br")))
    (when-let [js (first (fs/glob slot-dir "*.js"))]
      (let [br (str js ".br")]
        (print (str "  Done: " (size-kb (str js))))
        (when (fs/exists? br) (print (str "  " (size-kb br) " brotli")))
        (println)))))

(defn clean! []
  (if (fs/exists? ".cljs-lite-skill")
    (do (fs/delete-tree ".cljs-lite-skill")
        (println "Cleaned .cljs-lite-skill/"))
    (println "Nothing to clean.")))

(defn build! [label build-name exp-num cmd-prefix]
  (let [state        (read-state)
        n            (or exp-num (inc (:latest state)))
        measure-slot (str label "-" n)
        diag-slot    (str "diag-" label "-" n)]
    (println (str "Building " build-name " [exp " n "] …"))
    (println "  [measure]")
    (run-build! measure-slot +measure-build-opts build-name cmd-prefix)
    (println "  [diagnostic]")
    (run-build! diag-slot +diag-build-opts build-name cmd-prefix)
    (write-state (-> state
                     (assoc :latest n)
                     (assoc-in [n (keyword label)] {:measure measure-slot :diag diag-slot})))
    (println (str "Slots: " measure-slot "  |  " diag-slot))))

(defn analyze-slot
  "Returns analysis results for a slot, or exits if no artifact found."
  [slot-label]
  (let [slot-dir (str ".cljs-lite-skill/" slot-label)
        js-path  (first (fs/glob slot-dir "*.js"))
        map-path (first (fs/glob slot-dir "*.js.map"))]
    (when-not (and js-path (fs/exists? js-path))
      (println (str "No artifact in slot '" slot-label "' — run 'build' first."))
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
  (let [{:keys [measure diag]} (get-slots label)
        n (:latest (read-state))]
    (println (str "Experiment " n ": measure=" measure " diag=" diag))
    (print-check label (analyze-slot diag))))

(defn diff! [label-a label-b]
  (when-not (and label-a label-b)
    (println "Usage: bb lite.bb diff <a> <b>")
    (System/exit 1))
  (let [state    (read-state)
        n        (:latest state)
        slots-a  (get-slots label-a)
        slots-b  (get-slots label-b)
        measure-a (analyze-slot (:measure slots-a))
        measure-b (analyze-slot (:measure slots-b))
        diag-a    (analyze-slot (:diag slots-a))
        diag-b    (analyze-slot (:diag slots-b))]
    (println (str "\n=== diff [exp " n "]: " label-a " → " label-b " ===\n"))
    ;; size from measure slots
    (let [sa (fs/size (:js-path measure-a))
          sb (fs/size (:js-path measure-b))
          d  (- sb sa)]
      (print (str "JS: " (size-kb (:js-path measure-a)) " → " (size-kb (:js-path measure-b))
                  " (" (if (neg? d) "-" "+") (int (/ (Math/abs d) 1024)) "k)"))
      (when (and (:br-path measure-a) (:br-path measure-b))
        (let [bra (fs/size (:br-path measure-a))
              brb (fs/size (:br-path measure-b))
              bd  (- brb bra)]
          (print (str " | brotli: " (size-kb (:br-path measure-a)) " → " (size-kb (:br-path measure-b))
                      " (" (if (neg? bd) "-" "+") (int (/ (Math/abs bd) 1024)) "k)"))))
      (println))
    ;; leaked types from diag slots
    (let [la (set (:leaked diag-a))
          lb (set (:leaked diag-b))]
      (cond
        (and (empty? la) (empty? lb)) (println "Leaked: CLEAN (both)")
        (empty? lb)                   (println "Leaked: DIRTY -> CLEAN")
        (empty? la)                   (println "Leaked: CLEAN -> DIRTY")
        :else
        (do (println "Leaked: still dirty")
            (run! (fn [n] (println (str "  FIXED: " n))) (sort (set/difference la lb)))
            (run! (fn [n] (println (str "  NEW:   " n))) (sort (set/difference lb la)))))
      (println))
    ;; banned functions from diag slots
    (let [ba    (into #{} (keys (:found-banned diag-a)))
          bb    (into #{} (keys (:found-banned diag-b)))
          fixed (set/difference ba bb)
          added (set/difference bb ba)]
      (when (or (seq fixed) (seq added))
        (println "Banned functions:")
        (run! (fn [k] (println (str "  FIXED: " k))) (sort fixed))
        (run! (fn [k] (println (str "  NEW:   " k))) (sort added))
        (println)))
    ;; name count from diag slots
    (let [na  (:names diag-a)
          nb  (:names diag-b)
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

(defn report! [label]
  (let [{:keys [measure]} (get-slots label)
        html-path (str ".cljs-lite-skill/" measure "/report.html")]
    (when-not (fs/exists? html-path)
      (println (str "No report.html in " measure " — rebuild first (measure builds write the report)."))
      (System/exit 1))
    (let [data      (parse-report-edn html-path)
          module    (first (:build-modules data))
          src-bytes (:source-bytes module)
          src-info  (into {} (map (fn [s] [(:resource-name s) s]) (:build-sources data)))
          entries   (->> src-bytes
                         (filter (fn [[_ sz]] (pos? sz)))
                         (sort-by val >))
          total     (reduce + (map val entries))
          col-w     58]
      (println (str "\n=== report: " measure " (post-DCE bytes) ===\n"))
      (println (format (str "%-" col-w "s  %7s  %s") "source" "bytes" "type"))
      (println (str/join "" (repeat (+ col-w 22) "-")))
      (doseq [[rname sz] entries]
        (let [{:keys [type fs-root]} (src-info rname)
              tag (cond fs-root (str (name type) " [" fs-root "]")
                        type    (name type)
                        :else   "?")]
          (println (format (str "%-" col-w "s  %7d  %s") rname sz tag))))
      (println (str/join "" (repeat (+ col-w 22) "-")))
      (println (format (str "%-" col-w "s  %7d") "TOTAL" total)))))

;; ---- dispatch ----

(defn parse-build-args [args]
  (let [exp-idx (.indexOf (vec args) "--exp")
        exp-num (when (>= exp-idx 0) (Integer/parseInt (nth (vec args) (inc exp-idx))))
        args'   (if (>= exp-idx 0)
                  (into (vec (take exp-idx args)) (drop (+ exp-idx 2) args))
                  (vec args))
        [label build-name & cmd-prefix] args']
    (when-not build-name
      (println "Usage: bb lite.bb build <label> <build-name> [--exp <n>] [cmd-prefix…]")
      (println "Run 'validate' to see available builds.")
      (System/exit 1))
    [label build-name exp-num cmd-prefix]))

(let [[cmd & args] *command-line-args*]
  (case cmd
    "validate" (validate!)
    "clean"    (clean!)
    "build"    (let [[label build-name exp-num cmd-prefix] (parse-build-args args)]
                 (build! label build-name exp-num cmd-prefix))
    "check"    (check! (or (first args) "advanced"))
    "diff"     (diff! (first args) (second args))
    "report"   (report! (or (first args) "advanced"))
    (println "Usage: bb lite.bb <command> [args...]
  validate                                   list available browser builds
  clean                                      remove all slots (.cljs-lite-skill/)
  build <label> <build> [--exp <n>] [cmd…]  compile measure + diagnostic slots
  check [label]                              analyze diagnostic slot for label (default: advanced)
  diff  <a> <b>                              compare two labels in current experiment
  report [label]                             post-DCE bytes per source (default: advanced)")))
