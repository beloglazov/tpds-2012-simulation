(ns simulation.algorithms.markov
  (:use simulation.core
        simulation.workload-generator
        clj-predicates.core
        clojure.math.numeric-tower
        clojure.pprint)
  (:require [simulation.algorithms.markov.l-2-states :as l2]
            [simulation.algorithms.markov.l-3-states :as l3]
            [simulation.algorithms.markov.bruteforce :as bruteforce]
            [simulation.math :as math])
  (:import [flanagan.analysis Regression Stat]))

(defn utilization-to-state
  "Returns the state corresponding to the utilization and state config"
  [state-config utilization]
  {:pre [(coll? state-config)
         (not-negnum? utilization)]
   :post [(not-negnum? %)]}
  (loop [x (first state-config)
         xs (rest state-config)
         state 0
         prev -1]
    (if (or (nil? x) 
            (and (> utilization prev) (<= utilization x)))
      state
      (recur (first xs) (rest xs) (inc state) x))))

(defn utilization-to-states
  "Returns the state history corresponding to the utilization history and state config.
   Adds the 0 state to the beginning to simulate the first transition."
  [state-config utilization]
  {:pre [(coll? state-config)
         (coll? utilization)]
   :post [(coll? %)]}
  (conj (map (partial utilization-to-state state-config) utilization) 0))

(defn transition-counts
  "Returns a matrix containing numbers of transitions between states"
  [state-config state-history]
  {:pre [(coll? state-config)
         (coll? state-history)]
   :post [(coll? %)]}  
  (let [state-count (inc (count state-config))
        transitions (partition 2 1 state-history)]
    (map (fn [i] (map 
                   #(count (filter #{[i %]} transitions)) 
                   (range state-count))) 
         (range state-count))))

(defn transition-rates
  "Returns a matrix of transition rates corresponding to the utilization history"
  [state-config utilization]
  {:pre [(coll? state-config)
         (coll? utilization)]
   :post [(coll? %)]}
  (let [steps (dec (count utilization))
        states (utilization-to-states state-config utilization)
        state-count (inc (count state-config))
        transitions (partition 2 1 states)]
    (map (fn [i] (map 
                   #(double (/ (count (filter #{[i %]} transitions)) steps)) 
                   (range state-count))) 
         (range state-count))))

(defn build-state-vector
  "Returns the current state PMF corresponding to the utilization history and state config"
  [state-config utilization]
  {:pre [(coll? state-config)
         (coll? utilization)]
   :post [(coll? %)]}
  (let [state (utilization-to-state state-config (last utilization))]
    (map #(if (= state %) 1 0) 
         (range (inc (count state-config))))))

(defn build-q
  "Returns an infinisemal transition rate matrix corresponding to the utilization history"
  [state-config utilization]
  {:pre [(coll? state-config)
         (coll? utilization)]
   :post [(coll? %)]}
  (let [transition-rates (transition-rates state-config utilization)
        state-count (inc (count state-config))]
    (map (fn [row i]
           (let [row-vec (vec row)]
             (assoc row-vec i (- (- (apply + row) (get row-vec i)))))) 
         transition-rates (range state-count))))

(defn build-p
  "Returns a matrix of transition probabilities built from the utilization history"
  [state-config utilization]
  {:pre [(coll? state-config)
         (coll? utilization)]
   :post [(coll? %)]}
  (let [transitions (transition-counts state-config utilization)
        row-sums (map (partial apply +) transitions)]
    (map (fn [row row-sum]
           (map #(/ % row-sum) row))
         transitions row-sums)))

(defn substitute-m-in-q
  "Returns a complete matrix of transition rates after the substition of the m vector"
  [q m]
  {:pre [(coll? q)
         (coll? m)]
   :post [(coll? %)]}
  (let [cnt (count m)] 
    (conj (vec 
            (for [i (range cnt)
                  :let [row (vec (get (vec q) i))
                        m-val (get (vec m) i)
                        old-x (get row i)
                        new-x(- old-x m-val)]]
              (conj (assoc row i new-x) m-val)))
          (vec (repeat (inc cnt) 0.0)))))

(defn max-abs-rate
  "Returns the maximum absolute transition rate from a specified matrix"
  [q]
  {:pre [(coll? q)]
   :post [(not-negnum? %)]}
  (apply max 
         (map #(apply max (map abs %)) q)))

(defn p-to-q
  "Transforms a matrix of transition probabilities into the corresponding matrix of transition rates"
  [p]
  {:pre [(coll? p)]
   :post [(coll? %)]}
  (map (fn [row i]
           (let [row-vec (vec row)]
             (assoc row-vec i (dec (get row-vec i))))) 
         p (range (count p))))

(defn q-to-p
  "Transforms a matrix of transition rates into the corresponding matrix of transition probabilities"
  [q]
  {:pre [(coll? q)]
   :post [(coll? %)]}
  (let [max-rate (inc (max-abs-rate q))] ;(inc (Math/ceil (max-abs-rate q))) 
    (conj (vec 
            (map (fn [i row]
                   (let [updated-row (vec (map #(/ % max-rate) row))] 
                     (assoc updated-row
                            i (inc (get updated-row i))))) 
                 (range (dec (count q)))
                 q))
          (last q))))

(defn c-to-p
  "Transforms a matrix of transition counts and an m column to the corresponding matrix of transition probabilities"
  [c m]
  {:pre [(coll? c)
         (coll? m)]
   :post [(coll? %)]}
  (conj (vec (map (fn [r mi] 
                    (let [row (conj (vec r) mi)
                          sum (apply + row)] 
                      (if (= 0 sum)
                        row
                        (map #(/ % sum) row)))) 
                  c m))
        (repeat (inc (count c)) 0)))

(defn p-to-policy
  "Extracts the migration probabilities from a matrix of transition probabilities"
  [p]
  {:pre [(coll? p)]
   :post [(coll? %)]}
  (map last (butlast p)))

(defn solution-to-policy
  "Transforms transition rates to probabilities according to the time for which the rates have been calculated"
  [utilization m]
  {:pre [(coll? utilization)
         (coll? m)]
   :post [(coll? %)]}
  (let [n (dec (count utilization))]
    (map #(/ % n) m)))

(defn current-state
  "Returns the current state index corresponding to the state probability vector"
  [state-vector]
  {:pre [(coll? state-vector)]
   :post [(not-negnum? %)]}
  (loop [i 0]
    (if (= 1 (nth state-vector i))
      i
      (recur (inc i)))))

(defn issue-command
  "Issues a migration command according to the policy PMF p and state probability vector"
  [p state-vector]
  {:pre [(coll? p)
         (coll? state-vector)]
   :post [(boolean? %)]}
  (if (< (rand) 
         (do
           (prn "++")
           (pprint p) 
           (pprint state-vector)
           (pprint (current-state state-vector))
           (prn "--")
           (get (vec p) (current-state state-vector))))
    true
    false))

(defn time-in-state-n
  "Return the number of times the system stayed in the state n"
  [state-config state-history]
  {:pre [(coll? state-config)
         (coll? state-history)]
   :post [(not-negnum? %)]}
  (let [n (count state-config)]
    (count (filter #{n} state-history))))

(defn in-state-n?
  "Returns true of the system is currently in the state n, false otherwise"
  [state-vector]
  {:pre [(coll? state-vector)]
   :post [(boolean? %)]}
  (let [n (dec (count state-vector))] 
    (= 1 (nth state-vector n))))

(defn markov-optimal [workloads step otf state-config time-step migration-time host vms]
  {:pre [(coll? workloads)
         (posnum? step)
         (posnum? otf)
         (coll? state-config)
         (not-negnum? time-step)
         (not-negnum? migration-time)
         (map? host) 
         (coll? vms)]
   :post [(boolean? %)]}
  (let [utilization (host-utilization-history host vms)
        total-time (count utilization)]
    (let [state-vector (build-state-vector state-config utilization)
          state-history (utilization-to-states state-config utilization)
          time-in-state-n (time-in-state-n state-config state-history)
          p (:transitions (get-workload workloads total-time))
          ls (if (= 1 (count state-config))
               l2/ls
               l3/ls)]
      (if (every? #{0} (nth p (current-state state-vector)))
        false
        (let [policy (bruteforce/optimize step otf (/ migration-time time-step) ls p state-vector total-time time-in-state-n)
              command (issue-command policy state-vector)]
          (do 
            (prn "---------")
            (pprint total-time)
            (pprint p)
            (pprint policy)
            (pprint command)
            command))))))

(defn markov [step otf state-config time-step migration-time host vms]
  {:pre [(posnum? step)
         (posnum? otf)
         (coll? state-config)
         (not-negnum? time-step)
         (not-negnum? migration-time)
         (map? host) 
         (coll? vms)]
   :post [(boolean? %)]}
  (let [utilization (host-utilization-history host vms)
        time-in-states (count utilization)]
    (if (> time-in-states 29)
      (let [state-vector (build-state-vector state-config utilization)
            state-history (utilization-to-states state-config utilization)
            time-in-state-n (time-in-state-n state-config state-history)
            c (transition-counts state-config (take-last 30 state-history))
            ls (if (= 1 (count state-config))
                 l2/ls
                 l3/ls)]
        (if (every? #{0} (nth c (current-state state-vector)))
          false
          (let [solution (bruteforce/optimize step otf (/ migration-time time-step) ls c state-vector time-in-states time-in-state-n)
                p (c-to-p c solution)
                policy (p-to-policy p)
                command (issue-command policy state-vector)]
            command)))
      false)))












