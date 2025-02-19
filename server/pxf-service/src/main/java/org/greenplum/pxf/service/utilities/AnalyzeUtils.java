package org.greenplum.pxf.service.utilities;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.greenplum.pxf.api.model.Fragment;
import org.greenplum.pxf.api.model.RequestContext;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;


/**
 * Helper class to get statistics for ANALYZE.
 */
public class AnalyzeUtils {

    private static final Log LOG = LogFactory.getLog(AnalyzeUtils.class);

    /**
     * In case pxf_max_fragments parameter is declared, make sure not to get
     * over the limit. The returned fragments are evenly distributed, in order
     * to achieve good sampling.
     *
     * @param fragments fragments list
     * @param context container for parameters, including sampling data.
     * @return a list of fragments no bigger than pxf_max_fragments parameter.
     */
    static public List<Fragment> getSampleFragments(List<Fragment> fragments,
                                                    RequestContext context) {

        int listSize = fragments.size();
        int maxSize = context.getStatsMaxFragments();
        BitSet bitSet;

        if (maxSize == 0) {
            return fragments;
        }

        List<Fragment> samplingList = new ArrayList<>();

        LOG.debug("fragments list has " + listSize
                + " fragments, maxFragments = " + maxSize);

        bitSet = generateSamplingBitSet(listSize, maxSize);

        for (int i = 0; i < listSize; ++i) {
            if (bitSet.get(i)) {
                samplingList.add(fragments.get(i));
            }
        }

        return samplingList;
    }

    /**
     * Marks sampleSize bits out of the poolSize, in a uniform way.
     *
     * @param poolSize pool size
     * @param sampleSize sample size
     * @return bit set with sampleSize bits set out of poolSize.
     */
    static public BitSet generateSamplingBitSet(int poolSize, int sampleSize) {

        int chosen = 0, curIndex = 0;
        BitSet bitSet = new BitSet();

        if (poolSize <= 0 || sampleSize <= 0) {
            return bitSet;
        }

        if (sampleSize >= poolSize) {
            LOG.debug("sampling bit map has " + poolSize + " elements (100%)");
            bitSet.set(0, poolSize);
            return bitSet;
        }

        int skip = (poolSize / sampleSize) + 1;

        while (chosen < sampleSize) {

            bitSet.set(curIndex);
            chosen++;
            if (chosen == sampleSize) {
                break;
            }

            for (int i = 0; i < skip; ++i) {
                curIndex = nextClearBitModulo((++curIndex) % poolSize,
                        poolSize, bitSet);
                if (curIndex == -1) {
                    // should never happen
                    throw new IllegalArgumentException(
                            "Trying to sample more than pool size "
                                    + "(pool size " + poolSize
                                    + ", sampling size " + sampleSize);
                }
            }
        }

        LOG.debug("sampling bit map has " + chosen + " elements: " + bitSet);

        return bitSet;
    }

    /**
     * Returns index of next clear (false) bit, starting from and including
     * index. If all bits from index to the end are set (true), search from the
     * beginning. Return -1 if all bits are set (true).
     *
     * @param index starting point
     * @param poolSize the bit set size
     * @param bitSet bitset to search
     * @return index of next clear bit, starting in index
     */
    static private int nextClearBitModulo(int index, int poolSize, BitSet bitSet) {

        int indexToSet = bitSet.nextClearBit(index);
        if (indexToSet == poolSize && index != 0) {
            indexToSet = bitSet.nextClearBit(0);
        }
        /* means that all bits are already set, so we return -1 */
        if (indexToSet == poolSize) {
            return -1;
        }

        return indexToSet;
    }
}
