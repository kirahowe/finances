(ns finance-aggregator.plaid.errors
  "Plaid's error-code vocabulary and the transient-vs-terminal classification it
   drives.

   The *codes* are Plaid-specific - this is the one place that knows them. The
   *actions* it yields (:retry / :reconnect / :fail / :resolved) are the generic
   vocabulary the resync core acts on, so the core never names a Plaid code.

   Pure: data in, data out. Plaid's guidance is to branch on error_code, not HTTP
   status - so classification keys off the code string."
  )

(def retryable-error-codes
  "Transient: the institution or Plaid is temporarily unhappy - back off and retry."
  #{"INSTITUTION_DOWN" "INSTITUTION_NOT_RESPONDING" "INSTITUTION_NOT_AVAILABLE"
    "RATE_LIMIT_EXCEEDED" "INTERNAL_SERVER_ERROR" "PLANNED_MAINTENANCE"
    "API_ERROR" "PRODUCT_NOT_READY"})

(def reconnect-error-codes
  "User-action: the connection needs re-auth via Link update mode - never retry."
  #{"ITEM_LOGIN_REQUIRED" "PENDING_EXPIRATION" "PENDING_DISCONNECT"
    "INVALID_CREDENTIALS" "INVALID_MFA" "INVALID_UPDATED_USERNAME"
    "ITEM_LOCKED" "USER_PERMISSION_REVOKED" "USER_SETUP_REQUIRED"
    "ACCESS_NOT_GRANTED" "INSTITUTION_NO_LONGER_SUPPORTED"})

(def reset-error-codes
  "The stored cursor is unusable and must be discarded, not resumed. Plaid
   invalidates a mid-pagination cursor (one returned with has_more=true) once the
   underlying transaction data changes; resuming from it fails permanently with
   the SAME error every pass. The fix is to restart pagination from the loop's
   start cursor - here, discard the cursor and re-sync from scratch. (We no longer
   store mid-pagination cursors, so this only surfaces for a cursor left behind by
   the old per-page-persist behavior.)"
  #{"TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION"})

(defn classify
  "Map a Plaid error-code string to a generic action:
     :retry     - transient, back off and try again
     :reconnect - user must re-auth (Link update mode)
     :reset     - the resumable cursor is corrupt; discard it and re-sync fresh
     :fail      - unknown/other; surface it, don't auto-retry."
  [error-code]
  (cond
    (contains? retryable-error-codes error-code) :retry
    (contains? reconnect-error-codes error-code) :reconnect
    (contains? reset-error-codes error-code)     :reset
    :else :fail))

(defn sync-error
  "Resolve a failed Plaid sync into the generic
   {:action .. :error-code .. :error-message ..} the resync core persists.

   `primary` is the ex-data of the failing call - it carries :error-code when the
   call itself returned a Plaid error (the reliable signal). `item-error` is the
   optional /item/get supplement, consulted only when the call carried no code
   (item-level problems like ITEM_LOGIN_REQUIRED surface there). LOGIN_REPAIRED
   is a self-heal -> :resolved."
  [primary item-error fallback-message]
  (let [error-code    (or (:error-code primary) (:error-code item-error))
        error-message (or (:error-message primary) (:error-message item-error) fallback-message)
        action        (if (= "LOGIN_REPAIRED" error-code)
                        :resolved
                        (classify error-code))]
    {:action action :error-code error-code :error-message error-message}))
