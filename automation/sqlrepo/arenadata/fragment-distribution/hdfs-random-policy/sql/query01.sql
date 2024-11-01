-- @description query01 for PXF test to check fragments distribution across segments with HDFS profile using random policy
SELECT * FROM fd_random_hdfs_ext_table ORDER BY 1;
