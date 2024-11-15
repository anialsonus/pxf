package org.greenplum.pxf.plugins.jdbc.utils;

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

import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


public class DbProductTest {
    private static final Date[] DATES = new Date[1];
    private static final Timestamp[] TIMESTAMPS = new Timestamp[1];

    static {
        try {
            DATES[0] = new Date(
                    new SimpleDateFormat("yyyy-MM-dd").parse("2001-01-01 00:00:00").getTime()
            );
            TIMESTAMPS[0] = new Timestamp(
                    new SimpleDateFormat("yyyy-MM-dd").parse("2001-01-01 00:00:00").getTime()
            );
        } catch (ParseException e) {
            DATES[0] = null;
            TIMESTAMPS[0] = null;
        }
    }

    private static final String DB_NAME_UNKNOWN = "no such database";

    @Test
    public void testGetDbProduct() {
        assertEquals(DbProduct.MICROSOFT, DbProduct.getDbProduct("MICROSOFT", true));
        assertEquals(DbProduct.MYSQL, DbProduct.getDbProduct("MYSQL", true));
        assertEquals(DbProduct.ORACLE, DbProduct.getDbProduct("ORACLE", true));
        assertEquals(DbProduct.S3_SELECT, DbProduct.getDbProduct("S3 SELECT", true));
        assertEquals(DbProduct.SYBASE, DbProduct.getDbProduct("ADAPTIVE SERVER ENTERPRISE", true));
        assertEquals(DbProduct.POSTGRES, DbProduct.getDbProduct("POSTGRESQL", true));
    }

    @Test
    public void testUnknownProductIsPostgresProduct() {
        assertEquals(DbProduct.POSTGRES, DbProduct.getDbProduct(DB_NAME_UNKNOWN, true));
    }

    @Test
    public void testUnknownProductIsOtherProduct() {
        assertEquals(DbProduct.OTHER, DbProduct.getDbProduct(DB_NAME_UNKNOWN, false));
    }

    /**
     * This test also applies to Postgres database
     */
    @Test
    public void testUnknownDates() {
        final String[] expected = {"date'2001-01-01'"};

        DbProduct dbProduct = DbProduct.getDbProduct(DB_NAME_UNKNOWN, true);

        for (int i = 0; i < DATES.length; i++) {
            assertEquals(expected[i], dbProduct.wrapDate(String.valueOf(DATES[i])));
        }
    }

    /**
     * This test also applies to Postgres database
     */
    @Test
    public void testUnknownLocalDate() {
        DbProduct dbProduct = DbProduct.getDbProduct(DB_NAME_UNKNOWN, true);
        assertEquals("date'2001-03-04'", dbProduct.wrapDate(LocalDate.of(2001, 3, 4), false));
        assertEquals("date'2001-03-04 AD'", dbProduct.wrapDate(LocalDate.of(2001, 3, 4), true));
        assertEquals("date'99999-03-04 AD'", dbProduct.wrapDate(LocalDate.of(99999, 3, 4), true));
        assertEquals("date'0501-03-04 BC'", dbProduct.wrapDate(LocalDate.of(-500, 3, 4), true));
    }

    /**
     * This test also applies to Postgres database
     */
    @Test
    public void testUnknownTimestamps() {
        final String[] expected = {"'2001-01-01 00:00:00.0'"};

        DbProduct dbProduct = DbProduct.getDbProduct(DB_NAME_UNKNOWN, true);

        for (int i = 0; i < TIMESTAMPS.length; i++) {
            assertEquals(expected[i], dbProduct.wrapTimestamp(String.valueOf(TIMESTAMPS[i])));
        }
    }


    private static final String DB_NAME_ORACLE = "ORACLE";

    @Test
    public void testOracleDates() {
        final String[] expected = {"to_date('2001-01-01', 'YYYY-MM-DD')"};

        DbProduct dbProduct = DbProduct.ORACLE;

        for (int i = 0; i < DATES.length; i++) {
            assertEquals(expected[i], dbProduct.wrapDate(String.valueOf(DATES[i])));
        }
    }

    @Test
    public void testOracleLocalDate() {
        DbProduct dbProduct = DbProduct.ORACLE;
        assertEquals("to_date('2001-03-04', 'YYYY-MM-DD')", dbProduct.wrapDate(LocalDate.of(2001, 3, 4), false));
        assertEquals("to_date('2001-03-04', 'YYYY-MM-DD')", dbProduct.wrapDate(LocalDate.of(2001, 3, 4), true));
        assertEquals("to_date('-0500-03-04', 'YYYY-MM-DD')", dbProduct.wrapDate(LocalDate.of(-500, 3, 4), true));
    }

    @Test
    public void testOracleDatesWithTime() {
        final String[] expected = {"to_date('2001-01-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS')"};

        DbProduct dbProduct = DbProduct.ORACLE;

        for (int i = 0; i < TIMESTAMPS.length; i++) {
            assertEquals(expected[i], dbProduct.wrapDateWithTime(String.valueOf(TIMESTAMPS[i])));
        }
    }

    @Test
    public void testOracleTimestamps() {
        final String[] expected = {"to_timestamp('2001-01-01 00:00:00.0', 'YYYY-MM-DD HH24:MI:SS.FF')"};

        DbProduct dbProduct = DbProduct.ORACLE;

        for (int i = 0; i < TIMESTAMPS.length; i++) {
            assertEquals(expected[i], dbProduct.wrapTimestamp(String.valueOf(TIMESTAMPS[i])));
        }
    }

    @Test
    public void testMicrosoftDates() {
        final String[] expected = {"'2001-01-01'"};

        DbProduct dbProduct = DbProduct.MICROSOFT;

        for (int i = 0; i < DATES.length; i++) {
            assertEquals(expected[i], dbProduct.wrapDate(String.valueOf(DATES[i])));
        }
    }

    @Test
    public void testMySQLDates() {
        final String[] expected = {"DATE('2001-01-01')"};

        DbProduct dbProduct = DbProduct.MYSQL;

        for (int i = 0; i < DATES.length; i++) {
            assertEquals(expected[i], dbProduct.wrapDate(String.valueOf(DATES[i])));
        }
    }

    @Test
    public void testOracleWrapTimestampWithTZ() {
        String[] timestampsTZ = {
                "1985-05-11 15:10:00.12+03",
                "1985-05-12 15:10:00.123+05:30",
                "1985-05-13 15:10:00.1234+3",
                "1985-05-14 15:10:00-04:45",

        };
        final String[] expected = {
                "to_timestamp_tz('1985-05-11 15:10:00.12+03', 'YYYY-MM-DD HH24:MI:SS.FFTZH:TZM')",
                "to_timestamp_tz('1985-05-12 15:10:00.123+05:30', 'YYYY-MM-DD HH24:MI:SS.FFTZH:TZM')",
                "to_timestamp_tz('1985-05-13 15:10:00.1234+3', 'YYYY-MM-DD HH24:MI:SS.FFTZH:TZM')",
                "to_timestamp_tz('1985-05-14 15:10:00-04:45', 'YYYY-MM-DD HH24:MI:SS.FFTZH:TZM')",
        };
        DbProduct dbProduct = DbProduct.ORACLE;
        for (int i = 0; i < DATES.length; i++) {
            assertEquals(expected[i], dbProduct.wrapTimestampWithTZ(timestampsTZ[i]));
        }
    }

    @Test
    public void testMicrosoftWrapTimestampWithTZ() {
        String[] timestampsTZ = {
                "1985-05-11 15:10:00.12+03",
                "1985-05-12 15:11:00.123+05:30",
                "1985-05-13 15:12:00.1234+3",
                "1985-05-14 15:13:00-04:45",
                "1985-05-15 15:14:00-04",

        };
        final String[] expected = {
                "CONVERT(DATETIMEOFFSET, '1985-05-11T15:10:00.120+03:00')",
                "CONVERT(DATETIMEOFFSET, '1985-05-12T15:11:00.123+05:30')",
                "CONVERT(DATETIMEOFFSET, '1985-05-13T15:12:00.1234+03:00')",
                "CONVERT(DATETIMEOFFSET, '1985-05-14 15:13:00.000-04:45')",
                "CONVERT(DATETIMEOFFSET, '1985-05-15 15:14:00.000-04:00')"
        };
        DbProduct dbProduct = DbProduct.MICROSOFT;
        for (int i = 0; i < DATES.length; i++) {
            assertEquals(expected[i], dbProduct.wrapTimestampWithTZ(timestampsTZ[i]));
        }
    }

    @Test
    public void testPostgresWrapTimestampWithTZ() {
        String[] timestampsTZ = {
                "1985-05-11 15:10:00.12+03",
                "1985-05-12 15:10:00.123+05:30",
                "1985-05-13 15:10:00.1234+3",
                "1985-05-14 15:10:00-04:45",

        };
        final String[] expected = {
                "'1985-05-11 15:10:00.12+03'",
                "'1985-05-12 15:10:00.123+05:30'",
                "1985-05-13 15:10:00.1234+3'",
                "'1985-05-14 15:10:00-04:45'",
        };
        DbProduct dbProduct = DbProduct.POSTGRES;
        for (int i = 0; i < DATES.length; i++) {
            assertEquals(expected[i], dbProduct.wrapTimestampWithTZ(timestampsTZ[i]));
        }
    }

    @Test
    public void testUnknownWrapTimestampWithTZ() {
        DbProduct dbProduct = DbProduct.OTHER;
        Exception e = assertThrows(UnsupportedOperationException.class, () ->  dbProduct.wrapTimestampWithTZ("1985-05-11 15:10:00.12+03"));
        assertEquals("The database doesn't support pushdown of the `TIMESTAMP WITH TIME ZONE` data type", e.getMessage());
    }
}
