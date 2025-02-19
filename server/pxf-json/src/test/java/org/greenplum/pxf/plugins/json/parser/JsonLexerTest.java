package org.greenplum.pxf.plugins.json.parser;

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

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class JsonLexerTest {

    private static final Log LOG = LogFactory.getLog(JsonLexerTest.class);

    @Test
    public void testSimple() throws IOException {
        File testsDir = new File("src/test/resources/lexer-tests");
        File[] jsonFiles = testsDir.listFiles((file, s) -> s.endsWith(".json"));
        if (Objects.isNull(jsonFiles)) {
            throw new IllegalStateException("Json files cannot be null");
        }
        for (File jsonFile : jsonFiles) {
            File stateFile = new File(jsonFile.getAbsolutePath() + ".state");
            if (stateFile.exists()) {
                runTest(jsonFile, stateFile);
            }
        }
    }

    public static Pattern STATE_RECURRENCE = Pattern.compile("^([A-Za-z_0-9]+)\\{([0-9]+)}$");

    public void runTest(File jsonFile, File stateFile) throws IOException {
        List<String> lexerStates = FileUtils.readLines(stateFile, Charset.defaultCharset());

        try (InputStream jsonInputStream = Files.newInputStream(jsonFile.toPath())) {
            JsonLexer lexer = new JsonLexer();

            int byteOffset = 0;
            int i;
            ListIterator<String> stateIterator = lexerStates.listIterator();
            int recurrence = 0;
            JsonLexer.JsonLexerState expectedState = null;
            StringBuilder sb = new StringBuilder();
            int stateFileLineNum = 0;
            while ((i = jsonInputStream.read()) != -1) {
                byteOffset++;
                char c = (char) i;

                sb.append(c);

                lexer.lex(c);

                if (lexer.getState() == JsonLexer.JsonLexerState.WHITESPACE) {
                    // optimization to skip over multiple whitespaces
                    continue;
                }

                if (!stateIterator.hasNext()) {
                    fail(formatStateInfo(jsonFile, sb.toString(), byteOffset, stateFileLineNum)
                            + ": Input stream had character '" + c + "' but no matching state");
                }

                if (recurrence <= 0) {
                    String state = stateIterator.next().trim();
                    stateFileLineNum++;

                    while (state.isEmpty() || state.startsWith("#")) {
                        if (!stateIterator.hasNext()) {
                            fail(formatStateInfo(jsonFile, sb.toString(), byteOffset, stateFileLineNum)
                                    + ": Input stream had character '" + c + "' but no matching state");
                        }
                        state = stateIterator.next().trim();
                        stateFileLineNum++;
                    }

                    Matcher m = STATE_RECURRENCE.matcher(state);
                    recurrence = 1;
                    if (m.matches()) {
                        state = m.group(1);
                        recurrence = Integer.parseInt(m.group(2));
                    }
                    expectedState = JsonLexer.JsonLexerState.valueOf(state);
                }

                assertEquals(expectedState, lexer.getState(), formatStateInfo(jsonFile, sb.toString(), byteOffset, stateFileLineNum)
                        + ": Issue for char '" + c + "'");
                recurrence--;
            }

            if (stateIterator.hasNext()) {
                fail(formatStateInfo(jsonFile, sb.toString(), byteOffset, stateFileLineNum)
                        + ": Input stream has ended but more states were expected: '" + stateIterator.next() + "...'");
            }

        }

        LOG.info("File " + jsonFile.getName() + " passed");

    }

    static String formatStateInfo(File jsonFile, String streamContents, int streamByteOffset, int stateFileLineNum) {
        return jsonFile.getName() + ": Input stream currently at byte-offset " + streamByteOffset + ", contents = '"
                + streamContents + "'" + " state-file line = " + stateFileLineNum;
    }
}
