(ns finance-aggregator.test-utils.setup
  (:require [datalevin.core :as d]
            [finance-aggregator.data.schema :as schema])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def test-times (or (some-> (System/getenv "TEST_CHECK_TIMES") parse-long)
                    100))

;; Atoms to hold test connection (consistent with db_test.clj pattern)
(def ^:dynamic *test-conn* nil)
(def ^:dynamic *test-db-path* nil)

(defn create-temp-db-dir
  "Create a temporary directory for test database"
  []
  (let [temp-dir (Files/createTempDirectory "datalevin-test-" (make-array FileAttribute 0))]
    (.toString temp-dir)))

(defn delete-directory-recursive
  "Delete a directory and all its contents"
  [^File dir]
  (when (.exists dir)
    (doseq [file (.listFiles dir)]
      (if (.isDirectory file)
        (delete-directory-recursive file)
        (.delete file)))
    (.delete dir)))

(defn with-empty-db
  "Test fixture that creates an empty test database for each test.
   Uses atoms for connections to match existing test patterns."
  [f]
  (let [db-path (create-temp-db-dir)
        conn (d/get-conn db-path schema/schema)
        conn-atom (atom conn)]
    (try
      (binding [*test-conn* conn-atom
                *test-db-path* db-path]
        (f))
      (finally
        (d/close conn)
        (delete-directory-recursive (File. db-path))))))
