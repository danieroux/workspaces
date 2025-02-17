(ns nubank.workspaces.card-types.test
  (:require
    [cljs.core.async :as async :refer [go chan go-loop put! close! <!]]
    [cljs.reader :refer [read-string]]
    [cljs.test]
    ["react-dom"]
    [clojure.data]
    [com.fulcrologic.fulcro-css.css :as css]
    [com.fulcrologic.fulcro-css.localized-dom :as dom]
    [com.fulcrologic.fulcro.mutations :as fm]
    [com.fulcrologic.fulcro.components :as fp]
    [nubank.workspaces.card-types.fulcro3 :as ct.fulcro]
    [nubank.workspaces.data :as data]
    [nubank.workspaces.model :as wsm]
    [nubank.workspaces.ui :as ui]
    [nubank.workspaces.ui.core :as uc]
    [nubank.workspaces.ui.highlight :as highlight]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]))

(defonce test-context* (atom []))

(defn collect-test [m]
  (let [contexts (if (seq @test-context*)
                   @test-context*
                   (vec (get (cljs.test/get-current-env) :testing-contexts)))]
    (cljs.test/update-current-env! [::summary] (fnil conj [])
      (merge {:testing-contexts contexts} m))))

(defmethod cljs.test/report [::reporter :pass] [m]
  (cljs.test/inc-report-counter! :pass)
  (collect-test m)
  m)

(defmethod cljs.test/report [::reporter :fail] [m]
  (cljs.test/inc-report-counter! :fail)
  (collect-test m)
  m)

(defmethod cljs.test/report [::reporter :error] [m]
  (js/console.log "Error running test" (::test (cljs.test/get-current-env)))
  (js/console.error (:actual m))
  (cljs.test/inc-report-counter! :error)
  (collect-test m)
  m)

; fulcro reports

(defmethod cljs.test/report [::reporter :begin-behavior] [{:keys [string] :as m}]
  (swap! test-context* conj string)
  m)

(defmethod cljs.test/report [::reporter :end-behavior] [m]
  (swap! test-context* pop)
  m)

(defmethod cljs.test/report [::reporter :begin-manual] [{:keys [string] :as m}]
  (swap! test-context* conj string)
  m)

(defmethod cljs.test/report [::reporter :end-manual] [m]
  (swap! test-context* pop)
  m)

(defn now [] (.getTime (js/Date.)))

(defn test-success? [{:keys [report-counters]}]
  (= 0 (:fail report-counters) (:error report-counters)))

(defn results-duration [test-results]
  (transduce (map ::duration) + test-results))

(defn create-test-env [test]
  (assoc (cljs.test/empty-env)
    :reporter ::reporter
    ::test test))

(def single-test-timeout 500)

(defn run-test-blocks* [{::keys [test blocks]}]
  (let [out      (async/promise-chan)
        test-env (create-test-env test)]
    (cljs.test/set-env! test-env)
    (let [tests (conj blocks #(put! out (cljs.test/get-current-env)))]
      (try
        (cljs.test/run-block tests)
        (catch :default e
          (let [m {:type :error :actual e}]
            (cljs.test/report m)
            (put! out (cljs.test/get-current-env))))))
    out))

(defn run-test-blocks [input]
  (go
    (let [start    (now)
          timer    (async/timeout single-test-timeout)
          [result ch] (async/alts! [(run-test-blocks* input) timer])
          duration (- (now) start)]
      (if (not= ch timer)
        (assoc result ::duration duration)
        (do
          (cljs.test/report {:type :error :actual "Tests timed out. Please check Dev Console for Exceptions"})
          (assoc (cljs.test/get-current-env)
            ::duration duration
            :error "Execution timed out!"))))))

(defn test-cards-by-namespace []
  (->> (vals @data/card-definitions*)
    (filterv ::wsm/test?)
    (filterv ::test-forms)
    (sort-by ::wsm/card-id)
    (group-by (comp (fnil symbol '_) namespace ::wsm/card-id))))

(defn namespace-test-cards [ns] (get (test-cards-by-namespace) ns))

(defonce test-channel (chan (async/dropping-buffer 512)))

(defmulti test-runner ::type)

(defmethod test-runner ::test-one [{::keys [app test] :as input}]
  (go
    (fp/transact! app
      [`(fm/set-props {::running?    true
                       ::enqueued?   false
                       :test-results {}})]
      {:ref [::test-result-id "singleton"]})

    (<! (async/timeout 1))
    (ui/refresh-card-container test)
    (<! (async/timeout 1))

    (let [res (<! (run-test-blocks input))]
      (fp/transact! app
        [`(fm/set-props {:test-results ~res
                         ::done?       true
                         ::running?    false})]
        {:ref [::test-result-id "singleton"]})

      (<! (async/timeout 1))
      (ui/refresh-card-container test)
      (app/force-root-render! app)

      res)))

(declare NSTestGroup)

(defn app-test-block [app ns]
  (-> app app/current-state  (get-in [::test-var ns])))

(defn app-ns-test-block [app ns]
  (-> app app/current-state (get-in [::test-ns ns])))

(defn build-ns-test-group [{:keys [app] ::keys [test-ns ns-tests]}]
  (let [current (app-ns-test-block app test-ns)
        blocks  (mapv #(hash-map ::test-var (::wsm/card-id %)
                         :test-results nil
                         ::disabled? (-> (app-test-block app (::wsm/card-id %)) ::disabled?))
                  ns-tests)]
    {::enqueued?  true
     ::running?   false
     ::success?   true
     ::disabled?  (::disabled? current)
     ::collapsed? (if (contains? current ::collapsed?) (::collapsed? current) true)
     ::test-ns    test-ns
     ::test-vars  blocks}))

(fm/defmutation start-ns-test-namespaces [input]
  (action [{:keys [app state ref] :as env}]
    (let [source (get-in @state ref)]
      (merge/merge-component! app NSTestGroup
        (-> (build-ns-test-group (merge env source input))
          (assoc ::running? true ::enqueued? false))))))

(defn update-test-ns-state [test-ns-state state duration]
  (let [{::keys [test-vars]} (fdn/db->tree (fp/get-query NSTestGroup) test-ns-state state)
        success? (->> test-vars
                      (map :test-results)
                      (filter seq)
                      (every? test-success?))]
    (assoc test-ns-state
           ::done? true
           ::running? false
           ::success? success?
           ::duration duration)))

(fm/defmutation set-ns-test-result [{::keys [test-ns duration]}]
  (action [{:keys [state]}]
    (swap! state update-in [::test-ns test-ns] update-test-ns-state @state duration)))

(defn run-ns-test-blocks [{::keys [test-ns app ns-tests]}]
  (go
    (let [start (now)]
      (ui/refresh-card-container test-ns)
      (<! (async/timeout 1))

      (doseq [{::wsm/keys [card-id]
               ::keys     [test-forms]} ns-tests]
        (if-not (::disabled? (app-test-block app card-id))
          (let [res (<! (run-test-blocks {::test   card-id
                                          ::blocks test-forms}))]
            (fp/transact! app
              [`(fm/set-props {:test-results ~res ::duration ~(::duration res)})]
              {:ref [::test-var card-id]}))))

      (let [duration (- (now) start)]
        (fp/transact! app
          [`(set-ns-test-result {::test-ns ~test-ns ::duration ~duration})]
          {:ref [::test-ns test-ns]})))))

(defmethod test-runner ::test-ns [{::keys [test-ns app] :as env}]
  (go
    (let [test-cards (namespace-test-cards test-ns)]
      (fp/transact! app
        [`(start-ns-test-namespaces {::ns-tests ~test-cards})]
        {:ref [::test-ns test-ns]})

      (<! (async/timeout 1))
      (<! (run-ns-test-blocks (assoc env ::ns-tests test-cards)))

      (<! (async/timeout 1))
      (ui/refresh-card-container test-ns)

      (app/force-root-render! app)
      app)))

(declare AllTests)

(fm/defmutation start-all-tests [{::keys [test-namespaces]}]
  (action [{:keys [app] :as env}]
    (let [test-namespaces (->> test-namespaces
                            (into [] (map (fn [[test-ns ns-tests]] (build-ns-test-group (merge env {::test-ns  test-ns
                                                                                                    ::ns-tests ns-tests}))))))]
      (merge/merge-component! app AllTests
        (-> {::enqueued?       false
             ::running?        true
             ::test-namespaces test-namespaces})))))

(defn update-all-tests-state [all-tests-state state duration]
  (let [{::keys [test-namespaces]} (fdn/db->tree (fp/get-query AllTests) all-tests-state state)
        success? (every? ::success? test-namespaces)]
    (assoc all-tests-state
           ::done? true
           ::running? false
           ::success? success?
           ::duration duration)))

(fm/defmutation set-all-tests-result [{::keys [duration]}]
  (action [{:keys [state]}]
    (swap! state update-in [::all-tests-run "singleton"] update-all-tests-state @state duration)))

(defmethod test-runner ::test-all [{::keys [app] :as env}]
  (go
    (let [test-namespaces (sort-by first (test-cards-by-namespace))
          start           (now)]

      (fp/transact! app
        [`(start-all-tests {::test-namespaces ~test-namespaces})]
        {:ref [::all-tests-run "singleton"]})

      (doseq [[test-ns ns-tests] test-namespaces]
        (if-not (::disabled? (app-ns-test-block app test-ns))
          (<! (run-ns-test-blocks (assoc env ::test-ns test-ns ::ns-tests ns-tests)))))

      (let [duration (- (now) start)]
        (fp/transact! app
          [`(set-all-tests-result {::duration ~duration})]
          {:ref [::all-tests-run "singleton"]}))

      (<! (async/timeout 1))
      (ui/refresh-card-container `test-all)

      (app/force-root-render! app)
      app)))

(defn run-test-loop [ch]
  (go
    (loop []
      (when-let [{::keys [done] :as input} (<! ch)]
        (let [result (<! (test-runner input))]
          (put! done (or result {}))
          (cljs.test/clear-env!)
          (<! (async/timeout 1))
          (recur))))))

(defonce test-loop (run-test-loop test-channel))

(fm/defmutation enqueue-test-run [_]
  (action [{:keys [state ref]}]
    (swap! state update-in ref assoc
      ::enqueued? true
      ::done? false)))

(defn run-card-tests! [test app]
  (let [forms (-> (data/card-definition test) ::test-forms)
        out   (async/promise-chan)]
    (fp/transact! app [`(enqueue-test-run {})] {:ref [::test-result-id "singleton"]})

    (put! test-channel {::type   ::test-one
                        ::test   test
                        ::blocks forms
                        ::done   out
                        ::app    app})

    out))

(defn run-ns-tests! [ns app]
  (let [out (async/promise-chan)]
    (fp/transact! app [`(enqueue-test-run {})] {:ref [::test-ns ns]})

    (put! test-channel {::type    ::test-ns
                        ::test-ns ns
                        ::done    out
                        ::app     app})
    out))

(defn run-all-tests! [app]
  (let [out (async/promise-chan)]
    (fp/transact! app [`(enqueue-test-run {})] {:ref [::all-tests-run "singleton"]})

    (put! test-channel {::type ::test-all
                        ::done out
                        ::app  app})
    out))

(defn header-color [{::keys [card]} bg]
  ((::wsm/set-card-header-style card) {:background bg})
  nil)

(fp/defsc TestCSS [_ _]
  {:css [[:.test-result
          {:padding    "3px 6px"
           :margin-top "3px"}]
         [:.test-ns
          {:flex       "1"
           :align-self "flex-start"}]
         [:.test-ns-toolbar
          {:background      "#404040"
           :display         "flex"
           :align-items     "center"
           :justify-content "flex-end"
           :padding         "5px"
           :margin          "-10px -10px 10px"}]
         [:.test-ns-container
          {:margin-bottom "5px"}]
         [:.test-ns-var-header
          {:background  uc/color-dark-grey
           :color       "#fff"
           :font-family "Helvetica"
           :font-size   "14px"
           :padding     "4px 5px"}]
         [:.code
          {:font-family "monospace"
           :white-space "pre"}]]})

(def css (css/get-classnames TestCSS))

(ct.fulcro/add-component-css! TestCSS)

(defn try-pprint [x]
  (try
    (with-out-str (cljs.pprint/pprint x))
    (catch :default _ x)))

(defn print-code [s]
  (highlight/highlight {::highlight/source (try-pprint s)}))

(defn normalize-actual [{:keys [expected actual] :as props}]
  (if (and (sequential? expected)
        (= 3 (count expected))
        (= '= (first expected))
        (sequential? actual))
    (let [[_ expected actual] (second actual)]
      (assoc props
        :expected expected
        :actual actual))
    props))

(fp/defsc TestResult
  [this {:keys [actual expected type testing-contexts message] :as props}]
  {:initial-state (fn [_]
                    {})
   :query         [:actual :expected :type :testing-contexts :message]
   :css           [[:.test-result
                    {:padding    "3px 6px"
                     :margin-top "3px"}]
                   [:.compare-header
                    {:font-family uc/font-helvetica
                     :font-size   "14px"
                     :font-weight "bold"
                     :margin      "10px 0"}]]}
  (let [color (if (= :pass type) uc/color-green-light uc/color-red-dark)]
    (dom/div :.test-result {:style {:borderLeft (str "5px solid " color)}}
      (mapv #(dom/div {:key (str (hash %))} %) testing-contexts)
      (if (and message (not (seq testing-contexts)))
        (dom/div (str message))
        (if (and (= :pass type) (not (seq testing-contexts)))
          (dom/div (pr-str expected))))
      (if (not= :pass type)
        (let [{:keys [expected actual]} (normalize-actual props)
              [extra missing] (clojure.data/diff expected actual)
              error? (instance? js/Error actual)]
          (dom/div :.diff
            (dom/div :.compare-header "Expected")
            (print-code expected)
            (dom/div :.compare-header "Actual")
            (print-code actual)
            (if (not error?)
              (dom/div
                (if extra (dom/div :.compare-header "Diff extra"))
                (if extra (print-code extra))
                (if missing (dom/div :.compare-header "Diff missing"))
                (if missing (print-code missing))))))))))

(def test-result (fp/factory TestResult {:keyfn (comp hash (juxt :expected :actual :type :testing-contexts :message))}))

(fp/defsc SingleTest
  [this {::keys [enqueued? running?]
         :keys  [test-results]}]
  {:initial-state (fn [_]
                    {::test-result-id (random-uuid)})
   :ident         (fn [] [::test-result-id "singleton"])
   :query         [::enqueued? ::running? ::done?
                   {:test-results
                    [:report-counters
                     {::summary (fp/get-query TestResult)}]}]
   :css           []}
  (let [{::keys [summary]} test-results
        header-color #(header-color (fp/shared this) %)]
    (dom/div
      (cond
        enqueued?
        (do
          (header-color uc/color-yellow)
          "Waiting to run...")

        running?
        (do
          (header-color uc/color-yellow)
          "Running...")

        (test-success? test-results)
        (do
          (header-color uc/color-mint-green)
          (mapv test-result summary))

        :else
        (do
          (header-color uc/color-red-dark)
          (mapv test-result summary))))))

(defn test-card-init [card test]
  (let [{::ct.fulcro/keys [app]
         :as              card}
        (ct.fulcro/fulcro-card-init card
          {::ct.fulcro/root SingleTest
           ::ct.fulcro/app  {:shared
                             {::card card}

                             :client-did-mount
                             (fn [app]
                               (run-card-tests! test app))}})

        run-tests
        #(run-card-tests! test app)]

    (assoc card
      ::wsm/refresh (fn [_] (run-tests))
      ::wsm/render-toolbar (fn []
                             (let [state (app/current-state app)

                                   {::keys [running? done?]
                                    :keys  [test-results]}
                                   (get-in state [::test-result-id "singleton"])]
                               (dom/div {:style {:flex       "1"
                                                 :display    "flex"
                                                 :alignItems "center"}}
                                 (cond
                                   running?
                                   (dom/div {:style {:fontSize "12px"}} "Running...")

                                   done?
                                   (dom/div {:style {:fontSize "12px"}}
                                     "Finished in " (::duration test-results) "ms"))
                                 (dom/div {:style {:flex "1"}})
                                 (uc/button {:onClick run-tests} "Rerun tests")))))))

(defn test-card [card-id forms]
  {::test-forms forms
   ::wsm/align  ::wsm/align-top-flex
   ::wsm/test?  true
   ::wsm/init   #(test-card-init % card-id)})

(fp/defsc VarTestBlock
  [this {:keys  [test-results]
         ::keys [test-var disabled?]}]
  {:initial-state (fn [_]
                    {})
   :ident         [::test-var ::test-var]
   :query         [::test-var ::disabled? ::success? ::duration
                   {:test-results
                    [:report-counters
                     {::summary (fp/get-query TestResult)}]}]
   :css           [[:.test-var-container
                    {:margin-bottom "5px"}]
                   [:.test-var-header
                    {:background  uc/color-dark-grey
                     :color       "#fff"
                     :font-family "Helvetica"
                     :font-size   "14px"
                     :padding     "4px 5px"
                     :display     "flex"}]
                   [:.disabled {:text-decoration "line-through"}]
                   [:.title {:flex "1"}]]
   :css-include   [TestResult]}
  (let [bg-color (cond
                   disabled?
                   uc/color-light-grey

                   (seq test-results)
                   (if (test-success? test-results)
                     uc/color-green-light
                     uc/color-red-dark)

                   :else
                   uc/color-yellow)]
    (dom/div :.test-var-container
      (dom/div :.test-var-header
        {:style   {:borderLeft (str "5px solid " bg-color)}
         :classes [(if disabled? :.disabled)]}
        (dom/div :.title (name test-var))
        (dom/div (dom/input {:type     "checkbox"
                             :checked  (not disabled?)
                             :onChange #(fm/toggle! this ::disabled?)})))
      (mapv test-result (::summary test-results)))))

(def var-test-block (fp/factory VarTestBlock {:keyfn ::test-var}))

(defn runnable-status-color [{::keys [disabled? done? running? enqueued? success?]}]
  (cond
    disabled?
    uc/color-light-grey

    done?
    (if success? uc/color-mint-green uc/color-red-dark)

    running?
    uc/color-yellow

    enqueued?
    uc/color-yellow))

(fp/defsc NSTestGroup
  [this {::keys [test-vars] :as props}]
  {:initial-state (fn [ns]
                    {::enqueued? false
                     ::running?  false
                     ::test-ns   ns
                     ::test-vars []})
   :ident         [::test-ns ::test-ns]
   :query         [::test-ns ::enqueued? ::running? ::success? ::done? :report-counters
                   ::duration
                   {::test-vars (fp/get-query VarTestBlock)}]
   :css           [[:.test-ns
                    {:flex       "1"
                     :align-self "flex-start"}]]}
  (let [header-color #(header-color (fp/shared this) %)]
    (dom/div :.test-ns
      (header-color (runnable-status-color props))
      (mapv var-test-block test-vars))))

(fp/defsc AllTestNSTestGroup
  [this {::keys [test-ns test-vars collapsed? disabled?] :as props}
   {::keys [set-header?]
    :or    {set-header? true}}]
  {:initial-state (fn [ns]
                    {::enqueued?  false
                     ::running?   false
                     ::collapsed? true
                     ::disabled?  false
                     ::test-ns    ns
                     ::test-vars  []})
   :ident         [::test-ns ::test-ns]
   :query         [::test-ns ::enqueued? ::running? ::success? ::done? :report-counters
                   ::duration ::collapsed? ::disabled?
                   {::test-vars (fp/get-query VarTestBlock)}]
   :css           [[:.test-ns
                    {:flex       "1"
                     :align-self "flex-start"}]
                   [:.test-ns-header
                    {:background    "#404040"
                     :color         "#fff"
                     :font-family   "Helvetica"
                     :font-size     "16px"
                     :padding       "4px 5px"
                     :display       "flex"
                     :margin-bottom "3px"}]
                   [:.disabled {:text-decoration "line-through"}]
                   [:.status
                    {:cursor "pointer"
                     :margin "-4px 6px -4px -5px"
                     :width  "20px"}]
                   [:.title {:flex "1"}]]}
  (dom/div :.test-ns
    (dom/div :.test-ns-header {:classes [(if disabled? :.disabled)]}
      (dom/div :.status {:style   {:backgroundColor (runnable-status-color props)}
                         :onClick #(fm/toggle! this ::collapsed?)})
      (dom/div :.title (str test-ns))
      (dom/div (dom/input {:type     "checkbox"
                           :checked  (not disabled?)
                           :onChange #(fm/toggle! this ::disabled?)})))
    (if-not collapsed?
      (mapv var-test-block test-vars))))

(def all-test-ns-test-group (fp/factory AllTestNSTestGroup {:keyfn ::test-ns}))

(fp/defsc AllTests
  [this {::keys [test-namespaces enqueued? running? done? success?]}]
  {:initial-state (fn [_]
                    {::enqueued?       false
                     ::running?        false
                     ::test-namespaces []})
   :ident         (fn [] [::all-tests-run "singleton"])
   :query         [::enqueued? ::running? ::done? :report-counters ::success? ::duration

                   {::test-namespaces (fp/get-query AllTestNSTestGroup)}]
   :css           [[:.test-ns
                    {:flex       "1"
                     :align-self "flex-start"}]]}
  (let [header-color #(header-color (fp/shared this) %)]
    (dom/div :.test-ns
      (cond
        done?
        (header-color (if success? uc/color-mint-green uc/color-red-dark))

        running?
        (header-color uc/color-yellow)

        enqueued?
        (header-color uc/color-yellow))

      (mapv all-test-ns-test-group test-namespaces))))

(ct.fulcro/add-component-css! AllTests)

(defn test-ns-card-init [card test-ns]
  (let [{::ct.fulcro/keys [app]
         :as              card}
        (ct.fulcro/fulcro-card-init card
          {::ct.fulcro/root          NSTestGroup
           ::ct.fulcro/initial-state test-ns
           ::ct.fulcro/app           {:shared
                                      {::card card}

                                      :client-did-mount
                                      (fn [app]
                                        (run-ns-tests! test-ns app))}})

        run-tests
        #(run-ns-tests! test-ns app)]

    (assoc card
      ::wsm/refresh (fn [_] (run-tests))
      ::wsm/render-toolbar (fn []
                             (let [state (app/current-state app)

                                   {::keys [running? done? duration]}
                                   (get-in state [::test-ns test-ns])]
                               (dom/div {:style {:flex       "1"
                                                 :display    "flex"
                                                 :alignItems "center"}}
                                 (cond
                                   running?
                                   (dom/div {:style {:fontSize "12px"}} "Running...")

                                   done?
                                   (dom/div {:style {:fontSize "12px"}}
                                     "Finished in " duration "ms"))
                                 (dom/div {:style {:flex "1"}})
                                 (uc/button {:onClick run-tests} "Rerun tests")))))))

(defn test-ns-card [test-ns]
  {::wsm/test?          true
   ::wsm/card-unlisted? true
   ::wsm/align          ::wsm/align-top-flex
   ::wsm/init           #(test-ns-card-init % test-ns)
   ::wsm/card-width     4
   ::wsm/card-height    15})

(defn all-tests-card-init [card]
  (let [{::ct.fulcro/keys [app]
         :as              card}
        (ct.fulcro/fulcro-card-init card
          {::ct.fulcro/root AllTests
           ::ct.fulcro/app  {:shared
                             {::card card}

                             :client-did-mount
                             (fn [app]
                               (run-all-tests! app))}})

        run-tests
        #(run-all-tests! app)]

    (assoc card
      ::wsm/refresh (fn [_] (run-tests))
      ::wsm/render-toolbar (fn []
                             (let [state (app/current-state app)
                                   {::keys [running? done? duration]}
                                   (get-in state [::all-tests-run "singleton"])]
                               (dom/div {:style {:flex       "1"
                                                 :display    "flex"
                                                 :alignItems "center"}}
                                 (cond
                                   running?
                                   (dom/div {:style {:fontSize "12px"}} "Running...")

                                   done?
                                   (dom/div {:style {:fontSize "12px"}}
                                     "Finished in " duration "ms"))
                                 (dom/div {:style {:flex "1"}})
                                 (uc/button {:onClick run-tests} "Rerun tests")))))))

(defn all-tests-card []
  {::wsm/test?          true
   ::wsm/card-unlisted? true
   ::wsm/align          ::wsm/align-top-flex
   ::wsm/init           all-tests-card-init
   ::wsm/card-width     4
   ::wsm/card-height    15})
