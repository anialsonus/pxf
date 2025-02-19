package org.greenplum.pxf.plugins.hdfs.utilities;

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


import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionInputStream;
import org.apache.hadoop.io.compress.CompressionOutputStream;
import org.apache.hadoop.io.compress.Compressor;
import org.apache.hadoop.io.compress.Decompressor;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Codec class for UtilitiesTest
 * Can't be embedded inside UtilitiesTest due to junit limitation.
 */
public class NotSoNiceCodec implements CompressionCodec {

    @Override
    public CompressionOutputStream createOutputStream(OutputStream out) {
        return null;
    }

    @Override
    public CompressionOutputStream createOutputStream(OutputStream out,
                                                      Compressor compressor) {
        return null;
    }

    @Override
    public Class<? extends Compressor> getCompressorType() {
        return null;
    }

    @Override
    public Compressor createCompressor() {
        return null;
    }

    @Override
    public CompressionInputStream createInputStream(InputStream in) {
        return null;
    }

    @Override
    public CompressionInputStream createInputStream(InputStream in,
                                                    Decompressor decompressor) {
        return null;
    }

    @Override
    public Class<? extends Decompressor> getDecompressorType() {
        return null;
    }

    @Override
    public Decompressor createDecompressor() {
        return null;
    }

    @Override
    public String getDefaultExtension() {
        return null;
    }

}
