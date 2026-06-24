#!/usr/bin/env bb
;; Find defined vars with zero references anywhere in src/test/env.
;; Reads clj-kondo analysis EDN from the path given as the first arg.
(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def analysis (:analysis (edn/read-string (slurp (first *command-line-args*)))))

(def used
  (into #{}
        (map (juxt :to :name))
        (:var-usages analysis)))

;; defmethod targets show up as var-usages on the multimethod, but the method
;; defns themselves are anonymous; deftests are invoked by the runner; -main is
;; the gen-class entry. None of these are "dead" just because nobody calls them.
(def excluded-defined-by
  #{'clojure.test/deftest 'clojure.core/defmethod})

(def candidates
  (->> (:var-definitions analysis)
       (remove (fn [{:keys [defined-by name]}]
                 (or (contains? excluded-defined-by defined-by)
                     (= '-main name)
                     ;; integrant lifecycle + reitit route names etc.
                     (str/starts-with? (str name) "-"))))
       (filter (fn [{:keys [ns name]}]
                 (not (contains? used [ns name]))))
       (sort-by (juxt (comp str :ns) :row))))

(println "Total defined vars:" (count (:var-definitions analysis)))
(println "Total candidates (defined, never referenced):" (count candidates))
(println)
(doseq [[ns defs] (group-by :ns candidates)]
  (println (str "── " ns " (" (count defs) ")"))
  (doseq [{:keys [name row private defined-by]} (sort-by :row defs)]
    (println (format "   %-40s line %-4s %s%s"
                     name row
                     (if private "priv " "PUB  ")
                     defined-by)))
  (println))
