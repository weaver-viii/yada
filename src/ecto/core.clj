(ns ecto.core
  (:require
   [manifold.deferred :as d]
   [bidi.bidi :as bidi]
   bidi.swagger
   [clojure.core.match :refer (match)]
   [schema.core :as s]
   [ecto.protocols :as p]
   ))

;; API specs. are created like this

;; This is kind of like a bidi route structure

;; But we shouldn't limit ourselves to only that which is declared, because so much more can be generated, like 404s, etc.

;; "It is better to have 100 functions operate on one data structure than 10 functions on 10 data structures." —Alan Perlis

(defrecord Resource [])

(extend-protocol bidi/Matched
  Resource
  (resolve-handler [op m]
    (assoc m ::resource op))
  (unresolve-handler [op m]
    (when (= (:operationId op) (:handler m)) "")))

(defn op-handler [op]
  (fn [req]
    (cond
      :otherwise
      {:status 200 :body "foo"})))

(defn match-route [spec path & args]
  (apply bidi/match-route spec path args))

(defn check-cacheable [resource-metadata]
  ;; Check the resource metadata and return one of the following responses
  (cond
    false {:status 412}
    false {:status 304}
    :otherwise
    ;; We return nil which indicates the resource must be reserved
    nil))

;; TODO For authentication, implementation is out-of-band, in Ring
;; middleware or another mechanism for assoc'ing evidence of credentials
;; to the Ring request.

(defn make-handler
  [op
   {:keys
    [
     service-available?

     known-method?

     request-uri-too-long?

     allowed-method?

     ;; a function, may return a deferred value. The return value
     ;; must also contain a :data entry, containing the resource's
     ;; data, though this should usually be a deferred too,
     ;; because there's no guarantee it will be needed.  The
     ;; function takes the parameters provided in the request
     ;; (path, query, etc.)
     resource-metadata

     ;; a function, may return a deferred value. Parameters
     ;; indicate the (negotiated) content type.
     response-body

     ;; The allowed? callback will contain the entire op, the callback must
     ;; therefore extract the OAuth2 scopes, or whatever is needed to
     ;; authorize the request.
     allowed?

     ]
    :or {service-available? (constantly true)
         known-method? #{:get :put :post :delete :options :head}
         request-uri-too-long? 4096
         allowed-method? (-> op ::resource keys set)

         resource-metadata (constantly {})
         response-body (fn [_] "Hello World!")
         allowed? (constantly true)
         }}]

  (fn [req]
    (cond
      ;; Service Unavailable
      (not (p/service-available? service-available?))
      {:status 503}

      ;; Not Implemented
      (not (p/known-method? known-method? (:request-method req)))
      {:status 501}

      ;; Request URI Too Long
      (p/request-uri-too-long? request-uri-too-long? (:uri req))
      {:status 414}

      ;; Method Not Allowed
      (not (p/allowed-method? allowed-method? (:request-method req) op))
      {:status 405}

      :otherwise
      (if-let [resource-metadata (resource-metadata {})]
        ;; Resource exists - follow the exists chain
        (d/chain
         resource-metadata
         (fn [resource-metadata]
           (or (check-cacheable resource-metadata)
               (d/chain
                resource-metadata
                (fn [resource-metadata]
                  {:status 200
                   :body (response-body "text/plain")
                   })))))

        ;; Resource does not exist - follow the not-exists chain
        {:status 404}))))

;; handle-method-not-allowed 405 "Method not allowed."

#_(let [req (mock/request :put "/persons")]
  ((op-handler (apply handle-route routes (:uri req) (apply concat req))) req))

;; This is OK
;; ((api-handler api) (mock/request :get "/persons"))

;; This is should yield 405 "Method not allowed."
;; ((api-handler api) (mock/request :get "/persons"))

;; List of interesting things to do

;; There should be a general handler that does the right thing
;; wrt. available methods (Method Not Allowed) and calls out to
;; callbacks accordingly. Perhaps there's no sense in EVERYTHING being
;; overridable, as with Liberator. It should hard-code the things that
;; don't make sense to override, and use hooks for the rest.

;; Resource existence is most important - and not covered by swagger, so it's a key hook.

;; Return deferreds, if necessary, if the computation is length to compute (e.g. for exists? checks)

;; CORS support: build this in, make allow-origin first-class, which headers should be allowed should be a hook (with default)
