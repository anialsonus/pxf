package org.greenplum.pxf.service.fragment;

import org.apache.hadoop.conf.Configuration;
import org.greenplum.pxf.api.error.PxfRuntimeException;
import org.greenplum.pxf.api.examples.DemoFragmentMetadata;
import org.greenplum.pxf.api.model.Fragment;
import org.greenplum.pxf.api.model.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static org.greenplum.pxf.service.fragment.FragmentStrategyProvider.ACTIVE_SEGMENT_COUNT_OPTION;
import static org.junit.jupiter.api.Assertions.*;

class ActiveSegmentFragmentStrategyTest {
    private FragmentStrategy strategy;
    private RequestContext context1;
    private RequestContext context2;
    private RequestContext context3;
    private List<Fragment> fragmentList;

    @BeforeEach
    void setUp() {
        Configuration configuration = new Configuration();
        context1 = new RequestContext();
        context1.setTransactionId("XID-XYZ-123456");
        context1.setFragmenter("org.greenplum.pxf.api.model.Fragmenter1");
        context1.setSegmentId(0);
        context1.setGpCommandCount(1);
        context1.setGpSessionId(1);
        context1.setTotalSegments(1);
        context1.setDataSource("path.A");
        context1.setConfiguration(configuration);

        context2 = new RequestContext();
        context2.setTransactionId("XID-XYZ-654321");
        context2.setFragmenter("org.greenplum.pxf.api.model.Fragmenter2");
        context2.setSegmentId(0);
        context2.setGpCommandCount(1);
        context2.setGpSessionId(1);
        context2.setTotalSegments(1);
        context2.setDataSource("path.A");
        context2.setConfiguration(configuration);

        context3 = new RequestContext();
        context3.setTransactionId("XID-XYZ-654321");
        context3.setFragmenter("org.greenplum.pxf.api.model.Fragmenter3");
        context3.setSegmentId(0);
        context3.setGpCommandCount(1);
        context3.setGpSessionId(1);
        context3.setTotalSegments(1);
        context3.setDataSource("path.A");
        context3.setConfiguration(configuration);

        fragmentList = Arrays.asList(
                new Fragment("foo.bar", new DemoFragmentMetadata()),
                new Fragment("bar.foo", new DemoFragmentMetadata()),
                new Fragment("foobar", new DemoFragmentMetadata()),
                new Fragment("barfoo", new DemoFragmentMetadata())
        );

        strategy = new ActiveSegmentFragmentStrategy();
    }

    @Test
    public void testFragmenterCallWithOneActiveSegmentCount() {
        context1.setGpSessionId(0);
        context1.setGpCommandCount(0);
        context1.setSegmentId(0);
        context1.setTotalSegments(2);
        context1.addOption(ACTIVE_SEGMENT_COUNT_OPTION, "1");

        context2.setGpSessionId(0);
        context2.setGpCommandCount(0);
        context2.setSegmentId(1);
        context2.setTotalSegments(2);
        context2.addOption(ACTIVE_SEGMENT_COUNT_OPTION, "1");

        List<Fragment> response1 = strategy.filterFragments(fragmentList, context1);
        List<Fragment> response2 = strategy.filterFragments(fragmentList, context2);

        assertEquals(4, response1.size());
        assertEquals("foo.bar", response1.get(0).getSourceName());
        assertEquals("bar.foo", response1.get(1).getSourceName());
        assertEquals("foobar", response1.get(2).getSourceName());
        assertEquals("barfoo", response1.get(3).getSourceName());

        assertEquals(0, response2.size());
    }

    @Test
    public void testFragmenterCallWith2ActiveSegmentCountAnd3TotalSegments() {
        context1.setGpSessionId(0);
        context1.setGpCommandCount(0);
        context1.setSegmentId(0);
        context1.setTotalSegments(3);
        context1.addOption(ACTIVE_SEGMENT_COUNT_OPTION, "2");

        context2.setGpSessionId(0);
        context2.setGpCommandCount(0);
        context2.setSegmentId(1);
        context2.setTotalSegments(3);
        context2.addOption(ACTIVE_SEGMENT_COUNT_OPTION, "2");

        context3.setGpSessionId(0);
        context3.setGpCommandCount(0);
        context3.setSegmentId(2);
        context3.setTotalSegments(3);
        context3.addOption(ACTIVE_SEGMENT_COUNT_OPTION, "2");

        List<Fragment> response1 = strategy.filterFragments(fragmentList, context1);
        List<Fragment> response2 = strategy.filterFragments(fragmentList, context2);
        List<Fragment> response3 = strategy.filterFragments(fragmentList, context3);

        assertEquals(2, response1.size());
        assertEquals("foo.bar", response1.get(0).getSourceName());
        assertEquals("foobar", response1.get(1).getSourceName());

        assertEquals(2, response3.size());
        assertEquals("bar.foo", response3.get(0).getSourceName());
        assertEquals("barfoo", response3.get(1).getSourceName());

        assertEquals(0, response2.size());
    }

    @Test
    public void testFragmenterCallWithFragmentSizeLessThanTotalSegments() {
        fragmentList = Arrays.asList(
                new Fragment("foo.bar", new DemoFragmentMetadata()),
                new Fragment("bar.foo", new DemoFragmentMetadata())
        );

        context1.setGpSessionId(0);
        context1.setGpCommandCount(0);
        context1.setSegmentId(0);
        context1.setTotalSegments(3);
        context1.addOption(ACTIVE_SEGMENT_COUNT_OPTION, "3");

        context2.setGpSessionId(0);
        context2.setGpCommandCount(0);
        context2.setSegmentId(1);
        context2.setTotalSegments(3);
        context2.addOption(ACTIVE_SEGMENT_COUNT_OPTION, "3");

        context3.setGpSessionId(0);
        context3.setGpCommandCount(0);
        context3.setSegmentId(2);
        context3.setTotalSegments(3);
        context3.addOption(ACTIVE_SEGMENT_COUNT_OPTION, "3");

        List<Fragment> response1 = strategy.filterFragments(fragmentList, context1);
        List<Fragment> response2 = strategy.filterFragments(fragmentList, context2);
        List<Fragment> response3 = strategy.filterFragments(fragmentList, context3);

        assertEquals(1, response1.size());
        assertEquals("foo.bar", response1.get(0).getSourceName());

        assertEquals(1, response3.size());
        assertEquals("bar.foo", response3.get(0).getSourceName());

        assertEquals(0, response2.size());
    }

    @Test
    public void testFragmenterCallWithWrongActiveSegmentCount() {
        context1.setTransactionId("0");
        context1.setSegmentId(0);
        context1.setTotalSegments(1);
        context1.addOption(ACTIVE_SEGMENT_COUNT_OPTION, "WRONG");

        Exception e = assertThrows(PxfRuntimeException.class, () -> strategy.filterFragments(fragmentList, context1));
        assertEquals("Failed to get active segment count: For input string: \"WRONG\". Check the value of the parameter 'ACTIVE_SEGMENT_COUNT'", e.getMessage());
    }

    @Test
    public void testFragmenterCallWithLessThanOneActiveSegmentCount() {
        context1.setTransactionId("0");
        context1.setSegmentId(0);
        context1.setTotalSegments(1);
        context1.addOption(ACTIVE_SEGMENT_COUNT_OPTION, "0");

        Exception e = assertThrows(PxfRuntimeException.class, () -> strategy.filterFragments(fragmentList, context1));
        assertTrue(e.getMessage().contains("The parameter 'ACTIVE_SEGMENT_COUNT' has the value 0. The value of this " +
                "parameter cannot be less than 1 or cannot be greater than the total amount of segments [1 segment(s)]"));
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
        for (int i = 0; i < 10000000; i++) {
            fragmentList.add(fragment); // add the same fragment, save on memory, we only care about testing timings
        }

        context1.setSegmentId(10);
        context1.setTotalSegments(500);
        context1.setGpSessionId(500);
        context1.setGpCommandCount(0);
        context1.addOption(ACTIVE_SEGMENT_COUNT_OPTION, "50");

        long start = System.currentTimeMillis();
        List<Fragment> fragments = strategy.filterFragments(fragmentList, context1);
        long end = System.currentTimeMillis();

        assertInstanceOf(ArrayList.class, fragments);
        assertEquals(200000, fragments.size());
        assertEquals("mobile", fragments.get(0).getSourceName());
        assertTrue(end - start < 10000L); // should be less than 10 secs (8x margin), not minutes
    }

    @Test
    void getDistributionPolicy() {
        assertEquals(FragmentDistributionPolicy.ACTIVE_SEGMENT, strategy.getDistributionPolicy());
    }
}