/**
 * Copyright 2016-2017 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.nukleus.socks.internal.stream.server;

import static java.lang.String.format;
import static org.reaktivity.nukleus.socks.internal.stream.types.SocksAddressTypes.SOCKS_ADDRESS_DOMAIN;
import static org.reaktivity.nukleus.socks.internal.stream.types.SocksAddressTypes.SOCKS_ADDRESS_IP4;
import static org.reaktivity.nukleus.socks.internal.stream.types.SocksAddressTypes.SOCKS_ADDRESS_IP6;
import static org.reaktivity.nukleus.socks.internal.stream.types.SocksProtocolTypes.AUTH_METHOD_NONE;
import static org.reaktivity.nukleus.socks.internal.stream.types.SocksProtocolTypes.AUTH_NO_ACCEPTABLE_METHODS;
import static org.reaktivity.nukleus.socks.internal.stream.types.SocksProtocolTypes.COMMAND_CONNECT;
import static org.reaktivity.nukleus.socks.internal.stream.types.SocksProtocolTypes.REPLY_COMMAND_NOT_SUPPORTED;
import static org.reaktivity.nukleus.socks.internal.stream.types.SocksProtocolTypes.REPLY_SUCCEEDED;
import static org.reaktivity.nukleus.socks.internal.stream.types.SocksProtocolTypes.SOCKS_VERSION_5;

import java.util.Arrays;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.function.MessagePredicate;
import org.reaktivity.nukleus.socks.internal.metadata.Signal;
import org.reaktivity.nukleus.socks.internal.metadata.State;
import org.reaktivity.nukleus.socks.internal.stream.AbstractStream;
import org.reaktivity.nukleus.socks.internal.stream.AcceptTransitionListener;
import org.reaktivity.nukleus.socks.internal.stream.Context;
import org.reaktivity.nukleus.socks.internal.stream.Correlation;
import org.reaktivity.nukleus.socks.internal.stream.types.FragmentedFlyweight;
import org.reaktivity.nukleus.socks.internal.stream.types.SocksCommandRequestFW;
import org.reaktivity.nukleus.socks.internal.stream.types.SocksCommandResponseFW;
import org.reaktivity.nukleus.socks.internal.stream.types.SocksNegotiationRequestFW;
import org.reaktivity.nukleus.socks.internal.stream.types.SocksNegotiationResponseFW;
import org.reaktivity.nukleus.socks.internal.types.Flyweight;
import org.reaktivity.nukleus.socks.internal.types.OctetsFW;
import org.reaktivity.nukleus.socks.internal.types.StringFW;
import org.reaktivity.nukleus.socks.internal.types.control.RouteFW;
import org.reaktivity.nukleus.socks.internal.types.control.SocksRouteExFW;
import org.reaktivity.nukleus.socks.internal.types.stream.AbortFW;
import org.reaktivity.nukleus.socks.internal.types.stream.BeginFW;
import org.reaktivity.nukleus.socks.internal.types.stream.DataFW;
import org.reaktivity.nukleus.socks.internal.types.stream.EndFW;
import org.reaktivity.nukleus.socks.internal.types.stream.ResetFW;
import org.reaktivity.nukleus.socks.internal.types.stream.WindowFW;

public final class AcceptStream extends AbstractStream implements AcceptTransitionListener
{
    private final int socksInitialWindow;

    private MessageConsumer streamState;
    private MessageConsumer acceptReplyThrottleState;
    private MessageConsumer connectThrottleState;

    private int acceptReplyCredit;
    private int acceptReplyPadding;

    private int connectCredit;
    private int connectPadding;

    private int receivedAcceptBytes;

    private byte socksAtyp;
    private byte[] socksAddr;
    private int socksPort;

    final Correlation correlation;

    public AcceptStream(
        Context context,
        MessageConsumer acceptThrottle,
        long acceptStreamId,
        long acceptSourceRef,
        String acceptSourceName,
        long acceptCorrelationId)
    {
        super(context);
        this.socksInitialWindow = context.socksConfiguration.socksInitialWindow();
        final long acceptReplyStreamId = context.supplyStreamId.getAsLong();
        this.streamState = this::beforeBegin;
        this.acceptReplyThrottleState = this::handleAcceptReplyThrottleBeforeHandshake;
        this.connectThrottleState = this::handleConnectThrottleBeforeHandshake;
        context.router.setThrottle(
            acceptSourceName,
            acceptReplyStreamId,
            this::handleAcceptReplyThrottle);
        MessageConsumer acceptReplyEndpoint = context.router.supplyTarget(acceptSourceName);
        correlation = new Correlation(
            acceptThrottle,
            acceptStreamId,
            acceptSourceRef,
            acceptSourceName,
            acceptReplyStreamId,
            acceptCorrelationId,
            this
        );
        correlation.acceptReplyEndpoint(acceptReplyEndpoint);
        correlation.connectStreamId(context.supplyStreamId.getAsLong());
        correlation.connectCorrelationId(context.supplyCorrelationId.getAsLong());
        correlation.nextAcceptSignal(this::noop);
    }

    @Override
    protected void handleStream(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        streamState.accept(msgTypeId, buffer, index, length);
    }

    protected void handleAcceptReplyThrottle(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        acceptReplyThrottleState.accept(msgTypeId, buffer, index, length);
    }

    protected void handleConnectThrottle(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        connectThrottleState.accept(msgTypeId, buffer, index, length);
    }

    @State
    private void beforeBegin(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        if (msgTypeId == BeginFW.TYPE_ID)
        {
            initializeNegotiation();
        }
        else
        {
            doReset(correlation.acceptThrottle(), correlation.acceptStreamId());
        }
    }

    private void initializeNegotiation()
    {
        doBegin(
            correlation.acceptReplyEndpoint(),
            correlation.acceptReplyStreamId(),
            0L,
            correlation.acceptCorrelationId());
        doWindow(
            correlation.acceptThrottle(),
            correlation.acceptStreamId(),
            socksInitialWindow,
            0);
        this.streamState = this::afterNegotiationInitialized;
    }

    @State
    private void afterNegotiationInitialized(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        switch (msgTypeId)
        {
        case DataFW.TYPE_ID:
            final DataFW data = context.dataRO.wrap(buffer, index, index + length);
            receivedAcceptBytes += data.payload().sizeof();
            handleFragmentedData(
                data,
                context.socksNegotiationRequestRO,
                this::handleNegotiationFlyweight,
                correlation.acceptThrottle(),
                correlation.acceptStreamId());
             doWindow(correlation.acceptThrottle(), correlation.acceptStreamId(), data.payload().sizeof(), 0);
            break;
        case EndFW.TYPE_ID:
        case AbortFW.TYPE_ID:
            doAbort(correlation.acceptReplyEndpoint(), correlation.acceptReplyStreamId());
            break;
        default:
            doReset(correlation.acceptThrottle(), correlation.acceptStreamId());
            break;
        }
    }

    private void handleNegotiationFlyweight(
        FragmentedFlyweight<SocksNegotiationRequestFW> flyweight,
        DirectBuffer buffer,
        int offset,
        int limit)
    {
        final SocksNegotiationRequestFW socksNegotiation = flyweight.wrap(buffer, offset, limit);
        if (socksNegotiation.version() != SOCKS_VERSION_5)
        {
            doReset(correlation.acceptThrottle(), correlation.acceptStreamId());
        }
        int nmethods = socksNegotiation.nmethods();
        byte i = 0;
        for (; i < nmethods; i++)
        {
            if (socksNegotiation.methods()[0] == AUTH_METHOD_NONE)
            {
                break;
            }
        }
        if (i == nmethods)
        {
            correlation.nextAcceptSignal(this::attemptFailedNegotiationResponse);
            correlation.nextAcceptSignal().accept(true);
        }
        else
        {
            correlation.nextAcceptSignal(this::attemptNegotiationResponse);
            correlation.nextAcceptSignal().accept(true);
        }
    }

    private void updatePartial(int sentBytesWithPadding)
    {
        acceptReplyCredit -= sentBytesWithPadding;
    }

    private void updateNegotiationResponseComplete(int sentBytesWithPadding)
    {
        this.streamState = this::afterNegotiation;
        correlation.nextAcceptSignal(this::noop);
        acceptReplyCredit -= sentBytesWithPadding;
    }

    @Signal
    private void attemptNegotiationResponse(
        boolean isReadyState)
    {
        SocksNegotiationResponseFW socksNegotiationResponseFW = context.socksNegotiationResponseRW
            .wrap(context.writeBuffer, DataFW.FIELD_OFFSET_PAYLOAD, context.writeBuffer.capacity())
            .version(SOCKS_VERSION_5)
            .method(AUTH_METHOD_NONE)
            .build();
        doFragmentedData(socksNegotiationResponseFW,
            acceptReplyCredit,
            acceptReplyPadding,
            correlation.acceptReplyEndpoint(),
            correlation.acceptReplyStreamId(),
            this::updatePartial,
            this::updateNegotiationResponseComplete);
    }

    private void updateNegotiationFailedResponseComplete(int sentBytesWithPadding)
    {
        this.streamState = this::afterFailedHandshake;
        correlation.nextAcceptSignal(this::noop);
        acceptReplyCredit -= sentBytesWithPadding;
    }

    @Signal
    private void attemptFailedNegotiationResponse(
        boolean isReadyState)
    {
        SocksNegotiationResponseFW socksNegotiationResponseFW = context.socksNegotiationResponseRW
            .wrap(context.writeBuffer, DataFW.FIELD_OFFSET_PAYLOAD, context.writeBuffer.capacity())
            .version(SOCKS_VERSION_5)
            .method(AUTH_NO_ACCEPTABLE_METHODS)
            .build();
        doFragmentedData(socksNegotiationResponseFW,
            acceptReplyCredit,
            acceptReplyPadding,
            correlation.acceptReplyEndpoint(),
            correlation.acceptReplyStreamId(),
            this::updatePartial,
            this::updateNegotiationFailedResponseComplete);
    }

    @State
    private void afterFailedHandshake(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        // Only EndFrame should come back
    }

    @Signal
    private void noop(
        boolean isReadyState)
    {
    }

    @State
    private void afterNegotiation(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        switch (msgTypeId)
        {
        case DataFW.TYPE_ID:
            final DataFW data = context.dataRO.wrap(buffer, index, index + length);
            receivedAcceptBytes += data.payload().sizeof();
            handleFragmentedData(
                data,
                context.socksConnectionRequestRO,
                this::handleConnectRequestFlyweight,
                correlation.acceptThrottle(),
                correlation.acceptStreamId());
             doWindow(correlation.acceptThrottle(), correlation.acceptStreamId(), data.payload().sizeof(), 0);
            break;
        case EndFW.TYPE_ID:
        case AbortFW.TYPE_ID:
            doAbort(correlation.acceptReplyEndpoint(), correlation.acceptReplyStreamId());
            break;
        default:
            doReset(correlation.acceptThrottle(), correlation.acceptStreamId());
            break;
        }
    }

    private void handleConnectRequestFlyweight(
        FragmentedFlyweight<SocksCommandRequestFW> flyweight,
        DirectBuffer buffer,
        int offset,
        int limit)
    {
        final SocksCommandRequestFW socksCommandRequestFW = flyweight.wrap(buffer, offset, limit);
        if (socksCommandRequestFW.version() != SOCKS_VERSION_5)
        {
            doReset(correlation.acceptThrottle(), correlation.acceptStreamId());
        }
        if (socksCommandRequestFW.command() != COMMAND_CONNECT)
        {
            correlation.nextAcceptSignal(this::attemptFailedConnectionResponse);
            correlation.nextAcceptSignal().accept(true);
        }
        else
        {
            final RouteFW connectRoute = resolveTarget(
                correlation.acceptSourceRef(),
                correlation.acceptSourceName(),
                socksCommandRequestFW);
            if (connectRoute == null)
            {
                doReset(correlation.acceptThrottle(), correlation.acceptStreamId());
            }
            final String connectTargetName = connectRoute.target().asString();
            final MessageConsumer connectEndpoint = context.router.supplyTarget(connectTargetName);
            final long connectTargetRef = connectRoute.targetRef();
            final long connectStreamId = context.supplyStreamId.getAsLong();
            final long connectCorrelationId = context.supplyCorrelationId.getAsLong();
            correlation.connectRoute(connectRoute);
            correlation.connectEndpoint(connectEndpoint);
            correlation.connectTargetRef(connectTargetRef);
            correlation.connectTargetName(connectTargetName);
            correlation.connectStreamId(connectStreamId);
            correlation.connectCorrelationId(connectCorrelationId);
            context.correlations.put(connectCorrelationId, correlation); // Use this map on the CONNECT STREAM
            context.router.setThrottle(
                connectTargetName,
                connectStreamId,
                this::handleConnectThrottle);
            doBegin(
                connectEndpoint,
                connectStreamId,
                connectTargetRef,
                connectCorrelationId);
            correlation.nextAcceptSignal(this::noop);
            this.streamState = this::afterTargetConnectBegin;
        }
    }

    @State
    private void afterTargetConnectBegin(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        doReset(correlation.acceptThrottle(), correlation.acceptStreamId());
    }

    @Override
    public void transitionToConnectionReady()
    {
        socksPort = 0;
        socksAtyp = (byte) SOCKS_ADDRESS_IP4;
        socksAddr = new byte[4];
        Arrays.fill(socksAddr, (byte) 0x00);
        attemptConnectionResponse(false);
    }

    @Signal
    private void attemptFailedConnectionResponse(
        boolean isReadyState)
    {
        if (!isReadyState)
        {
            correlation.nextAcceptSignal(this::attemptFailedConnectionResponse);
        }
        SocksCommandResponseFW socksConnectResponseFW = context.socksConnectionResponseRW
            .wrap(context.writeBuffer, DataFW.FIELD_OFFSET_PAYLOAD, context.writeBuffer.capacity())
            .version(SOCKS_VERSION_5)
            .reply(REPLY_COMMAND_NOT_SUPPORTED)
            .bind((byte) 0x01, new byte[]{0x00, 0x00, 0x00, 0x00}, 0x00)
            .build();
        doFragmentedData(socksConnectResponseFW,
            acceptReplyCredit,
            acceptReplyPadding,
            correlation.acceptReplyEndpoint(),
            correlation.acceptReplyStreamId(),
            this::updatePartial,
            this::updateConnectionFailedResponseComplete);
    }

    private void updateConnectionFailedResponseComplete(int sentBytesWithPadding)
    {
        acceptReplyCredit -= sentBytesWithPadding;
        this.streamState = this::afterFailedHandshake;
        correlation.nextAcceptSignal(this::noop);
    }

    private void updateConnectionResponseComplete(int sentBytesWithPadding)
    {
        correlation.nextAcceptSignal(this::noop);
        acceptReplyCredit -= sentBytesWithPadding;

        // Optimistic case, the frames can be forwarded back and forth
        this.streamState = this::afterSourceConnect;
        this.acceptReplyThrottleState = this::handleAcceptReplyThrottleAfterHandshake;
        this.connectThrottleState = this::handleConnectThrottleAfterHandshake;
        doWindow(
            correlation.connectReplyThrottle(),
            correlation.connectReplyStreamId(),
            acceptReplyCredit,
            acceptReplyPadding);
        if (isConnectWindowGreaterThanAcceptWindow())
        {
            doWindow(
                correlation.acceptThrottle(),
                correlation.acceptStreamId(),
//                connectCredit - (socksInitialWindow - receivedAcceptBytes),
                connectCredit - socksInitialWindow,
                connectPadding);
        }
        else
        {
            this.connectThrottleState = this::handleConnectThrottleBufferUnwind;
            this.streamState = this::afterSourceConnectBufferUnwind;
        }
    }

    @Signal
    private void attemptConnectionResponse(
        boolean isReadyState)
    {
        if (!isReadyState)
        {
            correlation.nextAcceptSignal(this::attemptConnectionResponse);
        }
        SocksCommandResponseFW socksConnectResponseFW = context.socksConnectionResponseRW
            .wrap(context.writeBuffer, DataFW.FIELD_OFFSET_PAYLOAD, context.writeBuffer.capacity())
            .version(SOCKS_VERSION_5)
            .reply(REPLY_SUCCEEDED)
            .bind(socksAtyp, socksAddr, socksPort)
            .build();
        doFragmentedData(socksConnectResponseFW,
            acceptReplyCredit,
            acceptReplyPadding,
            correlation.acceptReplyEndpoint(),
            correlation.acceptReplyStreamId(),
            this::updatePartial,
            this::updateConnectionResponseComplete);
    }

    @State
    private void afterSourceConnectBufferUnwind(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        switch (msgTypeId)
        {
        case DataFW.TYPE_ID:
            final DataFW data = context.dataRO.wrap(buffer, index, index + length);
            receivedAcceptBytes += data.payload().sizeof();
            handleHighLevelDataBufferUnwind(
                data,
                getCurrentTargetCredit(),
                correlation.connectStreamId(),
                correlation.acceptThrottle(),
                correlation.acceptStreamId(),
                this::updateSentPartialData,
                this::updateSentCompleteData);
            break;
        case EndFW.TYPE_ID:
        case AbortFW.TYPE_ID:
            doAbort(correlation.acceptReplyEndpoint(), correlation.acceptReplyStreamId());
            break;
        default:
            doReset(correlation.acceptThrottle(), correlation.acceptStreamId());
        }
    }

    private void updateSentPartialData(
        OctetsFW payload,
        int payloadLength)
    {
        doForwardData(
            payload,
            payloadLength,
            correlation.connectStreamId(),
            correlation.connectEndpoint());
        connectCredit = 0;
    }

    private void updateSentCompleteData(OctetsFW payload)
    {
        doForwardData(
            payload,
            correlation.connectStreamId(),
            correlation.connectEndpoint());
        connectCredit -= payload.sizeof() + connectPadding;
        if (connectCredit == 0)
        {
            this.connectThrottleState = this::handleConnectThrottleAfterHandshake;
            this.streamState = this::afterSourceConnect;
        }
    }

    @State
    private void afterSourceConnect(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        switch (msgTypeId)
        {
        case DataFW.TYPE_ID:
            final DataFW data = context.dataRO.wrap(buffer, index, index + length);
            OctetsFW payload = data.payload();
            doForwardData(
                payload,
                correlation.connectStreamId(),
                correlation.connectEndpoint());
            break;
        case EndFW.TYPE_ID:
            doEnd(correlation.acceptReplyEndpoint(), correlation.acceptReplyStreamId());
            break;
        case AbortFW.TYPE_ID:
            doAbort(correlation.acceptReplyEndpoint(), correlation.acceptReplyStreamId());
            break;
        default:
            doReset(correlation.acceptThrottle(), correlation.acceptStreamId());
        }
    }

    private void handleAcceptReplyThrottleBeforeHandshake(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        switch (msgTypeId)
        {
        case WindowFW.TYPE_ID:
            final WindowFW window = context.windowRO.wrap(buffer, index, index + length);
            acceptReplyCredit += window.credit();
            acceptReplyPadding = window.padding();
            correlation.nextAcceptSignal().accept(true);
            break;
        case ResetFW.TYPE_ID:
            doReset(correlation.acceptThrottle(), correlation.acceptStreamId());
            break;
        default:
            // ignore
            break;
        }
    }

    private void handleAcceptReplyThrottleAfterHandshake(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        switch (msgTypeId)
        {
        case WindowFW.TYPE_ID:
            final WindowFW window = context.windowRO.wrap(buffer, index, index + length);
            doWindow(
                correlation.connectReplyThrottle(),
                correlation.connectReplyStreamId(),
                window.credit(),
                window.padding());
            break;
        case ResetFW.TYPE_ID:
            doReset(correlation.acceptThrottle(), correlation.acceptStreamId());
            doAbort(correlation.connectEndpoint(), correlation.connectStreamId());
            break;
        default:
            // ignore
            break;
        }
    }

    private void handleConnectThrottleBeforeHandshake(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        switch (msgTypeId)
        {
        case WindowFW.TYPE_ID:
            final WindowFW window = context.windowRO.wrap(buffer, index, index + length);
            connectCredit += window.credit();
            connectPadding = window.padding();
            break;
        case ResetFW.TYPE_ID:
            doReset(correlation.acceptThrottle(), correlation.acceptStreamId());
            break;
        default:
            // ignore
            break;
        }
    }

    private void handleConnectThrottleBufferUnwind(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        switch (msgTypeId)
        {
        case WindowFW.TYPE_ID:
            final WindowFW window = context.windowRO.wrap(buffer, index, index + length);
            connectCredit += window.credit();
            connectPadding = window.padding();
            handleThrottlingAndDataBufferUnwind(
                getCurrentTargetCredit(),
                this::updateSendDataFromBuffer,
                this::updateThrottlingWithEmptyBuffer);
            break;
        case ResetFW.TYPE_ID:
            doReset(correlation.acceptThrottle(), correlation.acceptStreamId());
            doAbort(correlation.acceptReplyEndpoint(), correlation.acceptReplyStreamId());
            doReset(correlation.connectReplyThrottle(), correlation.connectReplyStreamId());
            break;
        default:
            // ignore
            break;
        }
    }

    private void updateThrottlingWithEmptyBuffer(boolean shouldForwardSourceWindow)
    {
        if (isConnectWindowGreaterThanAcceptWindow())
        {
            this.connectThrottleState = this::handleConnectThrottleAfterHandshake;
            this.streamState = this::afterSourceConnect;
            if (shouldForwardSourceWindow)
            {
                doWindow(
                    correlation.acceptThrottle(),
                    correlation.acceptStreamId(),
                    receivedAcceptBytes,
                    connectPadding);
            }
        }
    }

    private void updateSendDataFromBuffer(
        MutableDirectBuffer acceptBuffer,
        int payloadSize)
    {
        doForwardData(
            acceptBuffer,
            0,
            payloadSize,
            correlation.connectStreamId(),
            correlation.connectEndpoint());
        connectCredit -= payloadSize + connectPadding;
    }

    private void handleConnectThrottleAfterHandshake(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        switch (msgTypeId)
        {
        case WindowFW.TYPE_ID:
            final WindowFW window = context.windowRO.wrap(buffer, index, index + length);

            doWindow(
                correlation.acceptThrottle(),
                correlation.acceptStreamId(),
                window.credit(),
                window.padding());
            break;
        case ResetFW.TYPE_ID:
            doReset(correlation.acceptThrottle(), correlation.acceptStreamId());
            doAbort(correlation.acceptReplyEndpoint(), correlation.acceptReplyStreamId());
            doReset(correlation.connectReplyThrottle(), correlation.connectReplyStreamId());
            break;
        default:
            // ignore
            break;
        }
    }

    RouteFW resolveTarget(
        long sourceRef,
        String sourceName,
        SocksCommandRequestFW socksCommandRequestFW)
    {
        MessagePredicate filter = (t, b, o, l) ->
        {
            RouteFW route = context.routeRO.wrap(b, o, l);
            final SocksRouteExFW routeEx = route.extension()
                .get(context.routeExRO::wrap);
            return sourceRef == route.sourceRef() &&
                sourceName.equals(route.source().asString()) &&
                routeEx.socksPort() == socksCommandRequestFW.port() &&
                routeEx.socksAddress().kind() == socksCommandRequestFW.atype() &&
                ((
                    socksCommandRequestFW.atype() == SOCKS_ADDRESS_IP4 &&
                    equalFlyweights(socksCommandRequestFW.socksAddress(), routeEx.socksAddress().ipv4Address())
                ) ||
                (
                    socksCommandRequestFW.atype() == SOCKS_ADDRESS_DOMAIN &&
                        ((StringFW)socksCommandRequestFW.socksAddress()).asString()
                            .equalsIgnoreCase(routeEx.socksAddress().domainName().asString())
                ) ||
                (
                    socksCommandRequestFW.atype() == SOCKS_ADDRESS_IP6 &&
                    equalFlyweights(socksCommandRequestFW.socksAddress(), routeEx.socksAddress().ipv6Address())
                ));
        };
        return context.router.resolve(0L, filter, this::wrapRoute);
    }

    private static boolean equalFlyweights(Flyweight f1, Flyweight f2)
    {
        if (f1 == null && f2 == null)
        {
            return true;
        }
        if (f1 == null || f2 == null)
        {
            return false;
        }
        if (f1.sizeof() != f2.sizeof())
        {
            return false;
        }
        for (int i = 0; i < f1.sizeof(); i++)
        {
            if (f1.buffer().getByte(f1.offset() + i) != f2.buffer().getByte(f2.offset() + i))
            {
                return false;
            }
        }
        return true;
    }

    private RouteFW wrapRoute(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        return context.routeRO.wrap(buffer, index, index + length);
    }

    public boolean isConnectWindowGreaterThanAcceptWindow()
    {
        System.out.println(format(
            "connectCredit: %d, connectPadding: %d, socksInitialWindow: %d, receivedAcceptBytes: %d",
            connectCredit, connectPadding, socksInitialWindow, receivedAcceptBytes));
        return connectCredit - connectPadding > socksInitialWindow - receivedAcceptBytes;
//        return connectCredit - connectPadding > socksInitialWindow;
    }

    private int getCurrentTargetCredit()
    {
        return connectCredit - connectPadding;
    }
}
