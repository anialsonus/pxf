-- @description query01 for PXF test to check fragments distribution across segments with JDBC profile using active-segment policy
-- but without parameter ACTIVE_SEGMENT_COUNT
SELECT * FROM fd_failed_active_segment_jdbc_limit_ext_table ORDER BY 1;
