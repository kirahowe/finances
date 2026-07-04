(ns finance-aggregator.db.users
  "User entity helpers. Single-user for now (auth/user-id), but the one place any
   user-scoped write ensures the user exists — so the eventual multi-user migration has
   a single seam."
  (:require
   [datalevin.core :as d])
  (:import
   [java.util Date]))

(defn ensure-user!
  "Create the user entity for `user-id` if absent. A `:*/user` lookup ref used as a ref
   VALUE (e.g. `:transaction/user`, `:connection/user`, `:reconciliation/user`) must
   resolve to an existing entity — datalevin does not upsert it the way it would a
   top-level unique-id map. New users get a `:user/created-at`."
  [conn user-id]
  (when-not (d/entity (d/db conn) [:user/id user-id])
    (d/transact! conn [{:user/id user-id :user/created-at (Date.)}])))
