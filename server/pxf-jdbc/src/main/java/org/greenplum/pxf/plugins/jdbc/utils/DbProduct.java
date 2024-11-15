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

import lombok.NonNull;
import org.greenplum.pxf.plugins.jdbc.utils.oracle.OracleJdbcUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME;
import static org.greenplum.pxf.plugins.jdbc.utils.DateTimeEraFormatters.OFFSET_DATE_TIME_WITH_TIME_ZONE_FORMATTER;

/**
 * A tool class to change PXF-JDBC plugin behaviour for certain external databases
 */
public enum DbProduct {
    MICROSOFT {
        @Override
        public String buildSessionQuery(String key, String value) {
            return String.format("SET %s %s", key, value);
        }

        /**
         * Convert Postgres timestamp with time zone string to the appropriate Microsoft SQL Server DATETIMEOFFSET string format.
         * The Microsoft SQL Server DATETIMEOFFSET type supports only the `+|-hh:mm` format or the literal `Z` for time zones.
         * Greenplum may send a timestamp with a time zone that contains only hours, for example, +03.
         * We use the OffsetDateTime#toString method to convert time zones without minutes to those with minutes or to Z.
         * In case of a parsing error, we will avoid pushdown and send the query without the WHERE clause.
         */
        @Override
        public String wrapTimestampWithTZ(String val) {
            try {
                String valStr = OffsetDateTime.parse(val, OFFSET_DATE_TIME_WITH_TIME_ZONE_FORMATTER).toString();
                return "CONVERT(DATETIMEOFFSET, '" + valStr + "')";
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        String.format("The value '%s' cannot be converted to the Microsoft SQL Server 'DATETIMEOFFSET' type", val));
            }
        }
    },

    MYSQL {
        @Override
        public String wrapDate(String val) {
            return "DATE('" + val + "')";
        }
    },

    ORACLE {
        @Override
        public String wrapDate(String val) {
            return "to_date('" + val + "', 'YYYY-MM-DD')";
        }

        @Override
        public String wrapDateWithTime(String val) {
            String valStr = String.valueOf(val);
            int index = valStr.lastIndexOf('.');
            if (index != -1) {
                valStr = valStr.substring(0, index);
            }
            return "to_date('" + valStr + "', 'YYYY-MM-DD HH24:MI:SS')";
        }

        @Override
        public String wrapTimestamp(String val) {
            return "to_timestamp('" + val + "', 'YYYY-MM-DD HH24:MI:SS.FF')";
        }

        /**
         * Convert Postgres timestamp with time zone string to the appropriate Oracle timestamp with time zone string format.
         */
        @Override
        public String wrapTimestampWithTZ(String val) {
            return "to_timestamp_tz('" + val + "', 'YYYY-MM-DD HH24:MI:SS.FFTZH:TZM')";
        }

        @Override
        public String buildSessionQuery(String key, String value) {
            return OracleJdbcUtils.buildSessionQuery(key, value);
        }
    },

    POSTGRES {
        @Override
        public String wrapDate(String val) {
            return "date'" + val + "'";
        }

        @Override
        public String wrapDate(@NonNull LocalDate val, boolean isDateWideRange) {
            return wrapDate(isDateWideRange ? val.format(DateTimeEraFormatters.LOCAL_DATE_FORMATTER) : val.toString());
        }

        @Override
        public String wrapTimestamp(@NonNull LocalDateTime val, boolean isDateWideRange) {
            return wrapTimestamp(isDateWideRange ? val.format(DateTimeEraFormatters.LOCAL_DATE_TIME_FORMATTER) : val.toString());
        }

        @Override
        public String wrapTimestampWithTZ(String val) {
            return "'" + val + "'";
        }
    },

    S3_SELECT {
        @Override
        public String wrapDate(String val) {
            return "TO_TIMESTAMP('" + val + "')";
        }

        @Override
        public String wrapDateWithTime(String val) {
            return wrapTimestamp(val);
        }

        @Override
        public String wrapTimestamp(String val) {
            return "TO_TIMESTAMP('" + val + "')";
        }
    },

    SYBASE {
        @Override
        public String buildSessionQuery(String key, String value) {
            return String.format("SET %s %s", key, value);
        }

        @Override
        public String wrapTimestampWithTZ(String val) {
            throw new UnsupportedOperationException(
                    String.format("The database %s doesn't support the TIMESTAMP WITH TIME ZONE data type", this));
        }
    },

    OTHER;

    /**
     * Wraps a given date value the way required by target database
     *
     * @param val {@link java.sql.Date} object to wrap
     * @return a string with a properly wrapped date object
     */
    public String wrapDate(String val) {
        return "'" + val + "'";
    }

    /**
     * Wraps a given date value the way required by target database
     *
     * @param val             {@link java.time.LocalDate} object to wrap
     * @param isDateWideRange flag which is used when the year might contain more than 4 digits
     * @return a string with a properly wrapped date object
     */
    public String wrapDate(@NonNull LocalDate val, boolean isDateWideRange) {
        return wrapDate(isDateWideRange ? val.format(ISO_LOCAL_DATE) : val.toString());
    }

    /**
     * Wraps a given date value to the date with time.
     * It might be used in some special cases.
     *
     * @param val {@link java.sql.Date} object to wrap
     * @return a string with a properly wrapped date object
     */
    public String wrapDateWithTime(String val) {
        return wrapTimestamp(val);
    }

    /**
     * Wraps a given timestamp value the way required by target database
     *
     * @param val {@link java.sql.Timestamp} object to wrap
     * @return a string with a properly wrapped timestamp object
     */
    public String wrapTimestamp(String val) {
        return "'" + val + "'";
    }

    /**
     * Wraps a given timestamp with time zone value the way required by target database
     *
     * @param val {@link java.sql.Types.TIME_WITH_TIMEZONE} object to wrap
     * @return a string with a properly wrapped timestamp object
     */
    public String wrapTimestampWithTZ(String val) {
        throw new UnsupportedOperationException("The database doesn't support pushdown of the `TIMESTAMP WITH TIME ZONE` data type");
    }

    /**
     * Wraps a given timestamp value the way required by target database
     *
     * @param val             {@link java.sql.Timestamp} object to wrap
     * @param isDateWideRange flag which is used when the year might contain more than 4 digits
     * @return a string with a properly wrapped timestamp object
     */
    public String wrapTimestamp(@NonNull LocalDateTime val, boolean isDateWideRange) {
        return wrapTimestamp(isDateWideRange ? val.format(ISO_LOCAL_DATE_TIME) :
                val.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }

    /**
     * Build a query to set session-level variables for target database
     *
     * @param key   variable name (key)
     * @param value variable value
     * @return a string with template SET query
     */
    public String buildSessionQuery(String key, String value) {
        return String.format("SET %s = %s", key, value);
    }

    /**
     * Get DbProduct for database by database name
     *
     * @param dbName database name
     * @return a DbProduct of the required class
     */
    public static DbProduct getDbProduct(String dbName, boolean treatUnknownDbmsAsPostgreSql) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Database product name is '{}'", dbName);
        }

        dbName = dbName.toUpperCase();
        DbProduct result;
        if (dbName.contains("MICROSOFT")) {
            result = DbProduct.MICROSOFT;
        } else if (dbName.contains("MYSQL")) {
            result = DbProduct.MYSQL;
        } else if (dbName.contains("ORACLE")) {
            result = DbProduct.ORACLE;
        } else if (dbName.contains("S3 SELECT")) {
            result = DbProduct.S3_SELECT;
        } else if (dbName.contains("ADAPTIVE SERVER ENTERPRISE")) {
            result = DbProduct.SYBASE;
        } else if (dbName.contains("POSTGRESQL")) {
            result = DbProduct.POSTGRES;
        } else {
            if (treatUnknownDbmsAsPostgreSql) {
                result = DbProduct.POSTGRES;
            } else {
                result = DbProduct.OTHER;
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("DbProduct '{}' is used", result);
        }
        return result;
    }

    private static final Logger LOG = LoggerFactory.getLogger(DbProduct.class);
}
