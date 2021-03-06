/*
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.metrics.serialization.hadoop.pig;

import com.ning.metrics.goodwill.access.GoodwillAccessor;
import com.ning.metrics.goodwill.access.GoodwillSchema;
import com.ning.metrics.goodwill.access.GoodwillSchemaField;
import com.ning.metrics.serialization.hadoop.HadoopThriftEnvelopeSerialization;
import com.ning.metrics.serialization.hadoop.HadoopThriftWritableSerialization;
import com.ning.metrics.serialization.schema.SchemaFieldType;
import com.ning.metrics.serialization.thrift.ThriftEnvelope;
import com.ning.metrics.serialization.thrift.ThriftField;
import com.ning.metrics.serialization.thrift.hadoop.ThriftWritable;
import com.ning.metrics.serialization.thrift.item.DataItem;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.pig.Expression;
import org.apache.pig.LoadFunc;
import org.apache.pig.LoadMetadata;
import org.apache.pig.ResourceSchema;
import org.apache.pig.ResourceStatistics;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigSplit;
import org.apache.pig.data.DataType;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.logicalLayer.schema.Schema;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class ThriftStorage extends LoadFunc implements LoadMetadata
{
    private final TupleFactory factory = TupleFactory.getInstance();
    private final GoodwillSchema schema;

    private Object value;
    private RecordReader reader;
    private PigSplit split;

    public ThriftStorage(String schemaName) throws IOException
    {
        this(schemaName, System.getProperty("goodwill.host", "127.0.0.1"), System.getProperty("goodwill.port", "8080"));
    }


    public ThriftStorage(String schemaName, String goodwillHost, String goodwillPort) throws IOException
    {
        try {
            GoodwillAccessor goodwillAccessor = new GoodwillAccessor(goodwillHost, Integer.parseInt(goodwillPort));
            schema = goodwillAccessor.getSchema(schemaName).get();

            if (schema == null) {
                throw new IOException(String.format("Unable to find schema %s in Goodwill (%s:%d)",
                    schemaName, goodwillHost, goodwillPort));
            }
        }
        catch (InterruptedException e) {
            throw new IOException("Interrupted while trying to fetch Thrift schema", e);
        }
        catch (ExecutionException e) {
            throw new IOException("Exception while trying to fetch Thrfit schema", e);
        }
    }

    /**
     * Communicate to the loader the location of the object(s) being loaded.
     * The location string passed to the LoadFunc here is the return value of
     * {@link org.apache.pig.LoadFunc#relativeToAbsolutePath(String, org.apache.hadoop.fs.Path)}. Implementations
     * should use this method to communicate the location (and any other information)
     * to its underlying InputFormat through the Job object.
     * <p/>
     * This method will be called in the backend multiple times. Implementations
     * should bear in mind that this method is called multiple times and should
     * ensure there are no inconsistent side effects due to the multiple calls.
     *
     * @param location Location as returned by
     *                 {@link org.apache.pig.LoadFunc#relativeToAbsolutePath(String, org.apache.hadoop.fs.Path)}
     * @param job      the {@link org.apache.hadoop.mapreduce.Job} object
     *                 store or retrieve earlier stored information from the {@link org.apache.pig.impl.util.UDFContext}
     * @throws java.io.IOException if the location is not valid.
     */
    @Override
    public void setLocation(String location, Job job) throws IOException
    {
        setIOSerializations(job.getConfiguration());
        FileInputFormat.setInputPaths(job, location);
    }

    /**
     * This will be called during planning on the front end. This is the
     * instance of InputFormat (rather than the class name) because the
     * load function may need to instantiate the InputFormat in order
     * to control how it is constructed.
     *
     * @return the InputFormat associated with this loader.
     * @throws java.io.IOException if there is an exception during InputFormat
     *                             construction
     */
    @Override
    public InputFormat getInputFormat() throws IOException
    {
        return new SequenceFileInputFormat<ThriftWritable, ThriftEnvelope>();
    }

    /**
     * Initializes LoadFunc for reading data.  This will be called during execution
     * before any calls to getNext.  The RecordReader needs to be passed here because
     * it has been instantiated for a particular InputSplit.
     *
     * @param reader {@link org.apache.hadoop.mapreduce.RecordReader} to be used by this instance of the LoadFunc
     * @param split  The input {@link org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigSplit} to process
     * @throws java.io.IOException if there is an exception during initialization
     */
    @Override
    public void prepareToRead(RecordReader reader, PigSplit split) throws IOException
    {
        this.reader = reader;
        this.split = split;
        setIOSerializations(split.getConf());
    }

    private void setIOSerializations(Configuration conf)
    {
        String[] configuredSerializations = conf.getStrings("io.serializations");
        int i = configuredSerializations.length;

        String[] allSerializations = new String[i + 3];
        System.arraycopy(configuredSerializations, 0, allSerializations, 0, i);

        allSerializations[i] = HadoopThriftWritableSerialization.class.getName();
        allSerializations[i + 1] = HadoopThriftEnvelopeSerialization.class.getName();
        allSerializations[i + 2] = "org.apache.hadoop.io.serializer.WritableSerialization";

        conf.setStrings("io.serializations", allSerializations);
    }

    @Override
    public Tuple getNext() throws IOException
    {
        try {
            if (reader == null || !reader.nextKeyValue()) {
                return null;
            }

            value = reader.getCurrentValue();

            if (value instanceof ThriftEnvelope) {
                final ThriftEnvelope envelope = (ThriftEnvelope) value;
                final Tuple tuple = factory.newTuple(envelope.getPayload().size());
                for (final ThriftField thriftField : envelope.getPayload()) {
                    final GoodwillSchemaField schemaField = schema.getFieldByPosition(thriftField.getId());

                    if (schemaField == null) {
                        throw new IOException(String.format("got a thrift ID [%d] that is not part of the schema", thriftField.getId()));
                    }

                    // Thrift starts at 1
                    tuple.set(thriftField.getId() - 1, convertToObject(thriftField.getDataItem(), schemaField.getType()));
                }

                return tuple;
            }
            else {
                throw new IOException(String.format("Expected ThriftEnvelope, not %s", value.getClass()));
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return null;
    }

    private Object convertToObject(DataItem dataItem, SchemaFieldType type)
    {
        switch (type) {
            // Pig does not support a boolean type (yet), so convert to integer
            case BOOLEAN:
                return dataItem.getBoolean() ? 1 : 0;
            case BYTE:
                return dataItem.getByte();
            case SHORT:
            case INTEGER:
                return dataItem.getInteger();
            case LONG:
                return dataItem.getLong();
            case DOUBLE:
                return dataItem.getDouble();
            case DATE:
            case IP:
            case STRING:
            default:
                return dataItem.getString();
        }
    }

    private byte getPigType(SchemaFieldType type)
    {
        switch (type) {
            case BOOLEAN:
                return DataType.INTEGER;
            case BYTE:
                return DataType.BYTE;
            case SHORT:
            case INTEGER:
                return DataType.INTEGER;
            case LONG:
                return DataType.LONG;
            case DOUBLE:
                return DataType.DOUBLE;
            case DATE:
            case IP:
            case STRING:
            default:
                return DataType.CHARARRAY;
        }
    }

    /**
     * Get a schema for the data to be loaded.
     *
     * @param location Location as returned by
     *                 {@link org.apache.pig.LoadFunc#relativeToAbsolutePath(String, org.apache.hadoop.fs.Path)}
     * @param job      The {@link org.apache.hadoop.mapreduce.Job} object - this should be used only to obtain
     *                 cluster properties through {@link org.apache.hadoop.mapreduce.Job#getConfiguration()} and not to set/query
     *                 any runtime job information.
     * @return schema for the data to be loaded. This schema should represent
     *         all tuples of the returned data.  If the schema is unknown or it is
     *         not possible to return a schema that represents all returned data,
     *         then null should be returned. The schema should not be affected by pushProjection, ie.
     *         getSchema should always return the original schema even after pushProjection
     * @throws java.io.IOException if an exception occurs while determining the schema
     */
    @Override
    public ResourceSchema getSchema(String location, Job job) throws IOException
    {
        List<Schema.FieldSchema> schemaList = new ArrayList<Schema.FieldSchema>();
        for (GoodwillSchemaField field : schema.getSchema()) {
            schemaList.add(new Schema.FieldSchema(field.getName(), getPigType(field.getType())));
        }

        return new ResourceSchema(new Schema(schemaList));
    }

    /**
     * Get statistics about the data to be loaded.  If no statistics are
     * available, then null should be returned.
     *
     * @param location Location as returned by
     *                 {@link org.apache.pig.LoadFunc#relativeToAbsolutePath(String, org.apache.hadoop.fs.Path)}
     * @param job      The {@link org.apache.hadoop.mapreduce.Job} object - this should be used only to obtain
     *                 cluster properties through {@link org.apache.hadoop.mapreduce.Job#getConfiguration()} and not to set/query
     *                 any runtime job information.
     * @return statistics about the data to be loaded.  If no statistics are
     *         available, then null should be returned.
     * @throws java.io.IOException if an exception occurs while retrieving statistics
     */
    @Override
    public ResourceStatistics getStatistics(String location, Job job) throws IOException
    {
        return null;
    }

    /**
     * Find what columns are partition keys for this input.
     *
     * @param location Location as returned by
     *                 {@link org.apache.pig.LoadFunc#relativeToAbsolutePath(String, org.apache.hadoop.fs.Path)}
     * @param job      The {@link org.apache.hadoop.mapreduce.Job} object - this should be used only to obtain
     *                 cluster properties through {@link org.apache.hadoop.mapreduce.Job#getConfiguration()} and not to set/query
     *                 any runtime job information.
     * @return array of field names of the partition keys. Implementations
     *         should return null to indicate that there are no partition keys
     * @throws java.io.IOException if an exception occurs while retrieving partition keys
     */
    @Override
    public String[] getPartitionKeys(String location, Job job) throws IOException
    {
        return null;
    }

    /**
     * Set the filter for partitioning.  It is assumed that this filter
     * will only contain references to fields given as partition keys in
     * getPartitionKeys. So if the implementation returns null in
     * {@link #getPartitionKeys(String, org.apache.hadoop.mapreduce.Job)}, then this method is not
     * called by Pig runtime. This method is also not called by the Pig runtime
     * if there are no partition filter conditions.
     *
     * @param partitionFilter that describes filter for partitioning
     * @throws java.io.IOException if the filter is not compatible with the storage
     *                             mechanism or contains non-partition fields.
     */
    @Override
    public void setPartitionFilter(Expression partitionFilter) throws IOException
    {
    }
}
