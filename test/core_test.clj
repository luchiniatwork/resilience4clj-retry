(ns core-test
  (:require [resilience4clj-retry.core :as retry]
            [resilience4clj-retry.interval-functions :as i-fns]
            [clojure.test :refer :all]
            [clojure.string :as string]))

;; mock factory for a failing an external call
(defn ^:private create-hello-works-after
  [x]
  (let [a (atom x)]
    (fn [n]
      (swap! a dec)
      (if (< @a 0)
        (str "Hello " n "!")
        (throw (ex-info "Couldn't say hello" {:extra-info :here}))))))


(deftest create-default-retry
  (let [{:keys [max-attempts
                interval-function]}
        (-> "my-retry"
            retry/create
            retry/config)]
    (is (= 3 max-attempts))
    (is (string/starts-with? (str (type interval-function))
                             "class io.github.resilience4j.retry.IntervalFunction$$Lambda"))))

(deftest create-custom-retry
  (let [{:keys [max-attempts
                interval-function]}
        (-> "my-retry"
            (retry/create {:interval-function (i-fns/of-randomized 1000)})
            retry/config)]
    (is (= 3 max-attempts))
    (is (string/starts-with? (str (type interval-function))
                             "class io.github.resilience4j.retry.IntervalFunction$$Lambda"))))

(deftest works-after-3-tries-2-failures
  (let [protected (retry/decorate (create-hello-works-after 2)
                                  (retry/create "my-retry"
                                                {:max-attempts 3}))]
    (is (= "Hello Test!"
           (protected "Test")))))

(deftest works-after-10-tries-5-failures
  (let [protected (retry/decorate (create-hello-works-after 5)
                                  (retry/create "my-retry"
                                                {:max-attempts 10}))]
    (is (= "Hello Test!"
           (protected "Test")))))

(deftest fails-after-2-tries-3-failures
  (let [protected (retry/decorate (create-hello-works-after 3)
                                  (retry/create "my-retry"
                                                {:max-attempts 2}))]
    (is (thrown? clojure.lang.ExceptionInfo (protected "Test")))
    (try
      (protected "Test")
      (catch Throwable e
        (is (= :here)
            (-> e ex-data :extra-info))))))
