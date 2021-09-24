/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.proxy.frontend.mysql.authentication;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import lombok.SneakyThrows;
import org.apache.shardingsphere.db.protocol.mysql.constant.MySQLCapabilityFlag;
import org.apache.shardingsphere.db.protocol.mysql.constant.MySQLConnectionPhase;
import org.apache.shardingsphere.db.protocol.mysql.constant.MySQLServerErrorCode;
import org.apache.shardingsphere.db.protocol.mysql.packet.generic.MySQLErrPacket;
import org.apache.shardingsphere.db.protocol.mysql.packet.generic.MySQLOKPacket;
import org.apache.shardingsphere.db.protocol.mysql.packet.handshake.MySQLHandshakePacket;
import org.apache.shardingsphere.db.protocol.mysql.payload.MySQLPacketPayload;
import org.apache.shardingsphere.infra.config.properties.ConfigurationProperties;
import org.apache.shardingsphere.infra.executor.kernel.ExecutorEngine;
import org.apache.shardingsphere.infra.metadata.ShardingSphereMetaData;
import org.apache.shardingsphere.infra.metadata.rule.ShardingSphereRuleMetaData;
import org.apache.shardingsphere.infra.optimize.context.filterable.FilterableOptimizerContext;
import org.apache.shardingsphere.mode.manager.ContextManager;
import org.apache.shardingsphere.mode.metadata.MetaDataContexts;
import org.apache.shardingsphere.mode.metadata.persist.MetaDataPersistService;
import org.apache.shardingsphere.proxy.backend.context.ProxyContext;
import org.apache.shardingsphere.proxy.frontend.authentication.AuthenticationResultBuilder;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Optional;
import java.util.Properties;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class MySQLAuthenticationEngineTest {
    
    private final MySQLAuthenticationHandler authenticationHandler = mock(MySQLAuthenticationHandler.class);
    
    private final MySQLAuthenticationEngine authenticationEngine = new MySQLAuthenticationEngine();
    
    private final byte[] authResponse = {-27, 89, -20, -27, 65, -120, -64, -101, 86, -100, -108, -100, 6, -125, -37, 117, 14, -43, 95, -113};
    
    @Before
    public void setUp() throws NoSuchFieldException, IllegalAccessException {
        initAuthenticationHandlerForAuthenticationEngine();
    }
    
    private void initAuthenticationHandlerForAuthenticationEngine() throws NoSuchFieldException, IllegalAccessException {
        Field field = MySQLAuthenticationEngine.class.getDeclaredField("authenticationHandler");
        field.setAccessible(true);
        field.set(authenticationEngine, authenticationHandler);
    }
    
    @Test
    public void assertHandshake() {
        ChannelHandlerContext context = getContext();
        assertTrue(authenticationEngine.handshake(context) > 0);
        verify(context).writeAndFlush(any(MySQLHandshakePacket.class));
    }
    
    @Test
    public void assertAuthenticationMethodMismatch() {
        setConnectionPhase(MySQLConnectionPhase.AUTH_PHASE_FAST_PATH);
        MySQLPacketPayload payload = mock(MySQLPacketPayload.class);
        ChannelHandlerContext channelHandlerContext = mock(ChannelHandlerContext.class);
        Channel channel = mock(Channel.class);
        when(payload.readStringNulByBytes()).thenReturn("root".getBytes());
        when(channel.remoteAddress()).thenReturn(new InetSocketAddress("localhost", 3307));
        when(channelHandlerContext.channel()).thenReturn(channel);
        when(payload.readInt4()).thenReturn(MySQLCapabilityFlag.CLIENT_PLUGIN_AUTH.getValue());
        authenticationEngine.authenticate(channelHandlerContext, payload);
        assertThat(getConnectionPhase(), is(MySQLConnectionPhase.AUTHENTICATION_METHOD_MISMATCH));
    }
    
    @Test
    public void assertAuthSwitchResponse() {
        setConnectionPhase(MySQLConnectionPhase.AUTHENTICATION_METHOD_MISMATCH);
        MySQLPacketPayload payload = mock(MySQLPacketPayload.class);
        Channel channel = mock(Channel.class);
        ChannelHandlerContext channelHandlerContext = mock(ChannelHandlerContext.class);
        when(payload.readStringEOFByBytes()).thenReturn(authResponse);
        when(channel.remoteAddress()).thenReturn(new InetSocketAddress("localhost", 3307));
        when(channelHandlerContext.channel()).thenReturn(channel);
        setAuthenticationResult();
        authenticationEngine.authenticate(channelHandlerContext, payload);
        assertThat(getAuthResponse(), is(authResponse));
    }
    
    @SneakyThrows(ReflectiveOperationException.class)
    private void setAuthenticationResult() {
        Field field = MySQLAuthenticationEngine.class.getDeclaredField("currentAuthResult");
        field.setAccessible(true);
        field.set(authenticationEngine, AuthenticationResultBuilder.continued("root", "", "sharding_db"));
    }
    
    @Test
    public void assertAuthWithLoginFail() throws NoSuchFieldException, IllegalAccessException {
        setConnectionPhase(MySQLConnectionPhase.AUTH_PHASE_FAST_PATH);
        ChannelHandlerContext context = getContext();
        setMetaDataContexts();
        when(authenticationHandler.login(anyString(), any(), any(), anyString())).thenReturn(Optional.of(MySQLServerErrorCode.ER_ACCESS_DENIED_ERROR));
        authenticationEngine.authenticate(context, getPayload("root", "sharding_db", authResponse));
        verify(context).writeAndFlush(any(MySQLErrPacket.class));
    }
    
    @Test
    public void assertAuthWithAbsentDatabase() throws NoSuchFieldException, IllegalAccessException {
        ChannelHandlerContext context = getContext();
        setMetaDataContexts();
        setConnectionPhase(MySQLConnectionPhase.AUTH_PHASE_FAST_PATH);
        authenticationEngine.authenticate(context, getPayload("root", "ABSENT DATABASE", authResponse));
        verify(context).writeAndFlush(any(MySQLErrPacket.class));
    }
    
    @Test
    public void assertAuth() throws NoSuchFieldException, IllegalAccessException {
        setConnectionPhase(MySQLConnectionPhase.AUTH_PHASE_FAST_PATH);
        ChannelHandlerContext context = getContext();
        when(authenticationHandler.login(anyString(), any(), any(), anyString())).thenReturn(Optional.empty());
        setMetaDataContexts();
        authenticationEngine.authenticate(context, getPayload("root", "sharding_db", authResponse));
        verify(context).writeAndFlush(any(MySQLOKPacket.class));
    }
    
    private void setMetaDataContexts() throws NoSuchFieldException, IllegalAccessException {
        Field contextManagerField = ProxyContext.getInstance().getClass().getDeclaredField("contextManager");
        contextManagerField.setAccessible(true);
        ContextManager contextManager = mock(ContextManager.class, RETURNS_DEEP_STUBS);
        MetaDataContexts metaDataContexts = new MetaDataContexts(mock(MetaDataPersistService.class),
                Collections.singletonMap("sharding_db", mock(ShardingSphereMetaData.class)), mock(ShardingSphereRuleMetaData.class),
                mock(ExecutorEngine.class), new ConfigurationProperties(new Properties()), mock(FilterableOptimizerContext.class));
        when(contextManager.getMetaDataContexts()).thenReturn(metaDataContexts);
        contextManagerField.set(ProxyContext.getInstance(), contextManager);
    }
    
    private MySQLPacketPayload getPayload(final String username, final String database, final byte[] authResponse) {
        MySQLPacketPayload result = mock(MySQLPacketPayload.class);
        when(result.readInt4()).thenReturn(MySQLCapabilityFlag.CLIENT_CONNECT_WITH_DB.getValue());
        when(result.readStringNul()).thenReturn(username).thenReturn(database);
        when(result.readStringNulByBytes()).thenReturn(authResponse);
        return result;
    }
    
    private ChannelHandlerContext getContext() {
        ChannelHandlerContext result = mock(ChannelHandlerContext.class);
        doReturn(getChannel()).when(result).channel();
        return result;
    }
    
    private Channel getChannel() {
        Channel result = mock(Channel.class);
        doReturn(getRemoteAddress()).when(result).remoteAddress();
        return result;
    }
    
    private SocketAddress getRemoteAddress() {
        SocketAddress result = mock(SocketAddress.class);
        when(result.toString()).thenReturn("127.0.0.1");
        return result;
    }
    
    @SneakyThrows(ReflectiveOperationException.class)
    private void setConnectionPhase(final MySQLConnectionPhase connectionPhase) {
        Field field = MySQLAuthenticationEngine.class.getDeclaredField("connectionPhase");
        field.setAccessible(true);
        field.set(authenticationEngine, connectionPhase);
    }
    
    @SneakyThrows(ReflectiveOperationException.class)
    private MySQLConnectionPhase getConnectionPhase() {
        Field field = MySQLAuthenticationEngine.class.getDeclaredField("connectionPhase");
        field.setAccessible(true);
        return (MySQLConnectionPhase) field.get(authenticationEngine);
    }
    
    @SneakyThrows(ReflectiveOperationException.class)
    private byte[] getAuthResponse() {
        Field field = MySQLAuthenticationEngine.class.getDeclaredField("authResponse");
        field.setAccessible(true);
        return (byte[]) field.get(authenticationEngine);
    }
}
