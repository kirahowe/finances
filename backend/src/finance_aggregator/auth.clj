(ns finance-aggregator.auth
  "Identity seam for the (currently single-user) application.

   Every operation runs as one hardcoded user (see the single-user-first
   design decision). Centralising the id here gives the eventual multi-user
   migration a single place to change, instead of a constant copy-pasted
   across providers, services, db, and handlers.")

(def user-id
  "Id of the current user. Single-user for now; multi-user will replace this
   constant with a request-scoped lookup."
  "test-user")
