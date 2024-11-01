package org.greenplum.pxf.service.fragment;

import java.util.ArrayList;
import java.util.List;

public class FragmentUtils {
    public static List<Integer> getActiveSegmentList(int shiftedIndex, int activeSegmentCount, int totalSegments) {
        // We will try to distribute fragments evenly between segments hosts,
        // but we don't know exactly how many logical segments per host.
        // Also, we don't know how many segment hosts in the cluster.
        List<Integer> activeSegmentList = new ArrayList<>();
        while (activeSegmentCount > 0) {
            int step = (int) Math.ceil((float) totalSegments / activeSegmentCount);
            // How many segments might be evenly distributed with the step
            int currentCount = totalSegments / step;
            for (int i = 0; i < currentCount; i++) {
                int id = shiftedIndex % totalSegments;
                // We need to do shiftedIndex++ if the list has already contained the segment id
                if (activeSegmentList.contains(id)) {
                    shiftedIndex++;
                    id = shiftedIndex % totalSegments;
                }
                activeSegmentList.add(id);
                shiftedIndex += step;
            }
            // Get the rest of the segments and distribute them again if activeSegmentCount > 0
            activeSegmentCount = activeSegmentCount - currentCount;
        }
        return activeSegmentList;
    }
}
