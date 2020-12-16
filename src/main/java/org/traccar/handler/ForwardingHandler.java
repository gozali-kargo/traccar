/*
 * Copyright 2015 - 2020 Anton Tananaev (anton@traccar.org)
 *
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
package org.traccar.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import jnr.constants.platform.windows.Inet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseDataHandler;
import org.traccar.Context;
import org.traccar.Main;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.IdentityManager;
import org.traccar.helper.Checksum;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.Position;

import javax.inject.Inject;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.InvocationCallback;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@ChannelHandler.Sharable
public class ForwardingHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ForwardingHandler.class);

    private final IdentityManager identityManager;
    private final ObjectMapper objectMapper;
    private final Client client;

    private final boolean urlVariables;

    private final boolean retryEnabled;
    private final int retryDelay;
    private final int retryCount;
    private final int retryLimit;

    private final AtomicInteger deliveryPending;

    @Inject
    public ForwardingHandler(
            Config config, IdentityManager identityManager, ObjectMapper objectMapper, Client client) {

        this.identityManager = identityManager;
        this.objectMapper = objectMapper;
        this.client = client;
        this.urlVariables = config.getBoolean(Keys.FORWARD_URL_VARIABLES);

        this.retryEnabled = config.getBoolean(Keys.FORWARD_RETRY_ENABLE);
        this.retryDelay = config.getInteger(Keys.FORWARD_RETRY_DELAY, 100);
        this.retryCount = config.getInteger(Keys.FORWARD_RETRY_COUNT, 10);
        this.retryLimit = config.getInteger(Keys.FORWARD_RETRY_LIMIT, 100);

        this.deliveryPending = new AtomicInteger(0);
    }

    class AsyncRequestAndCallback implements InvocationCallback<Response>, TimerTask {

        private int retries = 0;
        private byte[] message;
        private InetSocketAddress address;

        AsyncRequestAndCallback(InetSocketAddress address, Position position, boolean isDatagram) {
            final String raw = position.getString(Position.KEY_ORIGINAL);
        }

        private void send() {
        }

        private void sendUdp(){
            try{
                DatagramPacket packet = new DatagramPacket(message, message.length, address);
                DatagramSocket dsocket = new DatagramSocket();
                dsocket.send(packet);
                dsocket.close();
            } catch (Exception e) {
                System.err.println(e);
            }
        }

        private void sendTcp(){
                try (AsynchronousSocketChannel client = AsynchronousSocketChannel.open()) {
                    Future<Void> result = client.connect(address);
                    result.get();
                    ByteBuffer buffer = ByteBuffer.wrap(message);
                    Future<Integer> writeval = client.write(buffer);
                    writeval.get();
                    buffer.flip();
                    Future<Integer> readval = client.read(buffer);
                    readval.get();
                    buffer.clear();
                }
                catch (ExecutionException | IOException e) {
                    e.printStackTrace();
                }
                catch (InterruptedException e) {
                    System.out.println("Disconnected from the server.");
                }
            }
    }

        private void retry() {
            boolean scheduled = false;
            try {
                if (retryEnabled && deliveryPending.get() <= retryLimit && retries < retryCount) {
                    schedule();
                    scheduled = true;
                }
            } finally {
                int pending = scheduled ? deliveryPending.get() : deliveryPending.decrementAndGet();
                LOGGER.warn("Position forwarding failed: " + pending + " pending");
            }
        }

        private void schedule() {
            Main.getInjector().getInstance(Timer.class).newTimeout(
                this, retryDelay * (int) Math.pow(2, retries++), TimeUnit.MILLISECONDS);
        }

        @Override
        public void completed(Response response) {
            if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
                deliveryPending.decrementAndGet();
            } else {
                retry();
            }
        }

        @Override
        public void failed(Throwable throwable) {
            retry();
        }

        @Override
        public void run(Timeout timeout) {
            boolean sent = false;
            try {
                if (!timeout.isCancelled()) {
                    send();
                    sent = true;
                }
            } finally {
                if (!sent) {
                    deliveryPending.decrementAndGet();
                }
            }
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Position) {
            Position position = (Position) msg;
            final InetSocketAddress address = getForwardingAddress(position.getDeviceId());
            if(address != null) {
                boolean isDatagram = ctx.channel() instanceof DatagramChannel;
                AsyncRequestAndCallback request = new AsyncRequestAndCallback(address, position, isDatagram);
                request.send();
            }
            ctx.fireChannelRead(position);
        } else {
            super.channelRead(ctx, msg);
        }
    }

    private InetSocketAddress getForwardingAddress(long deviceId){
        return null;
    }


}
