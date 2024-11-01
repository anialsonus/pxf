package org.greenplum.pxf.service.fragment;

import org.greenplum.pxf.api.model.Fragment;
import org.greenplum.pxf.api.model.RequestContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Component
public class RoundRobinFragmentStrategy implements FragmentStrategy {
    private final static FragmentDistributionPolicy DISTRIBUTION_POLICY = FragmentDistributionPolicy.ROUND_ROBIN;

    @Override
    public List<Fragment> filterFragments(Collection<Fragment> fragments, RequestContext context) {
        int shiftedIndex = context.getGpSessionId() % context.getTotalSegments() + context.getGpCommandCount();
        List<Fragment> filteredFragments = new ArrayList<>((int) Math.ceil((double) fragments.size() / context.getTotalSegments()));
        for (Fragment fragment : fragments) {
            if (context.getSegmentId() == (shiftedIndex % context.getTotalSegments())) {
                filteredFragments.add(fragment);
            }
            shiftedIndex++;
        }
        return filteredFragments;
    }

    @Override
    public FragmentDistributionPolicy getDistributionPolicy() {
        return DISTRIBUTION_POLICY;
    }
}
