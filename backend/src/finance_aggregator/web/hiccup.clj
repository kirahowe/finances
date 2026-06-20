(ns finance-aggregator.web.hiccup
  "Datastar-aware Replicant helpers shared by every server-rendered view.

   Replicant's string renderer has two sharp edges that shape this namespace
   (see doc/plans/replicant-datastar-migration.md §5):

     1. It does NOT escape double quotes inside attribute values. Any Datastar
        expression embedded in an attribute must therefore avoid them: emit JS
        string literals single-quoted via `js-str`, and signal maps as
        single-quoted JS object literals via `signals`.
     2. Datastar's v1 attribute names use colons (`data-on:click`, `data-bind:x`,
        `data-class`). Clojure keyword literals can't carry those cleanly, so `a`
        builds the attribute map from string keys, coercing each to a keyword —
        Replicant renders an attribute keyword by its (name), colon preserved."
  (:require
   [charred.api :as json]
   [clojure.string :as str]
   [replicant.string :as rs]))

;; ---------------------------------------------------------------------------
;; Attribute + value helpers
;; ---------------------------------------------------------------------------

(defn a
  "Build a Replicant attribute map, coercing string keys to keywords so that
   colon-bearing Datastar attribute names survive rendering. Non-string keys
   (e.g. :class, :id, :style) pass through unchanged."
  [m]
  (reduce-kv (fn [acc k v] (assoc acc (if (string? k) (keyword k) k) v)) {} m))

(defn js-str
  "Quote `s` as a single-quoted JS string literal, escaping backslashes and
   single quotes. Use for any string value embedded in a Datastar attribute
   expression — Replicant does not escape double quotes in attribute values, so
   single quotes are the only safe delimiter."
  [s]
  (str \' (-> (str s)
              (str/replace "\\" "\\\\")
              (str/replace "'" "\\'"))
       \'))

(defn- js-value
  "Serialize a Clojure value to a JS literal. Strings are single-quoted/escaped
   via `js-str`; maps/vectors recurse; everything else uses its literal form."
  [v]
  (cond
    (map? v)        (str "{" (str/join ", "
                                       (map (fn [[k vv]] (str (name k) ": " (js-value vv))) v))
                         "}")
    (sequential? v) (str "[" (str/join ", " (map js-value v)) "]")
    (string? v)     (js-str v)
    (keyword? v)    (js-str (name v))
    (boolean? v)    (str v)
    (number? v)     (str v)
    (nil? v)        "null"
    :else           (js-str (str v))))

(defn signals
  "Serialize a Clojure map into a single-quoted-safe JS object literal for
   Datastar's `data-signals` attribute. Keys must be valid JS identifiers
   (Datastar signal names); values are serialized recursively via `js-value`."
  [m]
  (js-value m))

;; ---------------------------------------------------------------------------
;; Rendering
;; ---------------------------------------------------------------------------

(defn render
  "Render a hiccup fragment to an HTML string."
  [hiccup]
  (rs/render hiccup))

(defn render-page
  "Render a full HTML document: a DOCTYPE prologue + the rendered hiccup tree."
  [hiccup]
  (str "<!DOCTYPE html>" (rs/render hiccup)))

;; ---------------------------------------------------------------------------
;; Reading Datastar signals off a request
;; ---------------------------------------------------------------------------

(defn read-signals
  "Read the Datastar signals map off a Ring request, returning a keyword-keyed
   map (or nil).

   Datastar transports the full signals object two ways, and the existing
   middleware stack already surfaces both:

     - POST/PUT/PATCH: a JSON body (Content-Type application/json) which
       `wrap-json-request` has already parsed into `:body-params`.
     - GET/DELETE: the `datastar` query param, parsed here.

   Reading `:body-params` rather than the raw `:body` is deliberate — the global
   wrap-json-request consumes the body stream, so the Datastar SDK's `get-signals`
   body path would come back empty. This is the project-wide seam for reading
   signals; hypermedia handlers should call it rather than touching the body."
  [req]
  (or (:body-params req)
      (some-> (get-in req [:query-params "datastar"])
              (json/read-json :key-fn keyword))))
