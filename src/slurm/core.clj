(ns slurm.core
  (:gen-class)
  (:require [clojure.contrib.sql :as sql]
	    [clojure.contrib.string :as str-tools])
  (:use [slurm.error]
	[clojure.contrib.error-kit]
	[slurm.util]))

;; DB Access Objects
(defprotocol IDBInfo
  "General info on the DBConnection, common data requests on schema and objects"
  (get-table-primary-key      [#^DBConnection db-connection table-name])
  (get-table-primary-key-type [#^DBConnection db-connection table-name])
  (get-table-one-relations    [#^DBConnection db-connection table-name])
  (get-table-many-relations   [#^DBConnection db-connection table-name])
  (get-field-load             [#^DBConnection db-connection table-name field-name]))

;; TODO: fill these in, will save tonnes of boilerplate!
(defrecord DBConnection [spec schema load-graph]
  IDBInfo
  (get-table-primary-key      [_ table-name] ())
  (get-table-primary-key-type [_ table-name] ())
  (get-table-one-relations    [_ table-name] ())
  (get-table-many-relations   [_ table-name] ())
  (get-field-load             [_ table-name field-name] ()))

(defprotocol IDBField
  "Simple accessor for DBObjects, returns a column or relation object (loads if applicable/needed), and manages the access graph"
  (field [#^DBObject object field]))

(defrecord DBObject [table-name primary-key columns]
  IDBField
  (field [_ column-name] (get columns column-name))) ;; TODO: lazies should return a clause, then load and return result

(defrecord DBConstruct [table-name columns])

(defrecord DBClause [table-name column-name operator value])

;; Record Operations
;; TODO: recursively insert relations, adding the returned DBObject to the parent relation key
(defn- insert-record [db-connection-spec table-name record]
  (sql/with-connection db-connection-spec
    (sql/transaction
     (sql/insert-records
      table-name
      record)
     (let [request "SELECT LAST_INSERT_ID()"] ;; NOTE: this should return independently on each connection, races shouldn't be an issue (should verify this)
       (sql/with-query-results query-results
	 [request]
	 (DBObject. (keyword table-name) (first (apply vals query-results)) (into {} record))))))) ;; TODO: kill PK in column struct

(defn- select-record [db-connection-spec table-name table-primary-key column-name column-type operator value]
  (let [table-name (name table-name)
	table-primary-key (name table-primary-key)
	column-name (name column-name)
	column-type (name column-type) ;; NOTE: Column type matters when querying strings (must be escaped) and for
	                               ;;       some advanced queries (eg. between clauses) which shouldn't be escaped.
	operator (name (or operator :=))
	column-type-is-string (str-tools/substring? "varchar" (str-tools/lower-case column-type))
	escaped-value (if column-type-is-string
			(str "\"" value "\"")
			value)
	request (str "SELECT * FROM " table-name " WHERE " column-name " " operator " " escaped-value)]
    (do
      (sql/with-connection db-connection-spec
	(sql/with-query-results query-results
	  [request]
	  (doall (for [result query-results]
		   (let [primary-key (get (keyword table-primary-key) result "NULL") ;; TODO: fire off a warning on no PK
			 columns (dissoc (into {} result) (keyword table-primary-key))]
		     (DBObject. (keyword table-name) primary-key columns)))))))))

(defn- update-record [db-connection-spec table-name primary-key primary-key-type primary-key-value columns]
  (let [table-name (name table-name)
	primary-key (name primary-key)
	primary-key-type (name primary-key-type)
	primary-key-type-is-string (str-tools/substring? "varchar" (str-tools/lower-case primary-key-type))
	escaped-primary-key-value (if primary-key-type-is-string
				    (str "\"" primary-key-value "\"")
				    primary-key-value)
	escaped-column-values (for [[column-name column-value] columns]
				(if (string? column-value) ;; TODO: make this more rigorous by comparing type with schema instead of value
				  (str (name column-name) " = \"" column-value "\"")
				  (str (name column-name) " = " column-value)))
	formatted-columns (apply str (interpose ", " escaped-column-values))
	request (str "UPDATE " table-name " SET " formatted-columns " WHERE " primary-key " = " escaped-primary-key-value)]
    (sql/with-connection db-connection-spec
      (sql/do-commands request)))) ;; TODO: create a transaction and add hierarchy of changes to include relations (nb. nested transactions escape up)

;; TODO: need manual cleanup of relation tables for MyISAM (foreign constraints should kick in for InnoDB)
(defn- delete-record [db-connection-spec table-name primary-key primary-key-type primary-key-value]
  (let [table-name (name table-name)
	primary-key (name primary-key)
	primary-key-type (name primary-key-type)
	primary-key-type-is-string (str-tools/substring? "varchar" (str-tools/lower-case primary-key-type))
	escaped-primary-key-value (if primary-key-type-is-string
				    (str "\"" primary-key-value "\"")
				    primary-key-value)
	request (str "DELETE FROM " table-name " WHERE " primary-key " = " escaped-primary-key-value)]
    (sql/with-connection db-connection-spec
      (sql/do-commands request))))

;; ORM Interface (proper)
(defprotocol ISlurm
  "Simple CRUD interface for dealing with slurm objects"
  (create [#^DBConnection SlurmDB #^DBConstruct object])
  (fetch  [#^DBConnection SlurmDB #^DBClause clause])
  (update [#^DBConnection SlurmDB #^DBObject object])
  (delete [#^DBConnection SlurmDB #^DBObject object]))

;; TODO: Lots of error checking on this
(defrecord SlurmDB [#^DBConnection db-connection]
  ISlurm
  (create [_ object]
	  (let [db-connection-spec (:spec db-connection)
		table-name (name (:table-name object))
		columns (:columns object)]
	    (insert-record db-connection-spec table-name columns)))
  (fetch  [_ clause]
	  (let [db-connection-spec (:spec db-connection)
		db-schema (:schema db-connection)
		table-name (name (:table-name clause))
		table-def (first (filter #(= (keyword (:name %)) (keyword table-name))
					 (-> db-schema :tables)))
		table-primary-key (get table-def :primary-key :id)
		column-name (name (:column-name clause))
		column-type (name (get (:schema table-def) (keyword column-name) "int(11)"))
		operator (name (or (:operator clause) :=))
		value (:value clause)]
	    (select-record db-connection-spec table-name table-primary-key column-name column-type operator value)))
  (update [_ object]
	  (let [db-connection-spec (:spec db-connection)
		db-schema (:schema db-connection)
		table-name (name (:table-name object))
		table-def (first (filter #(= (keyword (:name %)) (keyword table-name))
					 (-> db-schema :tables)))
		table-primary-key (get table-def :primary-key :id)
		table-primary-key-type (get table-def :primary-key-type "int(11)")
		primary-key-value (:primary-key object)
		columns (:columns object)]
	    (update-record db-connection-spec table-name table-primary-key table-primary-key-type primary-key-value columns)))
  (delete [_ object]
	  (let [db-connection-spec (:spec db-connection)
		db-schema (:schema db-connection)
		table-name (name (:table-name object))
		table-def (first (filter #(= (keyword (:name %)) (keyword table-name))
					 (-> db-schema :tables)))
		table-primary-key (get table-def :primary-key :id)
		table-primary-key-type (get table-def :primary-key-type "int(11)")
		object-primary-key-value (get (:columns object) (keyword table-primary-key))]
	    (delete-record db-connection-spec table-name table-primary-key table-primary-key-type object-primary-key-value))))

;; DB Interface (direct access)
(defprotocol IDBAccess
  "Interface for directly querying the DB, perhaps useful for optimization (note, will not coerce results into Slurm objects).  Using SlurmDB is preferred."
  (query   [#^DBConnection DB query])
  (command [#^DBConnection DB command]))

(defrecord DB [#^DBConnection db-connection]
  IDBAccess
  (query [_ query]
	 (let [db-connection-spec (:spec db-connection)]
	   (sql/with-connection db-connection-spec
	     (sql/with-query-results query-results
	       [query]
	       (doall
		(for [result query-results]
		  result))))))
  (command [_ command]
	   (let [db-connection-spec (:spec db-connection)]
	     (sql/with-connection db-connection-spec
	       (sql/do-commands command)))))
  
;; Initialization and Verification
;; TODO: ugly, fix
(defn init
  "Configures DB connection, and initializes DB schema (creates db and tables if needed)."
  [schema-def
   & [fetch-graph]]
  (with-handler
    (let [db-schema (try (read-string (str schema-def)) (catch Exception e (println "Could not read schema definition.\nTrace:" e)))
	  db-host (get db-schema :db-server-pool "localhost")
	  ;; TODO: support server pools at some point, for now just grab a single hostname
	  db-host (if (string? db-host) db-host (first db-host)) ;; allow vector (multiple) or string (single) server defs
	  db-port (or (:db-port db-schema) 3306)
	  db-root-subname (str "//" db-host ":" db-port "/")
	  db-name (or (:db-name db-schema) "slurm_db")
	  db-user (or (:user db-schema) "root") ;; TODO: this is probably a bad idea for a default
	  db-password (:password db-schema)
	  db-tables (:tables db-schema)
	  db-connection-spec {:classname "com.mysql.jdbc.Driver"
			      :subprotocol "mysql"
			      :subname (str db-root-subname db-name)
			      :user db-user
			      :password db-password}]
	;; check db schema for bad keys, and verify the db connection (create db if it doesn't exist)
	(if (not (exists-db? db-connection-spec db-root-subname db-name))
	  (do
	    (raise SchemaWarningDBNoExist db-name "DB not found on host, will attempt to create it")
	    (create-db db-connection-spec db-root-subname db-name)))
	(let [unknown-keys (seq (filter (complement valid-schema-db-keys) (keys db-schema)))]
	  (if (not-empty unknown-keys)
	    (raise SchemaWarningUnknownKeys unknown-keys (str "Verify schema root keys"))))
	;; Consume the table definitions
	(doseq [table db-tables]
	  (let [unknown-keys (seq (filter (complement valid-schema-table-keys) (keys table)))]
	    (if (not-empty unknown-keys)
	      (raise SchemaWarningUnknownKeys unknown-keys (str "Verify table definition for '" (name (:name table "<TABLE-NAME-KEY-MISSING>")) "'"))))
	  (let [table-name (or (table :name) (raise SchemaErrorBadTableName table))
		table-primary-key (get table :primary-key :id)
		table-primary-key-type (get table :primary-key-type "int(11)")
		table-primary-key-auto-increment (get table :primary-key-auto-increment true)
		table-columns (get table :schema [])
		table-columns (cons [table-primary-key table-primary-key-type "PRIMARY KEY" (if (and table-primary-key-auto-increment
												     (str-tools/substring? "int"
															   (str-tools/lower-case (name table-primary-key-type))))
				                                                                     ;; only add auto-inc flag on int primary key types
											      "AUTO_INCREMENT")] table-columns)
		relations (for [relation (-> table :relations :one-to-one)]
			    (let [related-table (first (filter #(identical? (:name %) relation) db-tables))
				  related-table-name (:name related-table)
				  related-table-primary-key (get related-table :primary-key :id)
				  related-table-key-type (get related-table :primary-key-type "int(11)")
				  related-table-key (generate-relation-key-name related-table-name related-table-primary-key)
				  foreign-key-constraint (generate-foreign-key-constraint related-table-key related-table-name related-table-primary-key)]
			      [[related-table-key related-table-key-type]
			       foreign-key-constraint])) ;; returns a seq of relation key/type and constraint
		;; TODO: figure out how to set engine when creating tables (looks like this will need to be a patch on contrib)
		table-columns (concat table-columns (apply concat relations))]
	    ;; TODO: figure out what to do on db-schema updates (currently left to the user
	    ;;       to update table and slurm-schema on any change).
	    ;; IDEA: inconsistencies between db-schema and slurm-schema should trigger warning
	    (do
	      (if (not (exists-table? db-connection-spec db-root-subname db-name table-name))
		(do
		  (raise SchemaWarningTableNoExist (name table-name) "Table not found on host/db, will attempt to create it")
		  (apply create-table db-connection-spec table-name table-columns))) ;; if table doesn't exist, create it
	      (let [unknown-keys (seq (filter (complement valid-schema-relation-keys) (keys (-> table :relations))))]
		(if (not-empty unknown-keys)
		  (raise SchemaWarningUnknownKeys unknown-keys (str "Verify :relations keys on table '" (name table-name) "'"))))
	      (doseq [relation (-> table :relations :one-to-many)] ;; create multi-relation intermediate tables
		(let [related-table (or (first (filter #(identical? (:name %) relation) db-tables))
					(raise SchemaErrorBadTableName relation
					       (str "Relation table name not found or badly formed, on relation '"
						    (name relation) "' in table definition '"
						    (name table-name) "'")))
		      origin-table-name table-name
		      origin-table-primary-key table-primary-key
		      origin-table-key (generate-relation-key-name origin-table-name origin-table-primary-key)
		      origin-table-key-type table-primary-key-type
		      related-table-name (:name related-table)
		      related-table-primary-key (get related-table :primary-key :id)
		      related-table-key (generate-relation-key-name related-table-name related-table-primary-key)
		      related-table-key-type (get related-table :primary-key-type "int(11)")
		      relation-table-name (generate-relation-table-name table-name related-table-name)
		      relation-table-primary-key [:id "int(11)" "PRIMARY KEY" "AUTO_INCREMENT"] ;; relation tables cannot have configurable primary keys
		      relation-table-columns (conj [] relation-table-primary-key)
		      relation-table-columns (conj relation-table-columns [origin-table-key origin-table-key-type])
		      relation-table-columns (conj relation-table-columns (generate-foreign-key-constraint-cascade-delete origin-table-key
															  origin-table-name
															  origin-table-primary-key))
		      relation-table-columns (conj relation-table-columns [related-table-key related-table-key-type])
		      relation-table-columns (conj relation-table-columns (generate-foreign-key-constraint-cascade-delete related-table-key
															  related-table-name
															  related-table-primary-key))]
		  (if (not (exists-table? db-connection-spec db-root-subname db-name relation-table-name))
		    (do
		      (raise SchemaWarningTableNoExist (name relation-table-name) (str "Relation intermediary table (for "
										       (name table-name) "->"
										       (name related-table-name) ") not found on host/db, will attempt to create it"))
		      (apply create-table db-connection-spec relation-table-name relation-table-columns))))))))
      (DBConnection. db-connection-spec db-schema nil))
    (handle SchemaWarning [] (continue-with nil)) ;; NOTE: Logging behaviour is handled in the SchemaError/Warning def
    (handle SchemaError [])))                     ;;       Empty handlers are to supress java exception

;; Command-line Interface (used to init schemas)
;; TODO: at some point add a REPL to allow playing with the DB through the CLI
(defn -main [& args]
  (let [schema-file (first args)]
    (do
      (if (nil? schema-file)
	(println "Slurm command-line utility used to verify and initialize schema definition.\nUsage: java -jar slurm.jar <schema-filename>")
	(let [db (SlurmDB. (init (try (slurp schema-file) (catch Exception e (println "Could not load schema file.\nTrace:" e)))))
	      fetch-req (DBClause. :student :name := "Test Student")
	      new-row (DBConstruct. :student {:name "Test Student"})]
	  (.update db (into (.create db new-thing) {:columns {:name "Changed Name"}}))
	  (println (.field (.create db new-thing) :name))
	  (println (.fetch db fetch-req)))))))