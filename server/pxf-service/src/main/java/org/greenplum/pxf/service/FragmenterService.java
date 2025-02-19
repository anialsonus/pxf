package org.greenplum.pxf.service;

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

import com.google.common.util.concurrent.UncheckedExecutionException;
import org.greenplum.pxf.api.error.PxfRuntimeException;
import org.greenplum.pxf.api.model.Fragment;
import org.greenplum.pxf.api.model.Fragmenter;
import org.greenplum.pxf.api.model.RequestContext;
import org.greenplum.pxf.api.utilities.FragmenterCacheFactory;
import org.greenplum.pxf.service.utilities.AnalyzeUtils;
import org.greenplum.pxf.service.utilities.BasePluginFactory;
import org.greenplum.pxf.service.utilities.GSSFailureHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * The {@link FragmenterService} returns fragments for a given segment. It
 * performs caching of Fragment for a unique query. The first segment to
 * request the list of fragments will populate it, while the rest of the
 * segments will wait until the list of fragments is populated.
 */
@Component
public class FragmenterService {

    private static final Logger LOG = LoggerFactory.getLogger(FragmenterService.class);
    public static final String ACTIVE_SEGMENT_COUNT_OPTION = "ACTIVE_SEGMENT_COUNT";
    private final BasePluginFactory pluginFactory;
    private final FragmenterCacheFactory fragmenterCacheFactory;
    private final GSSFailureHandler failureHandler;

    public FragmenterService(FragmenterCacheFactory fragmenterCacheFactory,
                             BasePluginFactory pluginFactory,
                             GSSFailureHandler failureHandler) {
        this.fragmenterCacheFactory = fragmenterCacheFactory;
        this.pluginFactory = pluginFactory;
        this.failureHandler = failureHandler;
    }

    public List<Fragment> getFragmentsForSegment(RequestContext context) throws IOException {

        LOG.trace("Received FRAGMENTER call");

        // Get active segment count of logical segments which will take part in the request
        int activeSegmentCount = getActiveSegmentCount(context);

        Instant startTime = Instant.now();
        final String path = context.getDataSource();

        if (LOG.isDebugEnabled()) {
            LOG.debug("fragmentCache size={}, stats={}",
                    fragmenterCacheFactory.getCache().size(),
                    fragmenterCacheFactory.getCache().stats());
        }

        LOG.debug("FRAGMENTER started for path \"{}\"", path);

        List<Fragment> fragments = getFragmentsFromCache(context, startTime);

        List<Fragment> filteredFragments = filterFragments(fragments,
                context.getSegmentId(),
                activeSegmentCount,
                context.getTotalSegments(),
                context.getGpSessionId(),
                context.getGpCommandCount());

        if (LOG.isDebugEnabled()) {
            int numberOfFragments = filteredFragments.size();
            long elapsedMillis = Duration.between(startTime, Instant.now()).toMillis();

            LOG.debug("Returning {}/{} fragment{} for path {} in {} ms [profile={}, predicate {}available]",
                    numberOfFragments, fragments.size(), numberOfFragments == 1 ? "" : "s",
                    context.getDataSource(), elapsedMillis, context.getProfile(), context.hasFilter() ? "" : "un");
        }

        return filteredFragments;
    }

    private int getActiveSegmentCount(RequestContext context) {
        try {
            int activeSegmentCount = Optional.ofNullable(context.getOptions().get(ACTIVE_SEGMENT_COUNT_OPTION))
                    .map(Integer::valueOf)
                    .orElse(context.getTotalSegments());
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

    /**
     * Returns the list of fragments from the fragmenter cache. If the cache is
     * empty, it populates the cache with the list of fragments. When
     * concurrent requests are made to the cache with the same key, the first
     * request will populate the cache, while the other requests will wait
     * until the cache entry is populated.
     *
     * @param context   the request context
     * @param startTime the start time of the request
     * @return the list of fragments for the request
     * @throws IOException when an exception occurs
     */
    private List<Fragment> getFragmentsFromCache(RequestContext context, Instant startTime) throws IOException {
        final String fragmenterCacheKey = getFragmenterCacheKey(context);
        try {
            return fragmenterCacheFactory.getCache()
                    .get(fragmenterCacheKey, () -> {
                        LOG.debug("Caching fragments from segmentId={} with key={}",
                                context.getSegmentId(), fragmenterCacheKey);

                        List<Fragment> fragmentList = failureHandler.execute(context.getConfiguration(),
                                "get fragments", () -> getFragmenter(context).getFragments());

                        /* Create a fragmenter instance with API level parameters */
                        fragmentList = AnalyzeUtils.getSampleFragments(fragmentList, context);
                        updateFragmentIndex(fragmentList);

                        int numberOfFragments = fragmentList.size();
                        long elapsedMillis = Duration.between(startTime, Instant.now()).toMillis();
                        String fragmenterClassName = context.getFragmenter();
                        LOG.info("Returning {} fragment{} in {} ms [user={}, table={}.{}, resource={}, fragmenter={}, profile={}, predicate {}available]",
                                numberOfFragments,
                                numberOfFragments == 1 ? "" : "s",
                                elapsedMillis,
                                context.getUser(),
                                context.getSchemaName(),
                                context.getTableName(),
                                context.getDataSource(),
                                fragmenterClassName.substring(fragmenterClassName.lastIndexOf(".") + 1),
                                context.getProfile(),
                                context.hasFilter() ? "" : "un");

                        return fragmentList;
                    });
        } catch (UncheckedExecutionException | ExecutionException e) {
            // Unwrap the error
            Exception exception = e.getCause() != null ? (Exception) e.getCause() : e;
            if (exception instanceof IOException)
                throw (IOException) exception;
            throw new IOException(exception);
        }
    }

    /**
     * Filters the {@code fragments} for the given segment. To determine which
     * segment S should process an element at a given index i, use a randomized
     * MOD function
     * <p>
     * S = MOD(I + MOD(gp_session_id, N) + gp_command_count, N)
     * <p>
     * which ensures more fair work distribution for small lists of just a few
     * elements across N segments global session ID and command count are used
     * as a randomizer, as it is different for every query, while being the
     * same across all segments for a given query.
     *
     * @param fragments      the list of fragments
     * @param segmentId      the identifier for the segment processing the request
     * @param totalSegments  the total number of segments
     * @param gpSessionId    the Greenplum session ID
     * @param gpCommandCount the command number for this Greenplum Session ID
     * @return the filtered list of fragments for the given segment
     */
    private List<Fragment> filterFragments(List<Fragment> fragments,
                                           int segmentId,
                                           int activeSegmentCount,
                                           int totalSegments,
                                           int gpSessionId,
                                           int gpCommandCount) {
        /*
        We use a mod function inside the loop to distribute fragments across all N segments for processing
        in a round-robin fashion, where each segment will be allocated to work on every N-th fragment.

        We use an artificially shifted index so that queries with low fragment count are not always executed by
        the low-numbered segments. This helps to spread out the workload across the cluster to different PXF JVMs.
        Using gpCommandCount will ensure that consecutive queries for a single transaction are also shifted.
        */
        int shiftedIndex = gpSessionId % totalSegments + gpCommandCount; // index of fragment #0 to use for mod function

        if (totalSegments == activeSegmentCount) {
            List<Fragment> filteredFragments = new ArrayList<>((int) Math.ceil((double) fragments.size() / totalSegments));
            for (Fragment fragment : fragments) {
                if (segmentId == (shiftedIndex % totalSegments)) {
                    filteredFragments.add(fragment);
                }
                shiftedIndex++;
            }
            return filteredFragments;
        } else {
            int index = 0;
            List<Integer> activeSegmentList = getActiveSegmentList(shiftedIndex, activeSegmentCount, totalSegments);
            List<Fragment> filteredFragments = new ArrayList<>((int) Math.ceil((double) fragments.size() / activeSegmentCount));
            for (Fragment fragment : fragments) {
                if (segmentId == activeSegmentList.get(index % activeSegmentList.size())) {
                    filteredFragments.add(fragment);
                }
                index++;
            }
            return filteredFragments;
        }
    }

    private static List<Integer> getActiveSegmentList(int shiftedIndex, int activeSegmentCount, int totalSegments) {
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
        LOG.debug("The fragments will be distributed between segments with segment id: {}", activeSegmentList);
        return activeSegmentList;
    }

    /**
     * Returns the fragmenter initialized with the request context
     *
     * @param context the request context
     * @return the fragmenter initialized with the request context
     */
    private Fragmenter getFragmenter(RequestContext context) {
        return pluginFactory.getPlugin(context, context.getFragmenter());
    }

    /**
     * Returns a key for the fragmenter cache. TransactionID is not sufficient to key the cache.
     * For the case where we have multiple scans
     * (i.e select a, b from c where a = 'part1' union all select a, b from c where a = 'part2'),
     * the list of fragments for each scan in the query will be different, but the transactionID will be the same.
     * For that reason we must include the schema name, table name, and the filter string as part
     * of the fragmenter cache key. We also include dataSource for a case where the table is recreated during
     * the same transaction (in a PLSQL function, for example) and now points to a different location.
     *
     * @param context the request context
     * @return the key for the fragmenter cache
     */
    private String getFragmenterCacheKey(RequestContext context) {
        return String.format("%s:%s:%s:%s:%s",
                context.getTransactionId(),
                context.getSchemaName(),
                context.getTableName(),
                context.getDataSource(),
                context.getFilterString());
    }

    /**
     * Updates the fragments' indexes so that it is incremented by sourceName.
     * (E.g.: {"a", 0}, {"a", 1}, {"b", 0} ... )
     *
     * @param fragments fragments to be updated
     */
    private void updateFragmentIndex(List<Fragment> fragments) {
        int index = 0;
        String sourceName = null;
        for (Fragment fragment : fragments) {

            String currentSourceName = fragment.getSourceName();
            if (!currentSourceName.equals(sourceName)) {
                index = 0;
                sourceName = currentSourceName;
            }
            fragment.setIndex(index++);
        }
    }
}
