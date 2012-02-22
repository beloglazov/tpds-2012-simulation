(ns simulation.runners.artificial-workload
  (:use clj-predicates.core
        simulation.core
        clojure.pprint)
  (:require [simulation.workload-generator :as workload-generator] 
            [simulation.algorithms :as algorithms]
            [simulation.algorithms.markov :as markov]
            [simulation.io :as io])
  (:gen-class))

(def time-step 300.0)
(def time-limit 288)
(def migration-time 20.0)
(def workloads [{:until 30
                 :transitions [[0.8 0.2]
                               [1.0 0.0]]}
                {:until 80
                 :transitions [[0.5 0.5]
                               [1.0 0.0]]}
                {:until 100
                 :transitions [[0.2 0.8]
                               [1.0 0.0]]}
                {:until 150
                 :transitions [[0.8 0.2]
                               [0.5 0.5]]}
                {:until 160
                 :transitions [[0.8 0.2]
                               [0.2 0.8]]}
                {:until 180
                 :transitions [[0.8 0.2]
                               [0.8 0.2]]}
                {:until 220
                 :transitions [[0.2 0.8]
                               [0.8 0.2]]}
                {:until 288
                 :transitions [[0.2 0.8]
                               [0.2 0.8]]}])
(def state-config [1.0])
(def host (workload-generator/get-host))
(def vms [(workload-generator/get-vms workloads state-config time-limit)])
(def algorithm (partial markov/markov-optimal workloads 0.1 0.3 state-config))

(defn -main [& args]
  (let [results (map #(run-simulation 
                        algorithm 
                        time-step
                        migration-time
                        host
                        %
                        ;(fn [step step-vms overloading-steps]
                        ;  (println "=====================================================")
                        ;  (println "step:" step)
                        ;  (println "mips:" (current-vms-mips step-vms))
                        ;  (println "utilization:" (double (/ (current-vms-mips step-vms) (:mips host))))
                        ;  (println "otf:" (double (/ overloading-steps step))))
                        )
                     vms)
        avg-otf (double (/ 
                          (apply + (map #(:overloading-time-fraction %) results))
                          (count results)))
        avg-time (double (/ 
                           (apply + (map #(:total-time %) results))
                           (count results)))
        time-otf (/ (/ avg-time avg-otf) 1000)] 
    (pprint results)))