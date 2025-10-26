(ns finance-aggregator.test-utils.setup)

(def test-times (or (some-> (System/getenv "TEST_CHECK_TIMES") parse-long)
                    100))
