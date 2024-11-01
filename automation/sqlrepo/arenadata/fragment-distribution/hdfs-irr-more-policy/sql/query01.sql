-- @description query01 for PXF test to check fragments distribution across segments with HDFS profile using improved-round-robin policy when fragment count is more
-- than segment count but not equal to an even number of segments.
SELECT * FROM fd_improved_round_robin_more_hdfs_ext_table ORDER BY 1;
