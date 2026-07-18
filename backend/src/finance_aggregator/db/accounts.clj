(ns finance-aggregator.db.accounts
  "Account read queries shared by the JSON API and the server-rendered pages —
   one service layer, two presentations."
  (:require
   [clojure.string :as str]
   [datalevin.core :as d]))

(def account-pull-pattern
  "Pull an account with its institution name/logo and the id of the connection that
   syncs it — the shape both the JSON API and the hypermedia setup page render."
  '[* {:account/institution [:db/id :institution/name :institution/logo]
       :account/connection [:connection/id]}])

(defn list-with-institution
  "All accounts (with institution info) as pulled maps."
  [db-conn]
  (d/q '[:find [(pull ?e pattern) ...]
         :in $ pattern
         :where [?e :account/external-id _]]
       (d/db db-conn) account-pull-pattern))

(defn by-external-id
  "One account (same pull shape as list-with-institution) by its :account/external-id, or
   nil when it doesn't resolve to an account. Used to re-render a single row after a write
   (the /setup rename SSE patch) without refetching the whole account list."
  [db-conn external-id]
  (d/pull (d/db db-conn) account-pull-pattern [:account/external-id external-id]))

(defn inverted-account-ids
  "Set of account external-ids whose :account/invert-amount is true - the
   accounts whose canonical transaction sign is flipped once at import (see
   provider.normalize)."
  [db-conn]
  (set (d/q '[:find [?ext ...]
              :where
              [?a :account/invert-amount true]
              [?a :account/external-id ?ext]]
            (d/db db-conn))))

(defn set-display-name!
  "Set (or clear) an account's :account/display-name overlay — a user-authored rename
   over the provider's canonical :account/external-name, which is never mutated. The
   value is trimmed; a blank/whitespace-only/nil name — or one EQUAL to the provider's
   own name — retracts the override, falling back to the provider name. The equality
   case matters because the inline rename cell edits the SHOWN label: opening an
   un-renamed cell and clicking away commits the provider name verbatim, and storing
   that would be a pointless override that drags the muted provider-name caption out
   under an unchanged label. Looked up by :account/external-id (what the rename cell's
   @put names in its path); a no-op when the id doesn't resolve to an account."
  [db-conn external-id display-name]
  (let [{eid :db/id ext-name :account/external-name}
        (d/pull (d/db db-conn) '[:db/id :account/external-name] [:account/external-id external-id])]
    (when eid
      (let [trimmed (some-> display-name str/trim not-empty)]
        (d/transact! db-conn (if (and trimmed (not= trimmed ext-name))
                               [{:db/id eid :account/display-name trimmed}]
                               [[:db/retract eid :account/display-name]]))))))

(defn set-statement-polarity!
  "Set an account's EXPLICIT :account/statement-polarity override — the /setup accounts-table's
   per-account Statements toggle (web.pages.setup/set-account-polarity). Unlike
   set-display-name!, there's no 'blank clears the override' state: the control is a fixed
   two-option toggle (:as-signed / :inverted), so any change always writes an explicit value
   (see data.ledger/effective-statement-polarity for the account-type default this overrides).
   Anything other than those two keywords is ignored (defensive against a malformed courier
   value). Looked up by :account/external-id; a no-op when the id doesn't resolve to an
   account."
  [db-conn external-id polarity]
  (when (contains? #{:as-signed :inverted} polarity)
    (let [eid (:db/id (d/pull (d/db db-conn) '[:db/id] [:account/external-id external-id]))]
      (when eid
        (d/transact! db-conn [{:db/id eid :account/statement-polarity polarity}])))))

(defn external-ids-for-provider
  "Set of :account/external-id for accounts of `provider` already imported - the
   'remembered selection' for selectable providers (the setup link UI pre-checks
   these; the provider's fetch-accounts re-syncs them)."
  [db-conn provider]
  (set (d/q '[:find [?ext ...]
              :in $ ?prov
              :where
              [?a :account/provider ?prov]
              [?a :account/external-id ?ext]]
            (d/db db-conn) provider)))
