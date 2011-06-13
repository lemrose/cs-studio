/*******************************************************************************
 * Copyright (c) 2011 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.csstudio.archive.writer.rdb;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.csstudio.archive.writer.ArchiveWriter;
import org.csstudio.archive.writer.WriteChannel;
import org.csstudio.data.values.IDoubleValue;
import org.csstudio.data.values.IEnumeratedMetaData;
import org.csstudio.data.values.IEnumeratedValue;
import org.csstudio.data.values.ILongValue;
import org.csstudio.data.values.IMetaData;
import org.csstudio.data.values.INumericMetaData;
import org.csstudio.data.values.IValue;
import org.csstudio.platform.utility.rdb.RDBUtil;
import org.csstudio.platform.utility.rdb.RDBUtil.Dialect;

/** ArchiveWriter implementation for RDB
 *  @author Kay Kasemir
 *  @author Lana Abadie (PostgreSQL for original RDBArchive code)
 */
@SuppressWarnings("nls")
public class RDBArchiveWriter implements ArchiveWriter
{
    /** Status string for <code>Double.NaN</code> samples */
    final private static String NOT_A_NUMBER_STATUS = "NaN";

    /** Severity string for <code>Double.NaN</code> samples */
    final private static String NOT_A_NUMBER_SEVERITY = "INVALID";

    final private int SQL_TIMEOUT_SECS = Preferences.getSQLTimeoutSecs();
    
    final private int MAX_TEXT_SAMPLE_LENGTH = Preferences.getMaxStringSampleLength();
    
    /** RDB connection */
    final private RDBUtil rdb;

    /** SQL statements */
    final private SQL sql;

    /** Cache of channels by name */
    final private Map<String, RDBWriteChannel> channels = new HashMap<String, RDBWriteChannel>();
    
    /** Severity (ID, name) cache */
    private SeverityCache severities;
    
    /** Status (ID, name) cache */
    private StatusCache stati;
    
    /** Prepared statement for inserting 'double' samples */
    private PreparedStatement insert_double_sample = null;

    /** Prepared statement for inserting 'double' array samples */
    private PreparedStatement insert_double_array_sample = null;

    /** Prepared statement for inserting 'long' samples */
    private PreparedStatement insert_long_sample = null;

    /** Prepared statement for inserting 'String' samples */
    private PreparedStatement insert_txt_sample = null;

    /** Counter for accumulated samples in 'double' batch */
    private int batched_double_inserts = 0;

    /** Counter for accumulated samples in 'double array' batch */
    private int batched_double_array_inserts = 0;

    /** Counter for accumulated samples in 'long' batch */
    private int batched_long_inserts = 0;

    /** Counter for accumulated samples in 'String' batch */
    private int batched_txt_inserts = 0;
    
    /** Copy of batched samples, used to display batch errors */
    private final List<RDBWriteChannel> batched_channel = new ArrayList<RDBWriteChannel>();
    private final List<IValue> batched_samples = new ArrayList<IValue>();

    /** Initialize from preferences.
     *  This constructor will be invoked when an {@link ArchiveWriter}
     *  is created via the extension point.
     *  @throws Exception on error, for example RDB connection error
     */
    public RDBArchiveWriter() throws Exception
    {
        this(Preferences.getURL(), Preferences.getUser(), Preferences.getPassword(),
        		Preferences.getSchema());
    }

    /** Initialize.
     *  This constructor can be invoked by test code.
     *  @param url RDB URL
     *  @param user .. user name
     *  @param password .. password
     *  @throws Exception on error, for example RDB connection error
     */
    public RDBArchiveWriter(final String url, final String user, final String password) throws Exception
    {
        this(url, user, password, Preferences.getSchema());
    }

    /** Initialize
     *  @param url RDB URL
     *  @param user .. user name
     *  @param password .. password
     *  @param schema Schema/table prefix, ending in ".". May be empty
     *  @throws Exception on error, for example RDB connection error
     */
    public RDBArchiveWriter(final String url, final String user, final String password,
            final String schema) throws Exception
    {
        rdb = RDBUtil.connect(url, user, password, false);
        sql = new SQL(rdb.getDialect(), schema);
        severities = new SeverityCache(rdb, sql);
        stati = new StatusCache(rdb, sql);
        // Assert Auto-commit is off because this code commits as needed
        rdb.getConnection().setAutoCommit(false);
    }
    
    @Override
    public WriteChannel getChannel(final String name) throws Exception
    {
        // Check cache
        RDBWriteChannel channel = channels.get(name);
        if (channel == null)
        {
            // Get channel information from RDB
            final PreparedStatement statement = rdb.getConnection().prepareStatement(sql.channel_sel_by_name);
            try
            {
                statement.setString(1, name);
                final ResultSet result = statement.executeQuery();
                if (!result.next())
                    throw new Exception("Unknown channel " + name);
                channel = new RDBWriteChannel(name, result.getInt(1));
                result.close();
                channels.put(name, channel);
            }
            finally
            {
                statement.close();
            }
        }
        return channel;
    }

    @Override
    public void addSample(final WriteChannel channel, final IValue sample) throws Exception
    {
        final RDBWriteChannel rdb_channel = (RDBWriteChannel) channel;
        // Write meta data if it was never written or has changed
        final IMetaData meta = sample.getMetaData();
        if (meta != null  &&  !meta.equals(rdb_channel.getMetadata()))
        {
            writeMetaData(rdb_channel, meta);
            rdb.getConnection().commit();
            rdb_channel.setMetaData(meta);
        }
        batchSample(rdb_channel, sample);
        
        batched_channel.add(rdb_channel);
        batched_samples.add(sample);
    }

    /** @param channel Channel for which to write the meta data
     *  @param meta Meta data to write
     */
    private void writeMetaData(final RDBWriteChannel channel, final IMetaData meta) throws Exception
    {
        // Note that Strings have no meta data. But we don't know at this point
        // if it's really a string channel, or of this is just a special
        // string value like "disconnected".
        // In order to not delete any existing meta data,
        // we just do nothing for strings
        if (meta instanceof IEnumeratedMetaData)
        {
            // Clear numeric meta data, set enumerated in RDB
            NumericMetaDataHelper.delete(rdb, sql, channel);
            EnumMetaDataHelper.delete(rdb, sql, channel);
            EnumMetaDataHelper.insert(rdb, sql, channel, (IEnumeratedMetaData) meta);
        }
        else if (meta instanceof INumericMetaData)
        {
            // Clear enumerated meta data, replace numeric
            EnumMetaDataHelper.delete(rdb, sql, channel);
            NumericMetaDataHelper.delete(rdb, sql, channel);
            NumericMetaDataHelper.insert(rdb, sql, channel, (INumericMetaData) meta);
        }
    }

    /** Perform 'batched' insert for sample.
     *  <p>Needs eventual flush()
     *  @param channel Channel
     *  @param sample Sample to insert
     *  @throws Exception on error
     */
    private void batchSample(final RDBWriteChannel channel, final IValue sample) throws Exception
    {
        final Timestamp stamp = sample.getTime().toSQLTimestamp();
        final Severity severity =
                    severities.findOrCreate(sample.getSeverity().toString());
        final Status status = stati.findOrCreate(sample.getStatus());

        if (sample instanceof IDoubleValue)
        {
            final double[] dbl = ((IDoubleValue)sample).getValues();
            batchDoubleSamples(channel, stamp, severity, status, dbl);
        }
        else if (sample instanceof ILongValue)
        {
            final long[] num = ((ILongValue)sample).getValues();
            
            // Handle arrays as double
            if (num.length > 1)
            {
                final double[] dbl = new double[num.length];
                for (int i=0; i<dbl.length; ++i)
                    dbl[i] = num[i];
                batchDoubleSamples(channel, stamp, severity, status, dbl);
            }
            else
                batchLongSamples(channel, stamp, severity, status, num[0]);
        }
        else if (sample instanceof IEnumeratedValue)
        {   // Enum handled just like (long) integer
            final long num = ((IEnumeratedValue)sample).getValue();
            batchLongSamples(channel, stamp, severity, status, num);
        }
        else
        {   // Handle string and possible other types as strings
            final String txt = sample.format();
            batchTextSamples(channel, stamp, severity, status, txt);
        }
    }
    
    /** Helper for batchSample: Add double sample(s) to batch. */
    private void batchDoubleSamples(final RDBWriteChannel channel,
            final Timestamp stamp, final Severity severity,
            final Status status, final double[] dbl) throws Exception
    {
        if (insert_double_sample == null)
        {
            insert_double_sample =
                rdb.getConnection().prepareStatement(sql.sample_insert_double);
            insert_double_sample.setQueryTimeout(SQL_TIMEOUT_SECS);
        }
        // Catch not-a-number, which JDBC (at least Oracle) can't handle.
        if (Double.isNaN(dbl[0]))
        {
            insert_double_sample.setDouble(5, 0.0);
            completeAndBatchInsert(insert_double_sample,
                    channel, stamp,
                    severities.findOrCreate(NOT_A_NUMBER_SEVERITY),
                    stati.findOrCreate(NOT_A_NUMBER_STATUS));
        }
        else
        {
            insert_double_sample.setDouble(5, dbl[0]);
            completeAndBatchInsert(insert_double_sample, channel, stamp, severity, status);
        }
        ++batched_double_inserts;
        // More array elements?
        if (dbl.length > 1)
        {
            if (insert_double_array_sample == null)
                insert_double_array_sample =
                    rdb.getConnection().prepareStatement(
                            sql.sample_insert_double_array_element);
            for (int i = 1; i < dbl.length; i++)
            {
                insert_double_array_sample.setInt(1, channel.getId());
                insert_double_array_sample.setTimestamp(2, stamp);
                insert_double_array_sample.setInt(3, i);
                // Patch NaN.
                // Conundrum: Should we set the status/severity to indicate NaN?
                // Would be easy if we wrote the main sample with overall
                // stat/sevr at the end.
                // But we have to write it first to avoid index (key) errors
                // with the array sample time stamp....
                // Go back and update the main sample after the fact??
                if (Double.isNaN(dbl[i]))
                    insert_double_array_sample.setDouble(4, 0.0);
                else
                    insert_double_array_sample.setDouble(4, dbl[i]);
                // MySQL nanosecs
                if (rdb.getDialect() == Dialect.MySQL || rdb.getDialect() == Dialect.PostgreSQL)
                    insert_double_array_sample.setInt(5, stamp.getNanos());

                // Batch
                insert_double_array_sample.addBatch();
                ++batched_double_array_inserts;
            }
        }
    }
    
    /** Helper for batchSample: Add long sample to batch.  */
    private void batchLongSamples(final RDBWriteChannel channel,
            final Timestamp stamp, final Severity severity,
            final Status status, final long num) throws Exception
    {
        if (insert_long_sample == null)
        {
            insert_long_sample =
               rdb.getConnection().prepareStatement(sql.sample_insert_int);
            insert_long_sample.setQueryTimeout(SQL_TIMEOUT_SECS);
        }
        insert_long_sample.setLong(5, num);
        completeAndBatchInsert(insert_long_sample, channel, stamp, severity, status);
        ++batched_long_inserts;
    }

    /** Helper for batchSample: Add text sample to batch. */
    private void batchTextSamples(final RDBWriteChannel channel,
            final Timestamp stamp, final Severity severity,
            final Status status, String txt) throws Exception
    {
        if (insert_txt_sample == null)
        {
            insert_txt_sample =
                rdb.getConnection().prepareStatement(sql.sample_insert_string);
            insert_txt_sample.setQueryTimeout(SQL_TIMEOUT_SECS);
        }
        if (txt.length() > MAX_TEXT_SAMPLE_LENGTH)
        {
            Activator.getLogger().log(Level.INFO,
                "Value of {0} exceeds {1} chars: {2}",
                new Object[] { channel.getName(), MAX_TEXT_SAMPLE_LENGTH, txt });
            txt = txt.substring(0, MAX_TEXT_SAMPLE_LENGTH);
        }
        insert_txt_sample.setString(5, txt);
        completeAndBatchInsert(insert_txt_sample, channel, stamp, severity, status);
        ++batched_txt_inserts;
    }
    
    /** Helper for batchSample:
     *  Set the parameters common to all insert statements, add to batch.
     */
    private void completeAndBatchInsert(
            final PreparedStatement insert_xx, final RDBWriteChannel channel,
            final Timestamp stamp, final Severity severity,
            final Status status) throws Exception
    {
        // Set the stuff that's common to each type
        insert_xx.setInt(1, channel.getId());
        insert_xx.setTimestamp(2, stamp);
        insert_xx.setInt(3, severity.getId());
        insert_xx.setInt(4, status.getId());
        // MySQL nanosecs
        if (rdb.getDialect() == Dialect.MySQL  ||  rdb.getDialect() == Dialect.PostgreSQL)
            insert_xx.setInt(6, stamp.getNanos());
        // Batch
        insert_xx.addBatch();
    }

    /** {@inheritDoc}
     *  RDB implementation completes pending batches
     */
    @Override
    public void flush() throws Exception
    {
        try
        {
            if (batched_double_inserts > 0)
            {
                try
                {
                    checkBatchExecution(insert_double_sample);
                }
                finally
                {
                    batched_double_inserts = 0;
                }
            }
            if (batched_long_inserts > 0)
            {
                try
                {
                    checkBatchExecution(insert_long_sample);
                }
                finally
                {
                    batched_long_inserts = 0;
                }
            }
            if (batched_txt_inserts > 0)
            {
                try
                {
                    checkBatchExecution(insert_txt_sample);
                }
                finally
                {
                    batched_txt_inserts = 0;
                }
            }
            if (batched_double_array_inserts > 0)
            {
                try
                {
                    checkBatchExecution(insert_double_array_sample);
                }
                finally
                {
                    batched_double_array_inserts = 0;
                }
            }
        }
        catch (final Exception ex)
        {
            if (ex.getMessage().contains("unique"))
            {
                System.out.println(new Date().toString() + " Unique constraint error in these samples: " + ex.getMessage()); //$NON-NLS-1$
                if (batched_samples.size() != batched_channel.size())
                    System.out.println("Inconsistent batch history");
                final int N = Math.min(batched_samples.size(), batched_channel.size());
                for (int i=0; i<N; ++i)
                    attemptSingleInsert(batched_channel.get(i), batched_samples.get(i));
            }
            throw ex;
        }
        finally
        {
            batched_channel.clear();
            batched_samples.clear();
        }
    }
    
    /** Submit and clear the batch, or roll back on error */
    private void checkBatchExecution(final PreparedStatement insert) throws Exception
    {
        try
        {   // Try to perform the inserts
            // In principle this could return update counts for
            // each batched insert, but Oracle 10g and 11g just throw
            // an exception
            insert.executeBatch();
            rdb.getConnection().commit();
        }
        catch (final Exception ex)
        {
            try
            {
                // On failure, roll back.
                // With Oracle 10g, the BatchUpdateException doesn't
                // indicate which of the batched commands faulted...
                insert.clearBatch();
                // Still: Commit what's committable.
                // Unfortunately no way to know what failed,
                // and no way to re-submit the 'remaining' inserts.
                rdb.getConnection().commit();
            }
            catch (Exception nested)
            {
                Activator.getLogger().log(Level.WARNING,
                        "clearBatch(), commit() error after batch issue", nested);
            }
            throw ex;
        }
    }
    
    /** The batched insert failed, so try to insert this channel's sample
     *  individually, mostly to debug errors
     *  @param channel
     *  @param sample
     */
    private void attemptSingleInsert(final RDBWriteChannel channel, final IValue sample)
    {
        System.out.println("Individual insert of " + channel.getName() + " = " + sample.toString());
        try
        {
            final Timestamp stamp = sample.getTime().toSQLTimestamp();
            final Severity severity = severities.findOrCreate(sample.getSeverity().toString());
            final Status status = stati.findOrCreate(sample.getStatus());
            if (sample instanceof IDoubleValue)
            {
                final IDoubleValue dbl = (IDoubleValue) sample;
                if (dbl.getValues().length > 1)
                    throw new Exception("Not checking array samples");
                if (Double.isNaN(dbl.getValue()))
                    throw new Exception("Not checking NaN values");
                insert_double_sample.setInt(1, channel.getId());
                insert_double_sample.setTimestamp(2, stamp);
                insert_double_sample.setInt(3, severity.getId());
                insert_double_sample.setInt(4, status.getId());
                insert_double_sample.setDouble(5, dbl.getValue());
                // MySQL nanosecs
                if (rdb.getDialect() == Dialect.MySQL || rdb.getDialect() == Dialect.PostgreSQL)
                    insert_double_sample.setInt(6, stamp.getNanos());
                insert_double_sample.executeUpdate();
            }
            else if (sample instanceof ILongValue)
            {
                final ILongValue num = (ILongValue) sample;
                if (num.getValues().length > 1)
                    throw new Exception("Not checking array samples");
                insert_long_sample.setInt(1, channel.getId());
                insert_long_sample.setTimestamp(2, stamp);
                insert_long_sample.setInt(3, severity.getId());
                insert_long_sample.setInt(4, status.getId());
                insert_long_sample.setLong(5, num.getValue());
                // MySQL nanosecs
                if (rdb.getDialect() == Dialect.MySQL || rdb.getDialect() == Dialect.PostgreSQL)
                    insert_long_sample.setInt(6, stamp.getNanos());
                insert_long_sample.executeUpdate();
            }
            else if (sample instanceof IEnumeratedValue)
            {   // Enum handled just like (long) integer
                final IEnumeratedValue num = (IEnumeratedValue) sample;
                if (num.getValues().length > 1)
                    throw new Exception("Not checking array samples");
                insert_long_sample.setInt(1, channel.getId());
                insert_long_sample.setTimestamp(2, stamp);
                insert_long_sample.setInt(3, severity.getId());
                insert_long_sample.setInt(4, status.getId());
                insert_long_sample.setLong(5, num.getValue());
                // MySQL nanosecs
                if (rdb.getDialect() == Dialect.MySQL || rdb.getDialect() == Dialect.PostgreSQL)
                    insert_long_sample.setInt(6, stamp.getNanos());
                insert_long_sample.executeUpdate();
            }
            else
            {   // Handle string and possible other types as strings
                final String txt = sample.format();
                insert_txt_sample.setInt(1, channel.getId());
                insert_txt_sample.setTimestamp(2, stamp);
                insert_txt_sample.setInt(3, severity.getId());
                insert_txt_sample.setInt(4, status.getId());
                insert_txt_sample.setString(5, txt);
                // MySQL nanosecs
                if (rdb.getDialect() == Dialect.MySQL || rdb.getDialect() == Dialect.PostgreSQL)
                    insert_txt_sample.setInt(6, stamp.getNanos());
                insert_txt_sample.executeUpdate();
            }
            rdb.getConnection().commit();
        }
        catch (Exception ex)
        {
            System.out.println("Individual insert failed: " + ex.getMessage());
        }
    }

    /** {@inheritDoc} */
    @Override
    public void close()
    {
        channels.clear();
        if (severities != null)
        {
            severities.dispose();
            severities = null;
        }
        if (stati != null)
        {
            stati.dispose();
            stati = null;
        }
        rdb.close();
    }
}