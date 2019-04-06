[about]: https://github.com/luchiniatwork/resilience4clj-circuitbreaker/blob/master/docs/ABOUT.md
[breaker]: https://github.com/luchiniatwork/resilience4clj-circuitbreaker/
[cache-effect]: https://github.com/luchiniatwork/resilience4clj-cache#using-as-an-effect
[circleci-badge]: https://circleci.com/gh/luchiniatwork/resilience4clj-retry.svg?style=shield&circle-token=10db5b67206f6a8351da2e10a783b9667a68b8ac
[circleci]: https://circleci.com/gh/luchiniatwork/resilience4clj-retry
[clojars-badge]: https://img.shields.io/clojars/v/resilience4clj/resilience4clj-retry.svg
[clojars]: http://clojars.org/resilience4clj/resilience4clj-retry
[github-issues]: https://github.com/luchiniatwork/resilience4clj-retry/issues
[license-badge]: https://img.shields.io/badge/license-MIT-blue.svg
[license]: ./LICENSE
[status-badge]: https://img.shields.io/badge/project%20status-alpha-brightgreen.svg

# Resilience4Clj Retry

[![CircleCI][circleci-badge]][circleci]
[![Clojars][clojars-badge]][clojars]
[![License][license-badge]][license]
![Status][status-badge]

Resilience4clj is a lightweight fault tolerance library set built on
top of GitHub's Resilience4j and inspired by Netflix Hystrix. It was
designed for Clojure and functional programming with composability in
mind.

Read more about the [motivation and details of Resilience4clj
here][about].

Resilience4Clj Retry lets you decorate a function call with a
specified number of retry attempts. Once a successful call is reached,
the result is returned. The timing between attempts can be configured
in several different ways.

## Table of Contents

* [Getting Started](#getting-started)
* [Retry Settings](#retry-settings)
* [Fallback Strategies](#fallback-strategies)
* [Effects](#effects)
* [Metrics](#metrics)
* [Events](#events)
* [Exception Handling](#exception-handling)
* [Composing Further](#composing-further)
* [Bugs](#bugs)
* [Help!](#help)

## Getting Started

Add `resilience4clj/resilience4clj-retry` as a dependency to your
`deps.edn` file:

``` clojure
resilience4clj/resilience4clj-retry {:mvn/version "0.1.1"}
```

If you are using `lein` instead, add it as a dependency to your
`project.clj` file:

``` clojure
[resilience4clj/resilience4clj-retry "0.1.1"]
```

Require the library:

``` clojure
(require '[resilience4clj-retry.core :as r])
```

Then create a retry calling the function `create`:

``` clojure
(def retry (r/create "my-retry"))
```

Now you can decorate any function you have with the retry you just
defined.

For the sake of this example, we will need to use a contrived
approach. In real life you probably have a function that calls an API
via a GET HTTP and, due to its network-based nature, this function
sometimes fails. In our contrived example we will use a function that
returns a function that _we know_ will fail for a certain amount of
times and it will start working:

``` clojure
(defn create-hello-works-after
  [x]
  (let [a (atom x)]
    (fn [n]
      (swap! a dec)
      (if (< @a 0)
        (str "Hello " n "!")
        (throw (ex-info "Couldn't say hello"
                        {:extra-info :here}))))))
```

You can use this function like this:

``` clojure
(def hello-world (create-hello-works-after 2))
```

Then you can call `(hello-world "John")` and it will fail the first
two times you call it before starting to work normally.

By default, `r/create` will create a retry that has a max of 3
attempts and a waiting period of 500ms between calls. You can now
create a decorated version of your `hello-world` function above and
combine it with the `retry` we created before like this:

``` clojure
(def protected (r/decorate hello-world retry))
```

When you call `protected` it will be successful but take around a full
1000ms to run. That's because retry will wait 500ms between calls and
we know `hello-world` fails twice:

``` clojure
(time (protected "John"))
"Elapsed time: 1002.160511 msecs"
Hello John!
```

## Retry Settings

When creating a retry, you can fine tune three of its settings:

1. `:max-attempts` - the amount of attempts it will try before giving
   up. Default is `3`.
2. `:wait-duration` - the duration in milliseconds between attempts.
   Default is `500`.
3. `:interval-function` - sometimes you need a more advanced strategy
   between attempts. Some systems react better with a progressive
   backoff for instance (you can start retrying faster but increasing
   the waiting time in case the remote system is offline). For these
   cases you can specify an interval function that will control this
   waiting time. See below for more. Default here is a linear function
   of `:wait-duration` intervals.

These three options can be sent to `create` as a map. In the following
example, any function decorated with `retry` will be attempted for 10
times with in 300ms intervals.

``` clojure
(def retry (create {:max-attempts 10
                    :wait-duration 300}))
```

Resilience4clj provides a series of commonly-used interval
functions. They are all in the namespace
`resilience4clj-retry.interval-functions`:

* `of-default` - basic linear function with 500ms intervals
* `of-millis` - linear function with a specified interval in
  milliseconds
* `of-randomized` - starts with an initial, specified interval in
  milliseconds and then randomizes by an optional factor on subsequent
  attempts
* `of-exponential-backoff` - starts with an initial, specified
  interval in milliseconds and then backs off by an optional
  multiplier on subsequent calls (default multiplier is 1.5).
* `of-exponential-random-backoff` - starts with an initial, specified
  interval in milliseconds and then backs off by an optional
  multiplier and randomizes by an optional factor on subsequent calls.

## Fallback Strategies

When decorating your function with a retry you can opt to have a
fallback function. This function will be called instead of an
exception being thrown when the retry gives up after reaching the
max-attempts or when the call would fail (traditional throw). This
feature can be seen as an obfuscation of a try/catch to consumers.

This is particularly useful if you want to obfuscate from consumers
that the retry and/or that the external dependency failed. Example:

``` clojure
(def retry (r/create "hello-service-retry"))

(defn hello [person]
  ;; hypothetical flaky, external HTTP request
  (str "Hello " person))

(def retry-hello
  (r/decorate hello
              {:fallback (fn [e person]
                           (str "Hello from fallback to " person))}))
```

The signature of the fallback function is the same as the original
function plus an exception as the first argument (`e` on the example
above). This exception is an `ExceptionInfo` wrapping around the real
cause of the error. You can inspect the `:cause` node of this
exception to learn about the inner exception:

``` clojure
(defn fallback-fn [e]
  (str "The cause is " (-> e :cause)))
```

For more details on [Exception Handling](#exception-handling) see the
section below.

When considering fallback strategies there are usually three major
strategies:

1. **Failure**: the default way for Resilience4clj - just let the
   exceptiohn flow - is called a "Fail Fast" approach (the call will
   fail fast once the breaker is open). Another approach is "Fail
   Silently". In this approach the fallback function would simply hide
   the exception from the consumer (something that can also be done
   conditionally).
2. **Content Fallback**: some of the examples of content fallback are
   returning "static content" (where a failure would always yield the
   same static content), "stubbed content" (where a failure would
   yield some kind of related content based on the paramaters of the
   call), or "cached" (where a cached copy of a previous call with the
   same parameters could be sent back).
3. **Advanced**: multiple strategies can also be combined in order to
   create even better fallback strategies.

For more details on some of these strategies, read the section
[Effects](#effects) below.

## Effects

A common issue for some fallback strategies is to rely on a cache or
other content source (see Content Fallback above). In these cases, it
is good practice to persist the successful output of the function call
as a side-effect of the call itself.

Resilience4clj retry supports this behavior in the folling way:

``` clojure
(def retry (r/create "hello-service-retry"))

(defn hello [person]
  ;; hypothetical flaky, external HTTP request
  (str "Hello " person))

(def retry-hello
  (r/decorate hello
              {:effect (fn [ret person]
                         ;; ret will have the successful return from `hello`
                         ;; you can save it on a memory cache, disk, etc
                         )}))
```

The signature of the effect function is the same as the original
function plus a "return" argument as the first argument (`ret` on the
example above). This argument is the successful return of the
encapsulated function.

The effect function is called on a separate thread so it is
non-blocking.

You can see an example of how to use effects for caching purposes at
[using Resilience4clj cache as an effect][cache-effect].

## Metrics

The function `metrics` returns a map with the metrics of the retry:

``` clojure
(r/metrics my-retry)

=> {:number-of-successful-calls-without-retry-attempt 0,
    :number-of-failed-calls-without-retry-attempt 0,
    :number-of-successful-calls-with-retry-attempt 0,
    :number-of-failed-calls-with-retry-attempt 0}
```

The nodes should be self-explanatory.

## Events

You can listen to events generated by your retries. This is
particularly useful for logging, debugging, or monitoring the health
of your retries.

``` clojure
(def my-retry (r/create "my-retry"))

(cb/listen-event my-retry
                 :RETRY
                 (fn [evt]
                   (println (str "Received event " (:event-type evt)))))
```

There are four types of events:

1. `:SUCCESS` - informs that a call has been tried and succeeded
2. `:ERROR` - informs that a call has been retried, but still failed
5. `:IGNORED_ERROR` - informs that an error has been ignored
6. `:RETRY` - informs that a call has been tried, failed and will now
   be retried

Notice you have to listen to a particular type of event by specifying
the event-type you want to.

All events receive a map containing the `:event-type`, the
`:retry-name`, the event `:creation-time`, a
`:number-of-retry-attempts`,and the `:last-throwable`.

## Exception Handling

When a retry exhausts all the attempts it will throw the very last
exception returned from the decorated, failing function call.

## Composing Further

Resilience4clj is composed of [several modules][about] that
easily compose together. For instance, if you are also using the
[circuit breaker module][breaker] and assuming your import and basic
settings look like this:

``` clojure
(ns my-app
  (:require [resilience4clj-circuitbreaker.core :as cb]
            [resilience4clj-retry.core :as r]))

;; create a retry with default settings
(def retry (r/create "my-retry"))

;; create circuit breaker with default settings
(def breaker (cb/create "HelloService"))

;; flaky function you want to potentially retry
(defn flaky-hello []
  ;; hypothetical request to a flaky server that might fail (or not)
  "Hello World!")
```

Then you can create a protected call that combines both the retry and
the circuit breaker:

``` clojure
(def protected-hello (-> flaky-hello
                         (r/decorate retry)
                         (cb/decorate breaker)))
```

The resulting function on `protected-hello` will trigger the breaker
in case of a failed retries.

## Bugs

If you find a bug, submit a [Github issue][github-issues].

## Help

This project is looking for team members who can help this project
succeed!  If you are interested in becoming a team member please open
an issue.

## License

Copyright © 2019 Tiago Luchini

Distributed under the MIT License.
