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
package org.reaktivity.nukleus.socks.internal.stream.client;

import static org.reaktivity.nukleus.buffer.BufferPool.NO_SLOT;

import java.util.Optional;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.socks.internal.metadata.State;
import org.reaktivity.nukleus.socks.internal.stream.AbstractStreamProcessor;
import org.reaktivity.nukleus.socks.internal.stream.Context;
import org.reaktivity.nukleus.socks.internal.stream.Correlation;
import org.reaktivity.nukleus.socks.internal.stream.types.FragmentedFlyweight;
import org.reaktivity.nukleus.socks.internal.stream.types.SocksCommandResponseFW;
import org.reaktivity.nukleus.socks.internal.stream.types.SocksNegotiationResponseFW;
import org.reaktivity.nukleus.socks.internal.types.OctetsFW;
import org.reaktivity.nukleus.socks.internal.types.stream.AbortFW;
import org.reaktivity.nukleus.socks.internal.types.stream.BeginFW;
import org.reaktivity.nukleus.socks.internal.types.stream.DataFW;
import org.reaktivity.nukleus.socks.internal.types.stream.EndFW;
import org.reaktivity.nukleus.socks.internal.types.stream.ResetFW;
import org.reaktivity.nukleus.socks.internal.types.stream.WindowFW;

final class ConnectReplyStreamProcessor extends AbstractStreamProcessor
{

    public static final int MAX_WRITABLE_BYTES = 65535;
    public static final int MAX_WRITABLE_FRAMES = 65535;

    private MessageConsumer streamState;
    private MessageConsumer acceptReplyThrottleState;

    Correlation correlation;
    private final MessageConsumer connectReplyThrottle;
    private final long connectReplyStreamId;


    private int acceptReplyBytes;
    private int acceptReplyFrames;

    private int receivedConnectReplyBytes;
    private int receivedConnectReplyFrames;

    ConnectReplyStreamProcessor(
        Context context,
        MessageConsumer connectReplyThrottle,
        long connectReplyId)
    {
        super(context);
        this.streamState = this::beforeBegin;
        this.connectReplyThrottle = connectReplyThrottle;
        this.connectReplyStreamId = connectReplyId;
        this.acceptReplyThrottleState = this::handleAcceptReplyThrottleBeforeHandshake;
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

    @State
    private void beforeBegin(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        if (msgTypeId == BeginFW.TYPE_ID)
        {
            final BeginFW begin = context.beginRO.wrap(buffer, index, index + length);
            handleBegin(begin);
        }
        else
        {
            doReset(connectReplyThrottle, connectReplyStreamId);
        }
    }

    @State
    private void beforeNegotiationResponse(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        switch (msgTypeId)
        {
        case DataFW.TYPE_ID:
            final DataFW data = context.dataRO.wrap(buffer, index, index + length);
            receivedConnectReplyBytes += data.payload().sizeof();
            receivedConnectReplyFrames++;
            handleNegotiationResponse(data);
            break;
        case EndFW.TYPE_ID:
            final EndFW end = context.endRO.wrap(buffer, index, index + length);
            handleEnd(end);
            break;
        case AbortFW.TYPE_ID:
            final AbortFW abort = context.abortRO.wrap(buffer, index, index + length);
            handleAbort(abort);
            break;
        default:
            doReset(connectReplyThrottle, connectReplyStreamId);
            break;
        }
    }

    private void handleBegin(BeginFW begin)
    {
        final long connectRef = begin.sourceRef();
        final long correlationId = begin.correlationId();
        correlation = context.correlations.remove(correlationId);
        if (connectRef == 0L && correlation != null)
        {
            correlation.connectReplyThrottle(connectReplyThrottle);
            correlation.connectReplyStreamId(connectReplyStreamId);
            MessageConsumer acceptReplyEndpoint = context.router.supplyTarget(correlation.acceptSourceName());
            correlation.acceptReplyEndpoint(acceptReplyEndpoint);
            context.router.setThrottle(
                correlation.acceptSourceName(),
                correlation.acceptReplyStreamId(),
                this::handleAcceptReplyThrottle);
            System.out.println("CONNECT-REPLY/handleBegin");
            System.out.println("\tcontext: " + context);
            System.out.println("\tcontext.router" + context.router);
            System.out.println("\t" + correlation.acceptSourceName() + " : " + correlation.acceptReplyStreamId());
            doBegin(correlation.acceptReplyEndpoint(),
                correlation.acceptReplyStreamId(),
                0L,
                correlation.acceptCorrelationId());
            streamState = this::beforeNegotiationResponse;
            System.out.println("CONNECT-REPLY/handleBegin");
            doWindow(
                connectReplyThrottle,
                connectReplyStreamId,
                MAX_WRITABLE_BYTES,
                MAX_WRITABLE_FRAMES);
        }
        else
        {
            doReset(connectReplyThrottle, connectReplyStreamId);
        }
    }

    private void handleNegotiationResponse(DataFW data)
    {
        handleFragmentedData(
            data,
            context.socksNegotiationResponseRO,
            this::handleNegotiationFlyweight,
            correlation.acceptThrottle(),
            correlation.acceptStreamId());
    }

    private void handleNegotiationFlyweight(
        FragmentedFlyweight<SocksNegotiationResponseFW> flyweight,
        DirectBuffer buffer,
        int offset,
        int limit)
    {
        final SocksNegotiationResponseFW socksNegotiationResponse = flyweight.wrap(buffer, offset, limit);
        if (socksNegotiationResponse.version() != 0x05 ||
            socksNegotiationResponse.method() != 0x00)
        {
            doReset(connectReplyThrottle, connectReplyStreamId); // TODO diagnostic
        }
        streamState = this::beforeConnectionResponse;
        correlation.nextAcceptSignal().accept(true);
    }

    @State
    private void beforeConnectionResponse(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        switch (msgTypeId)
        {
        case DataFW.TYPE_ID:
            final DataFW data = context.dataRO.wrap(buffer, index, index + length);
            receivedConnectReplyBytes += data.payload().sizeof();
            receivedConnectReplyFrames++;
            handleConnectionResponse(data);
            break;
        case EndFW.TYPE_ID:
            final EndFW end = context.endRO.wrap(buffer, index, index + length);
            handleEnd(end);
            break;
        case AbortFW.TYPE_ID:
            final AbortFW abort = context.abortRO.wrap(buffer, index, index + length);
            handleAbort(abort);
            break;
        default:
            doReset(connectReplyThrottle, connectReplyStreamId);
            break;
        }
    }

    private void handleConnectionResponse(DataFW data)
    {
        handleFragmentedData(
            data,
            context.socksConnectionResponseRO,
            this::handleConnectionFlyweight,
            correlation.acceptThrottle(),
            correlation.acceptStreamId());
    }

    private void handleConnectionFlyweight(
        FragmentedFlyweight<SocksCommandResponseFW> flyweight,
        DirectBuffer buffer,
        int offset,
        int limit)
    {
        final SocksCommandResponseFW socksConnectionResponse = flyweight.wrap(buffer, offset, limit);
        if (socksConnectionResponse.version() != 0x05 ||
            socksConnectionResponse.reply() != 0x00)
        {
            doReset(connectReplyThrottle, connectReplyStreamId); // TODO diagnostic
        }
        streamState = this::afterConnectionResponse;
        correlation.acceptTransitionListener().transitionToConnectionReady(Optional.empty());

        if (isAcceptReplyWindowGreaterThanConnectReplyWindow())
        {
            // Forward to the ACCEPT throttle the available CONNECT window
            // Subtract what ACCEPT has already sent during handshake
            System.out.println("CONNECT/attemptConnectionResponse");
            doWindow(
                correlation.connectReplyThrottle(),
                correlation.connectReplyStreamId(),
                acceptReplyBytes - receivedConnectReplyBytes,
                acceptReplyFrames - receivedConnectReplyFrames);
        }
        else
        {
            this.acceptReplyThrottleState = this::handleAcceptReplyThrottleBufferUnwind;
            this.streamState = this::afterConnectionResponseBufferUnwind;
        }
    }

    @State
    private void afterConnectionResponseBufferUnwind(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        switch (msgTypeId)
        {
        case DataFW.TYPE_ID:
            final DataFW data = context.dataRO.wrap(buffer, index, index + length);
            handleHighLevelDataBufferUnwind(data);
            break;
        case EndFW.TYPE_ID:
            final EndFW end = context.endRO.wrap(buffer, index, index + length);
            handleEnd(end);
            break;
        case AbortFW.TYPE_ID:
            final AbortFW abort = context.abortRO.wrap(buffer, index, index + length);
            handleAbort(abort);
            break;
        default:
            doReset(connectReplyThrottle, connectReplyStreamId);
            break;
        }
    }

    private void handleHighLevelDataBufferUnwind(DataFW data)
    {
        OctetsFW payload = data.payload();
        if (this.slotWriteOffset != 0)
        {
            MutableDirectBuffer connectReplyBuffer = context.bufferPool.buffer(this.slotIndex, this.slotWriteOffset);
            connectReplyBuffer.putBytes(
                slotWriteOffset,
                payload.buffer(),
                payload.offset(),
                payload.sizeof());
            slotWriteOffset += payload.sizeof();
            return;
        }
        if (acceptReplyBytes > payload.sizeof() &&
            acceptReplyFrames > 0)
        {
            DataFW dataForwardFW = context.dataRW.wrap(context.writeBuffer, 0, context.writeBuffer.capacity())
                .streamId(correlation.acceptReplyStreamId())
                .payload(p -> p.set(
                    payload.buffer(),
                    payload.offset(),
                    payload.sizeof()))
                .extension(e -> e.reset())
                .build();
            System.out.println("CONNECT/handleHighLevelDataBufferUnwind: Forwarding data frame: \n\t" + data +
                "\n\t" + dataForwardFW);
            correlation.acceptReplyEndpoint()
                .accept(
                    dataForwardFW.typeId(),
                    dataForwardFW.buffer(),
                    dataForwardFW.offset(),
                    dataForwardFW.sizeof());
            acceptReplyBytes -= payload.sizeof();
            acceptReplyFrames--;
            System.out.println("CONNECT/handleHighLevelDataBufferUnwind connectWindowBytes: " +
                acceptReplyBytes + " connectWindowFrames: " + acceptReplyFrames);
            if (acceptReplyBytes == 0)
            {
                System.out.println("CONNECT/handleHighLevelDataBufferUnwind changing state, connect window emptied");
                this.acceptReplyThrottleState = this::handleAcceptReplyThrottleAfterHandshake;
                this.streamState = this::afterConnectionResponse;
            }
        }
        else
        {
            int bufferedSizeBytes = acceptReplyBytes;
            if (acceptReplyFrames > 0)
            {
                DataFW dataForwardFW = context.dataRW.wrap(context.writeBuffer, 0, context.writeBuffer.capacity())
                    .streamId(correlation.acceptReplyStreamId())
                    .payload(p -> p.set(
                        payload.buffer(),
                        payload.offset(),
                        acceptReplyBytes))
                    .extension(e -> e.reset())
                    .build();
                System.out.println("CONNECT/handleHighLevelDataBufferUnwind: Forwarding data frame: \n\t" + data +
                    "\n\t" + dataForwardFW);
                correlation.acceptReplyEndpoint()
                    .accept(
                        dataForwardFW.typeId(),
                        dataForwardFW.buffer(),
                        dataForwardFW.offset(),
                        dataForwardFW.sizeof());
                acceptReplyBytes = 0;
                acceptReplyFrames--;
            }
            else
            {
                bufferedSizeBytes = payload.sizeof();
            }
            if (NO_SLOT == (slotIndex = context.bufferPool.acquire(correlation.connectStreamId())))
            {
                doReset(correlation.connectReplyThrottle(), correlation.connectReplyStreamId());
                return;
            }
            MutableDirectBuffer connectReplyBuffer = context.bufferPool.buffer(this.slotIndex);
            connectReplyBuffer.putBytes(
                0,
                payload.buffer(),
                payload.offset() + bufferedSizeBytes,
                payload.sizeof() - bufferedSizeBytes
                                 );
            slotWriteOffset = payload.sizeof() - bufferedSizeBytes;
            slotReadOffset = 0;
        }
    }


    @State
    private void afterConnectionResponse(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        switch (msgTypeId)
        {
        case DataFW.TYPE_ID:
            final DataFW data = context.dataRO.wrap(buffer, index, index + length);
            receivedConnectReplyBytes += data.payload().sizeof();
            receivedConnectReplyFrames++;
            handleHighLevelData(data);
            break;
        case EndFW.TYPE_ID:
            final EndFW end = context.endRO.wrap(buffer, index, index + length);
            handleEnd(end);
            break;
        case AbortFW.TYPE_ID:
            final AbortFW abort = context.abortRO.wrap(buffer, index, index + length);
            handleAbort(abort);
            break;
        default:
            doReset(connectReplyThrottle, connectReplyStreamId);
            break;
        }
    }

    private void handleHighLevelData(DataFW data)
    {
        System.out.println("CONNECT-REPLY/handleHighLevelData");
        OctetsFW payload = data.payload();
        DataFW dataForwardFW = context.dataRW.wrap(context.writeBuffer, 0, context.writeBuffer.capacity())
            .streamId(correlation.acceptReplyStreamId())
            .payload(p -> p.set(
                payload.buffer(),
                payload.offset(),
                payload.sizeof()))
            .extension(e -> e.reset())
            .build();
        System.out.println("\t forwarding: " + data);
        System.out.println("\t to: " + dataForwardFW);
        correlation.acceptReplyEndpoint()
            .accept(
                dataForwardFW.typeId(),
                dataForwardFW.buffer(),
                dataForwardFW.offset(),
                dataForwardFW.sizeof());
    }

    private void handleEnd(EndFW end)
    {
        EndFW endForwardFW = context.endRW
            .wrap(context.writeBuffer, 0, context.writeBuffer.capacity())
            .streamId(correlation.acceptReplyStreamId())
            .build();
        correlation.acceptReplyEndpoint()
            .accept(
                endForwardFW.typeId(),
                endForwardFW.buffer(),
                endForwardFW.offset(),
                endForwardFW.sizeof());
    }

    private void handleAbort(AbortFW abort)
    {
        doAbort(correlation.acceptReplyEndpoint(), correlation.acceptReplyStreamId());
        correlation.acceptTransitionListener().transitionToAborted();
    }

    protected void handleAcceptReplyThrottle(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        acceptReplyThrottleState.accept(msgTypeId, buffer, index, length);
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
            System.out.println("CONNECT-REPLY/handleAcceptReplyThrottleBeforeHandshake");
            System.out.println("\tCONNECT-REPLY: " + window);
            acceptReplyBytes += window.update();
            acceptReplyFrames += window.frames();
            break;
        case ResetFW.TYPE_ID:
            final ResetFW reset = context.resetRO.wrap(buffer, index, index + length);
            handleReset(reset);
            break;
        default:
            // ignore
            break;
        }
    }

    private void handleAcceptReplyThrottleBufferUnwind(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        switch (msgTypeId)
        {
        case WindowFW.TYPE_ID:
            final WindowFW window = context.windowRO.wrap(buffer, index, index + length);
            acceptReplyBytes += window.update();
            acceptReplyFrames += window.frames();
            System.out.println("CONNECT-REPLY/handleAcceptReplyThrottleBufferUnwind: \n\t" + window);
            System.out.println("\tacceptReplyBytes: " + acceptReplyBytes + " acceptReplyFrames: " + acceptReplyFrames);
            if (acceptReplyFrames == 0)
            {
                return;
            }
            if (this.slotWriteOffset != 0)
            {
                MutableDirectBuffer connectReplyBuffer = context.bufferPool.buffer(this.slotIndex, slotReadOffset);
                final int payloadSize = slotWriteOffset - slotReadOffset;
                if (acceptReplyBytes < payloadSize)
                {
                    System.out.println("\treturning");
                    return; // not covering an empty data frame
                }
                System.out.println("\tComputed payload size: " + payloadSize + "bytes.");
                DataFW dataForwardFW = context.dataRW.wrap(context.writeBuffer, 0, context.writeBuffer.capacity())
                    .streamId(correlation.acceptReplyStreamId())
                    .payload(p -> p.set(
                        connectReplyBuffer,
                        0,
                        payloadSize))
                    .extension(e -> e.reset())
                    .build();
                System.out.println("\tForwarding buffered frame (based on window): \n\t" + window + "\n\t" + dataForwardFW);
                correlation.acceptReplyEndpoint()
                    .accept(
                        dataForwardFW.typeId(),
                        dataForwardFW.buffer(),
                        dataForwardFW.offset(),
                        dataForwardFW.sizeof());
                acceptReplyBytes -= dataForwardFW.payload().sizeof();
                acceptReplyFrames--;
                slotReadOffset += payloadSize;
                if (slotReadOffset == slotWriteOffset)
                {
                    // all buffer could be sent, must switch Throttling states
                    this.acceptReplyThrottleState = this::handleAcceptReplyThrottleAfterHandshake;
                    this.streamState = this::afterConnectionResponse;
                    // Release the buffer
                    context.bufferPool.release(this.slotIndex);
                    this.slotWriteOffset = 0;
                    this.slotReadOffset = 0;
                    this.slotIndex = NO_SLOT;
                }
            }
            else
            {
                System.out.println("\tNO Data frame received from accept yet!");
                if (isAcceptReplyWindowGreaterThanConnectReplyWindow())
                {
                    this.acceptReplyThrottleState = this::handleAcceptReplyThrottleAfterHandshake;
                    this.streamState = this::afterConnectionResponse;
                    System.out.println("CONNECT/handleAcceptReplyThrottleBufferUnwind" +
                        " changing state and restoring acceptThrottle");
                    doWindow(
                        correlation.acceptThrottle(),
                        correlation.acceptStreamId(),
                        receivedConnectReplyBytes,
                        receivedConnectReplyFrames);
                }
            }
            break;
        case ResetFW.TYPE_ID:
            final ResetFW reset = context.resetRO.wrap(buffer, index, index + length);
            doReset(correlation.connectReplyThrottle(), correlation.connectReplyStreamId());
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
            System.out.println("CONNECT-REPLY/handleAcceptReplyThrottleAfterHandshake");
            System.out.println("\tCONNECT-REPLY: " + window);
            doWindow(
                correlation.connectReplyThrottle(),
                correlation.connectReplyStreamId(),
                window.update(),
                window.frames());
            break;
        case ResetFW.TYPE_ID:
            final ResetFW reset = context.resetRO.wrap(buffer, index, index + length);
            handleReset(reset);
            break;
        default:
            // ignore
            break;
        }
    }

    private void handleReset(ResetFW reset)
    {
        doReset(connectReplyThrottle, connectReplyStreamId);
    }

    public boolean isAcceptReplyWindowGreaterThanConnectReplyWindow()
    {
        return
            acceptReplyBytes  >= MAX_WRITABLE_BYTES - receivedConnectReplyBytes &&
            acceptReplyFrames >= MAX_WRITABLE_FRAMES - receivedConnectReplyFrames;
    }

    public boolean isAcceptReplyWindowGreaterThan(
        int update,
        int frames)
    {
        return
            acceptReplyBytes  >= update &&
            acceptReplyFrames >= frames;
    }
}
