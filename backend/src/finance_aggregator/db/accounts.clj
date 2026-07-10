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
   value is trimmed; a blank/whitespace-only/nil name retracts the override, falling
   back to the provider name. Looked up by :account/external-id (what the setup rename
   form posts); a no-op when the id doesn't resolve to an account."
  [db-conn external-id display-name]
  (when-let [eid (:db/id (d/pull (d/db db-conn) '[:db/id] [:account/external-id external-id]))]
    (let [trimmed (some-> display-name str/trim not-empty)]
      (d/transact! db-conn (if trimmed
                             [{:db/id eid :account/display-name trimmed}]
                             [[:db/retract eid :account/display-name]])))))

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
