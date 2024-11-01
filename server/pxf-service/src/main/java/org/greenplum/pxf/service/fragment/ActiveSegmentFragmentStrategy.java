package org.greenplum.pxf.service.fragment;

import lombok.extern.slf4j.Slf4j;
import org.greenplum.pxf.api.error.PxfRuntimeException;
import org.greenplum.pxf.api.model.Fragment;
import org.greenplum.pxf.api.model.RequestContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class ActiveSegmentFragmentStrategy implements FragmentStrategy {
    private static final FragmentDistributionPolicy DISTRIBUTION_POLICY = FragmentDistributionPolicy.ACTIVE_SEGMENT;
    private static final String ACTIVE_SEGMENT_COUNT_OPTION = FragmentStrategyProvider.ACTIVE_SEGMENT_COUNT_OPTION;

    @Override
    public List<Fragment> filterFragments(Collection<Fragment> fragments, RequestContext context) {
        int shiftedIndex = context.getGpSessionId() % context.getTotalSegments() + context.getGpCommandCount();
        // We don't need all active segments if fragment size is less than active segment count
        int activeSegmentCount = Math.min(getActiveSegmentCount(context), fragments.size());

        int index = 0;
        List<Integer> activeSegmentList = FragmentUtils.getActiveSegmentList(shiftedIndex, activeSegmentCount, context.getTotalSegments());
        log.debug("The fragments will be distributed between segments with segment id: {}", activeSegmentList);
        // There is no chance to get fragment by the current segment if its id is not in the List
        if (!activeSegmentList.contains(context.getSegmentId())) {
            return Collections.emptyList();
        }
        List<Fragment> filteredFragments = new ArrayList<>((int) Math.ceil((double) fragments.size() / activeSegmentCount));
        for (Fragment fragment : fragments) {
            if (context.getSegmentId() == activeSegmentList.get(index % activeSegmentList.size())) {
                filteredFragments.add(fragment);
            }
            index++;
        }
        return filteredFragments;
    }

    @Override
    public FragmentDistributionPolicy getDistributionPolicy() {
        return DISTRIBUTION_POLICY;
    }

    private int getActiveSegmentCount(RequestContext context) {
        try {
            int activeSegmentCount = Optional.ofNullable(context.getOptions().get(ACTIVE_SEGMENT_COUNT_OPTION))
                    .map(Integer::valueOf)
                    .orElseThrow(() -> new PxfRuntimeException(
                            "The parameter " + ACTIVE_SEGMENT_COUNT_OPTION + " is not define while the fragment " +
                                    "distribution policy is " + DISTRIBUTION_POLICY.getName() + "." +
                                    " Add the parameter " + ACTIVE_SEGMENT_COUNT_OPTION + " to the external table DDL")
                    );
            if (activeSegmentCount < 1 || activeSegmentCount > context.getTotalSegments()) {
                String errorMessage = String.format("The parameter '%s' has the value %d. The value of this parameter " +
                                "cannot be less than 1 or cannot be greater than the total amount of segments [%d segment(s)]",
                        ACTIVE_SEGMENT_COUNT_OPTION, activeSegmentCount, context.getTotalSegments());
                throw new PxfRuntimeException(errorMessage);
            }
            return activeSegmentCount;
        } catch (Exception e) {
            throw new PxfRuntimeException(String.format("Failed to get active segment count: %s. Check the value of the parameter '%s'",
                    e.getMessage(), ACTIVE_SEGMENT_COUNT_OPTION), e);
        }
    }
}
