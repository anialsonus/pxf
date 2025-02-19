package org.greenplum.pxf.api.model;

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
import org.greenplum.pxf.api.error.UnsupportedTypeException;

/**
 * PXF supported output formats, enum which contains serializations classes
 */
@Getter
public enum OutputFormat {
    TEXT("org.greenplum.pxf.api.io.Text"),
    GPDBWritable("org.greenplum.pxf.api.io.GPDBWritable");

    private final String className;

    OutputFormat(String className) {
        this.className = className;
    }

    /**
     * Looks up output format for given class name if it exists.
     *
     * @param className class name implementing certain output format
     * @throws UnsupportedTypeException if output format with given class wasn't found
     * @return an output format with given class name
     */
    public static OutputFormat getOutputFormat(String className) {
        for (OutputFormat of : values()) {
            if (of.getClassName().equals(className)) {
                return of;
            }
        }
        throw new UnsupportedTypeException("Unable to find output format by given class name: " + className);
    }
}
