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

class RoundRobinFragmentStrategyTest {
    private FragmentStrategy strategy;
    private RequestContext context1;
    private RequestContext context2;
    private List<Fragment> fragmentList;

    @BeforeEach
    public void setup() {
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

        fragmentList = Arrays.asList(
                new Fragment("foo.bar", new DemoFragmentMetadata()),
                new Fragment("bar.foo", new DemoFragmentMetadata()),
                new Fragment("foobar", new DemoFragmentMetadata()),
                new Fragment("barfoo", new DemoFragmentMetadata())
        );

        strategy = new RoundRobinFragmentStrategy();
    }

    @Test
    void filterFragmentsWithShiftIndex2() {
        context1.setTotalSegments(2);
        context1.setSegmentId(0);
        List<Fragment> fragments1 = strategy.filterFragments(fragmentList, context1);
        assertEquals(2, fragments1.size());
        assertEquals("foo.bar", fragments1.get(0).getSourceName());
        assertEquals("foobar", fragments1.get(1).getSourceName());

        context2.setTotalSegments(2);
        context2.setSegmentId(1);
        List<Fragment> fragments2 = strategy.filterFragments(fragmentList, context2);
        assertEquals(2, fragments2.size());
        assertEquals("bar.foo", fragments2.get(0).getSourceName());
        assertEquals("barfoo", fragments2.get(1).getSourceName());
    }

    @Test
    void filterFragmentsWithShiftIndex3() {
        context1.setTotalSegments(2);
        context1.setSegmentId(0);
        context1.setGpCommandCount(2);
        List<Fragment> fragments1 = strategy.filterFragments(fragmentList, context1);
        assertEquals(2, fragments1.size());
        assertEquals("bar.foo", fragments1.get(0).getSourceName());
        assertEquals("barfoo", fragments1.get(1).getSourceName());

        context2.setTotalSegments(2);
        context2.setSegmentId(1);
        context2.setGpCommandCount(2);
        List<Fragment> fragments2 = strategy.filterFragments(fragmentList, context2);
        assertEquals(2, fragments2.size());
        assertEquals("foo.bar", fragments2.get(0).getSourceName());
        assertEquals("foobar", fragments2.get(1).getSourceName());
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
        for (int i=0; i<10000000; i++) {
            fragmentList.add(fragment); // add the same fragment, save on memory, we only care about testing timings
        }

        context1.setTransactionId("XID-XYZ-123456");
        context1.setSegmentId(0);
        context1.setTotalSegments(100);

        long start = System.currentTimeMillis();
        List<Fragment> fragments = strategy.filterFragments(fragmentList, context1);
        long end = System.currentTimeMillis();

        assertInstanceOf(ArrayList.class, fragments);
        assertEquals(100000, fragments.size());
        assertEquals("mobile", fragments.get(0).getSourceName());
        assertTrue(end-start < 10000L); // should be less than 10 secs (8x margin), not minutes
    }

    @Test
    void getDistributionPolicyTest() {
        assertEquals(FragmentDistributionPolicy.ROUND_ROBIN, strategy.getDistributionPolicy());
    }
}