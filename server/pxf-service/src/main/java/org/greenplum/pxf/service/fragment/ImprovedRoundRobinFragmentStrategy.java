package org.greenplum.pxf.service.fragment;

import lombok.extern.slf4j.Slf4j;
import org.greenplum.pxf.api.model.Fragment;
import org.greenplum.pxf.api.model.RequestContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class ImprovedRoundRobinFragmentStrategy implements FragmentStrategy {
    private final static FragmentDistributionPolicy DISTRIBUTION_POLICY = FragmentDistributionPolicy.IMPROVED_ROUND_ROBIN;

    @Override
    public List<Fragment> filterFragments(Collection<Fragment> fragments, RequestContext context) {
        int totalSegments = context.getTotalSegments();
        List<Fragment> filteredFragments = new ArrayList<>((int) Math.ceil((double) fragments.size() / totalSegments));
        int shiftedIndex = context.getGpSessionId() % totalSegments + context.getGpCommandCount();

        int fullIterationCount = fragments.size() / totalSegments;
        int restFragments = fragments.size() % totalSegments;
        // The fragment size is less than total segment count. We will try to distribute them between segments evenly
        if (fullIterationCount == 0 && restFragments != 0) {
            List<Integer> activeSegmentList = FragmentUtils.getActiveSegmentList(shiftedIndex, restFragments, totalSegments);
            log.debug("{} fragment(s) will be distributed between segments with segment id: {}", restFragments, activeSegmentList);
            // There is no chance to get fragment by the current segment if its id is not in the List
            if (!activeSegmentList.contains(context.getSegmentId())) {
                return Collections.emptyList();
            }
            int index = 0;
            for (Fragment fragment : fragments) {
                if (context.getSegmentId() == activeSegmentList.get(index % activeSegmentList.size())) {
                    filteredFragments.add(fragment);
                }
                index++;
            }
            return filteredFragments;
            // The fragment size is greater than total segment count.
            // We will distribute them between all segments and the rest part of fragments will be distributed between segments evenly
        } else if (fullIterationCount != 0 && restFragments != 0) {
            int index = 0;
            int fragmentCount = 0;
            int fullDistributionFragmentCount = fullIterationCount * totalSegments;
            List<Integer> activeSegmentList = FragmentUtils.getActiveSegmentList(shiftedIndex, restFragments, totalSegments);
            log.debug("{} fragments will be distributed between all segments {} time(s) and the rest {} fragment(s) will be " +
                    "distributed between segments with segment id: {}", fragments.size(), fullIterationCount, restFragments, activeSegmentList);
            for (Fragment fragment : fragments) {
                if (fragmentCount >= fullDistributionFragmentCount) {
                    if (context.getSegmentId() == activeSegmentList.get(index % activeSegmentList.size())) {
                        filteredFragments.add(fragment);
                    }
                    index++;
                } else {
                    if (context.getSegmentId() == (shiftedIndex % context.getTotalSegments())) {
                        filteredFragments.add(fragment);
                    }
                    shiftedIndex++;
                    fragmentCount++;
                }
            }
            return filteredFragments;
            // The fragment size is equal to an even number of total segments
        } else {
            log.debug("{} fragments will be distributed between {} segments evenly", fragments.size(), totalSegments);
            for (Fragment fragment : fragments) {
                if (context.getSegmentId() == (shiftedIndex % context.getTotalSegments())) {
                    filteredFragments.add(fragment);
                }
                shiftedIndex++;
            }
            return filteredFragments;
        }
    }

    @Override
    public FragmentDistributionPolicy getDistributionPolicy() {
        return DISTRIBUTION_POLICY;
    }
}
