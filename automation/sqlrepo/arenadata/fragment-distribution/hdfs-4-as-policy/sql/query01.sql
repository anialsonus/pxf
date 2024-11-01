-- @description query01 for PXF test to check fragments distribution across segments with HDFS profile
SELECT * FROM fd_4_active_segment_hdfs_ext_table ORDER BY 1;
