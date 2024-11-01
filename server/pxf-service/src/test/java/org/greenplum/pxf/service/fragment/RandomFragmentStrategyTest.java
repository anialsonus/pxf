package org.greenplum.pxf.service.fragment;

import org.apache.hadoop.conf.Configuration;
import org.greenplum.pxf.api.examples.DemoFragmentMetadata;
import org.greenplum.pxf.api.model.Fragment;
import org.greenplum.pxf.api.model.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class RandomFragmentStrategyTest {
    private FragmentStrategy strategy;
    private RequestContext context1;
    private RequestContext context2;
    private RequestContext context3;
    private List<Fragment> fragmentList;

    @BeforeEach
    void setUp() {
        Configuration configuration = new Configuration();
        context1 = new RequestContext();
        context1.setTransactionId("XID-XYZ-654321");
        context1.setFragmenter("org.greenplum.pxf.api.model.Fragmenter1");
        context1.setSegmentId(0);
        context1.setGpCommandCount(10);
        context1.setGpSessionId(3);
        context1.setTotalSegments(3);
        context1.setDataSource("path.A");
        context1.setConfiguration(configuration);

        context2 = new RequestContext();
        context2.setTransactionId("XID-XYZ-654321");
        context2.setFragmenter("org.greenplum.pxf.api.model.Fragmenter2");
        context2.setSegmentId(1);
        context2.setGpCommandCount(10);
        context2.setGpSessionId(3);
        context2.setTotalSegments(3);
        context2.setDataSource("path.A");
        context2.setConfiguration(configuration);

        context3 = new RequestContext();
        context3.setTransactionId("XID-XYZ-654321");
        context3.setFragmenter("org.greenplum.pxf.api.model.Fragmenter3");
        context3.setSegmentId(2);
        context3.setGpCommandCount(10);
        context3.setGpSessionId(3);
        context3.setTotalSegments(3);
        context3.setDataSource("path.A");
        context3.setConfiguration(configuration);

        fragmentList = Arrays.asList(
                new Fragment("tv", new DemoFragmentMetadata()),
                new Fragment("console", new DemoFragmentMetadata()),
                new Fragment("watch", new DemoFragmentMetadata()),
                new Fragment("computer", new DemoFragmentMetadata()),
                new Fragment("laptop", new DemoFragmentMetadata()),
                new Fragment("smartphone", new DemoFragmentMetadata()),
                new Fragment("pipelac", new DemoFragmentMetadata()),
                new Fragment("car", new DemoFragmentMetadata())
        );

        strategy = new RandomFragmentStrategy();
    }

    @Test
    void filterFragmentsWithShiftIndex10() {
        // We use Random with the same seed for all segments as new Random(shiftedIndex) to get random segment id.
        // This random has to produce the same random consequence if shiftedIndex still the same.
        // shiftedIndex = context.getGpSessionId % context.getTotalSegments + context.getGpCommandCount();
        context1.setGpCommandCount(10);
        context2.setGpCommandCount(10);
        context3.setGpCommandCount(10);
        IntStream.rangeClosed(0, 5).forEach(i -> {
            List<Fragment> fragments1 = strategy.filterFragments(fragmentList, context1);
            List<Fragment> fragments2 = strategy.filterFragments(fragmentList, context2);
            List<Fragment> fragments3 = strategy.filterFragments(fragmentList, context3);
            // segment id = 0
            assertEquals(4, fragments1.size());
            assertEquals("tv", fragments1.get(0).getSourceName());
            assertEquals("console", fragments1.get(1).getSourceName());
            assertEquals("watch", fragments1.get(2).getSourceName());
            assertEquals("computer", fragments1.get(3).getSourceName());
            // segment id = 1
            assertEquals(4, fragments2.size());
            assertEquals("laptop", fragments2.get(0).getSourceName());
            assertEquals("smartphone", fragments2.get(1).getSourceName());
            assertEquals("pipelac", fragments2.get(2).getSourceName());
            assertEquals("car", fragments2.get(3).getSourceName());
            // segment id = 2
            assertEquals(0, fragments3.size());
        });
    }

    @Test
    void filterFragmentsWithShiftIndex15() {
        // We use Random with the same seed for all segments as new Random(shiftedIndex) to get random segment id.
        // This random has to produce the same random consequence if shiftedIndex still the same.
        context1.setGpCommandCount(15);
        context2.setGpCommandCount(15);
        context3.setGpCommandCount(15);
        IntStream.rangeClosed(0, 6).forEach(i -> {
            List<Fragment> fragments1 = strategy.filterFragments(fragmentList, context1);
            List<Fragment> fragments2 = strategy.filterFragments(fragmentList, context2);
            List<Fragment> fragments3 = strategy.filterFragments(fragmentList, context3);
            // segment id = 0
            assertEquals(2, fragments1.size());
            assertEquals("tv", fragments1.get(0).getSourceName());
            assertEquals("computer", fragments1.get(1).getSourceName());
            // segment id = 1
            assertEquals(3, fragments2.size());
            assertEquals("watch", fragments2.get(0).getSourceName());
            assertEquals("laptop", fragments2.get(1).getSourceName());
            assertEquals("car", fragments2.get(2).getSourceName());
            // segment id = 2
            assertEquals(3, fragments3.size());
            assertEquals("console", fragments3.get(0).getSourceName());
            assertEquals("smartphone", fragments3.get(1).getSourceName());
            assertEquals("pipelac", fragments3.get(2).getSourceName());
        });
    }

    @Test
    void getDistributionPolicy() {
        assertEquals(FragmentDistributionPolicy.RANDOM, strategy.getDistributionPolicy());
    }
}