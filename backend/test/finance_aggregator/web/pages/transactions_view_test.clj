(ns finance-aggregator.web.pages.transactions-view-test
  "Render checks for the fragments whose branch logic matters — the monthly-close
   panel's open / ready / closed / drift states. Most rendering is proven in the
   browser (e2e); these cover branches an e2e can't cheaply reach (a closed month,
   drift since close)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.web.pages.transactions-view :as tv]
   [finance-aggregator.web.render :as r]))

(defn- html [hiccup] (str (r/render hiccup)))

(def ^:private mixed-rows
  [{:account-id 1 :name "Chequing" :status :reconciled  :difference 0M}
   {:account-id 2 :name "Visa"     :status :drift        :difference (bigdec "5.00")}
   {:account-id 3 :name "Savings"  :status :no-snapshot  :difference nil}])

(deftest close-panel-open-month
  (let [h (html (tv/close-panel
                 {:rows mixed-rows
                  :gate {:unreviewed 2 :uncategorized 1 :all-reviewed? false
                         :all-categorized? false :balanced? false :ready? false}
                  :closed? false :closed-at nil :drift nil}))]
    (testing "per-account statuses render"
      (is (re-find #"Chequing" h))
      (is (re-find #"matches" h))
      (is (re-find #"off by" h)))
    (testing "unreconciled accounts get a statement entry on an open month"
      (is (re-find #"reconcile-stmt" h))
      (is (re-find #"/transactions/reconcile/3/statement" h) "posts for the account eid"))
    (testing "gate lines + a disabled Close button (not ready)"
      (is (re-find #"2 to review" h))
      (is (re-find #"Close month" h))
      (is (re-find #"disabled" h))
      (is (not (re-find #"Reopen" h))))))

(deftest close-panel-ready-enables-close
  (let [h (html (tv/close-panel
                 {:rows [{:account-id 1 :name "A" :status :reconciled :difference 0M}]
                  :gate {:unreviewed 0 :uncategorized 0 :all-reviewed? true
                         :all-categorized? true :balanced? true :ready? true}
                  :closed? false :closed-at nil :drift nil}))]
    (is (re-find #"Close month" h))
    (is (re-find #"Balances match" h))
    (is (not (re-find #"disabled" h)) "ready → Close is enabled")))

(deftest close-panel-closed-month-shows-reopen-and-drift
  (let [h (html (tv/close-panel
                 {:rows [{:account-id 1 :name "A" :status :reconciled :difference 0M}]
                  :gate {:ready? true}
                  :closed? true :closed-at #inst "2026-03-03"
                  :drift {:frozen (bigdec "1865") :now (bigdec "1900")}}))]
    (testing "closed banner + Reopen; no statement inputs; no Close button"
      (is (re-find #"Closed" h))
      (is (re-find #"Reopen" h))
      (is (not (re-find #"Close month" h)))
      (is (not (re-find #"reconcile-stmt" h))))
    (testing "drift note shows the frozen-vs-now change"
      (is (re-find #"Changed since close" h)))))

(deftest close-panel-empty-when-no-activity
  (is (nil? (tv/close-panel {:rows [] :gate {}}))))
