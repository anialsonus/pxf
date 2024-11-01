-- @description query01 for PXF test to check fragments distribution across segments with JDBC profile using active-segment policy
SELECT * FROM fd_active_segment_jdbc_limit_ext_table ORDER BY 1;
