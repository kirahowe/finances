(ns finance-aggregator.web.pages.setup-test
  "Render smoke + handler tests for /setup. kaocha unit tests pass even when the
   handler/view render path 500s, so these exercise the full fetch -> present ->
   render path end to end against a real temporary db (no network)."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.db.connections :as connections]
   [finance-aggregator.db.credentials :as creds]
   [finance-aggregator.plaid.client :as plaid-client]
   [finance-aggregator.provider :as provider]
   [finance-aggregator.resync :as resync]
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
      (is (str/includes? body "••••0001"))
      (testing "the connections list is the patchable #connections fragment"
        (is (str/includes? body "id=\"connections\"")))
      (testing "Resync is a Datastar @post action (no full-page form), not a reload"
        (is (str/includes? body "data-on:click"))
        (is (str/includes? body "/setup/resync?connection-id="))
        (is (not (str/includes? body "action=\"/setup/resync\""))
            "the old reload-causing form is gone")))))

(deftest lunchflow-page-renders-selection
  (with-redefs [provider/available-accounts
                (fn [_ _] [{:external-id "lunchflow-1" :name "Chequing"
                            :institution-name "Tangerine"}])]
    (let [body (:body ((setup-page/lunchflow-page {:db-conn setup/*test-conn* :secrets {}}) {}))]
      (is (str/includes? body "Connect Lunchflow"))
      (is (str/includes? body "Tangerine"))
      (is (str/includes? body "Chequing")))))

(deftest lunchflow-page-renders-error-on-failure
  (with-redefs [provider/available-accounts (fn [_ _] (throw (ex-info "no key" {})))]
    (let [body (:body ((setup-page/lunchflow-page {:db-conn setup/*test-conn* :secrets {}}) {}))]
      ;; hiccup HTML-escapes the apostrophe (Couldn&apos;t), so match past it.
      (is (str/includes? body "load Lunchflow accounts: no key")))))

(deftest lunchflow-connect-no-op-without-selection
  (testing "no checkboxes selected -> redirect, no connection created, no future"
    (let [resp ((setup-page/lunchflow-connect {:db-conn setup/*test-conn* :secrets {}})
                {:params {}})]
      (is (= 303 (:status resp)))
      (is (nil? (connections/get-connection setup/*test-conn* "lunchflow"))))))

(deftest plaid-link-token-returns-json-token
  (with-redefs [plaid-client/create-link-token (fn [_ _] "link-tok-123")]
    (let [resp ((setup-page/plaid-link-token {:plaid-config {}}) {})]
      (is (= 200 (:status resp)))
      (is (= {:link_token "link-tok-123"} (:body resp))))))

(deftest plaid-exchange-stores-credential-and-creates-connection
  (let [conn setup/*test-conn*
        stored (atom nil)]
    (with-redefs [plaid-client/exchange-public-token
                  (fn [_ _] {:access_token "access-xyz" :item_id "item_z"})
                  creds/store-plaid-item-credential!
                  (fn [_ _ token item-id inst-name selected]
                    (reset! stored {:token token :item-id item-id
                                    :inst-name inst-name :selected selected})
                    nil)
                  ;; The initial sync runs in a background future; stub it out so the
                  ;; test makes no network call.
                  resync/resync-connection! (fn [_ _] :synced)]
      (let [resp ((setup-page/plaid-exchange {:db-conn conn :secrets {} :plaid-config {}})
                  {:body-params {:public_token "pub-tok"
                                 :institution {:name "Chase" :institution_id "ins_x"}
                                 :accounts [{:id "a1"} {:id "a2"}]}})]
        (testing "responds ok with the new connection id"
          (is (= 200 (:status resp)))
          (is (= {:ok true :connection "plaid:item_z"} (:body resp))))
        (testing "stored the exchanged token + selected accounts"
          (is (= {:token "access-xyz" :item-id "item_z"
                  :inst-name "Chase" :selected ["a1" "a2"]} @stored)))
        (testing "created the Plaid connection"
          (let [c (connections/get-connection conn "plaid:item_z")]
            (is (= :plaid (:connection/provider c)))
            (is (= "Chase" (:connection/institution-name c)))))))))
