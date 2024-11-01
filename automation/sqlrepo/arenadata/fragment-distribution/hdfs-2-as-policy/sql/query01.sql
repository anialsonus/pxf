-- @description query01 for PXF test to check fragments distribution across segments with HDFS profile using active-segment policy
SELECT * FROM fd_2_active_segment_hdfs_ext_table ORDER BY 1;
