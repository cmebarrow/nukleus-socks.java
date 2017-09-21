/*
 *
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
package org.reaktivity.nukleus.socks.internal.stream.types;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteOrder;

import org.agrona.BitUtil;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.reaktivity.nukleus.socks.internal.types.Flyweight;
import org.reaktivity.nukleus.socks.internal.types.StringFW;

public class SocksCommandResponseFW extends Flyweight implements Fragmented
{

    /*
     * From https://tools.ietf.org/html/rfc1928
        6.  Replies

           The SOCKS request information is sent by the client as soon as it has
           established a connection to the SOCKS server, and completed the
           authentication negotiations.  The server evaluates the request, and
           returns a reply formed as follows:

                +----+-----+-------+------+----------+----------+
                |VER | REP |  RSV  | ATYP | BND.ADDR | BND.PORT |
                +----+-----+-------+------+----------+----------+
                | 1  |  1  | X'00' |  1   | Variable |    2     |
                +----+-----+-------+------+----------+----------+

             Where:

                  o  VER    protocol version: X'05'
                  o  REP    Reply field:
                     o  X'00' succeeded
                     o  X'01' general SOCKS server failure
                     o  X'02' connection not allowed by ruleset
                     o  X'03' Network unreachable
                     o  X'04' Host unreachable
                     o  X'05' Connection refused
                     o  X'06' TTL expired
                     o  X'07' Command not supported
                     o  X'08' Address type not supported
                     o  X'09' to X'FF' unassigned
                  o  RSV    RESERVED
                  o  ATYP   address type of following address
                     o  IP V4 address: X'01'
                     o  DOMAINNAME: X'03'
                     o  IP V6 address: X'04'
                  o  BND.ADDR       server bound address
                  o  BND.PORT       server bound port in network octet order

           Fields marked RESERVED (RSV) must be set to X'00'.

           If the chosen method includes encapsulation for purposes of
           authentication, integrity and/or confidentiality, the replies are
           encapsulated in the method-dependent encapsulation.
     *
     */
    private static final int FIELD_OFFSET_VERSION = 0;
    private static final int FIELD_SIZEBY_VERSION = BitUtil.SIZE_OF_BYTE;

    private static final int FIELD_OFFSET_REPLY = FIELD_OFFSET_VERSION + FIELD_SIZEBY_VERSION;
    private static final int FIELD_SIZEBY_REPLY = BitUtil.SIZE_OF_BYTE;

    private static final int FIELD_OFFSET_RSV = FIELD_OFFSET_REPLY + FIELD_SIZEBY_REPLY;
    private static final int FIELD_SIZEBY_RSV = BitUtil.SIZE_OF_BYTE;

    private static final int FIELD_OFFSET_ADDRTYP = FIELD_OFFSET_RSV + FIELD_SIZEBY_RSV;
    private static final int FIELD_SIZEBY_ADDRTYP = BitUtil.SIZE_OF_BYTE;

    // BND.ADDR offset is computed for each request

    private static final int FIELD_SIZEBY_BNDPORT = BitUtil.SIZE_OF_SHORT; // Offset 0, relative to end of ADDRBND field

    // Temporary data used for decoding
    private final StringFW domainFW = new StringFW();
    private byte[] ipv4 = new byte[4];
    private byte[] ipv6 = new byte[16];

    @Override
    public int limit()
    {
        return decodeLimit(buffer(), offset());
    }

    @Override
    public boolean canWrap(
        DirectBuffer buffer,
        int offset,
        int maxLimit)
    {
        final int maxLength = maxLimit - offset;
        if (maxLength < FIELD_OFFSET_ADDRTYP + FIELD_SIZEBY_ADDRTYP)
        {
            return false;
        }

        return decodeLimit(buffer, offset) <= maxLimit;
    }

    private int decodeLimit(
        DirectBuffer buffer,
        int offset)
    {
        final int addrTypOffset = offset + FIELD_OFFSET_ADDRTYP;
        final byte addrLength;
        byte addrVariableSize = 0;
        switch (buffer.getByte(addrTypOffset))
        {
        case 0x01:
            addrLength = 4;
            break;
        case 0x03:
            addrLength = buffer.getByte(addrTypOffset + FIELD_SIZEBY_ADDRTYP);
            addrVariableSize = 1;
            break;
        case 0x04:
            addrLength = 16;
            break;
        default:
            throw new IllegalStateException("Unable to decode Socks bind address type");

        }

        return addrTypOffset + FIELD_SIZEBY_ADDRTYP + addrVariableSize + addrLength + FIELD_SIZEBY_BNDPORT;
    }

    @Override
    public SocksCommandResponseFW wrap(
        DirectBuffer buffer,
        int offset,
        int maxLimit)
    {
        super.wrap(buffer, offset, maxLimit);
        checkLimit(limit(), maxLimit);
        return this;
    }

    public byte version()
    {
        return (buffer().getByte(offset() + FIELD_OFFSET_VERSION));
    }

    public byte command()
    {
        return (buffer().getByte(offset() + FIELD_OFFSET_REPLY));
    }

    public byte atype()
    {
        return (buffer().getByte(offset() + FIELD_OFFSET_ADDRTYP));
    }

    public String domain()
    {
        if (atype() == 0x03)
        {
            return domainFW.wrap(buffer(), offset() + FIELD_OFFSET_ADDRTYP + FIELD_SIZEBY_ADDRTYP, maxLimit())
                .asString();
        }
        return null;
    }

    public InetAddress ip() throws UnknownHostException
    {
        if (atype() == 0x01)
        {
            buffer().getBytes(offset() + FIELD_OFFSET_ADDRTYP + FIELD_SIZEBY_ADDRTYP, ipv4);
            return Inet4Address.getByAddress(ipv4);
        }
        else if (atype() == 0x04)
        {
            buffer().getBytes(offset() + FIELD_OFFSET_ADDRTYP + FIELD_SIZEBY_ADDRTYP, ipv6);
            return Inet6Address.getByAddress(ipv6);
        }
        return null;
    }

    public int port()
    {
        return buffer().getShort(decodeLimit(buffer(), offset()) - FIELD_SIZEBY_BNDPORT, ByteOrder.BIG_ENDIAN);
    }

    public static final class Builder extends Flyweight.Builder<SocksCommandResponseFW>
    {
        public Builder()
        {
            super(new SocksCommandResponseFW());
        }

        @Override
        public Builder wrap(
            MutableDirectBuffer buffer,
            int offset,
            int maxLimit)
        {
            super.wrap(buffer, offset, maxLimit);
            buffer().putByte(offset() + FIELD_OFFSET_RSV, (byte) 0x00);
            return this;
        }

        public Builder version(byte version)
        {
            buffer().putByte(offset() + FIELD_OFFSET_VERSION, version);
            return this;
        }

        public Builder reply(byte reply)
        {
            buffer().putByte(offset() + FIELD_OFFSET_REPLY, reply);
            return this;
        }

        public Builder bind(
            byte atyp,
            byte[] addr,
            int port)
        {
            buffer().putByte(offset() + FIELD_OFFSET_ADDRTYP, atyp);

            int addrOffset = offset() + FIELD_OFFSET_ADDRTYP + FIELD_SIZEBY_ADDRTYP;
            if (atyp == 0x03)
            {
                buffer().putByte(addrOffset++, (byte) addr.length);
            }
            buffer().putBytes(addrOffset, addr);
            buffer().putByte(addrOffset + addr.length, (byte) ((port >> 8) & 0xFF));
            buffer().putByte(addrOffset + addr.length + 1, (byte) (port & 0xFF));
            return this;
        }
    }
}
