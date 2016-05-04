/**
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.rdb.sharding.jdbc;

import com.dangdang.ddframe.rdb.sharding.executor.PreparedStatementExecutor;
import com.dangdang.ddframe.rdb.sharding.executor.wrapper.PreparedStatementExecutorWrapper;
import com.dangdang.ddframe.rdb.sharding.jdbc.adapter.AbstractPreparedStatementAdapter;
import com.dangdang.ddframe.rdb.sharding.merger.ResultSetFactory;
import com.dangdang.ddframe.rdb.sharding.parser.result.merger.MergeContext;
import com.dangdang.ddframe.rdb.sharding.router.SQLExecutionUnit;
import com.dangdang.ddframe.rdb.sharding.router.SQLRouteResult;
import com.google.common.base.Function;
import com.google.common.collect.Lists;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * 支持分片的预编译语句对象.
 * 
 * @author zhangliang
 * @author caohao
 */
public final class ShardingPreparedStatement extends AbstractPreparedStatementAdapter {
    
    private final String sql;
    
    private final List<PreparedStatementExecutorWrapper> cachedRoutedPreparedStatements = new LinkedList<>();
    
    private Integer autoGeneratedKeys;
    
    private int[] columnIndexes;
    
    private String[] columnNames;
    
    private boolean hasExecuted;
    
    private final List<List<Object>> batchParameters = new ArrayList<>();
    
    public ShardingPreparedStatement(final ShardingConnection shardingConnection, final String sql) throws SQLException {
        this(shardingConnection, sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }
    
    public ShardingPreparedStatement(final ShardingConnection shardingConnection, 
            final String sql, final int resultSetType, final int resultSetConcurrency) throws SQLException {
        this(shardingConnection, sql, resultSetType, resultSetConcurrency, ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }
    
    public ShardingPreparedStatement(final ShardingConnection shardingConnection, 
            final String sql, final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability) throws SQLException {
        super(shardingConnection, resultSetType, resultSetConcurrency, resultSetHoldability);
        this.sql = sql;
    }
    
    public ShardingPreparedStatement(final ShardingConnection shardingConnection, final String sql, final int autoGeneratedKeys) throws SQLException {
        this(shardingConnection, sql);
        this.autoGeneratedKeys = autoGeneratedKeys;
    }
    
    public ShardingPreparedStatement(final ShardingConnection shardingConnection, final String sql, final int[] columnIndexes) throws SQLException {
        this(shardingConnection, sql);
        this.columnIndexes = columnIndexes;
    }
    
    public ShardingPreparedStatement(final ShardingConnection shardingConnection, final String sql, final String[] columnNames) throws SQLException {
        this(shardingConnection, sql);
        this.columnNames = columnNames;
    }
    
    @Override
    public ResultSet executeQuery() throws SQLException {
        hasExecuted = true;
        setCurrentResultSet(ResultSetFactory.getResultSet(new PreparedStatementExecutor(getShardingConnection().getShardingContext().getExecutorEngine(), 
                getRoutedPreparedStatements()).executeQuery(), getMergeContext()));
        return getCurrentResultSet();
    }
    
    @Override
    public int executeUpdate() throws SQLException {
        hasExecuted = true;
        return new PreparedStatementExecutor(getShardingConnection().getShardingContext().getExecutorEngine(), getRoutedPreparedStatements()).executeUpdate();
    }
    
    @Override
    public boolean execute() throws SQLException {
        hasExecuted = true;
        return new PreparedStatementExecutor(getShardingConnection().getShardingContext().getExecutorEngine(), getRoutedPreparedStatements()).execute();
    }
    
    @Override
    public void addBatch() throws SQLException {
        batchParameters.add(Lists.newArrayList(getParameters()));
        getParameters().clear();
    }
    
    @Override
    public void clearBatch() throws SQLException {
        batchParameters.clear();
    }
    
    @Override
    public int[] executeBatch() throws SQLException {
        hasExecuted = true;
        int[] result = new int[batchParameters.size()];
        int i = 0;
        for (List<Object> each : batchParameters) {
            List<PreparedStatementExecutorWrapper> routePreparedStatements = routeSQL(each);
            cachedRoutedPreparedStatements.addAll(routePreparedStatements);
            result[i++] = new PreparedStatementExecutor(getShardingConnection().getShardingContext().getExecutorEngine(), routePreparedStatements).executeUpdate();
        }
        return result;
    }
    
    private List<PreparedStatementExecutorWrapper> getRoutedPreparedStatements() throws SQLException {
        if (!hasExecuted) {
            return Collections.emptyList();
        }
        routeIfNeed();
        return cachedRoutedPreparedStatements;
    }
    
    
    @Override
    public List<? extends Statement> getRoutedStatements() throws SQLException {
        return Lists.transform(getRoutedPreparedStatements(), new Function<PreparedStatementExecutorWrapper, Statement>() {
            
            @Override
            public Statement apply(final PreparedStatementExecutorWrapper input) {
                return input.getPreparedStatement();
            }
        });
    }
    
    private void routeIfNeed() throws SQLException {
        if (!cachedRoutedPreparedStatements.isEmpty()) {
            return;
        }
        cachedRoutedPreparedStatements.addAll(routeSQL(getParameters()));
    }
    
    private List<PreparedStatementExecutorWrapper> routeSQL(final List<Object> parameters) throws SQLException {
        List<PreparedStatementExecutorWrapper> result = new ArrayList<>();
        SQLRouteResult sqlRouteResult = getShardingConnection().getShardingContext().getSqlRouteEngine().route(sql, parameters);
        MergeContext mergeContext = sqlRouteResult.getMergeContext();
        mergeContext.setExecutorEngine(getShardingConnection().getShardingContext().getExecutorEngine());
        setMergeContext(mergeContext);
        for (SQLExecutionUnit each : sqlRouteResult.getExecutionUnits()) {
            PreparedStatement preparedStatement = generatePrepareStatement(getShardingConnection().getConnection(each.getDataSource()), each.getSql());
            replayMethodsInvocation(preparedStatement);
            setParameters(preparedStatement, parameters);
            result.add(new PreparedStatementExecutorWrapper(preparedStatement, parameters, each));
        }
        return result;
    }
    
    private PreparedStatement generatePrepareStatement(final Connection conn, final String shardingSql) throws SQLException {
        if (null != autoGeneratedKeys) {
            return conn.prepareStatement(shardingSql, autoGeneratedKeys);
        }
        if (null != columnIndexes) {
            return conn.prepareStatement(shardingSql, columnIndexes);
        }
        if (null != columnNames) {
            return conn.prepareStatement(shardingSql, columnNames);
        }
        if (0 != getResultSetHoldability()) {
            return conn.prepareStatement(shardingSql, getResultSetType(), getResultSetConcurrency(), getResultSetHoldability());
        }
        return conn.prepareStatement(shardingSql, getResultSetType(), getResultSetConcurrency());
    }
    
    private void setParameters(final PreparedStatement preparedStatement, final List<Object> parameters) throws SQLException {
        int i = 1;
        for (Object each : parameters) {
            preparedStatement.setObject(i++, each);
        }
    }
}
