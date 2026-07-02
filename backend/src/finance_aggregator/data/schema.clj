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
   :account/connection    {:db/valueType :db.type/ref}      ; the :connection/* that syncs this account (stamped at sync time); the unit of resync/last-synced
   :account/csv-mapping   {:db/valueType :db.type/string}  ; EDN-encoded CSV column mapping (manual only)
   :account/invert-amount {:db/valueType :db.type/boolean}  ; Flip amount signs (applies to all accounts)
   :account/reported-balance  {:db/valueType :db.type/bigdec}   ; institution-reported current balance (latest sync)
   :account/available-balance {:db/valueType :db.type/bigdec}   ; institution-reported available balance (nil when not provided)
   :account/balance-as-of     {:db/valueType :db.type/instant}  ; when the reported balance was captured
   :account/user          {:db/valueType :db.type/ref}      ; ref to user (for data isolation)

   ;; Transactions
   :transaction/external-id   {:db/unique :db.unique/identity}
   :transaction/account       {:db/valueType :db.type/ref}  ; ref to account
   :transaction/date          {:db/valueType :db.type/instant}
   :transaction/posted-date   {:db/valueType :db.type/instant}
   :transaction/amount        {:db/valueType :db.type/bigdec}
   :transaction/payee         {:db/valueType :db.type/string}
   :transaction/description   {:db/valueType :db.type/string}
   :transaction/user-description {:db/valueType :db.type/string}  ; user-authored overlay over the imported description; absent = no override
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

   ;; Balance snapshots (reported-balance history for reconciliation).
   ;; One row per account per UTC day; :snapshot/id makes the daily write idempotent.
   :snapshot/id      {:db/unique :db.unique/identity}    ; "<account-external-id>:<yyyy-MM-dd>"
   :snapshot/account {:db/valueType :db.type/ref}
   :snapshot/date    {:db/valueType :db.type/instant}
   :snapshot/balance {:db/valueType :db.type/bigdec}
   :snapshot/source  {:db/valueType :db.type/keyword}    ; :reported, :manual, :calculated

   ;; Monthly close (reconciliation lock). One event per user per calendar month:
   ;; the "lock it in" record created when a month is closed. Being CLOSED is a
   ;; month-level fact - whether a transaction is reconciled is DERIVED from its
   ;; month having a close event, so a transaction later imported/edited into a
   ;; closed month surfaces as drift (frozen totals no longer match) and reopening
   ;; is just retracting this one entity. The totals are frozen at close so the
   ;; higher-level ongoing tracking reads immutable figures.
   :reconciliation/id        {:db/unique :db.unique/identity}  ; "<user-id>:<yyyy-MM>"
   :reconciliation/user      {:db/valueType :db.type/ref}
   :reconciliation/month     {:db/valueType :db.type/string}   ; "yyyy-MM"
   :reconciliation/closed-at {:db/valueType :db.type/instant}
   :reconciliation/income    {:db/valueType :db.type/bigdec}   ; frozen month totals (category-rollup)
   :reconciliation/expenses  {:db/valueType :db.type/bigdec}
   :reconciliation/transfers {:db/valueType :db.type/bigdec}
   :reconciliation/net       {:db/valueType :db.type/bigdec}   ; signed grand-total

   ;; Credentials (encrypted storage for API access tokens)
   :credential/id                  {:db/unique :db.unique/identity}
   :credential/user                {:db/valueType :db.type/ref}
   :credential/institution         {:db/valueType :db.type/keyword}  ; e.g. :plaid, :lunchflow
   :credential/item-id             {:db/valueType :db.type/string}   ; Plaid item_id (unique per bank connection)
   :credential/institution-name    {:db/valueType :db.type/string}   ; Human-readable institution name
   :credential/encrypted-data      {:db/valueType :db.type/string}   ; AES-256-GCM encrypted
   :credential/created-at          {:db/valueType :db.type/instant}
   :credential/last-used           {:db/valueType :db.type/instant}
   :credential/selected-account-ids {:db/valueType :db.type/string}  ; JSON-encoded vector of Plaid account IDs
   ;; LEGACY sync fields - sync state now lives on :connection/* (below). These
   ;; are read once by db.connections/ensure-from-credential! to seed a
   ;; connection's cursor/status on first migration, then never written again.
   ;; Don't wire new logic onto them.
   :credential/sync-cursor         {:db/valueType :db.type/string}   ; legacy: Plaid /transactions/sync cursor
   :credential/sync-status         {:db/valueType :db.type/keyword}  ; legacy: :pending, :syncing, :synced, :failed
   :credential/last-sync-at        {:db/valueType :db.type/instant}  ; legacy: when last successful sync completed
   :credential/transaction-count   {:db/valueType :db.type/long}     ; legacy: number of transactions synced

   ;; Connections (provider-agnostic sync-state for a linked institution).
   ;; One connection = one linked provider instance whose transactions we sync
   ;; (a Plaid Item, a Lunchflow link, a future Open-Banking grant). Holds only
   ;; sync bookkeeping; the encrypted token lives on :credential/*. The
   ;; sync-state is opaque - only the provider interprets it (Plaid's cursor).
   :connection/id                {:db/unique :db.unique/identity}    ; e.g. "plaid:<item-id>", "lunchflow"
   :connection/user              {:db/valueType :db.type/ref}
   :connection/provider          {:db/valueType :db.type/keyword}    ; :plaid, :lunchflow, ...
   :connection/external-id       {:db/valueType :db.type/string}     ; provider's connection id (Plaid item_id); absent for single-connection providers
   :connection/institution-name  {:db/valueType :db.type/string}
   :connection/sync-state        {:db/valueType :db.type/string}     ; opaque per-provider (Plaid cursor); provider interprets
   :connection/status            {:db/valueType :db.type/keyword}    ; :pending :syncing :backfilling :synced :stale :needs-reconnect :failed
   :connection/last-attempt-at   {:db/valueType :db.type/instant}    ; start of the most recent sync attempt
   :connection/last-success-at   {:db/valueType :db.type/instant}    ; completion of the most recent successful sync
   :connection/error-code        {:db/valueType :db.type/string}     ; provider error code (e.g. ITEM_LOGIN_REQUIRED)
   :connection/error-message     {:db/valueType :db.type/string}
   :connection/retry-count       {:db/valueType :db.type/long}       ; consecutive transient failures (backoff)
   :connection/first-failure-at  {:db/valueType :db.type/instant}    ; start of the current failure streak (bounds the elapsed-time ceiling)
   :connection/next-retry-at     {:db/valueType :db.type/instant}    ; earliest next attempt while backing off
   :connection/transaction-count {:db/valueType :db.type/long}
   :connection/created-at        {:db/valueType :db.type/instant}
   })
