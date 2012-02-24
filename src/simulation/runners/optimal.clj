(ns simulation.runners.optimal
  (:use simulation.core
        clj-predicates.core
        clojure.pprint)
  (:require [simulation.io :as io]
            [simulation.algorithms.markov :as markov]))

(def time-step 300)
(def host {:mips 12000}) ;4x3000
(def migration-time 30)

(defn calculate-otf [state-history number-of-states migration-time]
  {:pre [(coll? state-history)
         (posnum? number-of-states)
         (not-negnum? migration-time)]
   :post [(not-negnum? %)]}
  (double (/ (+ migration-time (count (filter #{(dec number-of-states)} state-history)))
             (+ migration-time (count state-history)))))

(defn solve [otf-constraint state-history number-of-states migration-time]
  {:pre [(posnum? otf-constraint)
         (coll? state-history)
         (posnum? number-of-states)
         (not-negnum? migration-time)]
   :post [(map? %)]}
  (loop [states (reverse state-history)]
    (let [otf (calculate-otf states number-of-states migration-time)] 
      (if (or (<= otf otf-constraint)
              (empty? states))
        {:otf otf
         :time (* time-step (+ migration-time (count states)))}
        (recur (rest states))))))

(defn -main [& args]
  (do
    (println "optimal")
    (pprint args) 
    (let [workload (io/read-pregenerated-workload (nth args 0))
          state-config (read-string (nth args 1))
          otf (read-string (nth args 2))
          output (nth args 3)
          number-of-states (inc (count state-config))
          results (map #(solve otf 
                               (markov/utilization-to-states state-config 
                                                             (host-utilization-history host %)) 
                               number-of-states (/ migration-time time-step)) 
                       workload)
          avg-otf (double (/ 
                            (apply + (map #(:otf %) results))
                            (count results)))
          avg-time (double (/ 
                             (apply + (map #(:time %) results))
                             (count results)))
          time-otf (/ (/ avg-time avg-otf) 1000)] 
      (do
;        (pprint results)
        (println "avg-otf" avg-otf)
        (println "avg-time" avg-time)
        (println "time-otf" time-otf)
        (io/spit-results output
                         (map #(assoc % 
                                      :algorithm "optimal"
                                      :param otf
                                      :state-config state-config
                                      :violation (if (> (:otf %) otf) 1 0)
                                      :execution-time 0.0) 
                              results))
        (prn)))))
