(ns finance-aggregator.db.accounts
  "Account read queries shared by the JSON API and the server-rendered pages —
   one service layer, two presentations."
  (:require
   [datalevin.core :as d]))

(def account-pull-pattern
  "Pull an account with its institution name — the shape both the JSON API and
   the hypermedia setup page render."
  '[* {:account/institution [:db/id :institution/name]}])

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
