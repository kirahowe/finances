# Plaid Account & Transaction Sync - Manual Testing Guide

This guide explains how to manually test the Plaid account and transaction syncing functionality with real Plaid sandbox credentials.

## Prerequisites

1. **Plaid credentials configured** in `resources/secrets.edn.age`
2. **Access token stored** in database (complete OAuth flow via `/api/plaid/exchange-token`)
3. **REPL running**: `clojure -M:repl -m nrepl.cmdline`

## Testing Flow

### Step 1: Connect to REPL and Load System

```clojure
;; Connect to REPL (port shown in startup output)

;; Load namespaces
(require '[finance-aggregator.plaid.service :as plaid-svc])
(require '[finance-aggregator.db.core :as db])
(require '[finance-aggregator.lib.secrets :as secrets])
(require '[datalevin.core :as d])

;; Load system (if not already running)
(require '[integrant.repl :as ig-repl])
(require '[integrant.repl.state :as state])
(ig-repl/go)
```

### Step 2: Set Up Dependencies

```clojure
;; Create deps map with system components
(def deps
  {:db-conn (:conn (:finance-aggregator.db/connection state/system))
   :secrets (:finance-aggregator.system/secrets state/system)
   :plaid-config (:finance-aggregator.plaid/config state/system)})

;; Verify you have a Plaid credential stored
(require '[finance-aggregator.db.credentials :as creds])
(creds/credential-exists? (:db-conn deps) :plaid)
;; => Should return true
```

### Step 3: Test Account Sync

```clojure
;; Sync accounts and institution
(def account-result (plaid-svc/sync-accounts! deps))

;; Inspect results
account-result
;; => {:success {:institutions 1, :accounts N}
;;     :failed {:institutions 0, :accounts 0}
;;     :errors []}

;; Verify in database
(let [db (d/db (:db-conn deps))]
  {:institutions (d/q '[:find (count ?e) . :where [?e :institution/id]] db)
   :accounts (d/q '[:find (count ?e) . :where [?e :account/external-id]] db)})
;; => {:institutions 1, :accounts N}

;; View account details
(let [db (d/db (:db-conn deps))]
  (d/q '[:find [(pull ?e [*]) ...]
         :where [?e :account/external-id]]
       db))
;; => List of account entities with all fields
```

### Step 4: Test Transaction Sync

```clojure
;; Sync 6 months of transactions (default)
(def tx-result (plaid-svc/sync-transactions! deps))

;; Inspect results
tx-result
;; => {:success {:transactions N}
;;     :failed {:transactions 0}
;;     :errors []}

;; Sync custom date range (3 months)
(def tx-result-3mo (plaid-svc/sync-transactions! deps {:months 3}))

;; Sync with specific end date
(def tx-result-custom (plaid-svc/sync-transactions!
                        deps
                        {:months 6 :end-date "2024-12-31"}))

;; Verify in database
(let [db (d/db (:db-conn deps))]
  (d/q '[:find (count ?e) . :where [?e :transaction/external-id]] db))
;; => Number of transactions

;; View sample transactions
(let [db (d/db (:db-conn deps))]
  (d/q '[:find [(pull ?e [:transaction/external-id
                           :transaction/date
                           :transaction/amount
                           :transaction/description
                           :transaction/payee]) ...]
         :where [?e :transaction/external-id]
         :limit 5]
       db))
;; => List of 5 transactions with details
```

### Step 5: Test Full Sync

```clojure
;; Sync both accounts and transactions
(def full-result (plaid-svc/sync-all! deps))

;; Inspect results
full-result
;; => {:accounts {...}
;;     :transactions {...}}

;; Verify no duplicates after re-running sync
(def full-result-2 (plaid-svc/sync-all! deps))

;; Count should be same (upsert behavior)
(let [db (d/db (:db-conn deps))]
  {:accounts (d/q '[:find (count ?e) . :where [?e :account/external-id]] db)
   :transactions (d/q '[:find (count ?e) . :where [?e :transaction/external-id]] db)})
```

### Step 6: Test Error Handling

```clojure
;; Test with no credential (should fail gracefully)
(require '[finance-aggregator.db.credentials :as creds])
(creds/delete-credential! (:db-conn deps) :plaid)

(def no-cred-result (plaid-svc/sync-accounts! deps))
no-cred-result
;; => {:success {:institutions 0, :accounts 0}
;;     :failed {:institutions 1, :accounts 0}
;;     :errors [{:type :no-credential, :message "..."}]}

;; Restore credential for further testing
;; (re-run OAuth flow via frontend)
```

## Verification Checklist

After running the tests above, verify:

- [ ] Institution is created with correct name and URL
- [ ] Accounts have correct Plaid types (depository, credit, etc.)
- [ ] Account currency defaults to USD when not provided
- [ ] Account masks are stored (last 4 digits)
- [ ] Transactions have correct amounts (as BigDecimal)
- [ ] Transactions have correct dates (as java.util.Date)
- [ ] Pending transactions are NOT imported
- [ ] Transaction payee uses merchant_name when available, falls back to name
- [ ] Running sync twice doesn't create duplicates (upsert behavior)
- [ ] Lookup refs work correctly (accounts reference institution, transactions reference account)
- [ ] All entities reference the correct user (test-user)

## Troubleshooting

### No Access Token

If `creds/credential-exists?` returns false:

1. Complete OAuth flow via frontend at `/plaid-test`
2. Or use curl to exchange a public token:
   ```bash
   curl -X POST http://localhost:8080/api/plaid/exchange-token \
     -H "Content-Type: application/json" \
     -d '{"publicToken": "public-sandbox-xxx"}'
   ```

### Empty Results

If sync returns 0 accounts/transactions:

1. Check Plaid credentials are valid (not expired)
2. Verify sandbox account has transactions (may need to generate test data)
3. Check logs for API errors: `tail -f logs/app.log`

### Database Errors

If you see Datalevin errors:

1. Verify database path exists and is writable
2. Check schema is loaded: `(keys finance-aggregator.data.schema/schema)`
3. Try restarting the system: `(ig-repl/reset)`

## Performance Notes

- Account sync: ~2-3 seconds (parallel fetching of item + institution + accounts)
- Transaction sync (6 months): ~3-5 seconds depending on transaction count
- Parallel transformation uses `pmap` for accounts and transactions
- Database upserts are atomic per entity type (institutions, accounts, transactions)

## Next Steps

After manual testing is complete:

1. Update integration test with real sandbox data
2. Add monitoring/alerting for sync failures
3. Consider adding scheduled sync via cron or similar
4. Add webhook support for real-time transaction updates
