package org.greenplum.pxf.service.fragment;

import org.greenplum.pxf.api.model.Fragment;
import org.greenplum.pxf.api.model.RequestContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

@Component
public class RandomFragmentStrategy implements FragmentStrategy {
    private final static FragmentDistributionPolicy DISTRIBUTION_POLICY = FragmentDistributionPolicy.RANDOM;

    @Override
    public List<Fragment> filterFragments(Collection<Fragment> fragments, RequestContext context) {
        int totalSegments = context.getTotalSegments();
        int shiftedIndex = context.getGpSessionId() % totalSegments + context.getGpCommandCount();
        Random random = new Random(shiftedIndex);
        List<Fragment> filteredFragments = new ArrayList<>((int) Math.ceil((double) fragments.size() / context.getTotalSegments()));
        for (Fragment fragment : fragments) {
            if (context.getSegmentId() == random.nextInt(totalSegments)) {
                filteredFragments.add(fragment);
            }
        }
        return filteredFragments;
    }

    @Override
    public FragmentDistributionPolicy getDistributionPolicy() {
        return DISTRIBUTION_POLICY;
    }
}
