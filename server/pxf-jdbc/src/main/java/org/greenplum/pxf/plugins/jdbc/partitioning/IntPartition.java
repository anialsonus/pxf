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

import java.util.Objects;

public interface IntPartition extends JdbcFragmentMetadata {
    Long getStart();
    Long getEnd();

    static IntPartition create(String column, Long start, Long end) {
        if (start == null && end == null) {
            throw new RuntimeException("Both boundaries cannot be null");
        }
        return Objects.equals(start, end) ?
                new IntValuePartition(column, start) :
                new IntRangePartition(column, start, end);
    }

    static String convert(Long b) {
        return b == null ? null : b.toString();
    }
}
