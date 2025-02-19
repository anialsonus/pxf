package org.greenplum.pxf.plugins.hdfs;

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


import org.apache.hadoop.io.Writable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;

/**
 * A class that provides a line reader from an input stream. Lines are
 * terminated by '\n' (LF) EOF also terminates an otherwise unterminated line.
 */
public class ChunkReader implements Closeable {
    public static final int DEFAULT_BUFFER_SIZE = 64 * 1024;
    private final InputStream in;
    private final byte[] buffer;
    // the number of bytes of real data in the buffer
    private int bufferLength = 0;
    // the current position in the buffer
    private int bufferPosn = 0;
    private static final byte LF = '\n';

    /**
     * Constructs a ChunkReader instance
     *
     * @param in input stream
     */
    public ChunkReader(InputStream in) {
        this.in = in;
        this.buffer = new byte[DEFAULT_BUFFER_SIZE];
    }

    /**
     * Closes the underlying stream.
     */
    @Override
    public void close() throws IOException {
        in.close();
    }

    /*
     * Internal class used for holding part of a chunk brought by one read()
     * operation on the input stream. We collect several such nodes in a list by
     * doing several read operation until we reach the chunk size -
     * maxBytesToConsume
     */
    private static class Node {
        /* part of a chunk brought in a single inputstream.read() operation */
        public byte[] slice;
        /* the size of the slice */
        public int len;
    }

    /**
     * Reads data in chunks of DEFAULT_CHUNK_SIZE, until we reach
     * maxBytesToConsume.
     *
     * @param str               - output parameter, will contain the read chunk byte array
     * @param maxBytesToConsume - requested chunk size
     * @return actual chunk size
     * @throws IOException if the first byte cannot be read for any reason
     *                     other than the end of the file, if the input stream has been closed,
     *                     or if some other I/O error occurs.
     */
    public int readChunk(Writable str, int maxBytesToConsume) throws IOException {
        ChunkWritable cw = (ChunkWritable) str;
        List<Node> list = new LinkedList<>();

        long bytesConsumed = 0;

        do {
            if (bufferLength > 0) {
                int remaining = bufferLength - bufferPosn;
                Node nd = new Node();
                nd.slice = new byte[remaining];
                nd.len = remaining;
                System.arraycopy(buffer, bufferPosn, nd.slice, 0, nd.len);
                list.add(nd);
                bytesConsumed += nd.len;
            } else {
                Node nd = new Node();
                nd.slice = new byte[buffer.length];
                nd.len = in.read(nd.slice);
                if (nd.len <= 0) {
                    break; // EOF
                }
                bytesConsumed += nd.len;
                list.add(nd);
            }

            bufferLength = bufferPosn = 0;

        } while (bytesConsumed < maxBytesToConsume);

        copyListToChunkWritable(cw, list, bytesConsumed);

        return (int) bytesConsumed;
    }

    /**
     * This function iterates over the list of nodes and copies data to ChunkWritable.box array
     *
     * @param cw            ChunkWritable Object
     * @param nodeList      List containing the Node Object
     * @param bytesConsumed chunk size
     */
    private void copyListToChunkWritable(ChunkWritable cw, List<Node> nodeList, long bytesConsumed) {

        if (nodeList.isEmpty()) {
            return;
        }

        cw.box = new byte[(int) bytesConsumed];
        int pos = 0;
        for (Node node : nodeList) {
            int len = node.len;
            System.arraycopy(node.slice, 0, cw.box, pos, len);
            pos += len;
        }
    }

    /**
     * Reads a line terminated by LF.
     *
     * @param str               - output parameter, will contain the read record
     * @param maxBytesToConsume - the line mustn't exceed this value
     * @return length of the line read
     * @throws IOException if the first byte cannot be read for any reason
     *                     other than the end of the file, if the input stream has been closed,
     *                     or if some other I/O error occurs.
     */
    public int readLine(Writable str, int maxBytesToConsume) throws IOException {
        ChunkWritable cw = (ChunkWritable) str;
        List<Node> list = new LinkedList<>();

        boolean newLine = false; // length of terminating newline
        long bytesConsumed = 0;

        do {
            int startPosn = bufferPosn; // starting from where we left off the last time
            if (bufferPosn >= bufferLength) {
                startPosn = bufferPosn = 0;

                bufferLength = in.read(buffer);
                if (bufferLength <= 0) {
                    break; // EOF
                }
            }

            for (; bufferPosn < bufferLength; ++bufferPosn) { // search for newline
                if (buffer[bufferPosn] == LF) {
                    newLine = true;
                    ++bufferPosn; // at next invocation proceed from following byte
                    break;
                }
            }

            int readLength = bufferPosn - startPosn;
            bytesConsumed += readLength;

            if (readLength > 0) {
                Node nd = new Node();
                nd.slice = new byte[readLength];
                nd.len = readLength;
                System.arraycopy(buffer, startPosn, nd.slice, 0, nd.len);
                list.add(nd);
            }
        } while (!newLine && bytesConsumed < maxBytesToConsume);

        copyListToChunkWritable(cw, list, bytesConsumed);

        return (int) bytesConsumed;
    }
}
