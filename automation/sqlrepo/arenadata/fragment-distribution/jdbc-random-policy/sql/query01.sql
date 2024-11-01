-- @description query01 for PXF test to check fragments distribution across segments with JDBC profile using random policy
SELECT * FROM fd_random_jdbc_ext_table ORDER BY 1;
