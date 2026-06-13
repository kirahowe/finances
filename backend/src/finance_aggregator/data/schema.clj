(ns finance-aggregator.data.schema
  "Enhanced Datalevin schema with user scoping for multi-user support.
   Each entity that needs user isolation includes a :user reference.")

(def schema
  {;; Users (for multi-user support)
   :user/id         {:db/unique :db.unique/identity}
   :user/email      {:db/valueType :db.type/string}
   :user/created-at {:db/valueType :db.type/instant}

   ;; Institutions
   :institution/id     {:db/unique :db.unique/identity}
   :institution/name   {:db/valueType :db.type/string}
   :institution/domain {:db/valueType :db.type/string}
   :institution/url    {:db/valueType :db.type/string}
   :institution/logo   {:db/valueType :db.type/string}   ; logo image URL (e.g. Lunchflow/Quiltt CDN)

   ;; Accounts
   :account/external-id   {:db/unique :db.unique/identity}  ; from e.g. SimpleFIN
   :account/external-name {:db/valueType :db.type/string}
   :account/institution   {:db/valueType :db.type/ref}      ; ref to institution entity
   :account/currency      {:db/valueType :db.type/string}
   :account/type          {:db/valueType :db.type/keyword}  ; :chequing, :credit, :savings, etc.
   :account/provider-type {:db/valueType :db.type/string}  ; provider-native account type (e.g. Plaid depository/credit, Lunchflow upstream connector)
   :account/provider-subtype {:db/valueType :db.type/string}  ; provider-native subtype (e.g. Plaid checking/savings)
   :account/mask          {:db/valueType :db.type/string}  ; Last 4 digits of account number
   :account/provider      {:db/valueType :db.type/keyword}  ; :plaid, :lunchflow, :manual, ...
   :account/csv-mapping   {:db/valueType :db.type/string}  ; EDN-encoded CSV column mapping (manual only)
   :account/invert-amount {:db/valueType :db.type/boolean}  ; Flip amount signs (applies to all accounts)
   :account/user          {:db/valueType :db.type/ref}      ; ref to user (for data isolation)

   ;; Transactions
   :transaction/external-id   {:db/unique :db.unique/identity}
   :transaction/account       {:db/valueType :db.type/ref}  ; ref to account
   :transaction/date          {:db/valueType :db.type/instant}
   :transaction/posted-date   {:db/valueType :db.type/instant}
   :transaction/amount        {:db/valueType :db.type/bigdec}
   :transaction/payee         {:db/valueType :db.type/string}
   :transaction/description   {:db/valueType :db.type/string}
   :transaction/memo          {:db/valueType :db.type/string}
   :transaction/category      {:db/valueType :db.type/ref}     ; ref to category
   :transaction/provider      {:db/valueType :db.type/keyword}  ; :plaid, :lunchflow, :manual, ... (provenance)
   :transaction/tags          {:db/valueType   :db.type/keyword
                               :db/cardinality :db.cardinality/many}  ; #{:income :transfer}
   :transaction/transfer-pair {:db/valueType :db.type/ref}   ; links paired transfers (set on both legs)
   :transaction/transfer-rejected {:db/valueType   :db.type/ref
                                   :db/cardinality :db.cardinality/many}  ; pairs the user rejected; written symmetrically so auto-match won't re-propose
   :transaction/user          {:db/valueType :db.type/ref}   ; ref to user (denormalized for query speed)
   :transaction/reviewed      {:db/valueType :db.type/boolean}  ; user-authored overlay; absent = not reviewed (nil-punned)
   :transaction/splits        {:db/valueType   :db.type/ref
                               :db/cardinality :db.cardinality/many
                               :db/isComponent true}  ; user-authored parts; parent owns them, cascades on retractEntity

   ;; Transaction split parts (sub-entities of a transaction; never imported/deduped)
   :split/amount   {:db/valueType :db.type/bigdec}   ; signed, same convention as :transaction/amount
   :split/category {:db/valueType :db.type/ref}
   :split/memo     {:db/valueType :db.type/string}   ; optional
   :split/order    {:db/valueType :db.type/long}     ; stable display order
   :split/reviewed {:db/valueType :db.type/boolean}  ; reviewed independently of the parent and siblings

   ;; Categories (user-defined)
   :category/name       {:db/valueType :db.type/string}
   :category/parent     {:db/valueType :db.type/ref}     ; hierarchical categories
   :category/type       {:db/valueType :db.type/keyword} ; :expense, :income, :transfer
   :category/ident      {:db/unique :db.unique/identity} ; like :category/groceries
   :category/sort-order {:db/valueType :db.type/long}    ; for custom ordering
   :category/user       {:db/valueType :db.type/ref}     ; ref to user (nil = system category)

   ;; Payee patterns for auto-categorization
   :payee-rule/pattern  {:db/valueType :db.type/string}
   :payee-rule/category {:db/valueType :db.type/ref}
   :payee-rule/user     {:db/valueType :db.type/ref}     ; ref to user

   ;; Balance snapshots (for reconciliation)
   :snapshot/account {:db/valueType :db.type/ref}
   :snapshot/date    {:db/valueType :db.type/instant}
   :snapshot/balance {:db/valueType :db.type/bigdec}
   :snapshot/source  {:db/valueType :db.type/keyword}    ; e.g. :simplefin, :manual, :calculated

   ;; Credentials (encrypted storage for API access tokens)
   :credential/id                  {:db/unique :db.unique/identity}
   :credential/user                {:db/valueType :db.type/ref}
   :credential/institution         {:db/valueType :db.type/keyword}  ; e.g. :plaid, :simplefin
   :credential/item-id             {:db/valueType :db.type/string}   ; Plaid item_id (unique per bank connection)
   :credential/institution-name    {:db/valueType :db.type/string}   ; Human-readable institution name
   :credential/encrypted-data      {:db/valueType :db.type/string}   ; AES-256-GCM encrypted
   :credential/created-at          {:db/valueType :db.type/instant}
   :credential/last-used           {:db/valueType :db.type/instant}
   :credential/sync-cursor         {:db/valueType :db.type/string}   ; Plaid /transactions/sync cursor
   :credential/selected-account-ids {:db/valueType :db.type/string}  ; JSON-encoded vector of Plaid account IDs
   :credential/sync-status         {:db/valueType :db.type/keyword}  ; :pending, :syncing, :synced, :failed
   :credential/last-sync-at        {:db/valueType :db.type/instant}  ; When last successful sync completed
   :credential/transaction-count   {:db/valueType :db.type/long}     ; Number of transactions synced
   })
