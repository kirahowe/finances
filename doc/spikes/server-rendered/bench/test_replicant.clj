(require '[replicant.string :as rs])
(println "=== Replicant SSR test ===")
(let [data {:name "Coffee" :amount "-4.50" :reviewed true}]
  (println
    (rs/render
      [:tr {:class (when (:reviewed data) "reviewed")}
       [:td (:name data)]
       [:td.amount (:amount data)]
       [:td [:input {:type "checkbox" :checked (:reviewed data)}]]
       [:td [:button {:data-on-click "@put('/tx/1/reviewed')"} "toggle"]]])))
