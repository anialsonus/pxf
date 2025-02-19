package org.greenplum.pxf.service.profile;

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

import lombok.Getter;

/**
 * Thrown when there is a configuration problem with pxf profiles definitions.
 * {@link ProfileConfException.MessageFormat#PROFILES_FILE_NOT_FOUND} when pxf-profiles.xml is missing from the CLASSPATH.
 * {@link ProfileConfException.MessageFormat#PROFILES_FILE_LOAD_ERR} when pxf-profiles.xml is not valid.
 * {@link ProfileConfException.MessageFormat#NO_PROFILE_DEF} when a profile entry or attribute is missing.
 */
@Getter
public class ProfileConfException extends RuntimeException {
    private final MessageFormat msgFormat;

    /**
     * Constructs a ProfileConfException.
     *
     * @param msgFormat the message format
     * @param msgArgs   the message arguments
     */
    ProfileConfException(MessageFormat msgFormat, String... msgArgs) {
        super(String.format(msgFormat.getFormat(), (Object[]) msgArgs));
        this.msgFormat = msgFormat;
    }

    @Getter
    public enum MessageFormat {
        PROFILES_FILE_NOT_FOUND("%s was not found in the CLASSPATH"),
        PROFILES_FILE_LOAD_ERR("Profiles configuration %s could not be loaded: %s"),
        NO_PROFILE_DEF("%s is not defined in %s"),
        NO_PLUGINS_IN_PROFILE_DEF("Profile %s does not define any plugins in %s");

        final String format;

        MessageFormat(String format) {
            this.format = format;
        }

    }
}
