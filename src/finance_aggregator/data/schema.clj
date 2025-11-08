(ns finance-aggregator.data.schema)

(def schema
  {;; Institutions
   :institution/id     {:db/unique :db.unique/identity}
   :institution/name   {:db/valueType :db.type/string}
   :institution/domain {:db/valueType :db.type/string}
   :institution/url    {:db/valueType :db.type/string}

   ;; Accounts
   :account/external-id   {:db/unique :db.unique/identity}  ; from e.g. SimpleFIN
   :account/external-name {:db/valueType :db.type/string}
   :account/institution   {:db/valueType :db.type/ref}      ; ref to institution entity
   :account/currency      {:db/valueType :db.type/string}
   :account/type          {:db/valueType :db.type/keyword}  ; :chequing, :credit, :savings, etc.

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
   :transaction/tags          {:db/valueType   :db.type/keyword
                               :db/cardinality :db.cardinality/many}  ; #{:income :transfer}
   :transaction/transfer-pair {:db/valueType :db.type/ref}   ; links paired transfers

   ;; Categories (user-defined)
   :category/name   {:db/valueType :db.type/string}
   :category/parent {:db/valueType :db.type/ref}     ; hierarchical categories
   :category/type   {:db/valueType :db.type/keyword} ; :expense, :income
   :category/ident  {:db/unique :db.unique/identity} ; like :category/groceries

   ;; Payee patterns for auto-categorization
   :payee-rule/pattern  {:db/valueType :db.type/string}
   :payee-rule/category {:db/valueType :db.type/ref}

   ;; Balance snapshots (for reconciliation)
   :snapshot/account {:db/valueType :db.type/ref}
   :snapshot/date    {:db/valueType :db.type/instant}
   :snapshot/balance {:db/valueType :db.type/bigdec}
   :snapshot/source  {:db/valueType :db.type/keyword}  ; e.g. :simplefin, :manual, :calculated
   })
