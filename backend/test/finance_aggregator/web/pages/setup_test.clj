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
   [finance-aggregator.web.pages.setup :as setup-page]
   [finance-aggregator.web.pages.setup-view :as view]
   [finance-aggregator.web.render :as r]
   [starfederation.datastar.clojure.adapter.http-kit :as hk]
   [starfederation.datastar.clojure.adapter.test :as sse-test])
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
            "the old reload-causing form is gone"))
      (testing "the Name cell is an inline-edit cell (web.inline-edit), not a form"
        (is (not (str/includes? body "<form")) "no rename form remains")
        (is (not (str/includes? body "action=\"/setup/account")))
        (is (not (str/includes? body ">Save<")) "no per-row Save button")
        (is (str/includes? body "id=\"account-name-acc-1\"")
            "a stable morph target for the rename SSE patch")
        (is (str/includes? body "account-name-button"))
        (is (str/includes? body "account-name-input"))
        (is (str/includes? body ">Chequing<") "resting text is the account's shown name")
        (is (str/includes? body "value=\"Chequing\"") "the editing input carries the current name")
        (is (str/includes? body "/setup/account/acc-1/name") "commit @put's the rename endpoint")
        (is (not (str/includes? body "account-original-name"))
            "no override yet -> no muted provider-name caption")
        (is (not (str/includes? body "name-saved-check"))
            "the saved check never renders on a full page load"))
      (testing "a Plaid account gets no per-row Sync button (that's Lunchflow-only)"
        (is (not (str/includes? body "/setup/sync-account?external-id=")))))))

(deftest page-renders-lunchflow-account-rename-override-and-sync-button
  (let [conn setup/*test-conn*]
    (d/transact! conn [{:user/id "test-user" :user/created-at (Date.)}])
    (connections/ensure-connection! conn {:id "lunchflow" :provider :lunchflow})
    (d/transact! conn [{:account/external-id "lunchflow-1" :account/external-name "Chequing"
                        :account/display-name "My Everyday Account"
                        :account/provider :lunchflow
                        :account/connection [:connection/id "lunchflow"]
                        :account/user [:user/id "test-user"]}])
    (let [body (:body ((setup-page/page {:db-conn conn}) {}))]
      (testing "the resting text + editing input show the override; the provider's own
                name shows muted alongside so the mapping stays visible"
        (is (str/includes? body ">My Everyday Account<") "resting text is the override, not the provider name")
        (is (str/includes? body "value=\"My Everyday Account\""))
        (is (str/includes? body "account-original-name"))
        (is (str/includes? body "Chequing")))
      (testing "a Lunchflow account gets a per-row Sync button (Datastar @post)"
        (is (str/includes?
             body
             (str "/setup/sync-account?external-id="
                  (java.net.URLEncoder/encode "lunchflow-1" "UTF-8"))))))))

(deftest page-splits-a-multi-institution-connection-into-per-institution-cards
  (let [conn setup/*test-conn*]
    (d/transact! conn [{:user/id "test-user" :user/created-at (Date.)}])
    (connections/ensure-connection! conn {:id "lunchflow" :provider :lunchflow})
    (connections/record-success! conn "lunchflow" :synced)
    (d/transact! conn [{:institution/id "ins_bmo" :institution/name "BMO"}
                       {:institution/id "ins_tng" :institution/name "Tangerine"}])
    (d/transact! conn [{:account/external-id "lf-1" :account/external-name "Chequing"
                        :account/provider :lunchflow
                        :account/institution [:institution/id "ins_bmo"]
                        :account/connection [:connection/id "lunchflow"]
                        :account/user [:user/id "test-user"]}
                       {:account/external-id "lf-2" :account/external-name "Visa"
                        :account/provider :lunchflow
                        :account/institution [:institution/id "ins_tng"]
                        :account/connection [:connection/id "lunchflow"]
                        :account/user [:user/id "test-user"]}])
    (let [body (:body ((setup-page/page {:db-conn conn}) {}))]
      (testing "the one Lunchflow connection renders one card per institution"
        (is (= 2 (count (re-seq #"badge-lunchflow" body))))
        (is (str/includes? body ">BMO<"))
        (is (str/includes? body ">Tangerine<")))
      (testing "each card's Resync is scoped to its own accounts, not the connection"
        (is (str/includes? body "/setup/sync-account?external-id=lf-1"))
        (is (str/includes? body "/setup/sync-account?external-id=lf-2"))
        (is (not (str/includes? body "/setup/resync?connection-id=lunchflow")))))))

;; --- Per-account resync scoping (SSE) ---------------------------------------

(defn- post-sync-account! [conn deps-extra params]
  (with-redefs [hk/->sse-response sse-test/->sse-response]
    ((setup-page/sync-account (merge {:db-conn conn} deps-extra)) {:params params})))

(deftest sync-account-scopes-the-resync-to-connected-lunchflow-accounts
  (let [conn setup/*test-conn*
        captured (atom nil)]
    (d/transact! conn [{:user/id "test-user" :user/created-at (Date.)}])
    (connections/ensure-connection! conn {:id "lunchflow" :provider :lunchflow})
    (d/transact! conn [{:account/external-id "lf-1" :account/external-name "Chequing"
                        :account/provider :lunchflow
                        :account/connection [:connection/id "lunchflow"]
                        :account/user [:user/id "test-user"]}
                       {:account/external-id "lf-2" :account/external-name "Savings"
                        :account/provider :lunchflow
                        :account/connection [:connection/id "lunchflow"]
                        :account/user [:user/id "test-user"]}])
    (with-redefs [resync/resync-connection!
                  (fn [deps target]
                    (reset! captured {:only (get-in deps [:extra-opts :only-account-ids])
                                      :target target})
                    :synced)]
      (testing "repeated external-id params scope the resync; unknown ids are dropped"
        (let [resp (post-sync-account! conn {} {"external-id" ["lf-1" "lf-2" "nope"]})]
          (is (= {:only #{"lf-1" "lf-2"} :target {:connection/id "lunchflow"}} @captured))
          (is (seq @(:body resp)) "patches the connections fragment")))
      (testing "a single id keeps the per-row Sync behavior"
        (post-sync-account! conn {} {"external-id" "lf-1"})
        (is (= #{"lf-1"} (:only @captured))))
      (testing "only-unknown ids patch nothing and never run a resync"
        (reset! captured nil)
        (let [resp (post-sync-account! conn {} {"external-id" "nope"})]
          (is (= [] @(:body resp)))
          (is (nil? @captured)))))))

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

;; --- Inline account rename (SSE) -------------------------------------------
;; setup-page/set-account-name is a Datastar PUT handler (SSE, not a redirect): run it
;; through the SDK's own fake adapter (starfederation.datastar.clojure.adapter.test) so the
;; real @put/SSE-patch path executes against a recording generator instead of a live
;; http-kit channel — no mocking of our own code, just swapping the transport.

(defn- put-account-name! [conn external-id name-value]
  (with-redefs [hk/->sse-response sse-test/->sse-response]
    ((setup-page/set-account-name {:db-conn conn})
     {:path-params {:external-id external-id} :body-params {:nameValue name-value}})))

(defn- display-name-of [conn external-id]
  (:account/display-name (d/pull (d/db conn) '[:account/display-name] [:account/external-id external-id])))

(deftest set-account-name-sets-trims-and-patches-the-cell
  (let [conn setup/*test-conn*]
    (d/transact! conn [{:account/external-id "acc-1" :account/external-name "Chequing"
                        :account/provider :plaid}])
    (let [resp (put-account-name! conn "acc-1" "  My Chequing  ")
          events (apply str @(:body resp))]
      (testing "sets a trimmed override (db.accounts/set-display-name! semantics)"
        (is (= "My Chequing" (display-name-of conn "acc-1"))))
      (testing "SSE-patches just the name cell, with the transient saved check"
        (is (str/includes? events "account-name-acc-1"))
        (is (str/includes? events "My Chequing"))
        (is (str/includes? events "name-saved-check"))))))

(deftest set-account-name-blank-commit-clears-the-override
  (let [conn setup/*test-conn*]
    (d/transact! conn [{:account/external-id "acc-1" :account/external-name "Chequing"
                        :account/display-name "Old Name" :account/provider :plaid}])
    (put-account-name! conn "acc-1" "")
    (is (nil? (display-name-of conn "acc-1")) "blank retracts the override")))

(deftest set-account-name-unknown-external-id-patches-nothing
  (let [resp (put-account-name! setup/*test-conn* "does-not-exist" "X")]
    (is (= [] @(:body resp)))))

;; --- Statement polarity toggle (SSE) ----------------------------------------
;; setup-page/set-account-polarity mirrors set-account-name end to end (same fake-adapter
;; seam above).

(defn- put-account-polarity! [conn external-id polarity-value]
  (with-redefs [hk/->sse-response sse-test/->sse-response]
    ((setup-page/set-account-polarity {:db-conn conn})
     {:path-params {:external-id external-id} :body-params {:polarityValue polarity-value}})))

(defn- polarity-of [conn external-id]
  (:account/statement-polarity (d/pull (d/db conn) '[:account/statement-polarity]
                                       [:account/external-id external-id])))

(deftest set-account-polarity-sets-and-patches-the-cell
  (let [conn setup/*test-conn*]
    (d/transact! conn [{:account/external-id "acc-1" :account/external-name "Visa"
                        :account/type :credit :account/provider :plaid}])
    (let [resp (put-account-polarity! conn "acc-1" "as-signed")
          events (apply str @(:body resp))]
      (testing "sets the explicit override (beating the :credit default of :inverted)"
        (is (= :as-signed (polarity-of conn "acc-1"))))
      (testing "SSE-patches just the polarity cell, with the transient saved check"
        (is (str/includes? events "account-polarity-acc-1"))
        (is (str/includes? events "name-saved-check"))))))

(deftest set-account-polarity-ignores-a-malformed-value
  (let [conn setup/*test-conn*]
    (d/transact! conn [{:account/external-id "acc-1" :account/external-name "Visa"
                        :account/provider :plaid}])
    (put-account-polarity! conn "acc-1" "sideways")
    (is (nil? (polarity-of conn "acc-1")) "an unknown value never writes")))

(deftest set-account-polarity-unknown-external-id-patches-nothing
  (let [resp (put-account-polarity! setup/*test-conn* "does-not-exist" "inverted")]
    (is (= [] @(:body resp)))))

;; --- setup-view/account-name-cell (pure — the new cell's own render checks) -----------

(def ^:private plain-account
  {:external-id "acc-1" :external-name "Chequing" :display-name ""
   :name "Chequing" :name-url "/setup/account/acc-1/name"})

(def ^:private overridden-account
  (assoc plain-account :display-name "My Chequing" :name "My Chequing"))

(deftest account-name-cell-resting-state-has-no-form-or-save
  (let [h (str (r/render (view/account-name-cell plain-account)))]
    (is (not (str/includes? h "<form")))
    (is (not (str/includes? h ">Save<")))
    (is (str/includes? h "account-name-button"))
    (is (str/includes? h "account-name-input"))
    (is (str/includes? h ">Chequing<"))
    (is (not (str/includes? h "account-original-name")) "no override -> no muted caption")
    (is (not (str/includes? h "name-saved-check")) "saved? defaults to false")))

(deftest account-name-cell-override-shows-muted-provider-name
  (let [h (str (r/render (view/account-name-cell overridden-account)))]
    (is (str/includes? h ">My Chequing<") "resting text is the override")
    (is (str/includes? h "account-original-name"))
    (is (str/includes? h "Provider name: Chequing"))))

(deftest account-name-cell-saved-flag-renders-check-only-when-set
  (is (str/includes? (str (r/render (view/account-name-cell plain-account :saved? true)))
                      "name-saved-check"))
  (is (not (str/includes? (str (r/render (view/account-name-cell plain-account :saved? false)))
                           "name-saved-check"))))

;; --- setup-view/account-polarity-cell (pure — the Statements toggle's own render checks) ---

(def ^:private as-signed-account
  {:external-id "acc-1" :polarity :as-signed :polarity-url "/setup/account/acc-1/statement-polarity"})

(def ^:private inverted-account
  (assoc as-signed-account :polarity :inverted))

(deftest account-polarity-cell-shows-the-effective-value-selected
  (let [h (str (r/render (view/account-polarity-cell as-signed-account)))]
    (is (str/includes? h "account-polarity-select"))
    (is (str/includes? h "<option selected=\"selected\" value=\"as-signed\">As signed</option>"))
    (is (str/includes? h "<option value=\"inverted\">Inverted</option>")
        "the other option renders with no selected attr")
    (is (not (str/includes? h "name-saved-check")) "saved? defaults to false")))

(deftest account-polarity-cell-inverted-selects-the-other-option
  (let [h (str (r/render (view/account-polarity-cell inverted-account)))]
    (is (str/includes? h "<option selected=\"selected\" value=\"inverted\">Inverted</option>"))
    (is (str/includes? h "<option value=\"as-signed\">As signed</option>"))))

(deftest account-polarity-cell-change-commits-the-courier-and-puts
  (let [h (str (r/render (view/account-polarity-cell as-signed-account)))]
    (is (str/includes? h "$polarityValue = el.value")
        "the change handler sets the courier before @put — no shared data-bind across rows")
    (is (str/includes? h "@put(&apos;/setup/account/acc-1/statement-polarity&apos;)"))))

(deftest account-polarity-cell-saved-flag-renders-check-only-when-set
  (is (str/includes? (str (r/render (view/account-polarity-cell as-signed-account :saved? true)))
                      "name-saved-check"))
  (is (not (str/includes? (str (r/render (view/account-polarity-cell as-signed-account :saved? false)))
                           "name-saved-check"))))

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
