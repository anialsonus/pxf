-- @description query01 for PXF test to check fragments distribution across segments HDFS profile when fragment count is equal to an even number of segments.
SELECT * FROM fd_improved_round_robin_even_hdfs_ext_table ORDER BY 1;
