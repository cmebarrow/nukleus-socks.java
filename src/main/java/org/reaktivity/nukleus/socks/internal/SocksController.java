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
package org.reaktivity.nukleus.socks.internal;

import static java.nio.ByteBuffer.allocateDirect;
import static java.nio.ByteOrder.nativeOrder;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CompletableFuture;

import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.reaktivity.nukleus.Controller;
import org.reaktivity.nukleus.ControllerSpi;
import org.reaktivity.nukleus.socks.internal.types.Flyweight;
import org.reaktivity.nukleus.socks.internal.types.control.Role;
import org.reaktivity.nukleus.socks.internal.types.control.RouteFW;
import org.reaktivity.nukleus.socks.internal.types.control.SocksRouteExFW;
import org.reaktivity.nukleus.socks.internal.types.control.UnrouteFW;

public final class SocksController implements Controller
{

    private static final int MAX_SEND_LENGTH = 1024; // TODO: Configuration and Context

    // TODO: thread-safe flyweights or command queue from public methods
    private final RouteFW.Builder routeRW = new RouteFW.Builder();
    private final UnrouteFW.Builder unrouteRW = new UnrouteFW.Builder();

    private final SocksRouteExFW.Builder routeExRW = new SocksRouteExFW.Builder();

    private final ControllerSpi controllerSpi;
    private final MutableDirectBuffer writeBuffer;

    public SocksController(
        ControllerSpi controllerSpi)
    {
        this.controllerSpi = controllerSpi;
        this.writeBuffer = new UnsafeBuffer(allocateDirect(MAX_SEND_LENGTH).order(nativeOrder()));
    }

    @Override
    public int process()
    {
        return controllerSpi.doProcess();
    }

    @Override
    public void close() throws Exception
    {
        controllerSpi.doClose();
    }

    @Override
    public Class<SocksController> kind()
    {
        return SocksController.class;
    }

    @Override
    public String name()
    {
        return "socks";
    }

    public CompletableFuture<Long> routeServer(
        String source,
        long sourceRef,
        String target,
        long targetRef,
        String dstAddrPort)
    {
        long correlationId = controllerSpi.nextCorrelationId();
        RouteFW route = routeRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .correlationId(correlationId)
            .role(b -> b.set(Role.SERVER))
            .source(source)
            .sourceRef(sourceRef)
            .target(target)
            .targetRef(targetRef)
            .extension(b -> b.set(visitRouteEx(dstAddrPort)))
            .build();
        return controllerSpi.doRoute(
            route.typeId(),
            route.buffer(),
            route.offset(),
            route.sizeof()
        );
    }

    public CompletableFuture<Long> routeClient(
        String source,
        long sourceRef,
        String target,
        long targetRef,
        String dstAddrPort
        )
    {
        long correlationId = controllerSpi.nextCorrelationId();
        RouteFW route = routeRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .correlationId(correlationId)
            .role(b -> b.set(Role.CLIENT))
            .source(source)
            .sourceRef(sourceRef)
            .target(target)
            .targetRef(targetRef)
            .extension(b -> b.set(visitRouteEx(dstAddrPort)))
            .build();
        return controllerSpi.doRoute(
            route.typeId(),
            route.buffer(),
            route.offset(),
            route.sizeof()
        );
    }

    public CompletableFuture<Void> unrouteServer(
        String source,
        long sourceRef,
        String target,
        long targetRef,
        String dstAddrPort
        )
    {
        long correlationId = controllerSpi.nextCorrelationId();
        UnrouteFW unroute = unrouteRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .correlationId(correlationId)
            .role(b -> b.set(Role.SERVER))
            .source(source)
            .sourceRef(sourceRef)
            .target(target)
            .targetRef(targetRef)
            .extension(b -> b.set(visitRouteEx(dstAddrPort)))
            .build();
        return controllerSpi.doUnroute(
            unroute.typeId(),
            unroute.buffer(),
            unroute.offset(),
            unroute.sizeof()
        );
    }

    public CompletableFuture<Void> unrouteClient(
        String source,
        long sourceRef,
        String target,
        long targetRef,
        String dstAddrPort)
    {
        long correlationId = controllerSpi.nextCorrelationId();
        UnrouteFW unroute = unrouteRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .correlationId(correlationId)
            .role(b -> b.set(Role.CLIENT))
            .source(source)
            .sourceRef(sourceRef)
            .target(target)
            .targetRef(targetRef)
            .extension(b -> b.set(visitRouteEx(dstAddrPort)))
            .build();
        return controllerSpi.doUnroute(
            unroute.typeId(),
            unroute.buffer(),
            unroute.offset(),
            unroute.sizeof());
    }

    private Flyweight.Builder.Visitor visitRouteEx(
        String dstAddrPort)
    {
        final String[] tokens = dstAddrPort.split(":");
        final int port = Integer.parseInt(tokens[1]);
        int tmpKind = -1;
        byte[] a = null;
        // Remove digits, ., : and [ ], and check if there is anything remaining
        if (tokens[0].replaceAll("[0-9\\.\\]\\[:]", "").length() == 0)
        {
            try
            {
                InetAddress inetAddress = InetAddress.getByName(tokens[0]);
                if (inetAddress instanceof Inet4Address)
                {
                    tmpKind = 1;
                }
                else if (inetAddress instanceof Inet6Address)
                {
                    tmpKind = 4;
                }
                a = inetAddress.getAddress();
            }
            catch (UnknownHostException e)
            {
            }
        }
        else
        {
            tmpKind = 3;
        }
        final int kind = tmpKind;
        final byte[] address = a;
        return (MutableDirectBuffer buffer, int offset, int limit) ->
            routeExRW.wrap(buffer, offset, limit)
                .socksAddress(builder ->
                {
                    System.out.println("kind: " + kind);
                    System.out.println("builder.maxLimit(): " + builder.maxLimit());
                    System.out.println("builder.limit(): " + builder.limit());
                    if (kind == 1)
                    {
                        builder.ipv4Address(builder1 -> builder1.set(address));
                    }
                    if (kind == 3)
                    {
                        builder.domainName(tokens[0]);
                    }
                    if (kind == 4)
                    {
                        builder.ipv6Address(builder12 -> builder12.set(address));
                    }
                })
                .socksPort(port)
                .build()
                .sizeof();
    }
}
