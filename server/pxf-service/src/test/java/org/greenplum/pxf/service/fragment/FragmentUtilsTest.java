package org.greenplum.pxf.service.fragment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FragmentUtilsTest {

    @Test
    void getActiveSegmentListWithShiftIndex0() {
        int shiftedIndex = 0;
        int activeSegmentCount = 2;
        int totalSegments = 10;
        List<Integer> expected = List.of(0, 5);
        List<Integer> actual = FragmentUtils.getActiveSegmentList(shiftedIndex, activeSegmentCount, totalSegments);
        assertEquals(expected, actual);
    }

    @Test
    void getActiveSegmentListWithShiftIndex1() {
        int shiftedIndex = 1;
        int activeSegmentCount = 3;
        int totalSegments = 10;
        List<Integer> expected = List.of(1, 5, 9);
        List<Integer> actual = FragmentUtils.getActiveSegmentList(shiftedIndex, activeSegmentCount, totalSegments);
        assertEquals(expected, actual);
    }

    @Test
    void getActiveSegmentListWithShiftBigIndex() {
        int shiftedIndex = 3;
        int activeSegmentCount = 3;
        int totalSegments = 10;
        List<Integer> expected = List.of(3, 7, 1);
        List<Integer> actual = FragmentUtils.getActiveSegmentList(shiftedIndex, activeSegmentCount, totalSegments);
        assertEquals(expected, actual);
    }

    @Test
    void getActiveSegmentListWithNotRepeatingSegId() {
        int shiftedIndex = 0;
        int activeSegmentCount = 5;
        int totalSegments = 6;
        List<Integer> expected = List.of(0, 2, 4, 1, 5);
        List<Integer> actual = FragmentUtils.getActiveSegmentList(shiftedIndex, activeSegmentCount, totalSegments);
        assertEquals(expected, actual);
    }

    @Test
    void getActiveSegmentListWhenActiveSegmentCountIsTheSameAsTotalSegments() {
        int shiftedIndex = 3;
        int activeSegmentCount = 6;
        int totalSegments = 6;
        List<Integer> expected = List.of(3, 4, 5, 0, 1, 2);
        List<Integer> actual = FragmentUtils.getActiveSegmentList(shiftedIndex, activeSegmentCount, totalSegments);
        assertEquals(expected, actual);
    }
}