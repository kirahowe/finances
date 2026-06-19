(ns spike.shared
  "Logic shared between the JVM backend and the ClojureScript island, compiled to
  BOTH targets from this one .cljc file. This is the payoff CLJS uniquely offers
  and TS+Zag cannot: write a rule once, run it on the server and in the browser.

  Kept portable on purpose — no Math/abs, no clojure.core/format (neither exists
  in cljs) — to illustrate the real discipline .cljc requires.")

(def categories
  ["Groceries" "Dining" "Transport" "Rent" "Utilities" "Income"
   "Shopping" "Health" "Subscriptions" "Transfer"])

(defn cents->str
  "Signed cents -> display string, e.g. -8423 -> \"-$84.23\". Used by the server
  to render amounts AND (to prove the point) by the CLJS island."
  [c]
  (let [neg? (neg? c)
        a    (if neg? (- c) c)
        d    (quot a 100)
        r    (rem a 100)]
    (str (when neg? "-") "$" d "." (when (< r 10) "0") r)))
