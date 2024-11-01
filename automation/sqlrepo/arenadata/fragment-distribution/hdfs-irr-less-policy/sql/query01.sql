-- @description query01 for PXF test to check fragments distribution across segments with HDFS profile using improved-round-robin policy when fragment count is less
-- than segment count.
SELECT * FROM fd_improved_round_robin_less_hdfs_ext_table ORDER BY 1;
