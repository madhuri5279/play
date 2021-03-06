(ns play.onyx-stream.twitter.chain-windows
  (:require [clojure.core.async :refer [chan >!! <!! close!]]
            [cheshire.core :as jp]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.api])
  (:import [java.util Date]))

(def capacity 1000)

(def input-chan (chan capacity))
(def input-buffer (atom {}))

(def output-chan (chan capacity))

(defn inject-in-ch [event lifecycle]
  {:core.async/buffer input-buffer
   :core.async/chan input-chan})

(defn inject-out-ch [event lifecycle]
  {:core.async/chan output-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(defn dump-window!
  [event window trigger {:keys [lower-bound upper-bound] :as window-data} state]
  (let [tags (->> (mapv :text state) (apply merge-with +))]
    (when tags
      (println
       (jp/generate-string
        (assoc {} :startDate (Date. lower-bound)
               :endDate (Date. upper-bound) :tags tags)) ","))))

(defn emit-segment
  [event window trigger {:keys [lower-bound upper-bound] :as window-data} state]
  (let [tags (->> (mapv :text state) (apply merge-with +))]
    (assoc {} :startDate (Date. lower-bound)
           :endDate (Date. upper-bound) :tags_7 tags)))

(defn emit-rollup
  [event window trigger {:keys [lower-bound upper-bound] :as window-data} state]
  (let [tags (->> (mapv :tags_7 state) (apply merge-with +))]
    (assoc {} :startDate (Date. lower-bound)
           :endDate (Date. upper-bound) :tags_15 tags)))

(def formatter (java.text.SimpleDateFormat. "EEE MMM dd HH:mm:ss Z yyyy"))

(defn pick
  "Picks the required fields from the segment"
  [segment]
  (select-keys segment [:id_str :text :created_at]))

(defn transform
  "Transforms the incoming segment"
  [segment]
  (assoc segment
         :created_at (.parse formatter (segment :created_at))
         :text (frequencies
                (re-seq
                 #"\bobama\b|\bobamacare\b|\bglobal\swarming\b|\bamerica\b|\bamericans\b"
                 (clojure.string/lower-case (segment :text))))))

(defn pick-text
  "Picks only the segments having text with tags"
  [segment]
  (filter #(not (empty? (% :text))) (vector segment)))

(defn tags?
  [event old-segment new-segment all-new tagfn]
  (new-segment tagfn))

(defn job-config
  [{:keys [id batch-size] :as params}]
  {:id id
   :batch-size batch-size
   :env-config {:zookeeper/address "127.0.0.1:2188"
                :zookeeper/server? true
                :zookeeper.server/port 2188
                :onyx/tenancy-id id}
   :peer-config {:zookeeper/address "127.0.0.1:2188"
                 :onyx/tenancy-id id
                 :onyx.peer/job-scheduler :onyx.job-scheduler/balanced
                 :onyx.messaging/impl :aeron
                 :onyx.messaging/peer-port 40200
                 :onyx.messaging/bind-addr "localhost"}})

(defn create-job
  [{:keys [id batch-size] :as params}]
  (let [workflow [[:in :pick]
                  [:pick :transform]
                  [:transform :pick-text]
                  [:pick-text :roll-up]
                  [:roll-up :out]]
        catalog [{:onyx/name :in
                  :onyx/plugin :onyx.plugin.core-async/input
                  :onyx/type :input
                  :onyx/medium :core.async
                  :onyx/batch-size batch-size
                  :onyx/max-peers 1
                  :onyx/doc "Reads segments from a core.async channel"}

                 {:onyx/name :pick
                  :onyx/fn ::pick
                  :onyx/type :function
                  :onyx/batch-size batch-size}

                 {:onyx/name :transform
                  :onyx/fn ::transform
                  :onyx/type :function
                  :onyx/batch-size batch-size}

                 {:onyx/name :pick-text
                  :onyx/fn ::pick-text
                  :onyx/type :function
                  :onyx/batch-size batch-size}

                 {:onyx/name :roll-up
                  :onyx/fn :clojure.core/identity
                  :onyx/type :function
                  :onyx/max-peers 1
                  :onyx/batch-size batch-size}

                 {:onyx/name :out
                  :onyx/plugin :onyx.plugin.core-async/output
                  :onyx/type :output
                  :onyx/medium :core.async
                  :onyx/max-peers 1
                  :onyx/batch-size batch-size
                  :onyx/doc "Writes segments to a core.async channel"}]
        lifecycles [{:lifecycle/task :in
                     :lifecycle/calls ::in-calls}
                    {:lifecycle/task :in
                     :lifecycle/calls :onyx.plugin.core-async/reader-calls}
                    {:lifecycle/task :out
                     :lifecycle/calls ::out-calls}
                    {:lifecycle/task :out
                     :lifecycle/calls :onyx.plugin.core-async/writer-calls}]
        windows [{:window/id :collect-segments
                  :window/task :pick-text
                  :window/type :fixed
                  :window/aggregation :onyx.windowing.aggregation/conj
                  :window/window-key :created_at
                  :window/range [5 :days]}
                 {:window/id :rollup-segments
                  :window/task :roll-up
                  :window/type :fixed
                  :window/aggregation :onyx.windowing.aggregation/conj
                  :window/window-key :startDate
                  :window/range [10 :days]}]
        triggers [{:trigger/window-id :collect-segments
                   :trigger/id :sync
                   :trigger/refinement :onyx.refinements/discarding
                   :trigger/fire-all-extents? true
                   :trigger/on :onyx.triggers/segment
                   :trigger/threshold [10 :elements]
                   :trigger/emit ::emit-segment}
                  {:trigger/window-id :rollup-segments
                   :trigger/id :rollup-trigger
                   :trigger/refinement :onyx.refinements/discarding
                   :trigger/fire-all-extents? true
                   :trigger/on :onyx.triggers/segment
                   :trigger/threshold [5 :elements]
                   :trigger/emit ::emit-rollup}]
        flow-conditions [{:flow/from :pick-text
                          :flow/to [:roll-up]
                          :tag/fn :tags_7
                          :flow/predicate [::tags? :tag/fn]
                          :flow/doc "Emits segment if this segment has a tag"}
                         {:flow/from :roll-up
                          :flow/to [:out]
                          :tag/fn :tags_15
                          :flow/predicate [::tags? :tag/fn]
                          :flow/doc "Emits segment if this segment has a tag"}]]
    {:workflow workflow
     :catalog catalog
     :lifecycles lifecycles
     :windows windows
     :triggers triggers
     :flow-conditions flow-conditions
     :task-scheduler :onyx.task-scheduler/balanced}))

(defn start-env
  [{:keys [env-config] :as params}]
  (onyx.api/start-env env-config))

(defn start-peers
  [{:keys [peer-config] :as params}
   {:keys [workflow] :as job}]
  (let [peer-group (onyx.api/start-peer-group peer-config)
        n-peers (count (set (mapcat identity workflow)))
        v-peers (onyx.api/start-peers n-peers peer-group)]
    {:peer-group peer-group :v-peers v-peers}))

(defn submit-job
  [{:keys [peer-config] :as params} job]
  (onyx.api/submit-job peer-config job))

(defn send-data
  [params segments]
  (doseq [segment segments]
    (>!! input-chan segment))
  ;; for await API to unblock
  (close! input-chan))

(defn get-result
  [{:keys [peer-config] :as params} active-job]
  (onyx.api/await-job-completion peer-config (:job-id active-job))
  (Thread/sleep 5000)
  (take-segments! output-chan 50))

(defn close-channels
  [params]
  ;;(close! utils/input-chan)
  (close! output-chan))

(defn stop-peers
  [{:keys [peer-group v-peers] :as peer-config}]
  (doseq [v-peer v-peers]
    (onyx.api/shutdown-peer v-peer))
  (onyx.api/shutdown-peer-group peer-group))

(defn stop-env
  [env]
  (onyx.api/shutdown-env env))

(def input-segments
  (as-> (slurp "resources/2017.json") $
    (jp/parse-string $ true)
    ;;(take 10 $)
    ))

(defn run-flow
  [params]
  (let [_ (println "Initializing Job Config...")
        config (job-config params)
        _ (println "Preparing Job Config...")
        job (create-job params)
        _ (println "Starting Onyx Environment...")
        env (start-env config)
        _ (println "Starting Peer Group and Peers...")
        peer-config (start-peers config job)
        _ (println "Running Job...")
        active-job (submit-job config job)]
    (merge {:env env :flow active-job} config job peer-config)))

(defn process-data
  [{:keys [flow] :as params}]
  (send-data params input-segments)
  (get-result params flow))

(defn stop-flow
  [{:keys [env] :as params}]
  (println "Closing Channels...")
  (close-channels params)
  (println "Stopping Peers and Peer Group...")
  (stop-peers params)
  (println "Stopping Onyx Environment...")
  (stop-env env))

(defn execute-flow
  [{:keys [id batch-size] :as params
    :or {id (java.util.UUID/randomUUID) batch-size 10}}]
  (let [flow (run-flow {:id id :batch-size batch-size})
        result (process-data flow)]
    (println result)
    (stop-flow flow)))
