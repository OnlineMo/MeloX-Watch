/*
 *  21.04.2004 Original verion. davagin@udm.ru.
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
/* Modified 2026-11-22: isolated Android local decode, bounded inputs and cancellation; GPL-2.0-or-later. */


package io.github.onlinemo.meloxwatch.data.codec.jmac.decoder;

import java.io.IOException;
import io.github.onlinemo.meloxwatch.data.codec.jmac.tools.ByteArrayReader;
import io.github.onlinemo.meloxwatch.data.codec.jmac.tools.File;
import io.github.onlinemo.meloxwatch.data.codec.jmac.tools.JMACException;

public abstract class UnBitArrayBase {
    protected long elements, bytes, bits, currentBitIndex;
    protected int validBytes;
    protected File io;
    protected long[] bitArray;
    protected ByteArrayReader reader;
    protected void createHelper(File input, int amount, int version) {
        if (input == null || amount != 16384 || version != 3990) throw new JMACException("Invalid decoder input");
        io = input;
        elements = amount / 4; bytes = amount; bits = bytes * 8;
        bitArray = new long[(int) elements]; reader = new ByteArrayReader(amount);
    }
    public void fillBitArray() throws IOException {
        int consumed = (int) (currentBitIndex >> 5);
        if (consumed < 0 || consumed > elements) throw new JMACException("Invalid bit position");
        System.arraycopy(bitArray, consumed, bitArray, 0, bitArray.length - consumed);
        int read = reader.reset(io, consumed * 4);
        validBytes = Math.max(0, validBytes - consumed * 4) + read;
        for (int index = bitArray.length - consumed; index < bitArray.length; index++) bitArray[index] = reader.readUnsignedInt();
        currentBitIndex &= 31;
    }
    public void fillAndResetBitArray(int position, int bitOffset) throws IOException {
        io.checkCancellation(); io.seek(position);
        currentBitIndex = bitOffset;
        validBytes = reader.reset(io, (int) bytes);
        for (int index = 0; index < bitArray.length; index++) bitArray[index] = reader.readUnsignedInt();
    }
    protected void requireAvailable(long bit, int count) {
        if (bit < 0 || count < 0 || bit + count > validBytes * 8L) throw new JMACException("Truncated APE bitstream");
    }
    protected long decodeValueXBits(long amount) throws IOException {
        if (amount < 0 || amount > 32) throw new JMACException("Invalid bit count");
        if (currentBitIndex + amount >= bits) fillBitArray();
        requireAvailable(currentBitIndex, (int) amount);
        long index = currentBitIndex >> 5;
        long left = 32 - (currentBitIndex & 31);
        currentBitIndex += amount;
        long mask = left == 32 ? 0xffff_ffffL : (1L << left) - 1;
        if (left >= amount) return (bitArray[(int) index] & mask) >> (left - amount);
        long right = amount - left;
        return ((bitArray[(int) index] & mask) << right) | (bitArray[(int) index + 1] >> (32 - right));
    }
    public void advanceToByteBoundary() {
        long remainder = currentBitIndex & 7;
        if (remainder != 0) currentBitIndex += 8 - remainder;
    }
    public long decodeValue(int method) throws IOException { return decodeValue(method, 0, 0); }
    public abstract long decodeValue(int method, int parameter1, int parameter2) throws IOException;
    public abstract int decodeValueRange(UnBitArrayState state) throws IOException;
    public abstract void flushState(UnBitArrayState state);
    public abstract void flushBitArray();
    public abstract void finalize_();
}
