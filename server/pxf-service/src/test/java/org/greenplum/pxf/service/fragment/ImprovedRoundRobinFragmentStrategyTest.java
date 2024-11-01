package org.greenplum.pxf.service.fragment;

import org.apache.hadoop.conf.Configuration;
import org.greenplum.pxf.api.examples.DemoFragmentMetadata;
import org.greenplum.pxf.api.model.Fragment;
import org.greenplum.pxf.api.model.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImprovedRoundRobinFragmentStrategyTest {
    private FragmentStrategy strategy;
    private RequestContext context1;
    private RequestContext context2;
    private RequestContext context3;

    @BeforeEach
    void setUp() {
        Configuration configuration = new Configuration();
        context1 = new RequestContext();
        context1.setTransactionId("XID-XYZ-123456");
        context1.setFragmenter("org.greenplum.pxf.api.model.Fragmenter1");
        context1.setGpCommandCount(0);
        context1.setGpSessionId(6);
        context1.setDataSource("path.A");
        context1.setConfiguration(configuration);

        context2 = new RequestContext();
        context2.setTransactionId("XID-XYZ-654321");
        context2.setFragmenter("org.greenplum.pxf.api.model.Fragmenter2");
        context2.setGpCommandCount(0);
        context2.setGpSessionId(6);
        context2.setDataSource("path.A");
        context2.setConfiguration(configuration);

        context3 = new RequestContext();
        context3.setTransactionId("XID-XYZ-654321");
        context3.setFragmenter("org.greenplum.pxf.api.model.Fragmenter3");
        context3.setGpCommandCount(0);
        context3.setGpSessionId(6);
        context3.setDataSource("path.A");
        context3.setConfiguration(configuration);

        strategy = new ImprovedRoundRobinFragmentStrategy();
    }

    /**
     * The fragment size is less than total segment count. We will try to distribute them between segments evenly.
     * The difference with 'round-robin' is that 'round-robin' algorithm would put these fragments on the segments
     * which are close to each other and might be on the same host.
     */
    @Test
    void filterFragmentsWhenFragmentSizeIsLessThanTotalSegments() {
        List<Fragment> fragmentList = Arrays.asList(
                new Fragment("foo.bar", new DemoFragmentMetadata()),
                new Fragment("bar.foo", new DemoFragmentMetadata())
        );

        context1.setTotalSegments(3);
        context1.setSegmentId(0);
        List<Fragment> fragments1 = strategy.filterFragments(fragmentList, context1);
        assertEquals(1, fragments1.size());
        assertEquals("foo.bar", fragments1.get(0).getSourceName());

        context2.setTotalSegments(3);
        context2.setSegmentId(1);
        List<Fragment> fragments2 = strategy.filterFragments(fragmentList, context2);
        assertEquals(0, fragments2.size());

        context3.setTotalSegments(3);
        context3.setSegmentId(2);
        List<Fragment> fragments3 = strategy.filterFragments(fragmentList, context3);
        assertEquals(1, fragments3.size());
        assertEquals("bar.foo", fragments3.get(0).getSourceName());
    }

    /**
     * The fragment size is equal to an even number of total segments
     */
    @Test
    void filterFragmentsWhenFragmentSizeIsEvenNumberOfTotalSegments() {
        List<Fragment> fragmentList = Arrays.asList(
                new Fragment("tv", new DemoFragmentMetadata()),
                new Fragment("console", new DemoFragmentMetadata()),
                new Fragment("watch", new DemoFragmentMetadata()),
                new Fragment("computer", new DemoFragmentMetadata()),
                new Fragment("laptop", new DemoFragmentMetadata()),
                new Fragment("smartphone", new DemoFragmentMetadata())
        );

        context1.setTotalSegments(3);
        context1.setSegmentId(0);
        List<Fragment> fragments1 = strategy.filterFragments(fragmentList, context1);
        assertEquals(2, fragments1.size());
        assertEquals("tv", fragments1.get(0).getSourceName());
        assertEquals("computer", fragments1.get(1).getSourceName());

        context2.setTotalSegments(3);
        context2.setSegmentId(1);
        List<Fragment> fragments2 = strategy.filterFragments(fragmentList, context2);
        assertEquals(2, fragments2.size());
        assertEquals("console", fragments2.get(0).getSourceName());
        assertEquals("laptop", fragments2.get(1).getSourceName());

        context3.setTotalSegments(3);
        context3.setSegmentId(2);
        List<Fragment> fragments3 = strategy.filterFragments(fragmentList, context3);
        assertEquals(2, fragments3.size());
        assertEquals("watch", fragments3.get(0).getSourceName());
        assertEquals("smartphone", fragments3.get(1).getSourceName());
    }

    /**
     * The fragment size (8) is greater than total segment count (3).
     * We will distribute 6 fragments between 3 segments (2 fragments per each segment)
     * and the rest 2 fragments will be distributed between segments 0 and 2
     */
    @Test
    void filterFragmentsWhenFragmentSizeIsGreaterThanTotalSegments() {
        List<Fragment> fragmentList = Arrays.asList(
                new Fragment("tv", new DemoFragmentMetadata()),
                new Fragment("console", new DemoFragmentMetadata()),
                new Fragment("watch", new DemoFragmentMetadata()),
                new Fragment("computer", new DemoFragmentMetadata()),
                new Fragment("laptop", new DemoFragmentMetadata()),
                new Fragment("smartphone", new DemoFragmentMetadata()),
                new Fragment("pipelac", new DemoFragmentMetadata()),
                new Fragment("car", new DemoFragmentMetadata())
        );

        context1.setTotalSegments(3);
        context1.setSegmentId(0);
        List<Fragment> fragments1 = strategy.filterFragments(fragmentList, context1);
        assertEquals(3, fragments1.size());
        assertEquals("tv", fragments1.get(0).getSourceName());
        assertEquals("computer", fragments1.get(1).getSourceName());
        assertEquals("pipelac", fragments1.get(2).getSourceName());

        context2.setTotalSegments(3);
        context2.setSegmentId(1);
        List<Fragment> fragments2 = strategy.filterFragments(fragmentList, context2);
        assertEquals(2, fragments2.size());
        assertEquals("console", fragments2.get(0).getSourceName());
        assertEquals("laptop", fragments2.get(1).getSourceName());

        context3.setTotalSegments(3);
        context3.setSegmentId(2);
        List<Fragment> fragments3 = strategy.filterFragments(fragmentList, context3);
        assertEquals(3, fragments3.size());
        assertEquals("watch", fragments3.get(0).getSourceName());
        assertEquals("smartphone", fragments3.get(1).getSourceName());
        assertEquals("car", fragments3.get(2).getSourceName());
    }

    // ----- TESTS for performance of list traversal -----
    @Test
    public void testListTraversalPerformance() {

        // This test makes sure we iterate properly (using an iterator, not the index-based for loop) over a LinkedList
        // that is returned by a fragmenter when building a list of fragment for a segment.
        // Tested on MacBookPro, the timings are as follows:
        // 10M fragments - from  15 mins to 1.3 secs
        //  1M fragments - from 8.2 secs to 1.3 secs
        // so we will run the large dataset that would've taken 15 minutes and make sure it computes within 10 seconds
        // allowing 8x margin for test slowness when running on slower machines on in the cloud under a heavy workload

        Fragment fragment = new Fragment("mobile", new DemoFragmentMetadata());
        List<Fragment> fragmentList = new LinkedList<>();
        for (int i = 0; i < 10000005; i++) {
            fragmentList.add(fragment); // add the same fragment, save on memory, we only care about testing timings
        }

        context1.setSegmentId(0);
        context1.setTotalSegments(500);
        context1.setGpSessionId(500);

        context2.setSegmentId(100);
        context2.setTotalSegments(500);
        context2.setGpSessionId(500);

        long start = System.currentTimeMillis();
        List<Fragment> fragments1 = strategy.filterFragments(fragmentList, context1);
        List<Fragment> fragments2 = strategy.filterFragments(fragmentList, context2);
        long end = System.currentTimeMillis();

        assertInstanceOf(ArrayList.class, fragments1);
        assertEquals(20001, fragments1.size());
        assertEquals("mobile", fragments1.get(0).getSourceName());

        assertInstanceOf(ArrayList.class, fragments2);
        assertEquals(20001, fragments2.size());
        assertEquals("mobile", fragments2.get(0).getSourceName());

        assertTrue(end - start < 20000L); // should be less than 10 secs (8x margin), not minutes
    }

    @Test
    void getDistributionPolicy() {
        assertEquals(FragmentDistributionPolicy.IMPROVED_ROUND_ROBIN, strategy.getDistributionPolicy());
    }
}