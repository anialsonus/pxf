package org.greenplum.pxf.plugins.hive.utilities;

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

import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.hive.ql.io.RCFileInputFormat;
import org.apache.hadoop.hive.ql.io.orc.OrcInputFormat;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.TextInputFormat;

/**
 * Factory class which returns optimal profile for given input format
 *
 */
public class ProfileFactory {
    private static final String HIVE_PROFILE = "hive";
    private static final String HIVE_TEXT_PROFILE = "hive:text";
    private static final String HIVE_RC_PROFILE = "hive:rc";
    private static final String HIVE_ORC_PROFILE = "hive:orc";

    /**
     * The method which returns optimal profile
     *
     * @param inputFormat input format of table/partition
     * @param hasComplexTypes whether record has complex types, see @EnumHiveToGpdbType
     * @param userProfileName profile name provided by user
     * @return name of optimal profile
     */
    public static String get(InputFormat<?,?> inputFormat, boolean hasComplexTypes, String userProfileName) {
        if (StringUtils.isNotBlank(userProfileName) && !userProfileName.equalsIgnoreCase(HIVE_PROFILE)) {
            return userProfileName;
        }

        if (inputFormat instanceof TextInputFormat && !hasComplexTypes) {
            return HIVE_TEXT_PROFILE;
        } else if (inputFormat instanceof RCFileInputFormat) {
            return HIVE_RC_PROFILE;
        } else if (inputFormat instanceof OrcInputFormat) {
            return HIVE_ORC_PROFILE;
        } else {
            return HIVE_PROFILE;
        }
    }

    /**
     * @see ProfileFactory#get(InputFormat, boolean, String)
     *
     * @param inputFormat input format of table/partition
     * @param hasComplexTypes whether record has complex types, see @EnumHiveToGpdbType
     * @return name of optimal profile
     */
    public static String get(InputFormat<?, ?> inputFormat, boolean hasComplexTypes) {
        return get(inputFormat, hasComplexTypes, null);
    }

}
