/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Protocol Buffers - Google's data interchange format
// Copyright 2008 Google Inc.  All rights reserved.
// https://developers.google.com/protocol-buffers/
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are
// met:
//
//     * Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
//     * Redistributions in binary form must reproduce the above
// copyright notice, this list of conditions and the following disclaimer
// in the documentation and/or other materials provided with the
// distribution.
//     * Neither the name of Google Inc. nor the names of its
// contributors may be used to endorse or promote products derived from
// this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
// THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
package org.apache.cassandra.utils.vint;

import java.io.DataInput;
import java.io.IOException;
import java.nio.ByteBuffer;

import net.nicoulaj.compilecommand.annotations.Inline;
import org.apache.cassandra.io.util.DataInputPlus;
import org.apache.cassandra.io.util.DataOutputPlus;

/**
 * Borrows idea from
 * https://developers.google.com/protocol-buffers/docs/encoding#varints
 */
public class VIntCoding
{
    public static final int MAX_SIZE = 10;

    public static long readUnsignedVInt(DataInput input) throws IOException
    {
        int firstByte = input.readByte();

        //Bail out early if this is one byte, necessary or it fails later
        if (firstByte >= 0)
            return firstByte;

        int size = numberOfExtraBytesToRead(firstByte);
        long retval = firstByte & firstByteValueMask(size);
        for (int ii = 0; ii < size; ii++)
        {
            byte b = input.readByte();
            retval <<= 8;
            retval |= b & 0xff;
        }

        return retval;
    }

    public static void skipUnsignedVInt(DataInputPlus input) throws IOException
    {
        int firstByte = input.readByte();
        if (firstByte < 0)
            input.skipBytesFully(numberOfExtraBytesToRead(firstByte));
    }

    /**
     * Note this method is the same as {@link #readUnsignedVInt(DataInput)},
     * except that we do *not* block if there are not enough bytes in the buffer
     * to reconstruct the value.
     *
     * WARNING: this method is only safe for vints we know to be representable by a positive long value.
     *
     * @return -1 if there are not enough bytes in the input to read the value; else, the vint unsigned value.
     */
    public static long getUnsignedVInt(ByteBuffer input, int readerIndex)
    {
        return getUnsignedVInt(input, readerIndex, input.limit());
    }
    public static long getUnsignedVInt(ByteBuffer input, int readerIndex, int readerLimit)
    {
        if (readerIndex < 0)
            throw new IllegalArgumentException("Reader index should be non-negative, but was " + readerIndex);

        if (readerIndex >= readerLimit)
            return -1;

        int firstByte = input.get(readerIndex++);

        //Bail out early if this is one byte, necessary or it fails later
        if (firstByte >= 0)
            return firstByte;

        int size = numberOfExtraBytesToRead(firstByte);
        if (readerIndex + size > readerLimit)
            return -1;

        long retval = firstByte & firstByteValueMask(size);
        for (int ii = 0; ii < size; ii++)
        {
            byte b = input.get(readerIndex++);
            retval <<= 8;
            retval |= b & 0xff;
        }

        return retval;
    }

    /**
     * Computes size of an unsigned vint that starts at readerIndex of the provided ByteBuf.
     *
     * @return -1 if there are not enough bytes in the input to calculate the size; else, the vint unsigned value size in bytes.
     */
    public static int computeUnsignedVIntSize(ByteBuffer input, int readerIndex)
    {
        return computeUnsignedVIntSize(input, readerIndex, input.limit());
    }
    public static int computeUnsignedVIntSize(ByteBuffer input, int readerIndex, int readerLimit)
    {
        if (readerIndex >= readerLimit)
            return -1;

        int firstByte = input.get(readerIndex);
        return 1 + ((firstByte >= 0) ? 0 : numberOfExtraBytesToRead(firstByte));
    }

    public static long readVInt(DataInput input) throws IOException
    {
        return decodeZigZag64(readUnsignedVInt(input));
    }

    // & this with the first byte to give the value part for a given extraBytesToRead encoded in the byte
    public static int firstByteValueMask(int extraBytesToRead)
    {
        // by including the known 0bit in the mask, we can use this for encodeExtraBytesToRead
        return 0xff >> extraBytesToRead;
    }

    public static int encodeExtraBytesToRead(int extraBytesToRead)
    {
        // because we have an extra bit in the value mask, we just need to invert it
        return ~firstByteValueMask(extraBytesToRead);
    }

    public static int numberOfExtraBytesToRead(int firstByte)
    {
        // we count number of set upper bits; so if we simply invert all of the bits, we're golden
        // this is aided by the fact that we only work with negative numbers, so when upcast to an int all
        // of the new upper bits are also set, so by inverting we set all of them to zero
        return Integer.numberOfLeadingZeros(~firstByte) - 24;
    }

    @Inline
    public static void writeUnsignedVInt(long value, DataOutputPlus output) throws IOException
    {
        int size = VIntCoding.computeUnsignedVIntSize(value);
        if (size == 1)
        {
            output.writeByte((int) value);
        }
        else if (size < 9)
        {
            int shift = (8 - size) << 3;
            int extraBytes = size - 1;
            long mask = (long)VIntCoding.encodeExtraBytesToRead(extraBytes) << 56;
            long register = (value << shift) | mask;
            output.writeBytes(register, size);
        }
        else if (size == 9)
        {
            output.write((byte) 0xFF);
            output.writeLong(value);
        }
        else
        {
            throw new AssertionError();
        }
    }

    @Inline
    public static void writeUnsignedVInt(long value, ByteBuffer output)
    {
        int size = VIntCoding.computeUnsignedVIntSize(value);
        if (size == 1)
        {
            output.put((byte) (value));
        }
        else if (size < 9)
        {
            int limit = output.limit();
            int pos = output.position();
            if (limit - pos >= size)
            {
                int shift = (8 - size) << 3;
                int extraBytes = size - 1;
                long mask = (long)VIntCoding.encodeExtraBytesToRead(extraBytes) << 56;
                long register = (value << shift) | mask;
                output.putLong(pos, register);
                output.position(pos + size);
            }
        }
        else if (size == 9)
        {
            output.put((byte) 0xFF);
            output.putLong(value);
        }
        else
        {
            throw new AssertionError();
        }
    }

    @Inline
    public static void writeVInt(long value, DataOutputPlus output) throws IOException
    {
        writeUnsignedVInt(encodeZigZag64(value), output);
    }

    @Inline
    public static void writeVInt(long value, ByteBuffer output) throws IOException
    {
        writeUnsignedVInt(encodeZigZag64(value), output);
    }

    /**
     * Decode a ZigZag-encoded 64-bit value.  ZigZag encodes signed integers
     * into values that can be efficiently encoded with varint.  (Otherwise,
     * negative values must be sign-extended to 64 bits to be varint encoded,
     * thus always taking 10 bytes on the wire.)
     *
     * @param n An unsigned 64-bit integer, stored in a signed int because
     *          Java has no explicit unsigned support.
     * @return A signed 64-bit integer.
     */
    public static long decodeZigZag64(final long n)
    {
        return (n >>> 1) ^ -(n & 1);
    }

    /**
     * Encode a ZigZag-encoded 64-bit value.  ZigZag encodes signed integers
     * into values that can be efficiently encoded with varint.  (Otherwise,
     * negative values must be sign-extended to 64 bits to be varint encoded,
     * thus always taking 10 bytes on the wire.)
     *
     * @param n A signed 64-bit integer.
     * @return An unsigned 64-bit integer, stored in a signed int because
     *         Java has no explicit unsigned support.
     */
    public static long encodeZigZag64(final long n)
    {
        // Note:  the right-shift must be arithmetic
        return (n << 1) ^ (n >> 63);
    }

    /** Compute the number of bytes that would be needed to encode a varint. */
    public static int computeVIntSize(final long param)
    {
        return computeUnsignedVIntSize(encodeZigZag64(param));
    }

    /** Compute the number of bytes that would be needed to encode an unsigned varint. */
    public static int computeUnsignedVIntSize(final long value)
    {
        int magnitude = Long.numberOfLeadingZeros(value | 1); // | with 1 to ensure magntiude <= 63, so (63 - 1) / 7 <= 8
        // the formula below is hand-picked to match the original 9 - ((magnitude - 1) / 7)
        return (639 - magnitude * 9) >> 6;
    }
}
