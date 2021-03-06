(ns resilience4clj-retry.core
  (:import
   (io.github.resilience4j.retry RetryConfig
                                 Retry
                                 Retry$EventPublisher)

   (io.github.resilience4j.retry.event RetryEvent
                                       RetryOnErrorEvent
                                       RetryOnRetryEvent
                                       RetryOnIgnoredErrorEvent
                                       RetryOnSuccessEvent)

   (io.github.resilience4j.core EventConsumer)

   (io.vavr.control Try)

   (java.time Duration)))

(defn ^:private anom-map
  [category msg]
  {:resilience4clj.anomaly/category (keyword "resilience4clj.anomaly" (name category))
   :resilience4clj.anomaly/message msg})

(defn ^:private anomaly!
  ([name msg]
   (throw (ex-info msg (anom-map name msg))))
  ([name msg cause]
   (throw (ex-info msg (anom-map name msg) cause))))

(defn ^:private get-failure-handler [{:keys [fallback]}]
  (if fallback
    (fn [& args] (apply fallback args))
    (fn [& args] (throw (-> args first :cause)))))

;; FIXME: manage retryOnException and/or retryOnResult (they are confusing in the docs)
(defn ^:private config-data->retry-config
  [{:keys [max-attempts wait-duration interval-function]}]
  (.build
   (cond-> (RetryConfig/custom)
     max-attempts      (.maxAttempts max-attempts)
     wait-duration     (.waitDuration (Duration/ofMillis wait-duration))
     interval-function (.intervalFunction interval-function))))

;; FIXME: retry config does not expose wait duration directly - this feels wrong
(defn ^:private retry-config->config-data
  [^RetryConfig retry-config]
  {:max-attempts      (.getMaxAttempts retry-config)
   :interval-function (.getIntervalFunction retry-config)})

(defmulti ^:private event->data
  (fn [^RetryEvent e]
    (-> e .getEventType .toString keyword)))

(defn ^:private base-event->data [^RetryEvent e]
  {:event-type (-> e .getEventType .toString keyword)
   :retry-name (.getName e)
   :number-of-retry-attempts (.getNumberOfRetryAttempts e)
   :creation-time (.getCreationTime e)
   :last-throwable (.getLastThrowable e)})

;; informs that a call has been tried and succeeded
(defmethod event->data :SUCCESS [^RetryOnSuccessEvent e]
  (base-event->data e))

;; informs that a call has been retried, but still failed
(defmethod event->data :ERROR [^RetryOnErrorEvent e]
  (base-event->data e))

;; informs that a call has been tried, failed and will now be retried
(defmethod event->data :RETRY [^RetryOnRetryEvent e]
  (base-event->data e))

;; informs that an error has been ignored
(defmethod event->data :IGNORED_ERROR [^RetryOnIgnoredErrorEvent e]
  (base-event->data e))

(defn ^:private event-consumer [f]
  (reify EventConsumer
    (consumeEvent [this e]
      (let [data (event->data e)]
        (f data)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create
  ([n]
   (create n nil))
  ([n opts]
   (if opts
     (Retry/of ^String n ^RetryConfig (config-data->retry-config opts))
     (Retry/ofDefaults ^String n))))

(defn config
  [^Retry retry]
  (-> retry
      .getRetryConfig
      retry-config->config-data))

(defn decorate
  ([f ^Retry retry]
   (decorate f retry nil))
  ([f ^Retry retry {:keys [effect] :as opts}]
   (fn [& args]
     (let [callable (reify Callable (call [_] (apply f args)))
           decorated-callable (Retry/decorateCallable retry callable)
           failure-handler (get-failure-handler opts)
           result (Try/ofCallable decorated-callable)]
       (if (.isSuccess result)
         (let [out (.get result)]
           (when effect
             (future (apply effect (conj args out))))
           out)
         (let [args' (-> args (conj {:cause (.getCause result)}))]
           (apply failure-handler args')))))))

(defn metrics
  [^Retry retry]
  (let [metrics (.getMetrics retry)]
    {;; the number of successful calls without a retry attempt
     :number-of-successful-calls-without-retry-attempt
     (.getNumberOfSuccessfulCallsWithoutRetryAttempt metrics)

     ;; the number of failed calls without a retry attempt
     :number-of-failed-calls-without-retry-attempt
     (.getNumberOfFailedCallsWithoutRetryAttempt metrics)

     ;; the number of successful calls after a retry attempt
     :number-of-successful-calls-with-retry-attempt
     (.getNumberOfSuccessfulCallsWithRetryAttempt metrics)

     ;; the number of failed calls after all retry attempts
     :number-of-failed-calls-with-retry-attempt
     (.getNumberOfFailedCallsWithRetryAttempt metrics)}))

(defn listen-event
  [^Retry retry event-key f]
  (let [^Retry$EventPublisher event-publisher (.getEventPublisher retry)
        ^EventConsumer consumer (event-consumer f)]
    (case event-key
      :SUCCESS (.onSuccess event-publisher consumer)
      :ERROR (.onError event-publisher consumer)
      :IGNORED_ERROR (.onIgnoredError event-publisher consumer)
      :RETRY (.onRetry event-publisher consumer))))

(comment
  (def retry (create "my-retry"))
  (config retry)

  (def retry2 (create "other-retry" {:wait-duration 1000
                                     :max-attempts 5}))
  (config retry2)

  (defn always-fails []
    (anomaly! :always-fails "Because I said so!"))

  ;; mock for an external call
  (defn external-call
    ([n]
     (external-call n nil))
    ([n {:keys [fail? wait]}]
     (println "calling...")
     (when wait
       (Thread/sleep wait))
     (if-not fail?
       (str "Hello " n "!")
       (anomaly! :broken-hello "Couldn't say hello"))))

  (defn random-call
    []
    (let [r (rand)]
      (cond
        (< r 0.4) "I worked!!"
        :else (anomaly! :sorry "Sorry. No cake!"))))
  
  (def protect-always-failure
    (decorate always-fails
              retry))
  
  (def call (decorate external-call
                      retry2
                      {:fallback (fn [n opts e]
                                   (str "Fallback reply for " n))}))

  (def call2 (decorate random-call
                       retry2
                       {:fallback (fn [e]
                                    (str "Fallback"))}))
  
  (listen-event retry2 :success (fn [e] (println (dissoc e :last-throwable))))
  (listen-event retry2 :error (fn [e] (println (dissoc e :last-throwable))))
  (listen-event retry2 :retry (fn [e] (println (dissoc e :last-throwable))))
  (listen-event retry2 :ignored-error (fn [e] (println (dissoc e :last-throwable))))

  (call "Bla" {:fail? false})

  (call2)

  #_(time (call "bla" {:fail? true}))

  #_(time (try
            (call "bla" {:fail? true})
            (catch Throwable t)))
  (metrics retry2)

  (let [r (proxy [io.github.resilience4j.retry RetryConfig] []
            (new-method []
              (println "Here")))]))
