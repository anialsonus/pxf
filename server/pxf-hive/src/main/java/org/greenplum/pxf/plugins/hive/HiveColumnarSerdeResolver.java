package org.greenplum.pxf.plugins.hive;

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

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.BinaryObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.BooleanObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.ByteObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.DoubleObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.FloatObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.HiveDecimalObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.IntObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.LongObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.ShortObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.StringObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.TimestampObjectInspector;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.JobConf;
import org.greenplum.pxf.api.OneField;
import org.greenplum.pxf.api.OneRow;
import org.greenplum.pxf.api.error.BadRecordException;
import org.greenplum.pxf.api.error.UnsupportedTypeException;
import org.greenplum.pxf.api.model.OutputFormat;
import org.greenplum.pxf.api.model.RequestContext;
import org.greenplum.pxf.api.utilities.ColumnDescriptor;
import org.greenplum.pxf.api.utilities.Utilities;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.apache.hadoop.hive.serde2.ColumnProjectionUtils.READ_ALL_COLUMNS;
import static org.apache.hadoop.hive.serde2.ColumnProjectionUtils.READ_COLUMN_IDS_CONF_STR;
import static org.apache.hadoop.hive.serde2.ColumnProjectionUtils.READ_COLUMN_NAMES_CONF_STR;
import static org.greenplum.pxf.api.io.DataType.VARCHAR;

/**
 * Specialized HiveResolver for a Hive table stored as RC file.
 * Use together with HiveInputFormatFragmenter/HiveRCFileAccessor.
 */
public class HiveColumnarSerdeResolver extends HiveResolver {
    private boolean firstColumn;
    private StringBuilder builder;
    private Map<String, HivePartition> partitionColumnNames;

    /* read the data supplied by the fragmenter: inputformat name, serde name, partition keys */
    @Override
    void parseUserData(RequestContext context) {
        super.parseUserData(context);
        partitionColumnNames = new HashMap<>();
        parseDelimiterChar(context);
    }

    @Override
    void initPartitionFields() {
        if (context.getOutputFormat() == OutputFormat.TEXT) {
            initTextPartitionFields(builder);
        } else {
            super.initPartitionFields();
        }
    }

    /*
     * The partition fields are initialized one time based on userData provided
     * by the fragmenter.
     */
    @Override
    void initTextPartitionFields(StringBuilder parts) {
        partitionColumnNames = metadata.getPartitions().stream()
                .collect(Collectors.toMap(partition -> StringUtils.lowerCase(partition.getName()),
                        partition -> partition));
    }

    /**
     * getFields returns a singleton list of OneField item.
     * OneField item contains two fields: an integer representing the VARCHAR type and a Java
     * Object representing the field value.
     */
    @Override
    public List<OneField> getFields(OneRow onerow) throws Exception {
        if (context.getOutputFormat() == OutputFormat.TEXT) {
            firstColumn = true;
            builder = new StringBuilder();
            Object tuple = deserializer.deserialize((Writable) onerow.getData());
            ObjectInspector oi = deserializer.getObjectInspector();
            traverseTuple(tuple, oi);
            return Collections.singletonList(new OneField(VARCHAR.getOID(), builder.toString()));
        } else {
            return super.getFields(onerow);
        }
    }

    @Override
    protected JobConf getJobConf() {
        StringBuilder projectedColumnNames = new StringBuilder();
        StringBuilder projectedColumnIds = new StringBuilder();

        String delim = ",";
        List<ColumnDescriptor> tupleDescription = context.getTupleDescription();
        for (int i = 0; i < tupleDescription.size(); i++) {
            ColumnDescriptor column = tupleDescription.get(i);
            if (column.isProjected() && hiveIndexes.get(i) != null) {
                if (projectedColumnNames.length() > 0) {
                    projectedColumnNames.append(delim);
                    projectedColumnIds.append(delim);
                }
                projectedColumnNames.append(column.columnName());
                projectedColumnIds.append(hiveIndexes.get(i));
            }
        }
        JobConf jobConf = super.getJobConf();
        jobConf.set(READ_ALL_COLUMNS, "false");
        jobConf.set(READ_COLUMN_IDS_CONF_STR, projectedColumnIds.toString());
        jobConf.set(READ_COLUMN_NAMES_CONF_STR, projectedColumnNames.toString());
        return jobConf;
    }

    /**
     * Handle a Hive record.
     * Supported object categories:
     * Primitive - including NULL
     * Struct (used by ColumnarSerDe to store primitives) - cannot be NULL
     * <p/>
     * Any other category will throw UnsupportedTypeException
     */
    private void traverseTuple(Object obj, ObjectInspector objInspector) throws BadRecordException {
        ObjectInspector.Category category = objInspector.getCategory();
        if ((obj == null) && (category != ObjectInspector.Category.PRIMITIVE)) {
            throw new BadRecordException("NULL Hive composite object");
        }
        switch (category) {
            case PRIMITIVE:
                resolvePrimitive(obj, (PrimitiveObjectInspector) objInspector);
                break;
            case STRUCT:
                StructObjectInspector soi = (StructObjectInspector) objInspector;
                List<? extends StructField> fields = soi.getAllStructFieldRefs();
                List<?> list = soi.getStructFieldsDataAsList(obj);
                if (list == null) {
                    throw new BadRecordException("Illegal value NULL for Hive data type Struct");
                }

                Map<String, Integer> columnNameToStructIndexMap =
                        IntStream.range(0, fields.size())
                                .boxed()
                                .collect(Collectors.toMap(i -> StringUtils.lowerCase(fields.get(i).getFieldName()), i -> i));

                List<ColumnDescriptor> tupleDescription = context.getTupleDescription();
                for (int j = 0; j < tupleDescription.size(); j++) {
                    ColumnDescriptor columnDescriptor = tupleDescription.get(j);
                    String lowercaseColumnName = StringUtils.lowerCase(columnDescriptor.columnName());
                    Integer i = columnNameToStructIndexMap.get(lowercaseColumnName);
                    Integer structIndex = hiveIndexes.get(j);
                    HivePartition partition;

                    if ((partition = partitionColumnNames.get(lowercaseColumnName)) != null) {
                        // Skip partitioned columns
                        addPartitionColumn(partition.getType(), partition.getValue());
                    } else if (!columnDescriptor.isProjected()) {
                        // Non-projected fields will be sent as null values.
                        // This case is invoked only in the top level of fields and
                        // not when interpreting fields of type struct.
                        traverseTuple(null, fields.get(i).getFieldObjectInspector());
                    } else if (structIndex < list.size()) {
                        traverseTuple(list.get(structIndex), fields.get(i).getFieldObjectInspector());
                    } else {
                        traverseTuple(null, fields.get(i).getFieldObjectInspector());
                    }
                }
                break;
            default:
                throw new UnsupportedTypeException("Hive object category: " + objInspector.getCategory() + " unsupported");
        }
    }

    private void addPartitionColumn(String type, String val) {
        if (!firstColumn) {
            builder.append(delimiter);
        }
        appendPartition(builder, type, val);
        firstColumn = false;
    }

    private void resolvePrimitive(Object o, PrimitiveObjectInspector oi) {

        if (!firstColumn) {
            builder.append(delimiter);
        }

        if (o == null) {
            builder.append(nullChar);
        } else {
            switch (oi.getPrimitiveCategory()) {
                case BOOLEAN:
                    builder.append(((BooleanObjectInspector) oi).get(o));
                    break;
                case SHORT:
                    builder.append(((ShortObjectInspector) oi).get(o));
                    break;
                case INT:
                    builder.append(((IntObjectInspector) oi).get(o));
                    break;
                case LONG:
                    builder.append(((LongObjectInspector) oi).get(o));
                    break;
                case FLOAT:
                    builder.append(((FloatObjectInspector) oi).get(o));
                    break;
                case DOUBLE:
                    builder.append(((DoubleObjectInspector) oi).get(o));
                    break;
                case DECIMAL:
                    builder.append(((HiveDecimalObjectInspector) oi).getPrimitiveJavaObject(o).bigDecimalValue());
                    break;
                case STRING:
                    builder.append(((StringObjectInspector) oi).getPrimitiveJavaObject(o));
                    break;
                case BINARY:
                    byte[] bytes = ((BinaryObjectInspector) oi).getPrimitiveJavaObject(o);
                    Utilities.byteArrayToOctalString(bytes, builder);
                    break;
                case TIMESTAMP:
                    builder.append(((TimestampObjectInspector) oi).getPrimitiveJavaObject(o));
                    break;
                case BYTE:  /* TINYINT */
                    builder.append(Short.valueOf(((ByteObjectInspector) oi).get(o)));
                    break;
                default:
                    throw new UnsupportedTypeException(oi.getTypeName()
                            + " conversion is not supported by HiveColumnarSerdeResolver");
            }
        }
        firstColumn = false;
    }
}
