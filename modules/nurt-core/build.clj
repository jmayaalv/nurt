(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'io.github.jmayaalv/nurt-core)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(def pom-data
  [[:description "Core event bus and interceptor pipeline"]
   [:url "https://github.com/jmayaalv/nurt"]
   [:licenses
    [:license
     [:name "MIT License"]
     [:url "https://opensource.org/licenses/MIT"]]]
   [:scm
    [:url "https://github.com/jmayaalv/nurt"]
    [:connection "scm:git:git://github.com/jmayaalv/nurt.git"]
    [:developerConnection "scm:git:ssh://git@github.com/jmayaalv/nurt.git"]
    [:tag (str "v" version)]]])

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar
  ([opts] (jar opts @basis))
  ([opts basis]
   (b/write-pom {:class-dir class-dir
                 :lib       lib
                 :version   version
                 :basis     basis
                 :src-dirs  ["src"]
                 :pom-data  pom-data})
   (b/copy-dir {:src-dirs   ["src"]
                :target-dir class-dir})
   (b/jar {:class-dir class-dir
           :jar-file  jar-file})
   opts))

(defn install [opts]
  (clean nil)
  (jar opts)
  (b/install {:basis     @basis
              :lib       lib
              :version   version
              :jar-file  jar-file
              :class-dir class-dir})
  opts)

(defn deploy [opts]
  (clean nil)
  (jar opts @basis)
  (dd/deploy {:installer :remote
              :artifact  jar-file
              :pom-file  (b/pom-path {:lib lib :class-dir class-dir})})
  opts)
