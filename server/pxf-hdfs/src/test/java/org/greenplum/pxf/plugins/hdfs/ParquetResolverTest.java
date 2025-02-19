package org.greenplum.pxf.plugins.hdfs;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.NanoTime;
import org.apache.parquet.example.data.simple.SimpleGroup;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;
import org.apache.parquet.format.converter.ParquetMetadataConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.pig.convert.DecimalUtils;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.Type;
import org.greenplum.pxf.api.OneField;
import org.greenplum.pxf.api.OneRow;
import org.greenplum.pxf.api.error.PxfRuntimeException;
import org.greenplum.pxf.api.error.UnsupportedTypeException;
import org.greenplum.pxf.api.io.DataType;
import org.greenplum.pxf.api.model.RequestContext;
import org.greenplum.pxf.api.utilities.ColumnDescriptor;
import org.greenplum.pxf.plugins.hdfs.parquet.ParquetConfig;
import org.greenplum.pxf.plugins.hdfs.parquet.ParquetTimestampUtilities;
import org.greenplum.pxf.plugins.hdfs.parquet.ParquetTypeConverterFactory;
import org.greenplum.pxf.plugins.hdfs.parquet.converters.ParquetTypeConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ParquetResolverTest {

    private ParquetResolver resolver;
    private RequestContext context;
    private MessageType schema;
    private String localTimestampString;

    @BeforeEach
    public void setup() {
        resolver = new ParquetResolver();
        context = new RequestContext();
        Configuration configuration = new Configuration();
        schema = new MessageType("test");
        context.setMetadata(schema);
        context.setConfig("default");
        context.setUser("test-user");
        context.setConfiguration(configuration);
        // for test cases that test conversions against server's time zone
        Instant timestamp = Instant.parse("2013-07-14T04:00:05Z"); // UTC
        ZonedDateTime localTime = timestamp.atZone(ZoneId.systemDefault());
        localTimestampString = localTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); // should be "2013-07-13 21:00:05" in PST

    }

    @Test
    public void testInitialize() {
        resolver.setRequestContext(context);
        resolver.afterPropertiesSet();
    }

    @Test
    public void testGetFields_FailsOnMissingSchema() {
        context.setMetadata(null);
        resolver.setRequestContext(context);

        Exception e = assertThrows(RuntimeException.class,
                () -> resolver.getFields(new OneRow()));
        assertEquals("No schema detected in request context", e.getMessage());
    }

    @Test
    public void testSetFields_FailsOnMissingSchema() {
        context.setMetadata(null);
        resolver.setRequestContext(context);

        Exception e = assertThrows(RuntimeException.class,
                () -> resolver.setFields(new ArrayList<>()));
        assertEquals("No schema detected in request context", e.getMessage());
    }

    @Test
    public void testSetFields_Primitive() throws IOException {
        schema = getParquetSchemaForPrimitiveTypes(Type.Repetition.OPTIONAL, false);
        // schema has changed, set metadata again
        context.setMetadata(schema);
        context.setTupleDescription(getColumnDescriptorsFromSchema(schema));
        resolver.setRequestContext(context);
        resolver.afterPropertiesSet();

        Instant timestamp = Instant.parse("2013-07-14T04:00:05Z"); // UTC
        ZonedDateTime localTime = timestamp.atZone(ZoneId.systemDefault());
        String localTimestampString = localTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); // should be "2013-07-13 21:00:05" in PST

        List<OneField> fields = new ArrayList<>();
        fields.add(new OneField(DataType.TEXT.getOID(), "row1"));
        fields.add(new OneField(DataType.TEXT.getOID(), "s_6"));
        fields.add(new OneField(DataType.INTEGER.getOID(), 1));
        fields.add(new OneField(DataType.FLOAT8.getOID(), 6.0d));
        fields.add(new OneField(DataType.NUMERIC.getOID(), "1.234560000000000000"));
        fields.add(new OneField(DataType.TIMESTAMP.getOID(), localTimestampString));
        fields.add(new OneField(DataType.REAL.getOID(), 7.7f));
        fields.add(new OneField(DataType.BIGINT.getOID(), 23456789L));
        fields.add(new OneField(DataType.BOOLEAN.getOID(), false));
        fields.add(new OneField(DataType.SMALLINT.getOID(), (short) 1));
        fields.add(new OneField(DataType.SMALLINT.getOID(), (short) 10));
        fields.add(new OneField(DataType.TEXT.getOID(), "abcd"));
        fields.add(new OneField(DataType.TEXT.getOID(), "abc"));
        fields.add(new OneField(DataType.BYTEA.getOID(), new byte[]{(byte) 49}));
        fields.add(new OneField(DataType.TIMESTAMP_WITH_TIME_ZONE.getOID(), "2013-07-13 21:00:05-07"));
        fields.add(new OneField(DataType.TIMESTAMP_WITH_TIME_ZONE.getOID(), "2013-07-14 16:45:05+12:45"));
        OneRow row = resolver.setFields(fields);
        assertNotNull(row);
        Object data = row.getData();
        assertNotNull(data);
        assertTrue(data instanceof Group);
        Group group = (Group) data;

        // assert column values
        assertEquals("row1", group.getString(0, 0));
        assertEquals("s_6", group.getString(1, 0));
        assertEquals(1, group.getInteger(2, 0));
        assertEquals(6.0d, group.getDouble(3, 0), 0d);
        assertEquals(BigDecimal.valueOf(1234560000000000000L, 18),
                DecimalUtils.binaryToDecimal(group.getBinary(4, 0), 19, 18));

        NanoTime nanoTime = NanoTime.fromBinary(group.getInt96(5, 0));
        assertEquals(2456488, nanoTime.getJulianDay()); // 14 Jul 2013 in Julian days
        assertEquals((4 * 60 * 60 + 5L) * 1000 * 1000 * 1000, nanoTime.getTimeOfDayNanos()); // 04:00:05 time
        assertEquals(7.7f, group.getFloat(6, 0), 0f);
        assertEquals(23456789L, group.getLong(7, 0));
        assertFalse(group.getBoolean(8, 0));
        assertEquals(1, group.getInteger(9, 0));
        assertEquals(10, group.getInteger(10, 0));
        assertEquals("abcd", group.getString(11, 0));
        assertEquals("abc", group.getString(12, 0));
        assertArrayEquals(new byte[]{(byte) 49}, group.getBinary(13, 0).getBytes());

        nanoTime = NanoTime.fromBinary(group.getInt96(14, 0));
        assertEquals(2456488, nanoTime.getJulianDay()); // 14 Jul 2013 in Julian days
        assertEquals((4 * 60 * 60 + 5L) * 1000 * 1000 * 1000, nanoTime.getTimeOfDayNanos()); // 04:00:05 time

        nanoTime = NanoTime.fromBinary(group.getInt96(15, 0));
        assertEquals(2456488, nanoTime.getJulianDay()); // 14 Jul 2013 in Julian days
        assertEquals((4 * 60 * 60 + 5L) * 1000 * 1000 * 1000, nanoTime.getTimeOfDayNanos()); // 04:00:05 time

        // assert value repetition count
        for (int i = 0; i < 16; i++) {
            assertEquals(1, group.getFieldRepetitionCount(i));
        }
    }

    @Test
    public void testSetFields_RightTrimChar() throws IOException {
        testSetFields_RightTrimCharHelper("abcd  ", "abc   ", "abc");
    }

    @Test
    public void testSetFields_RightTrimCharDontTrimTabChars() throws IOException {
        testSetFields_RightTrimCharHelper("abcd\t\t", "abc\t\t\t ", "abc\t\t\t");
    }

    @Test
    public void testSetFields_RightTrimCharDontTrimNewlineChars() throws IOException {
        testSetFields_RightTrimCharHelper("abcd\n\n", "abc\n\n\n ", "abc\n\n\n");
    }

    @Test
    public void testSetFields_RightTrimCharNoLeftTrim() throws IOException {
        testSetFields_RightTrimCharHelper("  abcd  ", "  abc   ", "  abc");
    }

    @Test
    public void testSetFields_Primitive_Nulls() throws IOException {
        schema = getParquetSchemaForPrimitiveTypes(Type.Repetition.OPTIONAL, false);
        // schema has changed, set metadata again
        context.setMetadata(schema);
        context.setTupleDescription(getColumnDescriptorsFromSchema(schema));
        resolver.setRequestContext(context);
        resolver.afterPropertiesSet();
        List<OneField> fields = new ArrayList<>();
        fields.add(new OneField(DataType.TEXT.getOID(), null));
        fields.add(new OneField(DataType.TEXT.getOID(), null));
        fields.add(new OneField(DataType.INTEGER.getOID(), null));
        fields.add(new OneField(DataType.FLOAT8.getOID(), null));
        fields.add(new OneField(DataType.NUMERIC.getOID(), null));
        fields.add(new OneField(DataType.TIMESTAMP.getOID(), null));
        fields.add(new OneField(DataType.REAL.getOID(), null));
        fields.add(new OneField(DataType.BIGINT.getOID(), null));
        fields.add(new OneField(DataType.BOOLEAN.getOID(), null));
        fields.add(new OneField(DataType.SMALLINT.getOID(), null));
        fields.add(new OneField(DataType.SMALLINT.getOID(), null));
        fields.add(new OneField(DataType.TEXT.getOID(), null));
        fields.add(new OneField(DataType.TEXT.getOID(), null));
        fields.add(new OneField(DataType.BYTEA.getOID(), null));
        fields.add(new OneField(DataType.TIMESTAMP_WITH_TIME_ZONE.getOID(), null));
        fields.add(new OneField(DataType.TIMESTAMP_WITH_TIME_ZONE.getOID(), null));


        OneRow row = resolver.setFields(fields);
        assertNotNull(row);
        Object data = row.getData();
        assertNotNull(data);
        assertTrue(data instanceof Group);
        Group group = (Group) data;
        // assert value repetition count
        for (int i = 0; i < 16; i++) {
            assertEquals(0, group.getFieldRepetitionCount(i));
        }
    }

    @Test
    public void testGetFields_Primitive_EmptySchema() throws IOException {
        resolver.setRequestContext(context);
        resolver.afterPropertiesSet();

        List<Group> groups = readParquetFile("primitive_types.parquet", 25, schema);
        OneRow row1 = new OneRow(groups.get(0)); // get row 1
        List<OneField> fields = resolver.getFields(row1);
        assertTrue(fields.isEmpty());
    }

    @Test
    public void testGetFields_Primitive() throws IOException {
        schema = getParquetSchemaForPrimitiveTypes(Type.Repetition.OPTIONAL, true);
        // schema has changed, set metadata again
        context.setMetadata(schema);
        context.setTupleDescription(getColumnDescriptorsFromSchema(schema));
        resolver.setRequestContext(context);
        resolver.afterPropertiesSet();

        List<Group> groups = readParquetFile("primitive_types.parquet", 25, schema);
        assertEquals(25, groups.size());

        List<OneField> fields = assertRow(groups, 0, 16);
        //s1 : "row1" : TEXT
        assertField(fields, 0, "row1", DataType.TEXT);
        assertField(fields, 1, "s_6", DataType.TEXT);
        assertField(fields, 2, 1, DataType.INTEGER);
        assertField(fields, 3, 6.0d, DataType.FLOAT8);
        assertField(fields, 4, BigDecimal.valueOf(1234560000000000000L, 18), DataType.NUMERIC);
        assertField(fields, 5, localTimestampString, DataType.TIMESTAMP);
        assertField(fields, 6, 7.7f, DataType.REAL);
        assertField(fields, 7, 23456789L, DataType.BIGINT);
        assertField(fields, 8, false, DataType.BOOLEAN);
        assertField(fields, 9, (short) 1, DataType.SMALLINT);
        assertField(fields, 10, (short) 10, DataType.SMALLINT);
        assertField(fields, 11, "abcd", DataType.TEXT);
        assertField(fields, 12, "abc", DataType.TEXT);
        assertField(fields, 13, new byte[]{(byte) 49}, DataType.BYTEA); // 49 is the ascii code for '1'
        // Parquet only stores the Timestamp (timezone information was lost)
        assertField(fields, 14, localTimestampString, DataType.TIMESTAMP);
        // Parquet only stores the Timestamp (timezone information was lost)
        assertField(fields, 15, localTimestampString, DataType.TIMESTAMP);

        // test nulls
        fields = assertRow(groups, 11, 16);
        assertField(fields, 1, null, DataType.TEXT);
        fields = assertRow(groups, 12, 16);
        assertField(fields, 2, null, DataType.INTEGER);
        fields = assertRow(groups, 13, 16);
        assertField(fields, 3, null, DataType.FLOAT8);
        fields = assertRow(groups, 14, 16);
        assertField(fields, 4, null, DataType.NUMERIC);
        fields = assertRow(groups, 15, 16);
        assertField(fields, 5, null, DataType.TIMESTAMP);
        fields = assertRow(groups, 16, 16);
        assertField(fields, 6, null, DataType.REAL);
        fields = assertRow(groups, 17, 16);
        assertField(fields, 7, null, DataType.BIGINT);
        fields = assertRow(groups, 18, 16);
        assertField(fields, 8, null, DataType.BOOLEAN);
        fields = assertRow(groups, 19, 16);
        assertField(fields, 9, null, DataType.SMALLINT);
        fields = assertRow(groups, 20, 16);
        assertField(fields, 10, null, DataType.SMALLINT);
        fields = assertRow(groups, 22, 16);
        assertField(fields, 11, null, DataType.TEXT);
        fields = assertRow(groups, 23, 16);
        assertField(fields, 12, null, DataType.TEXT);
        fields = assertRow(groups, 24, 16);
        assertField(fields, 13, null, DataType.BYTEA);
    }

    @Test
    public void testGetFields_Primitive_With_Projection() throws IOException {
        schema = getParquetSchemaForPrimitiveTypes(Type.Repetition.OPTIONAL, true);
        context.setTupleDescription(getColumnDescriptorsFromSchema(schema));

        // set odd columns to be not projected, their values will become null
        for (int i = 0; i < context.getTupleDescription().size(); i++) {
            context.getTupleDescription().get(i).setProjected(i % 2 == 0);
        }

        MessageType readSchema = buildReadSchema(schema);
        // schema has changed, set metadata again
        context.setMetadata(readSchema);

        resolver.setRequestContext(context);
        resolver.afterPropertiesSet();

        // use readSchema to read only specific columns from parquet file into Group
        List<Group> groups = readParquetFile("primitive_types.parquet", 25, readSchema);
        assertEquals(25, groups.size());

        List<OneField> fields = assertRow(groups, 0, 16);
        //s1 : "row1" : TEXT
        assertField(fields, 0, "row1", DataType.TEXT);
        assertField(fields, 1, null, DataType.TEXT);
        assertField(fields, 2, 1, DataType.INTEGER);
        assertField(fields, 3, null, DataType.FLOAT8);
        assertField(fields, 4, BigDecimal.valueOf(1234560000000000000L, 18), DataType.NUMERIC);
        assertField(fields, 5, null, DataType.TIMESTAMP);
        assertField(fields, 6, 7.7f, DataType.REAL);
        assertField(fields, 7, null, DataType.BIGINT);
        assertField(fields, 8, false, DataType.BOOLEAN);
        assertField(fields, 9, null, DataType.SMALLINT);
        assertField(fields, 10, (short) 10, DataType.SMALLINT);
        assertField(fields, 11, null, DataType.TEXT);
        assertField(fields, 12, "abc", DataType.TEXT);
        assertField(fields, 13, null, DataType.BYTEA); // 49 is the ascii code for '1'
        // Parquet only stores the Timestamp (timezone information was lost)
        assertField(fields, 14, localTimestampString, DataType.TIMESTAMP);
        // Parquet only stores the Timestamp (timezone information was lost)
        assertField(fields, 15, null, DataType.TIMESTAMP);

        // test nulls
        fields = assertRow(groups, 11, 16);
        assertField(fields, 1, null, DataType.TEXT);
        fields = assertRow(groups, 12, 16);
        assertField(fields, 2, null, DataType.INTEGER);
        fields = assertRow(groups, 13, 16);
        assertField(fields, 3, null, DataType.FLOAT8);
        fields = assertRow(groups, 14, 16);
        assertField(fields, 4, null, DataType.NUMERIC);
        fields = assertRow(groups, 15, 16);
        assertField(fields, 5, null, DataType.TIMESTAMP);
        fields = assertRow(groups, 16, 16);
        assertField(fields, 6, null, DataType.REAL);
        fields = assertRow(groups, 17, 16);
        assertField(fields, 7, null, DataType.BIGINT);
        fields = assertRow(groups, 18, 16);
        assertField(fields, 8, null, DataType.BOOLEAN);
        fields = assertRow(groups, 19, 16);
        assertField(fields, 9, null, DataType.SMALLINT);
        fields = assertRow(groups, 20, 16);
        assertField(fields, 10, null, DataType.SMALLINT);
        fields = assertRow(groups, 22, 16);
        assertField(fields, 11, null, DataType.TEXT);
        fields = assertRow(groups, 23, 16);
        assertField(fields, 12, null, DataType.TEXT);
        fields = assertRow(groups, 24, 16);
        assertField(fields, 13, null, DataType.BYTEA);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testGetFields_Primitive_RepeatedString() throws IOException {
        List<Type> columns = new ArrayList<>();
        columns.add(new PrimitiveType(Type.Repetition.REPEATED, PrimitiveTypeName.BINARY, "myString", org.apache.parquet.schema.OriginalType.UTF8));
        schema = new MessageType("TestProtobuf.StringArray", columns);
        context.setMetadata(schema);
        context.setTupleDescription(getColumnDescriptorsFromSchema(schema));
        resolver.setRequestContext(context);
        resolver.afterPropertiesSet();

        List<Group> groups = readParquetFile("proto-repeated-string.parquet", 3, schema);
        List<OneField> fields;

        // row 0
        fields = assertRow(groups, 0, 1);
        assertEquals(DataType.TEXT.getOID(), fields.get(0).type);
        assertEquals("[\"hello\",\"world\"]", fields.get(0).val);

        // row 1
        fields = assertRow(groups, 1, 1);
        assertEquals(DataType.TEXT.getOID(), fields.get(0).type);
        assertEquals("[\"good\",\"bye\"]", fields.get(0).val);

        // row 2
        fields = assertRow(groups, 2, 1);
        assertEquals(DataType.TEXT.getOID(), fields.get(0).type);
        assertEquals("[\"one\",\"two\",\"three\"]", fields.get(0).val);

    }

    @Test
    public void testGetFields_Primitive_Repeated_Synthetic() {
        // this test does not read the actual Parquet file, but rather construct Group object synthetically
        schema = getParquetSchemaForPrimitiveTypes(Type.Repetition.REPEATED, true);
        // schema has changed, set metadata again
        context.setMetadata(schema);
        context.setTupleDescription(getColumnDescriptorsFromSchema(schema));
        resolver.setRequestContext(context);
        resolver.afterPropertiesSet();

        /*
        Corresponding DB column types  are:
        TEXT,TEXT,INTEGER, DOUBLE PRECISION,NUMERIC,TIMESTAMP,REAL,BIGINT,BOOLEAN,SMALLINT,SMALLINT,VARCHAR(5),CHAR(3),BYTEA
         */

        Group group = new SimpleGroup(schema);

        group.add(0, "row1-1");
        group.add(0, "row1-2");

        // leave column 1 (t2) unset as part fo the test

        group.add(2, 1);
        group.add(2, 2);
        group.add(2, 3);

        group.add(3, 6.0d);
        group.add(3, -16.34d);

        BigDecimal value = new BigDecimal("12345678.9012345987654321"); // place of dot doesn't matter
        byte fillByte = (byte) (value.signum() < 0 ? 0xFF : 0x00);
        byte[] unscaled = value.unscaledValue().toByteArray();
        byte[] bytes = new byte[16];
        int offset = bytes.length - unscaled.length;
        for (int i = 0; i < bytes.length; i += 1) {
            bytes[i] = (i < offset) ? fillByte : unscaled[i - offset];
        }
        group.add(4, Binary.fromReusedByteArray(bytes));

        group.add(5, ParquetTimestampUtilities.getBinaryFromTimestamp("2019-03-14 14:10:28", true));
        group.add(5, ParquetTimestampUtilities.getBinaryFromTimestamp("1969-12-30 05:42:23.211211", true));

        group.add(6, 7.7f);
        group.add(6, -12345.35354646f);

        group.add(7, 23456789L);
        group.add(7, -123456789012345L);

        group.add(8, true);
        group.add(8, false);

        group.add(9, (short) 1);
        group.add(9, (short) -3);

        group.add(10, (short) 269);
        group.add(10, (short) -313);

        group.add(11, Binary.fromString("Hello"));
        group.add(11, Binary.fromString("World"));

        group.add(12, Binary.fromString("foo"));
        group.add(12, Binary.fromString("bar"));

        byte[] byteArray1 = new byte[]{(byte) 49, (byte) 50, (byte) 51};
        group.add(13, Binary.fromReusedByteArray(byteArray1, 0, 3));
        byte[] byteArray2 = new byte[]{(byte) 52, (byte) 53, (byte) 54};
        group.add(13, Binary.fromReusedByteArray(byteArray2, 0, 3));

        group.add(14, ParquetTimestampUtilities.getBinaryFromTimestampWithTimeZone("2019-03-14 14:10:28+07"));
        OffsetDateTime offsetDateTime1 = OffsetDateTime.parse("2019-03-14T14:10:28+07:00");
        ZonedDateTime localDateTime1 = offsetDateTime1.atZoneSameInstant(ZoneId.systemDefault());
        String localDateTimeString1 = localDateTime1.format(DateTimeFormatter.ofPattern("[yyyy-MM-dd HH:mm:ss]"));

        group.add(15, ParquetTimestampUtilities.getBinaryFromTimestampWithTimeZone("2019-03-14 14:10:28-07:30"));
        OffsetDateTime offsetDateTime2 = OffsetDateTime.parse("2019-03-14T14:10:28-07:30");
        ZonedDateTime localDateTime2 = offsetDateTime2.atZoneSameInstant(ZoneId.systemDefault());
        String localDateTimeString2 = localDateTime2.format(DateTimeFormatter.ofPattern("[yyyy-MM-dd HH:mm:ss]"));


        List<Group> groups = new ArrayList<>();
        groups.add(group);
        List<OneField> fields = assertRow(groups, 0, 16);

        assertField(fields, 0, "[\"row1-1\",\"row1-2\"]", DataType.TEXT);
        assertField(fields, 1, "[]", DataType.TEXT);
        assertField(fields, 2, "[1,2,3]", DataType.TEXT);
        assertField(fields, 3, "[6.0,-16.34]", DataType.TEXT);
        assertField(fields, 4, "[123456.789012345987654321]", DataType.TEXT); // scale fixed to 18 in schema
        assertField(fields, 5, "[\"2019-03-14 14:10:28\",\"1969-12-30 05:42:23.211211\"]", DataType.TEXT);
        assertField(fields, 6, "[7.7,-12345.354]", DataType.TEXT); // rounded to the precision of 8
        assertField(fields, 7, "[23456789,-123456789012345]", DataType.TEXT);
        assertField(fields, 8, "[true,false]", DataType.TEXT);
        assertField(fields, 9, "[1,-3]", DataType.TEXT);
        assertField(fields, 10, "[269,-313]", DataType.TEXT);
        assertField(fields, 11, "[\"Hello\",\"World\"]", DataType.TEXT);
        assertField(fields, 12, "[\"foo\",\"bar\"]", DataType.TEXT); // 3 chars only
        Base64.Encoder encoder = Base64.getEncoder(); // byte arrays are Base64 encoded into strings
        String expectedByteArrays = "[\"" + encoder.encodeToString(byteArray1) + "\",\"" + encoder.encodeToString(byteArray2) + "\"]";
        assertField(fields, 13, expectedByteArrays, DataType.TEXT);
        assertField(fields, 14, "[\"" + localDateTimeString1 + "\"]", DataType.TEXT);
        assertField(fields, 15, "[\"" + localDateTimeString2 + "\"]", DataType.TEXT);
    }

    @Test
    public void testGetFields_Primitive_RepeatedInt() throws IOException {
        List<Type> columns = new ArrayList<>();
        columns.add(new PrimitiveType(Type.Repetition.REPEATED, PrimitiveTypeName.INT32, "repeatedInt"));
        schema = new MessageType("TestProtobuf.RepeatedIntMessage", columns);
        context.setMetadata(schema);
        context.setTupleDescription(getColumnDescriptorsFromSchema(schema));
        resolver.setRequestContext(context);
        resolver.afterPropertiesSet();

        List<Group> groups = readParquetFile("old-repeated-int.parquet", 1, schema);
        List<OneField> fields = assertRow(groups, 0, 1);
        assertEquals(DataType.TEXT.getOID(), fields.get(0).type);
        assertEquals("[1,2,3]", fields.get(0).val);

    }

    @Test
    public void testGetFields_List() throws IOException {
        schema = getParquetSchemaForListTypesGeneratedByHive();
        // schema has changed, set metadata again
        context.setMetadata(schema);
        context.setTupleDescription(getColumnDescriptorsFromSchema(schema));
        resolver.setRequestContext(context);
        resolver.afterPropertiesSet();

        List<Group> groups = readParquetFile("parquet_list_types_without_null.parquet", 2, schema);
        assertEquals(2, groups.size());

        List<OneField> fields = assertRow(groups, 0, 13);
        assertField(fields, 0, 1, DataType.INTEGER);
        assertList(fields, 1, "{true}", DataType.BOOLARRAY);
        assertList(fields, 2, "{50}", DataType.INT2ARRAY);
        assertList(fields, 3, "{1}", DataType.INT4ARRAY);
        assertList(fields, 4, "{1}", DataType.INT8ARRAY);
        assertList(fields, 5, "{1.11}", DataType.FLOAT4ARRAY);
        assertList(fields, 6, "{1.7E308}", DataType.FLOAT8ARRAY);
        assertList(fields, 7, "{\"this is a test string\"}", DataType.TEXTARRAY);
        assertList(fields, 8, "{\"\\\\xdeadbeef\"}", DataType.BYTEAARRAY);
        assertList(fields, 9, "{hello}", DataType.BPCHARARRAY);// right trimmed spaces
        assertList(fields, 10, "{hello}", DataType.VARCHARARRAY);
        assertList(fields, 11, "{2022-10-07}", DataType.DATEARRAY);
        assertList(fields, 12, "{" + BigDecimal.valueOf(1234560000000000000L, 18) + "}", DataType.NUMERICARRAY);

        fields = assertRow(groups, 1, 13);
        assertField(fields, 0, 2, DataType.INTEGER);
        assertList(fields, 1, "{false,true,true,false}", DataType.BOOLARRAY);
        assertList(fields, 2, "{-128,96}", DataType.INT2ARRAY);
        assertList(fields, 3, "{2,3}", DataType.INT4ARRAY);
        assertList(fields, 4, "{-9223372036854775808,223372036854775808}", DataType.INT8ARRAY);
        assertList(fields, 5, "{-123456.984,123456.984}", DataType.FLOAT4ARRAY);
        assertList(fields, 6, "{1.0,-99.9}", DataType.FLOAT8ARRAY);
        assertList(fields, 7, "{\"this is a string with \\\"special\\\" characters\",\"this is a string without\"}", DataType.TEXTARRAY);
        assertList(fields, 8, "{\"\\\\xdeadbeef\",\"\\\\xadbeef\"}", DataType.BYTEAARRAY);
        assertList(fields, 9, "{\"this is exactly\",\" fifteen chars.\"}", DataType.BPCHARARRAY);
        assertList(fields, 10, "{\"this is exactly\",\" fifteen chars.\"}", DataType.VARCHARARRAY);
        assertList(fields, 11, "{2022-10-07,2022-10-08}", DataType.DATEARRAY);
        assertList(fields, 12, "{" + BigDecimal.valueOf(1234560000000000000L, 18) + "," + BigDecimal.valueOf(1234560000000000000L, 18) + "}", DataType.NUMERICARRAY);
    }

    @Test
    public void testGetFields_List_Nulls() throws IOException {
        schema = getParquetSchemaForListTypesGeneratedByHive();
        // schema has changed, set metadata again
        context.setMetadata(schema);
        context.setTupleDescription(getColumnDescriptorsFromSchema(schema));
        resolver.setRequestContext(context);
        resolver.afterPropertiesSet();

        List<Group> groups = readParquetFile("parquet_list_types.parquet", 6, schema);
        assertEquals(6, groups.size());

        List<OneField> fields = assertRow(groups, 3, 13);
        assertField(fields, 0, 4, DataType.INTEGER);
        assertList(fields, 1, null, DataType.BOOLARRAY);
        assertList(fields, 2, "{10,20}", DataType.INT2ARRAY);
        assertList(fields, 3, "{7,NULL,8}", DataType.INT4ARRAY);
        assertList(fields, 4, "{-9223372036854775808,0}", DataType.INT8ARRAY);
        assertList(fields, 5, "{2.3,4.5}", DataType.FLOAT4ARRAY);
        assertList(fields, 6, null, DataType.FLOAT8ARRAY);
        assertList(fields, 7, "{NULL,\"\"}", DataType.TEXTARRAY);
        assertList(fields, 8, "{NULL,\"\\\\x5c22\"}", DataType.BYTEAARRAY);
        assertList(fields, 9, null, DataType.BPCHARARRAY);
        assertList(fields, 10, null, DataType.VARCHARARRAY);
        assertList(fields, 11, "{2022-10-07,2022-10-07,NULL}", DataType.DATEARRAY);
        assertList(fields, 12, "{" + BigDecimal.valueOf(1234500000000000000L, 18) + "}", DataType.NUMERICARRAY);
    }

    @Test
    public void testGetFields_List_With_Projection() throws IOException {
        schema = getParquetSchemaForListTypesGeneratedByHive();
        context.setTupleDescription(getColumnDescriptorsFromSchema(schema));
        // set odd columns to be not projected, their values will become null
        for (int i = 0; i < context.getTupleDescription().size(); i++) {
            context.getTupleDescription().get(i).setProjected(i % 2 == 0);
        }

        MessageType readSchema = buildReadSchema(schema);
        // schema has changed, set metadata again
        context.setMetadata(readSchema);

        resolver.setRequestContext(context);
        resolver.afterPropertiesSet();

        List<Group> groups = readParquetFile("parquet_list_types_without_null.parquet", 2, readSchema);
        assertEquals(2, groups.size());

        List<OneField> fields = assertRow(groups, 0, 13);
        assertField(fields, 0, 1, DataType.INTEGER);
        assertList(fields, 1, null, DataType.BOOLARRAY);
        assertList(fields, 2, "{50}", DataType.INT2ARRAY);
        assertList(fields, 3, null, DataType.INT4ARRAY);
        assertList(fields, 4, "{1}", DataType.INT8ARRAY);
        assertList(fields, 5, null, DataType.FLOAT4ARRAY);
        assertList(fields, 6, "{1.7E308}", DataType.FLOAT8ARRAY);
        assertList(fields, 7, null, DataType.TEXTARRAY);
        assertList(fields, 8, "{\"\\\\xdeadbeef\"}", DataType.BYTEAARRAY);
        assertList(fields, 9, null, DataType.BPCHARARRAY);
        assertList(fields, 10, "{hello}", DataType.VARCHARARRAY);
        assertList(fields, 11, null, DataType.DATEARRAY);
        assertList(fields, 12, "{" + BigDecimal.valueOf(1234560000000000000L, 18) + "}", DataType.NUMERICARRAY);

        fields = assertRow(groups, 1, 13);
        assertField(fields, 0, 2, DataType.INTEGER);
        assertList(fields, 1, null, DataType.BOOLARRAY);
        assertList(fields, 2, "{-128,96}", DataType.INT2ARRAY);
        assertList(fields, 3, null, DataType.INT4ARRAY);
        assertList(fields, 4, "{-9223372036854775808,223372036854775808}", DataType.INT8ARRAY);
        assertList(fields, 5, null, DataType.FLOAT4ARRAY);
        assertList(fields, 6, "{1.0,-99.9}", DataType.FLOAT8ARRAY);
        assertList(fields, 7, null, DataType.TEXTARRAY);
        assertList(fields, 8, "{\"\\\\xdeadbeef\",\"\\\\xadbeef\"}", DataType.BYTEAARRAY);
        assertList(fields, 9, null, DataType.BPCHARARRAY);
        assertList(fields, 10, "{\"this is exactly\",\" fifteen chars.\"}", DataType.VARCHARARRAY);
        assertList(fields, 11, null, DataType.DATEARRAY);
        assertList(fields, 12, "{" + BigDecimal.valueOf(1234560000000000000L, 18) + "," + BigDecimal.valueOf(1234560000000000000L, 18) + "}", DataType.NUMERICARRAY);
    }

    @Test
    public void testGetFields_Timestamp_List_Nulls() throws IOException {
        schema = getParquetSchemaForTimestampListTypeGeneratedBySpark();
        // schema has changed, set metadata again
        context.setMetadata(schema);
        context.setTupleDescription(getColumnDescriptorsFromSchema(schema));
        resolver.setRequestContext(context);
        resolver.afterPropertiesSet();

        List<Group> groups = readParquetFile("parquet_timestamp_list_type.parquet", 6, schema);
        assertEquals(6, groups.size());

        // for test cases that test conversions against server's time zone
        Instant timestamp1 = Instant.parse("2022-10-05T18:30:00Z"); // UTC
        Instant timestamp2 = Instant.parse("2022-10-06T19:30:00Z"); // UTC
        Instant timestamp3 = Instant.parse("2022-10-07T20:30:00Z"); // UTC
        ZonedDateTime localTime1 = timestamp1.atZone(ZoneId.systemDefault());
        ZonedDateTime localTime2 = timestamp2.atZone(ZoneId.systemDefault());
        ZonedDateTime localTime3 = timestamp3.atZone(ZoneId.systemDefault());
        String localTimestampString1 = localTime1.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String localTimestampString2 = localTime2.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String localTimestampString3 = localTime3.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        List<OneField> fields = assertRow(groups, 0, 2);
        assertField(fields, 0, 1, DataType.INTEGER);
        assertList(fields, 1, "{\"" + localTimestampString1 + "\",\"" + localTimestampString2 + "\",\"" + localTimestampString3 + "\"}", DataType.TIMESTAMPARRAY);

        fields = assertRow(groups, 1, 2);
        assertField(fields, 0, 2, DataType.INTEGER);
        assertList(fields, 1, "{\"" + localTimestampString1 + "\",\"" + localTimestampString1 + "\",\"" + localTimestampString3 + "\"}", DataType.TIMESTAMPARRAY);

        fields = assertRow(groups, 2, 2);
        assertField(fields, 0, 3, DataType.INTEGER);
        assertList(fields, 1, "{NULL,\"" + localTimestampString1 + "\",\"" + localTimestampString1 + "\"}", DataType.TIMESTAMPARRAY);

        fields = assertRow(groups, 3, 2);
        assertField(fields, 0, 4, DataType.INTEGER);
        assertList(fields, 1, "{NULL}", DataType.TIMESTAMPARRAY);

        fields = assertRow(groups, 4, 2);
        assertField(fields, 0, 5, DataType.INTEGER);
        assertList(fields, 1, "{}", DataType.TIMESTAMPARRAY);

        fields = assertRow(groups, 5, 2);
        assertField(fields, 0, 6, DataType.INTEGER);
        assertList(fields, 1, null, DataType.TIMESTAMPARRAY);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testGetFields_Unsupported_Struct_List() {
        List<ColumnDescriptor> columnDescriptors = new ArrayList<>();
        ColumnDescriptor listColumnDescriptor = new ColumnDescriptor("unsupported_list", -1, 1, "", new Integer[]{});
        columnDescriptors.add(listColumnDescriptor);

        // LIST of customized Struct, with no parquet original type name
        schema = getParquetSchemaForUnsupportedListType()[0];
        // schema has changed, set metadata again
        context.setMetadata(schema);
        context.setTupleDescription(columnDescriptors);
        resolver.setRequestContext(context);
        resolver.afterPropertiesSet();

        List<Type> structFields = new ArrayList<>();

        structFields.add(new PrimitiveType(Type.Repetition.OPTIONAL, PrimitiveTypeName.INT32, "num1"));
        structFields.add(new PrimitiveType(Type.Repetition.OPTIONAL, PrimitiveTypeName.INT32, "num2"));
        structFields.add(new PrimitiveType(Type.Repetition.OPTIONAL, PrimitiveTypeName.INT32, "num3"));

        GroupType structGroupType = new GroupType(Type.Repetition.OPTIONAL, "element", structFields);
        Group structGroup = new SimpleGroup(structGroupType);

        GroupType listRepeatedGroupType = new GroupType(Type.Repetition.REPEATED, "repeated_list", structGroupType);
        Group listRepeatedGroup = new SimpleGroup(listRepeatedGroupType);
        listRepeatedGroup.add(0, structGroup);

        GroupType listGroupType = new GroupType(Type.Repetition.OPTIONAL, "unsupported_list", org.apache.parquet.schema.OriginalType.LIST, listRepeatedGroupType);
        Group listGroup = new SimpleGroup(listGroupType);
        listGroup.add(0, listRepeatedGroup);

        List<Group> groups = new ArrayList<>();
        groups.add(listGroup);

        Exception e = assertThrows(UnsupportedTypeException.class,
                () -> assertRow(groups, 0, 1));
        assertEquals("Parquet LIST of customized struct is not supported.", e.getMessage());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testGetFields_Unsupported_Map_List() {
        List<ColumnDescriptor> columnDescriptors = new ArrayList<>();
        ColumnDescriptor listColumnDescriptor = new ColumnDescriptor("unsupported_list", -1, 1, "", new Integer[]{});
        columnDescriptors.add(listColumnDescriptor);

        // LIST of customized Map, with no parquet original type name
        schema = getParquetSchemaForUnsupportedListType()[1];
        // schema has changed, set metadata again
        context.setMetadata(schema);
        context.setTupleDescription(columnDescriptors);
        resolver.setRequestContext(context);
        resolver.afterPropertiesSet();

        List<Type> mapFields = new ArrayList<>();

        mapFields.add(new PrimitiveType(Type.Repetition.REQUIRED, PrimitiveTypeName.INT32, "key"));
        mapFields.add(new PrimitiveType(Type.Repetition.OPTIONAL, PrimitiveTypeName.INT32, "value"));

        GroupType mapElementGroupType = new GroupType(Type.Repetition.OPTIONAL, "element", mapFields);
        Group mapElementGroup = new SimpleGroup(mapElementGroupType);

        GroupType mapRepeatedGroupType = new GroupType(Type.Repetition.REPEATED, "map", mapElementGroupType);
        Group mapRepeatedGroup = new SimpleGroup(mapRepeatedGroupType);
        mapRepeatedGroup.add(0, mapElementGroup);

        GroupType mapGroupType = new GroupType(Type.Repetition.OPTIONAL, "my_map", org.apache.parquet.schema.OriginalType.MAP, mapRepeatedGroupType);
        GroupType listRepeatedGroupType = new GroupType(Type.Repetition.REPEATED, "repeated_list", mapGroupType);
        Group listRepeatedGroup = new SimpleGroup(listRepeatedGroupType);
        listRepeatedGroup.add(0, mapRepeatedGroup);

        GroupType listGroupType = new GroupType(Type.Repetition.OPTIONAL, "unsupported_list", org.apache.parquet.schema.OriginalType.LIST, listRepeatedGroupType);
        Group listGroup = new SimpleGroup(listGroupType);
        listGroup.add(0, listRepeatedGroup);

        List<Group> groups = new ArrayList<>();
        groups.add(listGroup);

        Exception e = assertThrows(UnsupportedTypeException.class,
                () -> assertRow(groups, 0, 1));
        assertEquals("Parquet LIST of MAP is not supported.", e.getMessage());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testGetFields_List_List() {
        List<ColumnDescriptor> columnDescriptors = new ArrayList<>();
        ColumnDescriptor listColumnDescriptor = new ColumnDescriptor("supported_list", -1, 1, "", new Integer[]{});
        columnDescriptors.add(listColumnDescriptor);

        // LIST of LIST
        schema = getListOfList();
        // schema has changed, set metadata again
        context.setMetadata(schema);
        columnDescriptors = new ArrayList<>();
        listColumnDescriptor = new ColumnDescriptor("supported_list", DataType.INTEGER.getOID(), 1, "", new Integer[]{});
        columnDescriptors.add(listColumnDescriptor);
        context.setTupleDescription(columnDescriptors);
        resolver.setRequestContext(context);
        resolver.afterPropertiesSet();

        List<Type> innerListElementFields = new ArrayList<>();
        innerListElementFields.add(new PrimitiveType(Type.Repetition.REQUIRED, PrimitiveTypeName.INT32, "id"));

        GroupType innerListElementGroupType = new GroupType(Type.Repetition.OPTIONAL, "element", innerListElementFields);
        Group innerListElementGroup = new SimpleGroup(innerListElementGroupType);
        innerListElementGroup.add("id", 666);

        GroupType innerListRepeatedGroupType = new GroupType(Type.Repetition.REPEATED, "inner_repeated_list", innerListElementGroupType);
        Group innerRepeatedGroup = new SimpleGroup(innerListRepeatedGroupType);
        innerRepeatedGroup.add(0, innerListElementGroup);

        GroupType innerListGroupType = new GroupType(Type.Repetition.OPTIONAL, "inner_list", org.apache.parquet.schema.OriginalType.LIST, innerListRepeatedGroupType);
        Group innerListGroup = new SimpleGroup(innerListGroupType);
        innerListGroup.add(0, innerRepeatedGroup);

        GroupType listRepeatedGroupType = new GroupType(Type.Repetition.REPEATED, "repeated_list", innerListGroupType);
        Group listRepeatedGroup = new SimpleGroup(listRepeatedGroupType);
        listRepeatedGroup.add(0, innerListGroup);

        GroupType listGroupType = new GroupType(Type.Repetition.OPTIONAL, "unsupported_list", org.apache.parquet.schema.OriginalType.LIST, listRepeatedGroupType);
        Group listGroup = new SimpleGroup(listGroupType);
        listGroup.add(0, listRepeatedGroup);

        List<Group> groups = new ArrayList<>();
        groups.add(listGroup);
        List<OneField> fields = assertRow(groups, 0, 1);
        assertEquals(fields.size(), 1);
        assertEquals("{{666}}", fields.get(0).val);
        assertEquals(DataType.INT4ARRAY.getOID(), fields.get(0).type);
    }

    @Test
    public void testGetFields_Unsupported_Complex() {
        schema = getParquetSchemaForUnsupportedComplexType();
        // schema has changed, set metadata again
        context.setMetadata(schema);
        Exception e = assertThrows(UnsupportedTypeException.class,
                () -> context.setTupleDescription(getColumnDescriptorsFromSchema(schema)));
        assertEquals("Parquet complex type converter [MAP] is not supported", e.getMessage());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testGetFields_Invalid_Schema() {
        // test invalid parquet primitive schema
        List<Type> fields = new ArrayList<>();
        fields.add(new PrimitiveType(Type.Repetition.OPTIONAL, null, "", null));
        schema = new MessageType("invalid_list_schema", fields);
        context.setMetadata(schema);
        Exception e = assertThrows(PxfRuntimeException.class,
                () -> context.setTupleDescription(getColumnDescriptorsFromSchema(schema)));
        assertEquals("Invalid Parquet primitive schema. Parquet primitive type name is null.", e.getMessage());

        // test invalid parquet list schema
        fields = new ArrayList<>();
        fields.add(new GroupType(Type.Repetition.OPTIONAL, "invalid_list", org.apache.parquet.schema.OriginalType.LIST, new ArrayList<>()));
        schema = new MessageType("invalid_list_schema", fields);
        context.setMetadata(schema);
        e = assertThrows(PxfRuntimeException.class,
                () -> context.setTupleDescription(getColumnDescriptorsFromSchema(schema)));
        assertEquals(String.format("Invalid Parquet List schema: %s.", schema.getFields().get(0).toString().replace("\n", " ")), e.getMessage());

        fields = new ArrayList<>();
        GroupType repeatedGroupType = new GroupType(Type.Repetition.REPEATED, "list", new ArrayList<>());
        fields.add(new GroupType(Type.Repetition.OPTIONAL, "invalid_list", org.apache.parquet.schema.OriginalType.LIST, repeatedGroupType));
        schema = new MessageType("invalid_list_schema", fields);
        context.setMetadata(schema);
        e = assertThrows(PxfRuntimeException.class,
                () -> context.setTupleDescription(getColumnDescriptorsFromSchema(schema)));
        assertEquals(String.format("Invalid Parquet List schema: %s.", schema.getFields().get(0).toString().replace("\n", " ")), e.getMessage());
    }

    private List<OneField> assertRow(List<Group> groups, int desiredRow, int numFields) {
        OneRow row = new OneRow(groups.get(desiredRow)); // get row
        List<OneField> fields = resolver.getFields(row);
        assertEquals(numFields, fields.size());
        return fields;
    }

    private void assertField(List<OneField> fields, int index, Object expectedValue, DataType type) {
        assertEquals(type.getOID(), fields.get(index).type);

        if (expectedValue == null) {
            assertNull(fields.get(index).val);
        } else if (type == DataType.BYTEA) {
            assertArrayEquals((byte[]) expectedValue, (byte[]) fields.get(index).val);
        } else {
            assertEquals(expectedValue, fields.get(index).val);
        }
    }

    private void assertList(List<OneField> fields, int index, String expectedValue, DataType type) {
        if (type.getOID() == DataType.BPCHARARRAY.getOID() || type.getOID() == DataType.VARCHARARRAY.getOID()) {
            assertEquals(DataType.TEXTARRAY.getOID(), fields.get(index).type);
        } else {
            assertEquals(type.getOID(), fields.get(index).type);
        }

        if (expectedValue == null) {
            assertNull(fields.get(index).val);
        } else {
            assertEquals(expectedValue, String.valueOf(fields.get(index).val));
        }
    }

    @SuppressWarnings("deprecation")
    private MessageType getParquetSchemaForPrimitiveTypes(Type.Repetition repetition, boolean readCase) {
        List<Type> fields = new ArrayList<>();

        fields.add(new PrimitiveType(repetition, PrimitiveTypeName.BINARY, "s1", org.apache.parquet.schema.OriginalType.UTF8));
        fields.add(new PrimitiveType(repetition, PrimitiveTypeName.BINARY, "s2", org.apache.parquet.schema.OriginalType.UTF8));
        fields.add(new PrimitiveType(repetition, PrimitiveTypeName.INT32, "n1", null));
        fields.add(new PrimitiveType(repetition, PrimitiveTypeName.DOUBLE, "d1", null));
        fields.add(new PrimitiveType(repetition, PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY, 16, "dc1", org.apache.parquet.schema.OriginalType.DECIMAL, new org.apache.parquet.schema.DecimalMetadata(38, 18), null));
        fields.add(new PrimitiveType(repetition, PrimitiveTypeName.INT96, "tm", null));
        fields.add(new PrimitiveType(repetition, PrimitiveTypeName.FLOAT, "f", null));
        fields.add(new PrimitiveType(repetition, PrimitiveTypeName.INT64, "bg", null));
        fields.add(new PrimitiveType(repetition, PrimitiveTypeName.BOOLEAN, "b", null));

        // GPDB only has int16 and not int8 type, so for write tiny numbers int8 are still treated as shorts in16
        org.apache.parquet.schema.OriginalType tinyType = readCase ? org.apache.parquet.schema.OriginalType.INT_8 : org.apache.parquet.schema.OriginalType.INT_16;
        fields.add(new PrimitiveType(repetition, PrimitiveTypeName.INT32, "tn", tinyType));
        fields.add(new PrimitiveType(repetition, PrimitiveTypeName.INT32, "sml", org.apache.parquet.schema.OriginalType.INT_16));
        fields.add(new PrimitiveType(repetition, PrimitiveTypeName.BINARY, "vc1", org.apache.parquet.schema.OriginalType.UTF8));
        fields.add(new PrimitiveType(repetition, PrimitiveTypeName.BINARY, "c1", org.apache.parquet.schema.OriginalType.UTF8));
        fields.add(new PrimitiveType(repetition, PrimitiveTypeName.BINARY, "bin", null));

        fields.add(new PrimitiveType(repetition, PrimitiveTypeName.INT96, "tmtz", null));
        fields.add(new PrimitiveType(repetition, PrimitiveTypeName.INT96, "tmtz2", null));

        return new MessageType("hive_schema", fields);
    }

    @SuppressWarnings("deprecation")
    private MessageType getParquetSchemaForListTypesGeneratedByHive() {
        List<Type> fields = new ArrayList<>();

        Type.Repetition groupRepetition = Type.Repetition.OPTIONAL;
        Type.Repetition elementRepetition = Type.Repetition.OPTIONAL;
        fields.add(new PrimitiveType(elementRepetition, PrimitiveTypeName.INT32, "id", null));
        fields.add(generateListSchema(groupRepetition, "bag", elementRepetition, "array_element", PrimitiveTypeName.BOOLEAN, 0, "bool_arr", null));
        org.apache.parquet.schema.OriginalType tinyType = org.apache.parquet.schema.OriginalType.INT_8;
        fields.add(generateListSchema(groupRepetition, "bag", elementRepetition, "array_element", PrimitiveTypeName.INT32, 0, "smallint_arr", tinyType));
        fields.add(generateListSchema(groupRepetition, "bag", elementRepetition, "array_element", PrimitiveTypeName.INT32, 0, "int_arr", null));
        fields.add(generateListSchema(groupRepetition, "bag", elementRepetition, "array_element", PrimitiveTypeName.INT64, 0, "bigint_arr", null));
        fields.add(generateListSchema(groupRepetition, "bag", elementRepetition, "array_element", PrimitiveTypeName.FLOAT, 0, "real_arr", null));
        fields.add(generateListSchema(groupRepetition, "bag", elementRepetition, "array_element", PrimitiveTypeName.DOUBLE, 0, "double_arr", null));
        fields.add(generateListSchema(groupRepetition, "bag", elementRepetition, "array_element", PrimitiveTypeName.BINARY, 0, "text_arr", org.apache.parquet.schema.OriginalType.UTF8));
        fields.add(generateListSchema(groupRepetition, "bag", elementRepetition, "array_element", PrimitiveTypeName.BINARY, 0, "bytea_arr", null));
        fields.add(generateListSchema(groupRepetition, "bag", elementRepetition, "array_element", PrimitiveTypeName.BINARY, 0, "char_arr", org.apache.parquet.schema.OriginalType.UTF8));
        fields.add(generateListSchema(groupRepetition, "bag", elementRepetition, "array_element", PrimitiveTypeName.BINARY, 0, "varchar_arr", org.apache.parquet.schema.OriginalType.UTF8));
        fields.add(generateListSchema(groupRepetition, "bag", elementRepetition, "array_element", PrimitiveTypeName.INT32, 0, "date_arr", org.apache.parquet.schema.OriginalType.DATE));
        fields.add(generateListSchema(groupRepetition, "bag", elementRepetition, "array_element", PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY, 16, "numeric_arr", org.apache.parquet.schema.OriginalType.DECIMAL));

        return new MessageType("hive_schema", fields);
    }

    @SuppressWarnings("deprecation")
    private MessageType getParquetSchemaForTimestampListTypeGeneratedBySpark() {
        List<Type> fields = new ArrayList<>();
        fields.add(new PrimitiveType(Type.Repetition.OPTIONAL, PrimitiveTypeName.INT32, "id", null));
        fields.add(generateListSchema(Type.Repetition.OPTIONAL, "list", Type.Repetition.OPTIONAL, "element", PrimitiveTypeName.INT96, 0, "tm_arr", null));
        return new MessageType("spark_schema", fields);
    }

    @SuppressWarnings("deprecation")
    private GroupType generateListSchema(Type.Repetition groupRepetition, String repeatedGroupName, Type.Repetition elementRepetition, String elementName, PrimitiveTypeName primitiveTypeName, int length, String listName, org.apache.parquet.schema.OriginalType originalType) {
        PrimitiveType elementType;
        if (originalType == org.apache.parquet.schema.OriginalType.DECIMAL) {
            elementType = new PrimitiveType(elementRepetition, primitiveTypeName, length, elementName, originalType, new org.apache.parquet.schema.DecimalMetadata(38, 18), null);
        } else {
            elementType = new PrimitiveType(elementRepetition, primitiveTypeName, elementName, originalType);
        }
        GroupType repeatedGroupType = new GroupType(Type.Repetition.REPEATED, repeatedGroupName, elementType);
        return new GroupType(groupRepetition, listName, org.apache.parquet.schema.OriginalType.LIST, repeatedGroupType);
    }

    @SuppressWarnings("deprecation")
    private MessageType[] getParquetSchemaForUnsupportedListType() {
        MessageType[] messageTypes = new MessageType[2];

        // List of customized Struct
        List<Type> structFields = new ArrayList<>();

        structFields.add(new PrimitiveType(Type.Repetition.OPTIONAL, PrimitiveTypeName.INT32, "num1"));
        structFields.add(new PrimitiveType(Type.Repetition.OPTIONAL, PrimitiveTypeName.INT32, "num2"));
        structFields.add(new PrimitiveType(Type.Repetition.OPTIONAL, PrimitiveTypeName.INT32, "num3"));

        GroupType structGroupType = new GroupType(Type.Repetition.OPTIONAL, "element", structFields);
        GroupType listRepeatedGroupType = new GroupType(Type.Repetition.REPEATED, "repeated_list", structGroupType);
        GroupType listGroupType = new GroupType(Type.Repetition.OPTIONAL, "unsupported_list", org.apache.parquet.schema.OriginalType.LIST, listRepeatedGroupType);
        List<Type> fields = new ArrayList<>();
        fields.add(listGroupType);
        messageTypes[0] = new MessageType("spark_schema", fields);

        // List of Map
        List<Type> mapFields = new ArrayList<>();

        mapFields.add(new PrimitiveType(Type.Repetition.REQUIRED, PrimitiveTypeName.INT32, "key"));
        mapFields.add(new PrimitiveType(Type.Repetition.OPTIONAL, PrimitiveTypeName.INT32, "value"));

        GroupType mapRepeatedGroupType = new GroupType(Type.Repetition.REPEATED, "map", mapFields);
        GroupType mapGroupType = new GroupType(Type.Repetition.OPTIONAL, "my_map", org.apache.parquet.schema.OriginalType.MAP, mapRepeatedGroupType);
        listRepeatedGroupType = new GroupType(Type.Repetition.REPEATED, "repeated_list", mapGroupType);
        listGroupType = new GroupType(Type.Repetition.OPTIONAL, "unsupported_list", org.apache.parquet.schema.OriginalType.LIST, listRepeatedGroupType);
        fields = new ArrayList<>();

        fields.add(listGroupType);
        messageTypes[1] = new MessageType("spark_schema", fields);

        return messageTypes;
    }

    @SuppressWarnings("deprecation")
    private static MessageType getListOfList() {
        GroupType listGroupType;
        GroupType listRepeatedGroupType;
        List<Type> fields;
        // List of List
        List<Type> innerListElementFields = new ArrayList<>();

        innerListElementFields.add(new PrimitiveType(Type.Repetition.REQUIRED, PrimitiveTypeName.INT32, "id"));

        GroupType innerListRepeatedGroupType = new GroupType(Type.Repetition.REPEATED, "inner_repeated_list", innerListElementFields);
        GroupType innerListGroupType = new GroupType(Type.Repetition.OPTIONAL, "inner_list", org.apache.parquet.schema.OriginalType.LIST, innerListRepeatedGroupType);
        listRepeatedGroupType = new GroupType(Type.Repetition.REPEATED, "repeated_list", innerListGroupType);
        listGroupType = new GroupType(Type.Repetition.OPTIONAL, "unsupported_list", org.apache.parquet.schema.OriginalType.LIST, listRepeatedGroupType);
        fields = new ArrayList<>();

        fields.add(listGroupType);
        return new MessageType("spark_schema", fields);
    }

    @SuppressWarnings("deprecation")
    private MessageType getParquetSchemaForUnsupportedComplexType() {
        List<Type> mapFields = new ArrayList<>();

        mapFields.add(new PrimitiveType(Type.Repetition.REQUIRED, PrimitiveTypeName.INT32, "key"));
        mapFields.add(new PrimitiveType(Type.Repetition.OPTIONAL, PrimitiveTypeName.INT32, "value"));

        GroupType repeatedGroupType = new GroupType(Type.Repetition.REPEATED, "map", mapFields);
        GroupType groupType = new GroupType(Type.Repetition.OPTIONAL, "unsupported_complex", org.apache.parquet.schema.OriginalType.MAP, repeatedGroupType);
        List<Type> fields = new ArrayList<>();

        fields.add(groupType);
        return new MessageType("spark_schema", fields);
    }

    @SuppressWarnings("deprecation")
    private List<Group> readParquetFile(String file, long expectedSize, MessageType schema) throws IOException {
        List<Group> result = new ArrayList<>();
        String parquetFile = Objects.requireNonNull(getClass().getClassLoader().getResource("parquet/" + file)).getPath();
        Path path = new Path(parquetFile);

        ParquetFileReader fileReader = new ParquetFileReader(new Configuration(), path, ParquetMetadataConverter.NO_FILTER);
        PageReadStore rowGroup;
        while ((rowGroup = fileReader.readNextRowGroup()) != null) {
            MessageColumnIO columnIO = new ColumnIOFactory().getColumnIO(schema);
            RecordReader<Group> recordReader = columnIO.getRecordReader(rowGroup, new GroupRecordConverter(schema));
            long rowCount = rowGroup.getRowCount();
            for (long i = 0; i < rowCount; i++) {
                result.add(recordReader.read());
            }
        }
        fileReader.close();
        assertEquals(expectedSize, result.size());
        return result;
    }

    private List<ColumnDescriptor> getColumnDescriptorsFromSchema(MessageType schema) {
        return schema.getFields()
                .stream()
                .map(f -> {
                    Type type = f.isPrimitive() ? f.asPrimitiveType() : f.asGroupType();
                    ParquetConfig parquetConfig = ParquetConfig.builder().build();
                    ParquetTypeConverter converter = new ParquetTypeConverterFactory(parquetConfig).create(type, DataType.UNSUPPORTED_TYPE);
                    return new ColumnDescriptor(f.getName(), converter.getDataType().getOID(), 1, "", new Integer[]{});
                })
                .collect(Collectors.toList());
    }

    private MessageType buildReadSchema(MessageType originalSchema) {
        List<Type> originalFields = originalSchema.getFields();
        List<Type> projectedFields = new ArrayList<>();
        for (int i = 0; i < context.getTupleDescription().size(); i++) {
            if (context.getTupleDescription().get(i).isProjected()) {
                projectedFields.add(originalFields.get(i));
            }
        }
        return new MessageType(originalSchema.getName(), projectedFields);
    }

    @SuppressWarnings("deprecation")
    private void testSetFields_RightTrimCharHelper(String varchar, String inputChar, String expectedChar) throws IOException {
        List<Type> typeFields = new ArrayList<>();
        typeFields.add(new PrimitiveType(Type.Repetition.OPTIONAL, PrimitiveTypeName.BINARY, "vc1", org.apache.parquet.schema.OriginalType.UTF8));
        typeFields.add(new PrimitiveType(Type.Repetition.OPTIONAL, PrimitiveTypeName.BINARY, "c1", org.apache.parquet.schema.OriginalType.UTF8));
        schema = new MessageType("hive_schema", typeFields);
        context.setMetadata(schema);

        List<ColumnDescriptor> columnDescriptors = new ArrayList<>();
        columnDescriptors.add(new ColumnDescriptor("vc1", DataType.VARCHAR.getOID(), 0, "varchar", null));
        columnDescriptors.add(new ColumnDescriptor("c1", DataType.BPCHAR.getOID(), 1, "char", null));
        context.setTupleDescription(columnDescriptors);

        resolver.setRequestContext(context);
        resolver.afterPropertiesSet();

        List<OneField> fields = new ArrayList<>();
        fields.add(new OneField(DataType.TEXT.getOID(), varchar));
        // the whitespace on the after 'abc   ' needs to be trimmed
        fields.add(new OneField(DataType.TEXT.getOID(), inputChar));

        OneRow row = resolver.setFields(fields);
        assertNotNull(row);
        Object data = row.getData();
        assertNotNull(data);
        assertTrue(data instanceof Group);
        Group group = (Group) data;

        // assert column values
        assertEquals(varchar, group.getString(0, 0));
        assertEquals(expectedChar, group.getString(1, 0));

        // assert value repetition count
        for (int i = 0; i < 2; i++) {
            assertEquals(1, group.getFieldRepetitionCount(i));
        }
    }
}
