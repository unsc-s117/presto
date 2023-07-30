/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.plugin.jdbc;

import com.facebook.airlift.log.Logger;
import com.facebook.presto.common.Page;
import com.facebook.presto.common.block.Block;
import com.facebook.presto.common.type.DecimalType;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.common.type.UuidType;
import com.facebook.presto.spi.ConnectorPageSink;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.PrestoException;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Shorts;
import com.google.common.primitives.SignedBytes;
import io.airlift.slice.Slice;
import org.joda.time.DateTimeZone;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLNonTransientException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.facebook.presto.common.type.BigintType.BIGINT;
import static com.facebook.presto.common.type.BooleanType.BOOLEAN;
import static com.facebook.presto.common.type.Chars.isCharType;
import static com.facebook.presto.common.type.DateType.DATE;
import static com.facebook.presto.common.type.Decimals.readBigDecimal;
import static com.facebook.presto.common.type.DoubleType.DOUBLE;
import static com.facebook.presto.common.type.IntegerType.INTEGER;
import static com.facebook.presto.common.type.RealType.REAL;
import static com.facebook.presto.common.type.SmallintType.SMALLINT;
import static com.facebook.presto.common.type.TinyintType.TINYINT;
import static com.facebook.presto.common.type.UuidType.prestoUuidToJavaUuid;
import static com.facebook.presto.common.type.VarbinaryType.VARBINARY;
import static com.facebook.presto.common.type.Varchars.isVarcharType;
import static com.facebook.presto.plugin.jdbc.JdbcErrorCode.JDBC_ERROR;
import static com.facebook.presto.plugin.jdbc.JdbcErrorCode.JDBC_NON_TRANSIENT_ERROR;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static java.lang.Float.intBitsToFloat;
import static java.lang.Math.toIntExact;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.TimeUnit.DAYS;
import static org.joda.time.chrono.ISOChronology.getInstanceUTC;

public class JdbcPageSink
        implements ConnectorPageSink
{
    private static final Logger log = Logger.get(JdbcPageSink.class);

    private final Connection connection;
    private final PreparedStatement statement;

    private final List<Type> columnTypes;
    private final int maxBatchSize;
    private int batchSize;

    public JdbcPageSink(ConnectorSession session, JdbcOutputTableHandle handle, JdbcClient jdbcClient)
    {
        try {
            connection = jdbcClient.getConnection(session, JdbcIdentity.from(session), handle);
        }
        catch (SQLException e) {
            throw new PrestoException(JDBC_ERROR, e);
        }

        try {
            connection.setAutoCommit(false);
            statement = connection.prepareStatement(jdbcClient.buildInsertSql(session, handle));
        }
        catch (SQLException e) {
            closeWithSuppression(connection, e);
            throw new PrestoException(JDBC_ERROR, e);
        }

        columnTypes = handle.getColumnTypes();

        // Making batch size configurable allows performance tuning for insert/write-heavy workloads over multiple connections.
        this.maxBatchSize = jdbcClient.getWriteBatchSize();
    }

    @Override
    public CompletableFuture<?> appendPage(Page page)
    {
        try {
            for (int position = 0; position < page.getPositionCount(); position++) {
                for (int channel = 0; channel < page.getChannelCount(); channel++) {
                    appendColumn(page, position, channel);
                }

                statement.addBatch();
                batchSize++;

                if (batchSize >= maxBatchSize) {
                    statement.executeBatch();
                    connection.commit();
                    connection.setAutoCommit(false);
                    batchSize = 0;
                }
            }
        }
        catch (SQLException e) {
            throw new PrestoException(JDBC_ERROR, e);
        }
        return NOT_BLOCKED;
    }

    private void appendColumn(Page page, int position, int channel)
            throws SQLException
    {
        Block block = page.getBlock(channel);
        int parameter = channel + 1;

        if (block.isNull(position)) {
            statement.setObject(parameter, null);
            return;
        }

        Type type = columnTypes.get(channel);
        if (BOOLEAN.equals(type)) {
            statement.setBoolean(parameter, type.getBoolean(block, position));
        }
        else if (BIGINT.equals(type)) {
            statement.setLong(parameter, type.getLong(block, position));
        }
        else if (INTEGER.equals(type)) {
            statement.setInt(parameter, toIntExact(type.getLong(block, position)));
        }
        else if (SMALLINT.equals(type)) {
            statement.setShort(parameter, Shorts.checkedCast(type.getLong(block, position)));
        }
        else if (TINYINT.equals(type)) {
            statement.setByte(parameter, SignedBytes.checkedCast(type.getLong(block, position)));
        }
        else if (DOUBLE.equals(type)) {
            statement.setDouble(parameter, type.getDouble(block, position));
        }
        else if (REAL.equals(type)) {
            statement.setFloat(parameter, intBitsToFloat(toIntExact(type.getLong(block, position))));
        }
        else if (type instanceof DecimalType) {
            statement.setBigDecimal(parameter, readBigDecimal((DecimalType) type, block, position));
        }
        else if (isVarcharType(type) || isCharType(type)) {
            statement.setString(parameter, type.getSlice(block, position).toStringUtf8());
        }
        else if (VARBINARY.equals(type)) {
            statement.setBytes(parameter, type.getSlice(block, position).getBytes());
        }
        else if (DATE.equals(type)) {
            // convert to midnight in default time zone
            long utcMillis = DAYS.toMillis(type.getLong(block, position));
            long localMillis = getInstanceUTC().getZone().getMillisKeepLocal(DateTimeZone.getDefault(), utcMillis);
            statement.setDate(parameter, new Date(localMillis));
        }
        else if (UuidType.UUID.equals(type)) {
            Slice slice = type.getSlice(block, position);
            statement.setObject(parameter, prestoUuidToJavaUuid(slice));
        }
        else {
            throw new PrestoException(NOT_SUPPORTED, "Unsupported column type: " + type.getDisplayName());
        }
    }

    @Override
    public CompletableFuture<Collection<Slice>> finish()
    {
        // commit and close
        try (Connection connection = this.connection;
                PreparedStatement statement = this.statement) {
            if (batchSize > 0) {
                statement.executeBatch();
                connection.commit();
            }
        }
        catch (SQLNonTransientException e) {
            throw new PrestoException(JDBC_NON_TRANSIENT_ERROR, e);
        }
        catch (SQLException e) {
            throw new PrestoException(JDBC_ERROR, e);
        }
        // the committer does not need any additional info
        return completedFuture(ImmutableList.of());
    }

    @SuppressWarnings("unused")
    @Override
    public void abort()
    {
        // rollback and close
        try (Connection connection = this.connection;
                PreparedStatement statement = this.statement) {
            connection.rollback();
        }
        catch (SQLException e) {
            // Exceptions happened during abort do not cause any real damage so ignore them
            log.debug(e, "SQLException when abort");
        }
    }

    @SuppressWarnings("ObjectEquality")
    private static void closeWithSuppression(Connection connection, Throwable throwable)
    {
        try {
            connection.close();
        }
        catch (Throwable t) {
            // Self-suppression not permitted
            if (throwable != t) {
                throwable.addSuppressed(t);
            }
        }
    }
}
