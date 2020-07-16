(ns metabase.api.setup-test
  "Tests for /api/setup endpoints."
  (:require [clojure.test :refer :all]
            [medley.core :as m]
            [metabase
             [email :as email]
             [http-client :as http]
             [public-settings :as public-settings]
             [setup :as setup]
             [test :as mt]
             [util :as u]]
            [metabase.api.setup :as setup-api]
            [metabase.integrations.slack :as slack]
            [metabase.middleware.session :as mw.session]
            [metabase.models
             [database :refer [Database]]
             [setting :as setting]
             [user :refer [User]]]
            [metabase.models.setting.cache-test :as setting.cache-test]
            [metabase.test.fixtures :as fixtures]
            [schema.core :as s]
            [toucan.db :as db]))

;; make sure the default test users are created before running these tests, otherwise we're going to run into issues
;; if it attempts to delete this user and it is the only admin test user
(use-fixtures :once (fixtures/initialize :test-users))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                  POST /setup                                                   |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- do-with-setup* [request-body thunk]
  (try
    (mt/discard-setting-changes [site-name site-locale anon-tracking-enabled admin-email]
      (thunk))
    (finally
      (db/delete! User :email (get-in request-body [:user :email]))
      (when-let [db-name (get-in request-body [:database :name])]
        (db/delete! Database :name db-name)))))

(defn- do-with-setup [request-body thunk]
  (let [request-body (merge-with merge
                       {:token (setup/create-token!)
                        :prefs {:site_name "Metabase Test"}
                        :user  {:first_name (mt/random-name)
                                :last_name  (mt/random-name)
                                :email      (mt/random-email)
                                :password   "anythingUP12!!"}}
                       request-body)]
    (do-with-setup*
     request-body
     (fn []
       (testing "API response should return a Session UUID"
         (is (schema= {:id (s/pred mt/is-uuid-string? "UUID string")}
                      (http/client :post 200 "setup" request-body))))
       ;; reset our setup token
       (setup/create-token!)
       (thunk)))))

(defmacro ^:private with-setup [request-body & body]
  `(do-with-setup ~request-body (fn [] ~@body)))

(deftest create-superuser-test
  (testing "POST /api/setup"
    (testing "Check that we can create a new superuser via setup-token"
      (let [email (mt/random-email)]
        (with-setup {:user {:email email}}
          (testing "new User should be created"
            (is (db/exists? User :email email)))
          (testing "Creating a new admin user should set the `admin-email` Setting"
            (is (= email
                   (public-settings/admin-email))))

          (testing "Should record :user-joined Activity (#12933)"
            (let [user-id         (u/get-id (db/select-one-id User :email email))
                  ;; recording the Activity entries happens asynchronously. So if they're not already there then we
                  ;; can wait a few milliseconds and try again. Usually this is pretty much instantaneous but with CI
                  ;; being slow it's probably best to be robust and keep trying up to 250ms until they show up.
                  wait-for-result (fn [thunk]
                                    (loop [tries 5]
                                      (or (thunk)
                                          (when (pos? tries)
                                            (Thread/sleep 50)
                                            (recur (dec tries))))))]
              (is (schema= {:topic    (s/eq :user-joined)
                            :model_id (s/eq user-id)
                            :user_id  (s/eq user-id)
                            :model    (s/eq "user")
                            s/Keyword s/Any}
                           (wait-for-result #(db/select-one 'Activity :topic "user-joined", :user_id user-id)))))))))))

(deftest setup-settings-test
  (testing "POST /api/setup"
    (testing "check that we can set various Settings during setup"
      (doseq [[setting-name {:keys [k vs]}] {:site-name
                                             {:k  "site_name"
                                              :vs {"Cam's Metabase" "Cam's Metabase"}}

                                             :anon-tracking-enabled
                                             {:k  "allow_tracking"
                                              :vs {"TRUE"  true
                                                   "true"  true
                                                   true    true
                                                   nil     true
                                                   "FALSE" false
                                                   "false" false
                                                   false   false}}

                                             :site-locale
                                             {:k  "site_locale"
                                              :vs {nil     "en" ; `en` is the default
                                                   "es"    "es"
                                                   "ES"    "es"
                                                   "es-mx" "es_MX"
                                                   "es_MX" "es_MX"}}}
              [v expected] vs]
        (testing (format "Set Setting %s to %s" (pr-str setting-name) (pr-str v))
          (with-setup {:prefs {k v}}
            (testing "should be set"
              (is (= expected
                     (setting/get setting-name))))))))))

(deftest create-database-test
  (testing "POST /api/setup"
    (testing "Check that we can Create a Database when we set up MB (#10135)"
      (doseq [[k {:keys [default]}] {:is_on_demand     {:default false}
                                     :is_full_sync     {:default true}
                                     :auto_run_queries {:default true}}
              v                     [true false nil]]
        (let [db-name (mt/random-name)]
          (with-setup {:database {:engine  "h2"
                                  :name    db-name
                                  :details {:db  "file:/home/hansen/Downloads/Metabase/longnames.db",
                                            :ssl true}
                                  k        v}}
            (testing "Database should be created"
              (is (= true
                     (db/exists? Database :name db-name))))
            (testing (format "should be able to set %s to %s (default: %s) during creation" k (pr-str v) default)
              (is (= (if (some? v) v default)
                     (db/select-one-field k Database :name db-name))))))))))

(defn- setup! [f & args]
  (let [body {:token (setup/create-token!)
              :prefs {:site_name "Metabase Test"}
              :user  {:first_name (mt/random-name)
                      :last_name  (mt/random-name)
                      :email      (mt/random-email)
                      :password   "anythingUP12!!"}}
        body (apply f body args)]
    (do-with-setup* body #(http/client :post 400 "setup" body))))

(deftest setup-validation-test
  (testing "POST /api/setup validation"
    (testing ":token"
      (testing "missing"
        (is (= {:errors {:token "Token does not match the setup token."}}
               (setup! dissoc :token))))

      (testing "incorrect"
        (is (= {:errors {:token "Token does not match the setup token."}}
               (setup! assoc :token "foobar")))))

    (testing "site name"
      (is (= {:errors {:site_name "value must be a non-blank string."}}
             (setup! m/dissoc-in [:prefs :site_name]))))

    (testing "site locale"
      (testing "invalid format"
        (is (schema= {:errors {:site_locale #".*must be a valid two-letter ISO language or language-country code.*"}}
                     (setup! assoc-in [:prefs :site_locale] "eng-USA"))))
      (testing "non-existent locale"
        (is (schema= {:errors {:site_locale #".*must be a valid two-letter ISO language or language-country code.*"}}
                     (setup! assoc-in [:prefs :site_locale] "en-EN")))))

    (testing "user"
      (testing "first name"
        (is (= {:errors {:first_name "value must be a non-blank string."}}
               (setup! m/dissoc-in [:user :first_name]))))

      (testing "last name"
        (is (= {:errors {:last_name "value must be a non-blank string."}}
               (setup! m/dissoc-in [:user :last_name]))))

      (testing "email"
        (testing "missing"
          (is (= {:errors {:email "value must be a valid email address."}}
                 (setup! m/dissoc-in [:user :email]))))

        (testing "invalid"
          (is (= {:errors {:email "value must be a valid email address."}}
                 (setup! assoc-in [:user :email] "anything")))))

      (testing "password"
        (testing "missing"
          (is (= {:errors {:password "Insufficient password strength"}}
                 (setup! m/dissoc-in [:user :password]))))

        (testing "invalid"
          (is (= {:errors {:password "Insufficient password strength"}}
                 (setup! assoc-in [:user :password] "anything"))))))))

(deftest setup-with-empty-cache-test
  (testing "POST /api/setup"
    ;; I have seen this fail randomly, no idea why
    (testing "Make sure setup completes successfully if Settings cache needs to be restored"
      (setting.cache-test/reset-last-update-check!)
      (setting.cache-test/clear-cache!)
      (let [db-name (mt/random-name)]
        (with-setup {:database {:engine "h2", :name db-name}}
          (is (db/exists? Database :name db-name)))))))

(deftest transaction-test
  (testing "POST /api/setup/"
    (testing "should run in a transaction -- if something fails, all changes should be rolled back"
      (let [user-email  (mt/random-email)
            setup-token (setup/create-token!)
            site-name   (mt/random-name)
            db-name     (mt/random-name)
            body        {:token    setup-token
                         :prefs    {:site_locale "es_MX"
                                    :site_name   site-name}
                         :database {:engine "h2"
                                    :name   db-name}
                         :user     {:first_name (mt/random-name)
                                    :last_name  (mt/random-name)
                                    :email      user-email
                                    :password   "p@ssw0rd"}}]
        (do-with-setup*
         body
         (fn []
           (is (schema= {:message (s/eq "Oops!"), s/Keyword s/Any}
                        (with-redefs [mw.session/set-session-cookie (fn [& _] (throw (ex-info "Oops!" {})))]
                          (http/client :post 500 "setup" body))))
           (testing "New user shouldn't exist"
             (is (= false
                    (db/exists? User :email user-email))))
           (testing "New DB shouldn't exist"
             ;; TODO -- we should also be deleting relevant sync tasks for the DB, but this doesn't matter too much
             ;; for right now.
             (is (= false
                    (db/exists? Database :engine "h2", :name db-name))))
           (testing "Settings should not be changed"
             (is (not= site-name
                       (public-settings/site-name)))
             (is (= "en"
                    (public-settings/site-locale))))
           (testing "Setup token should still be set"
             (is (= setup-token
                    (setup/setup-token))))))))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                            POST /api/setup/validate                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

(deftest validate-setup-test
  (testing "POST /api/setup/validate"
    (is (= {:errors {:token "Token does not match the setup token."}}
           (http/client :post 400 "setup/validate" {})))
    (is (= {:errors {:token "Token does not match the setup token."}}
           (http/client :post 400 "setup/validate" {:token "foobar"})))
    ;; make sure we have a valid setup token
    (setup/create-token!)
    (is (= {:errors {:engine "value must be a valid database engine."}}
           (http/client :post 400 "setup/validate" {:token (setup/setup-token)})))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         GET /api/setup/admin_checklist                                         |
;;; +----------------------------------------------------------------------------------------------------------------+

;; basic sanity check
(deftest admin-checklist-test
  (with-redefs [db/exists?              (constantly true)
                db/count                (constantly 5)
                email/email-configured? (constantly true)
                slack/slack-configured? (constantly false)]
    (is (= [{:name  "Get connected"
             :tasks [{:title        "Add a database"
                      :completed    true
                      :triggered    true
                      :is_next_step false}
                     {:title        "Set up email"
                      :completed    true
                      :triggered    true
                      :is_next_step false}
                     {:title        "Set Slack credentials"
                      :completed    false
                      :triggered    true
                      :is_next_step true}
                     {:title        "Invite team members"
                      :completed    true
                      :triggered    true
                      :is_next_step false}]}
            {:name  "Curate your data"
             :tasks [{:title        "Hide irrelevant tables"
                      :completed    true
                      :triggered    false
                      :is_next_step false}
                     {:title        "Organize questions"
                      :completed    true
                      :triggered    false
                      :is_next_step false}
                     {:title        "Create metrics"
                      :completed    true
                      :triggered    false
                      :is_next_step false}
                     {:title        "Create segments"
                      :completed    true
                      :triggered    false
                      :is_next_step false}]}]
           (for [{group-name :name, tasks :tasks} (#'setup-api/admin-checklist)]
             {:name  (str group-name)
              :tasks (for [task tasks]
                       (-> (select-keys task [:title :completed :triggered :is_next_step])
                           (update :title str)))})))))
