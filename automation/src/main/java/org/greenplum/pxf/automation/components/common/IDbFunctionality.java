package org.greenplum.pxf.automation.components.common;

import org.greenplum.pxf.automation.structures.tables.basic.Table;

import java.util.ArrayList;

/**
 * Define functionality over Data bases
 */
public interface IDbFunctionality {

	void createTable(Table table) throws Exception;

	/**
	 * Convenient method for creating table - will drop the old table if exists create it and check
	 * if exists
	 * 
	 * @param table to create
	 * @throws Exception if an error occurs
	 */
	void createTableAndVerify(Table table) throws Exception;

	void dropTable(Table table, boolean cascade) throws Exception;

	void dropDataBase(String schemaName, boolean cascade, boolean ignoreFail)
			throws Exception;

	/**
	 * Inserts data from source Table data to target Table.
	 * 
	 * @param source table to read the data from
	 * @param target table to get the required table to insert to
	 * @throws Exception if an error occurs
	 */
	void insertData(Table source, Table target) throws Exception;

	void createDataBase(String schemaName, boolean ignoreFail) throws Exception;

	/**
	 * Creates a database with a given encoding, lc_collate, and lc_types values
	 *
	 * @param schemaName        the name of the database to be created
	 * @param ignoreFail        ignore on error creation
	 * @param encoding          the encoding
	 * @param localeCollate     the lc_collate
	 * @param localeCollateType the lc_ctype
	 * @throws Exception when a database creation error occurs
	 */
	void createDataBase(String schemaName, boolean ignoreFail, String encoding, String localeCollate, String localeCollateType) throws Exception;

	/**
	 * Gets tables list for data base
	 * 
	 * @param dataBaseName with a list of tables
	 * @return List of Table names for data base
	 * @throws Exception if an error occurs
	 */
	ArrayList<String> getTableList(String dataBaseName) throws Exception;

	/**
	 * Queries Data into Table Object.
	 * 
	 * @param table - the table to query
	 * @param query - the query string
	 * @throws Exception if an error occurs
	 */
	void queryResults(Table table, String query) throws Exception;

	/**
	 * Checks if Table exists in specific Schema.
	 * 
	 * @param table to check if exists
	 * @return true if exists.
	 * @throws Exception if an error occurs
	 */
	boolean checkTableExists(Table table) throws Exception;

	/**
	 * Checks if Database exists, if so return true.
	 * 
	 * @param dbName to check if exists
	 * @return if exists or not
	 * @throws Exception if an error occurs
	 */
	boolean checkDataBaseExists(String dbName) throws Exception;

	/**
	 * Gets list of all existing Data Bases.
	 * 
	 * @return list of existing DBs
	 * @throws Exception if an error occurs
	 */
	ArrayList<String> getDataBasesList() throws Exception;

    /**
     * Grant read permissions on a table
     * @param table - table
     * @param user - user
     * @throws Exception if an error occurs
     */
    void grantReadOnTable(Table table, String user) throws Exception;

    /**
     * Grant write permissions on a table
     * @param table - table
     * @param user - user
     * @throws Exception if an error occurs
     */
    void grantWriteOnTable(Table table, String user) throws Exception;
}
