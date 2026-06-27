(ns finance-aggregator.web.pages.setup-test
  "Render smoke + handler tests for /setup. kaocha unit tests pass even when the
   handler/view render path 500s, so these exercise the full fetch -> present ->
   render path end to end against a real temporary db (no network)."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.db.connections :as connections]
   [finance-aggregator.test-utils.setup :as setup]
   [finance-aggregator.web.pages.setup :as setup-page])
  (:import
   [java.util Date]))

(use-fixtures :each setup/with-empty-db)

(deftest page-renders-empty-state
  (let [resp ((setup-page/page {:db-conn setup/*test-conn*}) {})]
    (is (= 200 (:status resp)))
    (is (string? (:body resp)))
    (is (str/includes? (:body resp) "Setup"))
    (is (str/includes? (:body resp) "No connections yet"))))

(deftest page-renders-connection-with-its-accounts
  (let [conn setup/*test-conn*]
    (d/transact! conn [{:user/id "test-user" :user/created-at (Date.)}])
    (connections/ensure-connection! conn {:id "plaid:item_x" :provider :plaid
                                          :institution-name "Test Bank"})
    (connections/record-success! conn "plaid:item_x" :synced)
    (d/transact! conn [{:institution/id "ins_x" :institution/name "Test Bank"}])
    (d/transact! conn [{:account/external-id "acc-1" :account/external-name "Chequing"
                        :account/provider :plaid :account/mask "0001" :account/currency "CAD"
                        :account/institution [:institution/id "ins_x"]
                        :account/connection [:connection/id "plaid:item_x"]
                        :account/user [:user/id "test-user"]}])
    (let [body (:body ((setup-page/page {:db-conn conn}) {}))]
      (is (str/includes? body "Test Bank"))
      (is (str/includes? body "Chequing"))
      (is (str/includes? body "Synced"))
      (is (str/includes? body "Resync"))
      (is (str/includes? body "••••0001")))))

(deftest resync-connection-handler-no-op-guards
  (let [handler (setup-page/resync-connection {:db-conn setup/*test-conn*})]
    (testing "blank connection-id redirects without firing a resync"
      (is (= 303 (:status (handler {:params {"connection-id" ""}})))))
    (testing "unknown connection-id redirects without firing a resync"
      (is (= 303 (:status (handler {:params {"connection-id" "nope"}})))))))
