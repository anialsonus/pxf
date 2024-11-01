package org.greenplum.pxf.service.fragment;

import org.apache.hadoop.conf.Configuration;
import org.greenplum.pxf.api.model.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.greenplum.pxf.service.fragment.FragmentStrategyProvider.ACTIVE_SEGMENT_COUNT_OPTION;
import static org.greenplum.pxf.service.fragment.FragmentStrategyProvider.FRAGMENT_DISTRIBUTION_POLICY_OPTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FragmentStrategyProviderTest {
    @Mock
    private RoundRobinFragmentStrategy roundRobinFragmentStrategy;
    @Mock
    private ActiveSegmentFragmentStrategy activeSegmentFragmentStrategy;
    @Mock
    private ImprovedRoundRobinFragmentStrategy improvedRoundRobinFragmentStrategy;
    @Mock
    private RandomFragmentStrategy randomFragmentStrategy;

    private RequestContext context;
    private FragmentStrategyProvider strategyProvider;

    @BeforeEach
    void setUp() {
        Configuration configuration = new Configuration();
        context = new RequestContext();
        context.setTransactionId("XID-XYZ-123456");
        context.setFragmenter("org.greenplum.pxf.api.model.Fragmenter1");
        context.setSegmentId(0);
        context.setGpCommandCount(0);
        context.setGpSessionId(2);
        context.setTotalSegments(2);
        context.setDataSource("path.A");
        context.setConfiguration(configuration);

        List<FragmentStrategy> strategies = Arrays.asList(
                roundRobinFragmentStrategy,
                improvedRoundRobinFragmentStrategy,
                activeSegmentFragmentStrategy,
                randomFragmentStrategy);
        when(roundRobinFragmentStrategy.getDistributionPolicy()).thenReturn(FragmentDistributionPolicy.ROUND_ROBIN);
        when(activeSegmentFragmentStrategy.getDistributionPolicy()).thenReturn(FragmentDistributionPolicy.ACTIVE_SEGMENT);
        when(improvedRoundRobinFragmentStrategy.getDistributionPolicy()).thenReturn(FragmentDistributionPolicy.IMPROVED_ROUND_ROBIN);
        when(randomFragmentStrategy.getDistributionPolicy()).thenReturn(FragmentDistributionPolicy.RANDOM);

        strategyProvider = new FragmentStrategyProvider(strategies);
    }

    @Test
    public void defaultFragmentDistributionPolicyOptionTest() {
        assertInstanceOf(RoundRobinFragmentStrategy.class, strategyProvider.getStrategy(context));
    }

    @Test
    public void roundRobinFragmentDistributionPolicyOptionTest() {
        context.addOption(FRAGMENT_DISTRIBUTION_POLICY_OPTION, "round-robin");
        assertInstanceOf(RoundRobinFragmentStrategy.class, strategyProvider.getStrategy(context));
    }

    @Test
    public void activeSegmentFragmentDistributionPolicyOptionTest() {
        context.addOption(FRAGMENT_DISTRIBUTION_POLICY_OPTION, "active-segment");
        assertInstanceOf(ActiveSegmentFragmentStrategy.class, strategyProvider.getStrategy(context));
    }

    /**
     * We return active-segment strategy if the parameter FRAGMENT_DISTRIBUTION_POLICY was not set,
     * but the parameter ACTIVE_SEGMENT_COUNT is present. We need it for backward compatability
     */
    @Test
    public void activeSegmentFragmentDistributionPolicyOptionForBackwardCompatabilityTest() {
        context.addOption(ACTIVE_SEGMENT_COUNT_OPTION, "1");
        assertInstanceOf(ActiveSegmentFragmentStrategy.class, strategyProvider.getStrategy(context));
    }

    @Test
    public void improvedRoundRobinFragmentDistributionPolicyOptionTest() {
        context.addOption(FRAGMENT_DISTRIBUTION_POLICY_OPTION, "improved-round-robin");
        assertInstanceOf(ImprovedRoundRobinFragmentStrategy.class, strategyProvider.getStrategy(context));
    }

    @Test
    public void randomFragmentDistributionPolicyOptionTest() {
        context.addOption(FRAGMENT_DISTRIBUTION_POLICY_OPTION, "random");
        assertInstanceOf(RandomFragmentStrategy.class, strategyProvider.getStrategy(context));
    }

    @Test
    public void strategyPolicyIsNotFound() {
        context.addOption(FRAGMENT_DISTRIBUTION_POLICY_OPTION, "fake-policy");
        Exception exception = assertThrows(IllegalArgumentException.class, () -> strategyProvider.getStrategy(context));
        String errorMessage = exception.getMessage();
        assertEquals("Cannot find corresponding fragment distribution policy with name fake-policy", errorMessage);
    }
}
