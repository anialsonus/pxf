package org.greenplum.pxf.service.serde;

import org.greenplum.pxf.api.model.OutputFormat;
import org.greenplum.pxf.api.model.RequestContext;
import org.greenplum.pxf.plugins.hdfs.utilities.PgUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RecordReaderFactoryTest {

    private RecordReaderFactory factory;
    private RequestContext context;

    @BeforeEach
    public void before() {
        context = new RequestContext();
        factory = new RecordReaderFactory(new PgUtilities());
    }

    @Test
    public void testGetGPDBWritableReader() {
        context.setOutputFormat(OutputFormat.GPDBWritable);
        assertTrue(factory.getRecordReader(context, false) instanceof GPDBWritableRecordReader);
        assertTrue(factory.getRecordReader(context, true) instanceof GPDBWritableRecordReader);
    }

    @Test
    public void testGetStreamRecordReader() {
        context.setOutputFormat(OutputFormat.TEXT);
        assertTrue(factory.getRecordReader(context, true) instanceof StreamRecordReader);
    }

    @Test
    public void testGetTextRecordReader() {
        context.setOutputFormat(OutputFormat.TEXT);
        assertTrue(factory.getRecordReader(context, false) instanceof TextRecordReader);
    }

    @Test
    public void testGetReaderErrorNoOutputFormat() {
        context.setOutputFormat(null);
        Throwable thrown = assertThrows(NullPointerException.class, () -> factory.getRecordReader(context, false));
        assertEquals("outputFormat is not set in RequestContext", thrown.getMessage());
    }
}
