(ns finance-aggregator.http.integration-test
  "Integration tests for HTTP stack including middleware.

   These tests verify that the full HTTP pipeline works correctly,
   including query parameter parsing from the query string."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [finance-aggregator.http.router :as router]
   [finance-aggregator.db.categories :as categories]
   [finance-aggregator.test-utils.setup :as setup]
   [charred.api :as json]
   [datalevin.core :as d])
  (:import [java.util Date]))

(use-fixtures :each setup/with-empty-db)

(defn date-for
  "Create a Date for YYYY-MM-DD at midnight UTC."
  [year month day]
  (-> (java.time.LocalDate/of year month day)
      (.atStartOfDay (java.time.ZoneId/of "UTC"))
      .toInstant
      Date/from))

(defn make-test-transaction
  "Create a transaction map for testing with specified posted-date."
  [external-id payee amount posted-date]
  {:transaction/external-id external-id
   :transaction/payee payee
   :transaction/amount (bigdec amount)
   :transaction/posted-date posted-date})

(defn create-test-handler
  "Create the full application handler with all middleware."
  []
  (router/create-handler
   {:db-conn setup/*test-conn*
    :secrets {}
    :plaid-config {}
    :cors-config {:allowed-origins ["*"]}}))

(defn make-request
  "Create a minimal Ring request map for testing.
   This simulates what the HTTP adapter would pass to the handler."
  ([method uri]
   (make-request method uri nil))
  ([method uri query-string]
   {:request-method method
    :uri uri
    :query-string query-string
    :headers {}}))

(deftest full-stack-transactions-month-filter-test
  (testing "Full HTTP stack filters transactions by month query param"
    ;; Insert test transactions spanning multiple months
    (d/transact! setup/*test-conn*
                 [(make-test-transaction "tx-jan-1" "Starbucks" 5.00 (date-for 2025 1 15))
                  (make-test-transaction "tx-feb-1" "Amazon" 25.00 (date-for 2025 2 10))
                  (make-test-transaction "tx-jan-2" "Walmart" 50.00 (date-for 2025 1 20))])

    (let [handler (create-test-handler)
          ;; Create request with query string (like a real browser would)
          ;; The query string needs to be parsed by middleware into :query-params
          request (make-request :get "/api/transactions" "month=2025-01")
          response (handler request)]
      (is (= 200 (:status response)))
      (let [body (json/read-json (:body response) :key-fn keyword)
            data (:data body)]
        ;; Should only have the 2 January transactions - NOT all 3!
        (is (= 2 (count data))
            (str "Expected 2 transactions for January, but got " (count data)
                 ". This indicates query params are not being parsed from the query string. "
                 "Transactions returned: " (pr-str (map :transaction/external-id data))))
        (is (every? #(or (= "tx-jan-1" (:transaction/external-id %))
                         (= "tx-jan-2" (:transaction/external-id %)))
                    data))))))

(deftest full-stack-transactions-no-filter-test
  (testing "Full HTTP stack returns all transactions when no month param"
    ;; Insert test transactions
    (d/transact! setup/*test-conn*
                 [(make-test-transaction "tx-1" "Starbucks" 5.00 (date-for 2025 1 15))
                  (make-test-transaction "tx-2" "Amazon" 25.00 (date-for 2025 2 10))
                  (make-test-transaction "tx-3" "Walmart" 50.00 (date-for 2025 1 20))])

    (let [handler (create-test-handler)
          request (make-request :get "/api/transactions")
          response (handler request)]
      (is (= 200 (:status response)))
      (let [body (json/read-json (:body response) :key-fn keyword)
            data (:data body)]
        (is (= 3 (count data)))))))

(defn- json-request [method uri body]
  {:request-method method
   :uri uri
   :query-string nil
   :headers {"content-type" "application/json"}
   :body (json/write-json-str body)})

(defn- make-account! [external-id name]
  (d/transact! setup/*test-conn* [{:institution/id (str "inst-" external-id) :institution/name "Bank"}])
  (d/transact! setup/*test-conn* [{:account/external-id external-id :account/external-name name
                                   :account/institution [:institution/id (str "inst-" external-id)]}])
  (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:account/external-id external-id])))

(deftest full-stack-transfer-matching-test
  (testing "suggest -> confirm -> list carries the pair -> unmatch clears it"
    (let [checking (make-account! "chk" "Checking")
          savings (make-account! "sav" "Savings")
          _ (d/transact! setup/*test-conn*
                         [(assoc (make-test-transaction "tf-out" "Transfer" -100.00 (date-for 2025 1 15))
                                 :transaction/account checking)
                          (assoc (make-test-transaction "tf-in" "Transfer" 100.00 (date-for 2025 1 16))
                                 :transaction/account savings)])
          out-id (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:transaction/external-id "tf-out"]))
          in-id (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:transaction/external-id "tf-in"]))
          handler (create-test-handler)
          suggest (handler (make-request :get "/api/transfers/suggestions"))
          suggest-body (json/read-json (:body suggest) :key-fn keyword)]
      (is (= 200 (:status suggest)))
      (is (= 1 (count (:data suggest-body))))

      (let [confirm (handler (json-request :post "/api/transfers" {:outflowId out-id :inflowId in-id}))]
        (is (= 200 (:status confirm))))

      ;; The list endpoint now carries the partner snapshot on both legs.
      (let [txns (:data (json/read-json (:body (handler (make-request :get "/api/transactions"))) :key-fn keyword))
            out-tx (first (filter #(= "tf-out" (:transaction/external-id %)) txns))]
        (is (= in-id (get-in out-tx [:transaction/transfer-pair :db/id]))))

      ;; A confirmed pair is no longer suggested.
      (let [again (handler (make-request :get "/api/transfers/suggestions"))]
        (is (empty? (:data (json/read-json (:body again) :key-fn keyword)))))

      ;; Unmatch clears the link on both legs.
      (is (= 200 (:status (handler (make-request :delete (str "/api/transfers/" out-id))))))
      (let [out-tx (->> (:data (json/read-json (:body (handler (make-request :get "/api/transactions"))) :key-fn keyword))
                        (filter #(= "tf-out" (:transaction/external-id %)))
                        first)]
        (is (nil? (:transaction/transfer-pair out-tx)))))))

(deftest full-stack-set-splits-test
  (testing "Full HTTP stack: split a transaction, then see the parts on the list"
    (let [g (:db/id (categories/create! setup/*test-conn*
                                         {:category/name "Groceries" :category/type :expense
                                          :category/ident :category/groceries}))
          h (:db/id (categories/create! setup/*test-conn*
                                         {:category/name "Household" :category/type :expense
                                          :category/ident :category/household}))
          _ (d/transact! setup/*test-conn*
                         [(make-test-transaction "tx-split" "Costco" -100.00 (date-for 2025 1 15))])
          tx-id (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:transaction/external-id "tx-split"]))
          handler (create-test-handler)
          ;; PUT the splits as real JSON (amounts as strings) through all middleware.
          put-resp (handler (json-request :put (str "/api/transactions/" tx-id "/splits")
                                          {:splits [{:amount "-60.00" :categoryId g}
                                                    {:amount "-40.00" :categoryId h}]}))
          put-body (json/read-json (:body put-resp) :key-fn keyword)]
      (is (= 200 (:status put-resp)))
      (is (true? (:success put-body)))
      (is (= 2 (count (get-in put-body [:data :transaction/splits]))))

      ;; The list endpoint now returns the parts via the extended pull pattern.
      (let [list-resp (handler (make-request :get "/api/transactions"))
            tx (->> (:data (json/read-json (:body list-resp) :key-fn keyword))
                    (filter #(= "tx-split" (:transaction/external-id %)))
                    first)
            parts (sort-by :split/order (:transaction/splits tx))]
        (is (= 2 (count parts)))
        ;; Each part's category ref must resolve to a real category. A dangling ref
        ;; (db/id with no category/name) would crash the client's schema parse.
        (is (every? #(get-in % [:split/category :category/name]) parts))
        ;; The list carries the server-computed reconciliation flag.
        (is (true? (:transaction/splits-balanced tx))))))

  (testing "Full HTTP stack: a non-existent split category returns 400"
    (let [g (:db/id (categories/create! setup/*test-conn*
                                         {:category/name "Groceries2" :category/type :expense
                                          :category/ident :category/groceries2}))
          _ (d/transact! setup/*test-conn*
                         [(make-test-transaction "tx-badcat" "Costco" -100.00 (date-for 2025 1 15))])
          tx-id (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:transaction/external-id "tx-badcat"]))
          handler (create-test-handler)
          resp (handler (json-request :put (str "/api/transactions/" tx-id "/splits")
                                      {:splits [{:amount "-60.00" :categoryId g}
                                                {:amount "-40.00" :categoryId 999999}]}))
          body (json/read-json (:body resp) :key-fn keyword)]
      (is (= 400 (:status resp)))
      (is (false? (:success body)))))

  (testing "Full HTTP stack: non-reconciling splits return a 400"
    (let [a (:db/id (categories/create! setup/*test-conn*
                                         {:category/name "A" :category/type :expense
                                          :category/ident :category/a}))
          b (:db/id (categories/create! setup/*test-conn*
                                         {:category/name "B" :category/type :expense
                                          :category/ident :category/b}))
          _ (d/transact! setup/*test-conn*
                         [(make-test-transaction "tx-bad" "Costco" -100.00 (date-for 2025 1 15))])
          tx-id (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:transaction/external-id "tx-bad"]))
          handler (create-test-handler)
          resp (handler (json-request :put (str "/api/transactions/" tx-id "/splits")
                                      {:splits [{:amount "-60.00" :categoryId a}
                                                {:amount "-30.00" :categoryId b}]}))
          body (json/read-json (:body resp) :key-fn keyword)]
      (is (= 400 (:status resp)))
      (is (false? (:success body))))))
