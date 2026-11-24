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


package io.github.onlinemo.meloxwatch.data.codec.jmac.tools;

import java.io.IOException;
import java.util.Arrays;

/** Buffer padding is cleared on every refill. The bit reader separately checks actual valid bytes. */
public final class ByteArrayReader {
    private final byte[] data;
    private int index;
    public ByteArrayReader(int size) {
        if (size < 0 || size > 16384) throw new JMACException("Invalid bit buffer");
        data = new byte[size];
    }
    public int reset(File input, int size) throws IOException {
        if (size < 0 || size > data.length) throw new JMACException("Invalid refill");
        Arrays.fill(data, (byte) 0);
        index = 0;
        int count = 0;
        while (count < size) {
            input.checkCancellation();
            int read = input.read(data, count, size - count);
            if (read < 0) break;
            if (read == 0) throw new IOException("APE input made no progress");
            count += read;
        }
        return count;
    }
    public long readUnsignedInt() {
        long value = (data[index] & 255L) | ((data[index + 1] & 255L) << 8) |
            ((data[index + 2] & 255L) << 16) | ((data[index + 3] & 255L) << 24);
        index += 4;
        return value;
    }
}
