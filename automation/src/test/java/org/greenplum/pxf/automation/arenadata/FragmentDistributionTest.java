package org.greenplum.pxf.automation.arenadata;

import org.greenplum.pxf.automation.components.cluster.MultiNodeCluster;
import org.greenplum.pxf.automation.components.cluster.PhdCluster;
import org.greenplum.pxf.automation.components.cluster.installer.nodes.Node;
import org.greenplum.pxf.automation.components.cluster.installer.nodes.SegmentNode;
import org.greenplum.pxf.automation.enums.EnumPartitionType;
import org.greenplum.pxf.automation.features.BaseFeature;
import org.greenplum.pxf.automation.structures.tables.basic.Table;
import org.greenplum.pxf.automation.structures.tables.pxf.ExternalTable;
import org.greenplum.pxf.automation.structures.tables.pxf.ReadableExternalTable;
import org.greenplum.pxf.automation.structures.tables.utils.TableFactory;
import org.testng.annotations.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.greenplum.pxf.automation.PxfTestConstant.PXF_LOG_RELATIVE_PATH;
import static org.greenplum.pxf.automation.PxfTestUtil.getCmdResult;
import static org.junit.Assert.assertEquals;

public class FragmentDistributionTest extends BaseFeature {
    private static final String PG_SOURCE_TABLE_NAME = "pg_fd_source_table";
    private static final String JDBC_EXT_TABLE_NAME = "fd_jdbc_ext_table";
    private static final String JDBC_LIMIT_EXT_TABLE_NAME = "fd_jdbc_limit_ext_table";
    private static final String JDBC_ACTIVE_SEGMENT_EXT_TABLE_NAME = "fd_active_segment_jdbc_limit_ext_table";
    private static final String JDBC_ACTIVE_SEGMENT_FAILED_EXT_TABLE_NAME = "fd_failed_active_segment_jdbc_limit_ext_table";
    private static final String JDBC_RANDOM_EXT_TABLE_NAME = "fd_random_jdbc_ext_table";
    private static final String JDBC_IMPROVED_ROUND_ROBIN_EXT_TABLE_NAME = "fd_improved_round_robin_jdbc_ext_table";
    private static final String HDFS_EXT_TABLE_NAME = "fd_hdfs_ext_table";
    private static final String HDFS_LIMIT_EXT_TABLE_NAME = "fd_hdfs_limit_ext_table";
    private static final String HDFS_4_ACTIVE_SEGMENT_EXT_TABLE_NAME = "fd_4_active_segment_hdfs_ext_table";
    private static final String HDFS_2_ACTIVE_SEGMENT_EXT_TABLE_NAME = "fd_2_active_segment_hdfs_ext_table";
    private static final String HDFS_RANDOM_EXT_TABLE_NAME = "fd_random_hdfs_ext_table";
    private static final String HDFS_IRR_MORE_EXT_TABLE_NAME = "fd_improved_round_robin_more_hdfs_ext_table";
    private static final String HDFS_IRR_EVEN_EXT_TABLE_NAME = "fd_improved_round_robin_even_hdfs_ext_table";
    private static final String HDFS_IRR_LESS_EXT_TABLE_NAME = "fd_improved_round_robin_less_hdfs_ext_table";
    private static final String[] SOURCE_TABLE_FIELDS = new String[]{
            "id    int",
            "descr   text"};
    private static final String PXF_TEMP_LOG_PATH = "/tmp/pxf";
    private static final String PXF_TEMP_LOG_FILE = PXF_TEMP_LOG_PATH + "/pxf-service.log";
    private static final String DELIMITER = ";";
    private static final String CAT_COMMAND = "cat " + PXF_TEMP_LOG_FILE;
    private static final String PXF_LOG_GET_FRAGMENTS_COUNT = CAT_COMMAND + " | grep -oP 'Returning ([1-9]+/\\d+) fragment' | awk -F ' ' '{print $2}' | awk -F '/' '{sum+=$1;} END{print sum;}'";
    private static final String PXF_LOG_GET_IDLE_SEGMENTS = CAT_COMMAND + " | grep -oP 'Returning (0/\\d+) fragment' | wc -l";
    private static final String PXF_LOG_GET_ACTIVE_SEGMENTS = CAT_COMMAND + " | grep -oP 'Returning ([1-9]+/\\d+) fragment' | wc -l";
    private static final String PXF_LOG_CHECK_POLICY_TEMPLATE = CAT_COMMAND + "| grep \"The '%s' fragment distribution policy will be used\" | wc -l";
    private List<Node> pxfNodes;
    private String pxfLogFile;
    private String hdfsPathWith2Files;
    private String hdfsPathWith6Files;
    private String hdfsPathWith8Files;
    private String hdfsPathWith12Files;
    private Table postgresSourceTable;
    private String restartCommand;

    @Override
    protected void beforeClass() throws Exception {
        String pxfHome = cluster.getPxfHome();
        restartCommand = pxfHome + "/bin/pxf restart";
        if (cluster instanceof MultiNodeCluster) {
            pxfNodes = ((MultiNodeCluster) cluster).getNode(SegmentNode.class, PhdCluster.EnumClusterServices.pxf);
        }
        pxfLogFile = pxfHome + "/" + PXF_LOG_RELATIVE_PATH;
        hdfsPathWith2Files = hdfs.getWorkingDirectory() + "/text_fragment_distribution_2/";
        hdfsPathWith6Files = hdfs.getWorkingDirectory() + "/text_fragment_distribution_6/";
        hdfsPathWith8Files = hdfs.getWorkingDirectory() + "/text_fragment_distribution_8/";
        hdfsPathWith12Files = hdfs.getWorkingDirectory() + "/text_fragment_distribution_12/";
        prepareData();
        changeLogLevel("debug");
        cluster.runCommand("mkdir -p " + PXF_TEMP_LOG_PATH);
    }

    @Override
    public void afterClass() throws Exception {
        changeLogLevel("info");
    }

    protected void prepareData() throws Exception {
        preparePgSourceTable();
        prepareHdfsFiles(2, hdfsPathWith2Files);
        prepareHdfsFiles(6, hdfsPathWith6Files);
        prepareHdfsFiles(8, hdfsPathWith8Files);
        prepareHdfsFiles(12, hdfsPathWith12Files);
        createGpdbReadableJdbcTable();
        createGpdbReadableJdbcTableWithLimit();
        createGpdbReadableJdbcTableWithActiveSegmentDistribution();
        createGpdbReadableJdbcTableWithFailedActiveSegmentDistribution();
        createGpdbReadableJdbcTableWithRandomDistribution();
        createGpdbReadableJdbcTableWithImprovedRoundRobinDistribution();
        createGpdbReadableHdfsTable();
        createGpdbReadableHdfsTableWithLimit();
        createGpdbReadableHdfsTableWith4ActiveSegmentDistribution();
        createGpdbReadableHdfsTableWith2ActiveSegmentDistribution();
        createGpdbReadableHdfsTableWithRandomDistribution();
        createGpdbReadableHdfsTableWithImprovedRoundRobinDistribution(HDFS_IRR_MORE_EXT_TABLE_NAME, hdfsPathWith8Files);
        createGpdbReadableHdfsTableWithImprovedRoundRobinDistribution(HDFS_IRR_EVEN_EXT_TABLE_NAME, hdfsPathWith12Files);
        createGpdbReadableHdfsTableWithImprovedRoundRobinDistribution(HDFS_IRR_LESS_EXT_TABLE_NAME, hdfsPathWith2Files);
    }

    private void preparePgSourceTable() throws Exception {
        postgresSourceTable = new Table(PG_SOURCE_TABLE_NAME, SOURCE_TABLE_FIELDS);
        postgresSourceTable.setDistributionFields(new String[]{"id"});
        gpdb.createTableAndVerify(postgresSourceTable);
        // Generate rows for source table
        int count = 20;
        String[][] rows = new String[count][2];
        for (int i = 0; i < count; i++) {
            rows[i][0] = String.valueOf(i + 1);
            rows[i][1] = "text" + (i + 1);
        }
        Table dataTable = new Table("dataTable", SOURCE_TABLE_FIELDS);
        dataTable.addRows(rows);
        gpdb.insertData(dataTable, postgresSourceTable);
    }

    private void prepareHdfsFiles(int filesCount, String path) {
        // Create 6 files to receive 6 fragments
        IntStream.rangeClosed(1, filesCount)
                .forEach(i -> {
                    String[][] rows = {{String.valueOf(i), "text" + i}};
                    Table hdfsDataTable = new Table("hdfsDataTable", SOURCE_TABLE_FIELDS);
                    hdfsDataTable.addRows(rows);
                    try {
                        hdfs.writeTableToFile(path + "file-" + i, hdfsDataTable, DELIMITER);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private void createGpdbReadableJdbcTable() throws Exception {
        ExternalTable gpdbReadablePgTable = createJdbcExtPartitionTable(JDBC_EXT_TABLE_NAME, null);
        gpdb.createTableAndVerify(gpdbReadablePgTable);
    }

    private void createGpdbReadableJdbcTableWithLimit() throws Exception {
        String customProperties = "ACTIVE_SEGMENT_COUNT=2";
        ExternalTable gpdbReadablePgTable = createJdbcExtPartitionTable(JDBC_LIMIT_EXT_TABLE_NAME, customProperties);
        gpdb.createTableAndVerify(gpdbReadablePgTable);
    }

    private void createGpdbReadableJdbcTableWithActiveSegmentDistribution() throws Exception {
        String customProperties = "FRAGMENT_DISTRIBUTION_POLICY=active-segment&ACTIVE_SEGMENT_COUNT=2";
        ExternalTable gpdbReadablePgTable = createJdbcExtPartitionTable(JDBC_ACTIVE_SEGMENT_EXT_TABLE_NAME, customProperties);
        gpdb.createTableAndVerify(gpdbReadablePgTable);
    }

    private void createGpdbReadableJdbcTableWithFailedActiveSegmentDistribution() throws Exception {
        String customProperties = "FRAGMENT_DISTRIBUTION_POLICY=active-segment";
        ExternalTable gpdbReadablePgTable = createJdbcExtPartitionTable(JDBC_ACTIVE_SEGMENT_FAILED_EXT_TABLE_NAME, customProperties);
        gpdb.createTableAndVerify(gpdbReadablePgTable);
    }

    private void createGpdbReadableJdbcTableWithRandomDistribution() throws Exception {
        String customProperties = "FRAGMENT_DISTRIBUTION_POLICY=random";
        ExternalTable gpdbReadablePgTable = createJdbcExtPartitionTable(JDBC_RANDOM_EXT_TABLE_NAME, customProperties);
        gpdb.createTableAndVerify(gpdbReadablePgTable);
    }

    private void createGpdbReadableJdbcTableWithImprovedRoundRobinDistribution() throws Exception {
        String customProperties = "FRAGMENT_DISTRIBUTION_POLICY=improved-round-robin";
        ExternalTable gpdbReadablePgTable = createJdbcExtPartitionTable(JDBC_IMPROVED_ROUND_ROBIN_EXT_TABLE_NAME, customProperties);
        gpdb.createTableAndVerify(gpdbReadablePgTable);
    }

    private ExternalTable createJdbcExtPartitionTable(String tableName, String customParameters) {
        return TableFactory.getPxfJdbcReadablePartitionedTable(
                tableName,
                SOURCE_TABLE_FIELDS,
                postgresSourceTable.getName(),
                0,
                "1:20",
                "4",
                EnumPartitionType.INT,
                "default",
                customParameters);
    }

    private void createGpdbReadableHdfsTable() throws Exception {
        ReadableExternalTable gpdbReadableHdfsTable = TableFactory.getPxfReadableCSVTable(
                HDFS_EXT_TABLE_NAME,
                SOURCE_TABLE_FIELDS,
                hdfsPathWith6Files,
                DELIMITER);
        gpdb.createTableAndVerify(gpdbReadableHdfsTable);
    }

    private void createGpdbReadableHdfsTableWithLimit() throws Exception {
        ReadableExternalTable gpdbReadableHdfsTable = TableFactory.getPxfReadableCSVTable(
                HDFS_LIMIT_EXT_TABLE_NAME,
                SOURCE_TABLE_FIELDS,
                hdfsPathWith6Files,
                DELIMITER);
        gpdbReadableHdfsTable.setUserParameters(new String[]{"ACTIVE_SEGMENT_COUNT=2"});
        gpdb.createTableAndVerify(gpdbReadableHdfsTable);
    }

    private void createGpdbReadableHdfsTableWith4ActiveSegmentDistribution() throws Exception {
        ReadableExternalTable gpdbReadableHdfsTable = TableFactory.getPxfReadableCSVTable(
                HDFS_4_ACTIVE_SEGMENT_EXT_TABLE_NAME,
                SOURCE_TABLE_FIELDS,
                hdfsPathWith6Files,
                DELIMITER);
        gpdbReadableHdfsTable.setUserParameters(new String[]{"FRAGMENT_DISTRIBUTION_POLICY=active-segment", "ACTIVE_SEGMENT_COUNT=4"});
        gpdb.createTableAndVerify(gpdbReadableHdfsTable);
    }

    private void createGpdbReadableHdfsTableWith2ActiveSegmentDistribution() throws Exception {
        ReadableExternalTable gpdbReadableHdfsTable = TableFactory.getPxfReadableCSVTable(
                HDFS_2_ACTIVE_SEGMENT_EXT_TABLE_NAME,
                SOURCE_TABLE_FIELDS,
                hdfsPathWith8Files,
                DELIMITER);
        gpdbReadableHdfsTable.setUserParameters(new String[]{"FRAGMENT_DISTRIBUTION_POLICY=active-segment", "ACTIVE_SEGMENT_COUNT=2"});
        gpdb.createTableAndVerify(gpdbReadableHdfsTable);
    }

    private void createGpdbReadableHdfsTableWithRandomDistribution() throws Exception {
        ReadableExternalTable gpdbReadableHdfsTable = TableFactory.getPxfReadableCSVTable(
                HDFS_RANDOM_EXT_TABLE_NAME,
                SOURCE_TABLE_FIELDS,
                hdfsPathWith8Files,
                DELIMITER);
        gpdbReadableHdfsTable.setUserParameters(new String[]{"FRAGMENT_DISTRIBUTION_POLICY=random"});
        gpdb.createTableAndVerify(gpdbReadableHdfsTable);
    }

    private void createGpdbReadableHdfsTableWithImprovedRoundRobinDistribution(String tableName, String sourcePath) throws Exception {
        ReadableExternalTable gpdbReadableHdfsTable = TableFactory.getPxfReadableCSVTable(
                tableName,
                SOURCE_TABLE_FIELDS,
                sourcePath,
                DELIMITER);
        gpdbReadableHdfsTable.setUserParameters(new String[]{"FRAGMENT_DISTRIBUTION_POLICY=improved-round-robin"});
        gpdb.createTableAndVerify(gpdbReadableHdfsTable);
    }

    /**
     * Check default fragments distribution policy with JDBC profile. The default policy is 'round-robin'.
     * Parameters: not define
     * We don't know exactly how many fragments will get each logical segments, but the total fragments has to be 8.
     * checkPolicyResult has to have 3 lines per node (1 per each logical segment on the segment host);
     * checkIdleSegments has to be 0 as each segment will get at least 1 fragment;
     * fragmentsCount has to be 8 as a summary from all segments.
     */
    @Test(groups = {"arenadata"}, description = "Check JDBC fragments distribution across segments with default policy (round-robin)")
    public void fragmentDistributionForJdbcWithDefaultPolicyTest() throws Exception {
        cleanLogs();
        runSqlTest("arenadata/fragment-distribution/jdbc");
        int fragmentsCount = 0;
        for (Node pxfNode : pxfNodes) {
            cluster.copyFromRemoteMachine(pxfNode.getUserName(), pxfNode.getPassword(), pxfNode.getHost(), pxfLogFile, PXF_TEMP_LOG_PATH);
            copyLogs(getMethodName(), pxfNode.getHost());
            String checkPolicyResult = getCmdResult(cluster, String.format(PXF_LOG_CHECK_POLICY_TEMPLATE, "round-robin"));
            assertEquals("3", checkPolicyResult);
            String checkIdleSegments = getCmdResult(cluster, PXF_LOG_GET_IDLE_SEGMENTS);
            assertEquals("0", checkIdleSegments);
            fragmentsCount += Integer.parseInt(getCmdResult(cluster, PXF_LOG_GET_FRAGMENTS_COUNT));
            cluster.deleteFileFromNodes(PXF_TEMP_LOG_FILE, false);
        }
        assertEquals(8, fragmentsCount);
    }

    /**
     * Check active-segment distribution policy with JDBC profile (backward compatability test as we don't use
     * FRAGMENT_DISTRIBUTION_POLICY parameter here. But if the parameter ACTIVE_SEGMENT_COUNT is present we apply active-segment policy).
     * Parameters: ACTIVE_SEGMENT_COUNT=2
     * checkPolicyResult has to have 3 lines per node (1 per each logical segment on the segment host)
     * checkIdleSegments has to be 2 per each segment host as we have only 1 active segment per node and the other 2 segments will not get fragments;
     * fragmentsCount has to be 4 for each segment host as we have 8 fragments total,
     * and they will be split between 2 segment hosts evenly.
     */
    @Test(groups = {"arenadata"}, description = "Check JDBC fragments distribution across segments. Run on limit segments")
    public void fragmentDistributionForJdbcWithLimitTest() throws Exception {
        cleanLogs();
        runSqlTest("arenadata/fragment-distribution/jdbc-limit");
        for (Node pxfNode : pxfNodes) {
            cluster.copyFromRemoteMachine(pxfNode.getUserName(), pxfNode.getPassword(), pxfNode.getHost(), pxfLogFile, PXF_TEMP_LOG_PATH);
            copyLogs(getMethodName(), pxfNode.getHost());
            String checkPolicyResult = getCmdResult(cluster, String.format(PXF_LOG_CHECK_POLICY_TEMPLATE, "active-segment"));
            assertEquals("3", checkPolicyResult);
            String checkIdleSegments = getCmdResult(cluster, PXF_LOG_GET_IDLE_SEGMENTS);
            assertEquals("2", checkIdleSegments);
            String fragmentsCount = getCmdResult(cluster, PXF_LOG_GET_FRAGMENTS_COUNT);
            assertEquals("4", fragmentsCount);
            cluster.deleteFileFromNodes(PXF_TEMP_LOG_FILE, false);
        }
    }

    /**
     * Check active-segment distribution policy with JDBC profile.
     * Parameters: FRAGMENT_DISTRIBUTION_POLICY=active-segment, ACTIVE_SEGMENT_COUNT=2
     * checkPolicyResult has to have 3 lines per node (1 per each logical segment on the segment host)
     * checkIdleSegments has to be 2 per each segment host as we have only 1 active segment per node and the other 2 segments will not get fragments;
     * fragmentsCount has to be 4 for each segment host as we have 8 fragments total,
     * and they will be split between 2 segment hosts evenly.
     */
    @Test(groups = {"arenadata"}, description = "Check JDBC fragments distribution across segments with active-segment distribution policy")
    public void fragmentDistributionForJdbcWithActiveSegmentPolicyTest() throws Exception {
        cleanLogs();
        runSqlTest("arenadata/fragment-distribution/jdbc-as-policy");
        for (Node pxfNode : pxfNodes) {
            cluster.copyFromRemoteMachine(pxfNode.getUserName(), pxfNode.getPassword(), pxfNode.getHost(), pxfLogFile, PXF_TEMP_LOG_PATH);
            copyLogs(getMethodName(), pxfNode.getHost());
            String checkPolicyResult = getCmdResult(cluster, String.format(PXF_LOG_CHECK_POLICY_TEMPLATE, "active-segment"));
            assertEquals("3", checkPolicyResult);
            String checkIdleSegments = getCmdResult(cluster, PXF_LOG_GET_IDLE_SEGMENTS);
            assertEquals("2", checkIdleSegments);
            String fragmentsCount = getCmdResult(cluster, PXF_LOG_GET_FRAGMENTS_COUNT);
            assertEquals("4", fragmentsCount);
            cluster.deleteFileFromNodes(PXF_TEMP_LOG_FILE, false);
        }
    }

    /**
     * Check active-segment distribution policy with JDBC profile without ACTIVE_SEGMENT_COUNT parameter.
     * Parameters: FRAGMENT_DISTRIBUTION_POLICY=active-segment, ACTIVE_SEGMENT_COUNT is not present.
     * The query will fail.
     */
    @Test(groups = {"arenadata"}, description = "Check JDBC fragments distribution across segments with failed active-segment distribution policy")
    public void fragmentDistributionForJdbcWithFailedActiveSegmentPolicyTest() throws Exception {
        runSqlTest("arenadata/fragment-distribution/jdbc-failed-as-policy");
    }

    /**
     * Check random fragments distribution policy with JDBC profile.
     * Parameters: FRAGMENT_DISTRIBUTION_POLICY=random
     * We don't know exactly how many fragments will get each logical segments, but the total fragments has to be 8.
     * checkPolicyResult has to have 3 lines per node (1 per each logical segment on the segment host)
     * fragmentsCount has to be 8 as a summary from all segments.
     */
    @Test(groups = {"arenadata"}, description = "Check JDBC fragments distribution across segments with random distribution policy")
    public void fragmentDistributionForJdbcWithRandomPolicyTest() throws Exception {
        cleanLogs();
        runSqlTest("arenadata/fragment-distribution/jdbc-random-policy");
        int fragmentsCount = 0;
        for (Node pxfNode : pxfNodes) {
            cluster.copyFromRemoteMachine(pxfNode.getUserName(), pxfNode.getPassword(), pxfNode.getHost(), pxfLogFile, PXF_TEMP_LOG_PATH);
            copyLogs(getMethodName(), pxfNode.getHost());
            String checkPolicyResult = getCmdResult(cluster, String.format(PXF_LOG_CHECK_POLICY_TEMPLATE, "random"));
            assertEquals("3", checkPolicyResult);
            fragmentsCount += Integer.parseInt(getCmdResult(cluster, PXF_LOG_GET_FRAGMENTS_COUNT));
            cluster.deleteFileFromNodes(PXF_TEMP_LOG_FILE, false);
        }
        assertEquals(8, fragmentsCount);
    }

    /**
     * Check improved-round-robin fragments distribution policy with JDBC profile.
     * Parameters: FRAGMENT_DISTRIBUTION_POLICY=improved-round-robin
     * checkPolicyResult has to have 3 lines per node (1 per each logical segment on the segment host)
     * checkIdleSegments has to be 0 as each segment will get at least 1 fragment;
     * fragmentsCount has to be 4 for each segment host as we have 8 fragments total. The policy will
     * distribute 6 fragments between all segments and the rest 2 fragments will be distributed between segment hosts evenly
     */
    @Test(groups = {"arenadata"}, description = "Check JDBC fragments distribution across segments with improved-round-robin distribution policy")
    public void fragmentDistributionForJdbcWithImprovedRoundRobinPolicyTest() throws Exception {
        cleanLogs();
        runSqlTest("arenadata/fragment-distribution/jdbc-irr-policy");
        for (Node pxfNode : pxfNodes) {
            cluster.copyFromRemoteMachine(pxfNode.getUserName(), pxfNode.getPassword(), pxfNode.getHost(), pxfLogFile, PXF_TEMP_LOG_PATH);
            copyLogs(getMethodName(), pxfNode.getHost());
            String checkPolicyResult = getCmdResult(cluster, String.format(PXF_LOG_CHECK_POLICY_TEMPLATE, "improved-round-robin"));
            assertEquals("3", checkPolicyResult);
            String checkIdleSegments = getCmdResult(cluster, PXF_LOG_GET_IDLE_SEGMENTS);
            assertEquals("0", checkIdleSegments);
            String fragmentCount = getCmdResult(cluster, PXF_LOG_GET_FRAGMENTS_COUNT);
            assertEquals("4", fragmentCount);
            cluster.deleteFileFromNodes(PXF_TEMP_LOG_FILE, false);
        }
    }

    /**
     * Check default fragments distribution policy with HDFS profile. The default policy is 'round-robin'.
     * Parameters: not define
     * We don't know exactly how many fragments will get each logical segments, but the total fragments has to be 6.
     * checkPolicyResult has to have 3 lines per node (1 per each logical segment on the segment host)
     * checkIdleSegments has to be 0 as each segment will get at least 1 fragment;
     * fragmentsCount has to be 6 as a summary from all segments.
     */
    @Test(groups = {"arenadata"}, description = "Check HDFS fragments distribution across segments. Run on all segments")
    public void fragmentDistributionForHdfsTest() throws Exception {
        cleanLogs();
        runSqlTest("arenadata/fragment-distribution/hdfs");
        int fragmentsCount = 0;
        for (Node pxfNode : pxfNodes) {
            cluster.copyFromRemoteMachine(pxfNode.getUserName(), pxfNode.getPassword(), pxfNode.getHost(), pxfLogFile, PXF_TEMP_LOG_PATH);
            copyLogs(getMethodName(), pxfNode.getHost());
            String checkPolicyResult = getCmdResult(cluster, String.format(PXF_LOG_CHECK_POLICY_TEMPLATE, "round-robin"));
            assertEquals("3", checkPolicyResult);
            String checkIdleSegments = getCmdResult(cluster, PXF_LOG_GET_IDLE_SEGMENTS);
            assertEquals("0", checkIdleSegments);
            fragmentsCount += Integer.parseInt(getCmdResult(cluster, PXF_LOG_GET_FRAGMENTS_COUNT));
            cluster.deleteFileFromNodes(PXF_TEMP_LOG_FILE, false);
        }
        assertEquals(6, fragmentsCount);
    }

    /**
     * Check active-segment distribution policy with HDFS profile (backward compatability test as we don't use
     * FRAGMENT_DISTRIBUTION_POLICY parameter here. But if the parameter ACTIVE_SEGMENT_COUNT is present we apply active-segment policy).
     * Parameters: ACTIVE_SEGMENT_COUNT=2
     * checkPolicyResult has to have 3 lines per node (1 per each logical segment on the segment host)
     * checkIdleSegments has to be 2 per each segment host as we have only 1 active segment per node and the other 2 segments will not get fragments;
     * fragmentsCount has to be 3 for each segment host as we have 6 fragments total,
     * and they will be split between 2 segment hosts evenly.
     */
    @Test(groups = {"arenadata"}, description = "Check HDFS fragments distribution across segments. Run on 2 segments")
    public void fragmentDistributionForHdfsWithLimitTest() throws Exception {
        cleanLogs();
        runSqlTest("arenadata/fragment-distribution/hdfs-limit");
        for (Node pxfNode : pxfNodes) {
            cluster.copyFromRemoteMachine(pxfNode.getUserName(), pxfNode.getPassword(), pxfNode.getHost(), pxfLogFile, PXF_TEMP_LOG_PATH);
            copyLogs(getMethodName(), pxfNode.getHost());
            String checkPolicyResult = getCmdResult(cluster, String.format(PXF_LOG_CHECK_POLICY_TEMPLATE, "active-segment"));
            assertEquals("3", checkPolicyResult);
            String checkIdleSegments = getCmdResult(cluster, PXF_LOG_GET_IDLE_SEGMENTS);
            assertEquals("2", checkIdleSegments);
            String fragmentsCount = getCmdResult(cluster, PXF_LOG_GET_FRAGMENTS_COUNT);
            assertEquals("3", fragmentsCount);
            cluster.deleteFileFromNodes(PXF_TEMP_LOG_FILE, false);
        }
    }

    /**
     * Check active-segment distribution policy with HDFS profile.
     * Parameters: FRAGMENT_DISTRIBUTION_POLICY=active-segment, ACTIVE_SEGMENT_COUNT=4
     * checkPolicyResult has to have 3 lines per node (1 per each logical segment on the segment host)
     * idleSegmentsCount has to be 2 for all cluster as we have 4 active segments and the other 2 segments will not get fragments;
     * activeSegmentsCount has to be 4;
     * fragmentsCount has to be 6 for all segments. We try to split them evenly between segment hosts but there is no guarantee.
     * That is why we check the summary from all segments hosts.
     */
    @Test(groups = {"arenadata"}, description = "Check HDFS fragments distribution across segments with 4 active-segment distribution policy")
    public void fragmentDistributionForHdfsWith4ActiveSegmentPolicyTest() throws Exception {
        cleanLogs();
        runSqlTest("arenadata/fragment-distribution/hdfs-4-as-policy");
        int fragmentsCount = 0;
        int activeSegmentsCount = 0;
        int idleSegmentsCount = 0;
        for (Node pxfNode : pxfNodes) {
            cluster.copyFromRemoteMachine(pxfNode.getUserName(), pxfNode.getPassword(), pxfNode.getHost(), pxfLogFile, PXF_TEMP_LOG_PATH);
            copyLogs(getMethodName(), pxfNode.getHost());
            String checkPolicyResult = getCmdResult(cluster, String.format(PXF_LOG_CHECK_POLICY_TEMPLATE, "active-segment"));
            assertEquals("3", checkPolicyResult);
            fragmentsCount += Integer.parseInt(getCmdResult(cluster, PXF_LOG_GET_FRAGMENTS_COUNT));
            activeSegmentsCount += Integer.parseInt(getCmdResult(cluster, PXF_LOG_GET_ACTIVE_SEGMENTS));
            idleSegmentsCount += Integer.parseInt(getCmdResult(cluster, PXF_LOG_GET_IDLE_SEGMENTS));
            cluster.deleteFileFromNodes(PXF_TEMP_LOG_FILE, false);
        }
        assertEquals(4, activeSegmentsCount);
        assertEquals(2, idleSegmentsCount);
        assertEquals(6, fragmentsCount);
    }

    /**
     * Check active-segment distribution policy with HDFS profile.
     * Parameters: FRAGMENT_DISTRIBUTION_POLICY=active-segment, ACTIVE_SEGMENT_COUNT=2
     * checkPolicyResult has to have 3 lines per node (1 per each logical segment on the segment host)
     * checkIdleSegments has to be 2 per each segment host as we have only 1 active segment per node and the other 2 segments will not get fragments;
     * fragmentsCount has to be 4 for each segment host as we have 8 fragments total,
     * and they will be split between 2 segment hosts evenly.
     */
    @Test(groups = {"arenadata"}, description = "Check HDFS fragments distribution across segments with 2 active-segment distribution policy")
    public void fragmentDistributionForHdfsWith2ActiveSegmentPolicyTest() throws Exception {
        cleanLogs();
        runSqlTest("arenadata/fragment-distribution/hdfs-2-as-policy");
        for (Node pxfNode : pxfNodes) {
            cluster.copyFromRemoteMachine(pxfNode.getUserName(), pxfNode.getPassword(), pxfNode.getHost(), pxfLogFile, PXF_TEMP_LOG_PATH);
            copyLogs(getMethodName(), pxfNode.getHost());
            String checkPolicyResult = getCmdResult(cluster, String.format(PXF_LOG_CHECK_POLICY_TEMPLATE, "active-segment"));
            assertEquals("3", checkPolicyResult);
            String checkIdleSegments = getCmdResult(cluster, PXF_LOG_GET_IDLE_SEGMENTS);
            assertEquals("2", checkIdleSegments);
            String fragmentsCount = getCmdResult(cluster, PXF_LOG_GET_FRAGMENTS_COUNT);
            assertEquals("4", fragmentsCount);
            cluster.deleteFileFromNodes(PXF_TEMP_LOG_FILE, false);
        }
    }

    /**
     * Check random fragments distribution policy with HDFS profile.
     * Parameters: FRAGMENT_DISTRIBUTION_POLICY=random
     * We don't know exactly how many fragments will get each logical segments, but the total fragments has to be 8.
     * checkPolicyResult has to have 3 lines per node (1 per each logical segment on the segment host)
     * fragmentsCount has to be 8 as a summary from all segments.
     */
    @Test(groups = {"arenadata"}, description = "Check HDFS fragments distribution across segments with random distribution policy")
    public void fragmentDistributionForHdfsWithRandomPolicyTest() throws Exception {
        cleanLogs();
        runSqlTest("arenadata/fragment-distribution/hdfs-random-policy");
        int fragmentCount = 0;
        for (Node pxfNode : pxfNodes) {
            cluster.copyFromRemoteMachine(pxfNode.getUserName(), pxfNode.getPassword(), pxfNode.getHost(), pxfLogFile, PXF_TEMP_LOG_PATH);
            copyLogs(getMethodName(), pxfNode.getHost());
            String checkPolicyResult = getCmdResult(cluster, String.format(PXF_LOG_CHECK_POLICY_TEMPLATE, "random"));
            assertEquals("3", checkPolicyResult);
            fragmentCount += Integer.parseInt(getCmdResult(cluster, PXF_LOG_GET_FRAGMENTS_COUNT));
            cluster.deleteFileFromNodes(PXF_TEMP_LOG_FILE, false);
        }
        assertEquals(8, fragmentCount);
    }

    /**
     * Check improved-round-robin fragments distribution policy with HDFS profile when fragment count is more
     * than segment count but not equal to an even number of segments.
     * Parameters: FRAGMENT_DISTRIBUTION_POLICY=improved-round-robin
     * checkPolicyResult has to have 3 lines per node (1 per each logical segment on the segment host)
     * checkIdleSegments has to be 0 as each segment will get at least 1 fragment;
     * fragmentsCount has to be 4 for each segment host as we have 8 fragments total. The policy will
     * distribute 6 fragments between all segments and the rest 2 fragments will be distributed between segment hosts evenly
     */
    @Test(groups = {"arenadata"}, description = "Check HDFS fragments distribution across segments with improved-round-robin " +
            "distribution policy when fragment count is more than segment count but not equal to an even number of segments")
    public void fragmentDistributionForHdfsWithIrrPolicyWithMoreFragmentsTest() throws Exception {
        cleanLogs();
        runSqlTest("arenadata/fragment-distribution/hdfs-irr-more-policy");
        for (Node pxfNode : pxfNodes) {
            cluster.copyFromRemoteMachine(pxfNode.getUserName(), pxfNode.getPassword(), pxfNode.getHost(), pxfLogFile, PXF_TEMP_LOG_PATH);
            copyLogs(getMethodName(), pxfNode.getHost());
            String checkPolicyResult = getCmdResult(cluster, String.format(PXF_LOG_CHECK_POLICY_TEMPLATE, "improved-round-robin"));
            assertEquals("3", checkPolicyResult);
            String checkIdleSegments = getCmdResult(cluster, PXF_LOG_GET_IDLE_SEGMENTS);
            assertEquals("0", checkIdleSegments);
            String fragmentsCount = getCmdResult(cluster, PXF_LOG_GET_FRAGMENTS_COUNT);
            assertEquals("4", fragmentsCount);
            cluster.deleteFileFromNodes(PXF_TEMP_LOG_FILE, false);
        }
    }

    /**
     * Check improved-round-robin fragments distribution policy with HDFS profile when fragment count is equal to an even number of segments.
     * Parameters: FRAGMENT_DISTRIBUTION_POLICY=improved-round-robin
     * checkPolicyResult has to have 3 lines per node (1 per each logical segment on the segment host)
     * checkIdleSegments has to be 0 as each segment will get at least 1 fragment;
     * fragmentsCount has to be 6 for each segment host as we have 12 fragments total. The policy will
     * distribute 2 fragments for each logical segment.
     */
    @Test(groups = {"arenadata"}, description = "Check HDFS fragments distribution across segments with improved-round-robin " +
            "distribution policy when fragment count is equal to an even number of segments")
    public void fragmentDistributionForHdfsWithIrrPolicyWithEvenFragmentsTest() throws Exception {
        cleanLogs();
        runSqlTest("arenadata/fragment-distribution/hdfs-irr-even-policy");
        for (Node pxfNode : pxfNodes) {
            cluster.copyFromRemoteMachine(pxfNode.getUserName(), pxfNode.getPassword(), pxfNode.getHost(), pxfLogFile, PXF_TEMP_LOG_PATH);
            copyLogs(getMethodName(), pxfNode.getHost());
            String checkPolicyResult = getCmdResult(cluster, String.format(PXF_LOG_CHECK_POLICY_TEMPLATE, "improved-round-robin"));
            assertEquals("3", checkPolicyResult);
            String checkIdleSegments = getCmdResult(cluster, PXF_LOG_GET_IDLE_SEGMENTS);
            assertEquals("0", checkIdleSegments);
            String fragmentsCount = getCmdResult(cluster, PXF_LOG_GET_FRAGMENTS_COUNT);
            assertEquals("6", fragmentsCount);
            cluster.deleteFileFromNodes(PXF_TEMP_LOG_FILE, false);
        }
    }

    /**
     * Check improved-round-robin fragments distribution policy with HDFS profile when fragment count is less
     * than segment count.
     * Parameters: FRAGMENT_DISTRIBUTION_POLICY=improved-round-robin
     * checkPolicyResult has to have 3 lines per node (1 per each logical segment on the segment host)
     * checkIdleSegments has to be 2 as we have only 2 fragments and they should be on a different segment hosts;
     * fragmentsCount has to be 1 for each segment host as we have 2 fragments total. The policy will
     * split these 2 fragments between 2 segment hosts evenly.
     */
    @Test(groups = {"arenadata"}, description = "Check HDFS fragments distribution across segments with improved-round-robin " +
            "distribution policy when fragment count is less than segment count")
    public void fragmentDistributionForHdfsWithIrrPolicyWithLessFragmentsTest() throws Exception {
        cleanLogs();
        runSqlTest("arenadata/fragment-distribution/hdfs-irr-less-policy");
        for (Node pxfNode : pxfNodes) {
            cluster.copyFromRemoteMachine(pxfNode.getUserName(), pxfNode.getPassword(), pxfNode.getHost(), pxfLogFile, PXF_TEMP_LOG_PATH);
            copyLogs(getMethodName(), pxfNode.getHost());
            String checkPolicyResult = getCmdResult(cluster, String.format(PXF_LOG_CHECK_POLICY_TEMPLATE, "improved-round-robin"));
            assertEquals("3", checkPolicyResult);
            String checkIdleSegments = getCmdResult(cluster, PXF_LOG_GET_IDLE_SEGMENTS);
            assertEquals("2", checkIdleSegments);
            String fragmentsCount = getCmdResult(cluster, PXF_LOG_GET_FRAGMENTS_COUNT);
            assertEquals("1", fragmentsCount);
            cluster.deleteFileFromNodes(PXF_TEMP_LOG_FILE, false);
        }
    }

    private void changeLogLevel(String level) throws Exception {
        cluster.runCommandOnNodes(pxfNodes, String.format("export PXF_LOG_LEVEL=%s;%s", level, restartCommand));
    }

    private void cleanLogs() throws Exception {
        cluster.runCommandOnNodes(pxfNodes, "> " + pxfLogFile);
    }

    private void copyLogs(String methodName, String host) throws Exception {
        cluster.runCommand("cp " + PXF_TEMP_LOG_FILE + " " + PXF_TEMP_LOG_FILE + "-" + methodName + "-" + host);
    }

    private String getMethodName() {
        return Thread.currentThread()
                .getStackTrace()[2]
                .getMethodName();
    }
}
