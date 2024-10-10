package org.greenplum.pxf.plugins.jdbc.partitioning;

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

import org.greenplum.pxf.plugins.jdbc.utils.DbProduct;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class IntPartitionTest {

    private final DbProduct dbProduct = null;

    private final String COL_RAW = "col";
    private final String QUOTE = "\"";
    private final String COL = QUOTE + COL_RAW + QUOTE;
    private final boolean WRAP_DATE_WITH_TIME = false;

    @Test
    public void testNormal() {
        IntPartition partition = IntPartition.create(COL_RAW, 0L, 1L);
        String constraint = partition.toSqlConstraint(QUOTE, dbProduct, WRAP_DATE_WITH_TIME);

        assertEquals(COL + " >= 0 AND " + COL + " < 1", constraint);
    }

    @Test
    public void testRightBounded() {
        IntPartition partition = IntPartition.create(COL_RAW, null, 0L);
        String constraint = partition.toSqlConstraint(QUOTE, dbProduct, WRAP_DATE_WITH_TIME);

        assertEquals(COL + " < 0", constraint);
    }

    @Test
    public void testLeftBounded() {
        IntPartition partition = IntPartition.create(COL_RAW, 0L, null);
        String constraint = partition.toSqlConstraint(QUOTE, dbProduct, WRAP_DATE_WITH_TIME);

        assertEquals(COL + " >= 0", constraint);
    }

    @Test
    public void testRightBoundedInclusive() {
        IntPartition partition = IntPartition.create(COL_RAW, null, 0L);
        String constraint = partition.toSqlConstraint(QUOTE, dbProduct, WRAP_DATE_WITH_TIME);

        assertEquals(COL + " < 0", constraint);
    }

    @Test
    public void testLeftBoundedInclusive() {
        IntPartition partition = IntPartition.create(COL_RAW, 0L, null);
        String constraint = partition.toSqlConstraint(QUOTE, dbProduct, WRAP_DATE_WITH_TIME);

        assertEquals(COL + " >= 0", constraint);
    }

    @Test
    public void testEqualBoundaries() {
        IntPartition partition = IntPartition.create(COL_RAW, 0L, 0L);
        String constraint = partition.toSqlConstraint(QUOTE, dbProduct, WRAP_DATE_WITH_TIME);

        assertEquals(COL + " = 0", constraint);
    }

    @Test
    public void testEqualBoundariesMaxValue() {
        long value = Long.MAX_VALUE;
        IntPartition partition = IntPartition.create(COL_RAW, value, value);
        String constraint = partition.toSqlConstraint(QUOTE, dbProduct, WRAP_DATE_WITH_TIME);

        assertEquals(COL + " = " + value, constraint);
    }

    @Test
    public void testInvalidBothBoundariesNull() {
        assertThrows(RuntimeException.class,
            () -> IntPartition.create(COL_RAW, null, null)
        );
    }

    @Test
    public void testInvalidColumnNull() {
        assertThrows(RuntimeException.class,
            () -> IntPartition.create(null, 0L, 1L)
        );
    }

    @Test
    public void testInvalidNullQuoteString() {
        IntPartition partition = IntPartition.create(COL_RAW, 0L, 1L);

        assertThrows(RuntimeException.class,
            () -> partition.toSqlConstraint(null, dbProduct, WRAP_DATE_WITH_TIME));
    }
}
