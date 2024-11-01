package org.greenplum.pxf.service.fragment;

import lombok.extern.slf4j.Slf4j;
import org.greenplum.pxf.api.error.PxfRuntimeException;
import org.greenplum.pxf.api.model.RequestContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class FragmentStrategyProvider {
    public static final String FRAGMENT_DISTRIBUTION_POLICY_OPTION = "FRAGMENT_DISTRIBUTION_POLICY";
    public static final String ACTIVE_SEGMENT_COUNT_OPTION = "ACTIVE_SEGMENT_COUNT";
    private final Map<FragmentDistributionPolicy, FragmentStrategy> strategyMap;

    public FragmentStrategyProvider(Collection<FragmentStrategy> strategies) {
        this.strategyMap = new HashMap<>();
        strategies.forEach(strategy -> strategyMap.put(strategy.getDistributionPolicy(), strategy));
    }

    public FragmentStrategy getStrategy(RequestContext context) {
        FragmentDistributionPolicy policy = getPolicyFromContext(context);
        FragmentStrategy strategy = Optional.ofNullable(strategyMap.get(policy))
                .orElseThrow(() -> new PxfRuntimeException("The strategy for fragment distribution policy '" + policy.getName() + "' was not found"));
        log.debug("The '{}' fragment distribution policy will be used", policy.getName());
        return strategy;
    }

    private FragmentDistributionPolicy getPolicyFromContext(RequestContext context) {
        return Optional.ofNullable(context.getOption(ACTIVE_SEGMENT_COUNT_OPTION))
                .map(str -> FragmentDistributionPolicy.ACTIVE_SEGMENT) // for backward compatability
                .orElseGet(() -> Optional.ofNullable(context.getOption(FRAGMENT_DISTRIBUTION_POLICY_OPTION))
                        .map(FragmentDistributionPolicy::fromName)
                        .orElse(FragmentDistributionPolicy.ROUND_ROBIN));
    }
}
