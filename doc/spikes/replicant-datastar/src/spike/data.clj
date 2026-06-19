(ns spike.data
  "Fake, in-memory transaction data shaped like the real app's transactions.

  The spike deliberately does NOT touch Datalevin — the research question is
  about the interaction model (can server-rendered hypermedia carry this app's
  client interactivity?), not the data layer, which is unchanged. In-memory data
  keeps the spike runnable without the native LMDB libs / seeded DB.")

;; A signed-amount transaction (inflows +, outflows −), mirroring the project's
;; provider conventions. Splits are modelled as child parts that carry their own
;; category + amount — the real app navigates these as extra grid rows.
(def categories
  ["Groceries" "Dining" "Transport" "Rent" "Utilities" "Income"
   "Shopping" "Health" "Subscriptions" "Transfer"])

(def accounts
  [{:id "chk" :name "Checking" :institution "Pine Bank"}
   {:id "sav" :name "Savings" :institution "Pine Bank"}
   {:id "cc"  :name "Travel Card" :institution "Aspen CU"}])

(defn- tx [id date payee account category cents reviewed splits]
  {:id id :date date :payee payee :account account
   :category category :cents cents :reviewed reviewed :splits splits})

(def seed
  (vec
   (concat
    [(tx 1 "2026-06-01" "Whole Foods Market" "chk" "Groceries" -8423 false nil)
     (tx 2 "2026-06-02" "Shell Oil 47823" "cc" "Transport" -5210 false nil)
     (tx 3 "2026-06-02" "Payroll — Acme Corp" "chk" "Income" 412300 true nil)
     ;; A split transaction: one Costco charge divided across categories. In the
     ;; real grid this becomes a parent row (description) + 3 child rows.
     (tx 4 "2026-06-03" "Costco Wholesale" "cc" nil -19200 false
         [{:id 401 :category "Groceries" :cents -12000 :memo "food" :reviewed false}
          {:id 402 :category "Health" :cents -4200 :memo "pharmacy" :reviewed false}
          {:id 403 :category "Shopping" :cents -3000 :memo "household" :reviewed true}])
     (tx 5 "2026-06-04" "Spotify USA" "cc" "Subscriptions" -1199 true nil)
     (tx 6 "2026-06-05" "Transfer to Savings" "chk" "Transfer" -50000 false nil)
     (tx 7 "2026-06-05" "Transfer from Checking" "sav" "Transfer" 50000 false nil)
     (tx 8 "2026-06-06" "Pacific Gas & Electric" "chk" "Utilities" -14387 false nil)
     (tx 9 "2026-06-07" "Blue Bottle Coffee" "cc" "Dining" -742 false nil)
     (tx 10 "2026-06-08" "Rent — Maple Apartments" "chk" "Rent" -210000 true nil)
     (tx 11 "2026-06-09" "Amazon Marketplace" "cc" "Shopping" -6650 false nil)
     (tx 12 "2026-06-10" "Trader Joe's" "chk" "Groceries" -5512 false nil)
     (tx 13 "2026-06-11" "Uber Trip" "cc" "Transport" -2340 false nil)
     (tx 14 "2026-06-12" "CVS Pharmacy" "chk" "Health" -3178 false nil)
     (tx 15 "2026-06-13" "Netflix" "cc" "Subscriptions" -1549 true nil)]
    ;; A few more so the table is tall enough to feel like a real ledger.
    (for [i (range 16 41)]
      (tx i (format "2026-06-%02d" (+ 1 (mod i 28)))
          (rand-nth ["Corner Store" "Gas N Go" "Local Cafe" "Bookshop" "Hardware Co"
                     "Pet Supplies" "Pharmacy Plus" "Farmers Market"])
          (:id (rand-nth accounts))
          (rand-nth categories)
          (- (+ 200 (* 137 i)))
          (zero? (mod i 3))
          nil)))))

(def ^:private !db (atom seed))

(defn all-transactions [] @!db)

(defn account-name [id]
  (->> accounts (filter #(= (:id %) id)) first :name))

(defn set-reviewed! [tx-id reviewed?]
  (swap! !db (fn [txs]
               (mapv (fn [t] (if (= (:id t) tx-id) (assoc t :reviewed reviewed?) t))
                     txs)))
  reviewed?)

(defn set-category! [tx-id category]
  (swap! !db (fn [txs]
               (mapv (fn [t] (if (= (:id t) tx-id) (assoc t :category category) t))
                     txs)))
  category)

(defn set-payee! [tx-id payee]
  (swap! !db (fn [txs]
               (mapv (fn [t] (if (= (:id t) tx-id) (assoc t :payee payee) t))
                     txs)))
  payee)

(defn review-counts []
  (let [txs @!db
        total (count txs)
        reviewed (count (filter :reviewed txs))]
    {:total total :reviewed reviewed :remaining (- total reviewed)}))
