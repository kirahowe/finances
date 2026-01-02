(ns finance-aggregator.lib.log
  "Structured logging using Telemere.

   Provides a simple interface for structured logging with automatic console output.
   Telemere handles formatting automatically - human-readable in development,
   structured data for production parsing."
  (:require [taoensso.telemere :as t]))

(defn init!
  "Initialize logging configuration.

   Options:
   - :min-level - Minimum log level (default :info for production, :debug for development)
   - :env - Environment name (default 'development')

   Telemere automatically outputs to console with sensible defaults.
   No handler configuration needed for typical use."
  ([]
   (init! {}))
  ([{:keys [min-level env]
     :or {env "development"}}]
   ;; Set minimum log level based on environment
   (let [level (or min-level (if (= env "development") :debug :info))]
     (t/set-min-level! level))))

(defn debug
  "Log debug message with optional structured data"
  ([msg] (t/log! :debug msg))
  ([msg data] (t/log! {:level :debug :data data} msg)))

(defn info
  "Log info message with optional structured data"
  ([msg] (t/log! :info msg))
  ([msg data] (t/log! {:level :info :data data} msg)))

(defn warn
  "Log warning message with optional structured data"
  ([msg] (t/log! :warn msg))
  ([msg data] (t/log! {:level :warn :data data} msg)))

(defn error
  "Log error message with optional structured data and exception"
  ([msg] (t/log! :error msg))
  ([msg data] (t/log! {:level :error :data data} msg))
  ([msg data ex] (t/log! {:level :error :data data :error ex} msg)))
