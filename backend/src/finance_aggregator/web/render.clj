(ns finance-aggregator.web.render
  "The hiccup2 render seam for server-authoritative Datastar pages — the replacement
   for the Replicant seam (web.hiccup), introduced in the server-authoritative rewrite
   (see doc/plans/datastar-server-authoritative-rewrite.md).

   hiccup2 escapes attribute values by default, which collapses the old seam to almost
   nothing:

     - String (and colon-bearing) attribute keys render verbatim, so Datastar's
       `data-on:click` / `data-bind` go in a plain attribute map — no `a` coercion.
     - `data-signals` is just JSON: hiccup2 escapes the `\"` to `&quot;`, the browser
       decodes it back, and Datastar reads valid JSON — no single-quoted `js-str` /
       `js-value` workaround.
     - Server data never goes into a client expression in this architecture (it lives
       in morphed HTML), so the JS-string-escaping `js-str` has no remaining callers.

   What's left is render + read-signals."
  (:require
   [charred.api :as json]
   [hiccup.util :as hu]
   [hiccup2.core :as h2]))

;; --- Rendering --------------------------------------------------------------

(defn render
  "Render a hiccup fragment to an HTML string (attributes escaped)."
  [hiccup]
  (str (h2/html hiccup)))

(defn render-page
  "Render a full HTML document: a DOCTYPE prologue + the rendered hiccup tree."
  [hiccup]
  (str "<!DOCTYPE html>" (render hiccup)))

(defn signals
  "Serialize a Clojure map into a JSON string for a `data-signals` attribute. hiccup2
   escapes the embedded quotes; the browser decodes them, so Datastar parses real JSON.
   Ephemeral, client-only signals should be _-prefixed by the caller so Datastar omits
   them from backend requests."
  [m]
  (json/write-json-str m))

(defn raw
  "Wrap a pre-rendered HTML string so hiccup2 embeds it verbatim (no re-escaping) — for
   the rare case of splicing already-rendered markup into a hiccup tree."
  [s]
  (hu/raw-string s))

;; --- Reading Datastar signals off a request ---------------------------------

(defn read-signals
  "Read the Datastar signals map off a Ring request as a keyword-keyed map (or nil).
   Renderer-agnostic; unchanged from the Replicant seam.

   Datastar sends the full signals object two ways, both already surfaced by the
   middleware stack:
     - POST/PUT/PATCH: a JSON body, parsed by wrap-json-request into :body-params.
     - GET/DELETE: the `datastar` query param, parsed here.

   Reading :body-params (not the raw body) is deliberate — the global wrap-json-request
   consumes the body stream, so the SDK's body-path get-signals would come back empty."
  [req]
  (or (:body-params req)
      (some-> (get-in req [:query-params "datastar"])
              (json/read-json :key-fn keyword))))
