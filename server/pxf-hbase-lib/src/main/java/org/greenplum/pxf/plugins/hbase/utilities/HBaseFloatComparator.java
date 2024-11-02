package org.greenplum.pxf.plugins.hbase.utilities;

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

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.hadoop.hbase.exceptions.DeserializationException;
import org.apache.hadoop.hbase.filter.ByteArrayComparable;
import org.apache.hadoop.hbase.filter.SubstringComparator;
import org.apache.hadoop.hbase.protobuf.generated.ComparatorProtos;
import org.apache.hadoop.hbase.util.Bytes;

/**
 * This is a Filter comparator for HBase It is external to PXF HBase code.
 * <p>
 * To use with HBase it must reside in the classpath of every region server.
 * <p>
 * It converts a value into {@link Long} before comparing.
 * The filter is good for any integer numeric comparison i.e. integer, bigint, smallint.
 * <p>
 * according to HBase 0.96 requirements, this must serialized using Protocol Buffers
 * ({@link #toByteArray()} and {@link #parseFrom(byte[])} methods).
 * <p>
 * A reference can be found in {@link SubstringComparator}.
 * This class MUST ONLY use features from java 8 and lower as it will be loaded within hbase
 */
public class HBaseFloatComparator extends ByteArrayComparable{

    private final Float val;

    public HBaseFloatComparator(float inVal) {
        super(Bytes.toBytes(inVal));
        this.val = inVal;
    }

    @Override
    public byte[] toByteArray() {
        ComparatorProtos.ByteArrayComparable.Builder builder = ComparatorProtos.ByteArrayComparable.newBuilder();
        builder.setValue(ByteString.copyFrom(getValue()));
        return builder.build().toByteArray();
    }

    @Override
    public int compareTo(byte[] value, int offset, int length) {
        if (length == 0)
            return 1;

        String valueAsString = new String(value, offset, length);
        Float valueAsFloat = Float.parseFloat(valueAsString);
        return val.compareTo(valueAsFloat);
    }

    public static ByteArrayComparable parseFrom(final byte[] pbBytes) throws DeserializationException {
        ComparatorProtos.ByteArrayComparable proto;
        try {
            proto = ComparatorProtos.ByteArrayComparable.parseFrom(pbBytes);
        } catch (InvalidProtocolBufferException e) {
            throw new DeserializationException(e);
        }

        return new HBaseFloatComparator(Bytes.toFloat(proto.getValue().toByteArray()));
    }
}
