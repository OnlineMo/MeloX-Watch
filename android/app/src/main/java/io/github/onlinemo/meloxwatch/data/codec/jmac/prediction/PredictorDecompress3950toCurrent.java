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


package io.github.onlinemo.meloxwatch.data.codec.jmac.prediction;

import java.util.Arrays;
import io.github.onlinemo.meloxwatch.data.codec.jmac.tools.JMACException;
import io.github.onlinemo.meloxwatch.data.codec.jmac.tools.RollBufferFastInt;

/** The 3950 predictor used by 3990 Fast/Normal. Arithmetic intentionally wraps 32 bits as in the format. */
public final class PredictorDecompress3950toCurrent extends IPredictorDecompress {
    private static final int WINDOW_BLOCKS = 512;
    private final int[] coefficientsA = new int[4], coefficientsB = new int[5];
    private final RollBufferFastInt predictionA = new RollBufferFastInt(WINDOW_BLOCKS, 8);
    private final RollBufferFastInt predictionB = new RollBufferFastInt(WINDOW_BLOCKS, 8);
    private final RollBufferFastInt adaptA = new RollBufferFastInt(WINDOW_BLOCKS, 8);
    private final RollBufferFastInt adaptB = new RollBufferFastInt(WINDOW_BLOCKS, 8);
    private final NNFilter filter;
    private int position, lastA, scaledA, scaledB;

    public PredictorDecompress3950toCurrent(int compression, int version) {
        super(compression, version);
        if (version != 3990 || (compression != 1000 && compression != 2000)) throw new JMACException("Invalid predictor");
        filter = compression == 2000 ? new NNFilter16(11, version) : null;
    }

    @Override public int decompressValue(int residual, int previousChannel) {
        if (position == WINDOW_BLOCKS) {
            predictionA.roll(); predictionB.roll(); adaptA.roll(); adaptB.roll(); position = 0;
        }
        int filtered = filter == null ? residual : filter.decompress(residual);
        int indexA = predictionA.index, indexB = predictionB.index;
        int[] valuesA = predictionA.data, valuesB = predictionB.data;
        valuesA[indexA] = lastA;
        valuesA[indexA - 1] = lastA - valuesA[indexA - 1];
        valuesB[indexB] = previousChannel - ((scaledB * 31) >> 5);
        scaledB = previousChannel;
        valuesB[indexB - 1] = valuesB[indexB] - valuesB[indexB - 1];
        int predictedA = lastA * coefficientsA[0] + valuesA[indexA - 1] * coefficientsA[1] +
            valuesA[indexA - 2] * coefficientsA[2] + valuesA[indexA - 3] * coefficientsA[3];
        int predictedB = valuesB[indexB] * coefficientsB[0] + valuesB[indexB - 1] * coefficientsB[1] +
            valuesB[indexB - 2] * coefficientsB[2] + valuesB[indexB - 3] * coefficientsB[3] +
            valuesB[indexB - 4] * coefficientsB[4];
        int currentA = filtered + ((predictedA + (predictedB >> 1)) >> 10);
        int adaptationA = adaptA.index, adaptationB = adaptB.index;
        adaptA.data[adaptationA] = adaptation(lastA);
        adaptA.data[adaptationA - 1] = adaptation(valuesA[indexA - 1]);
        adaptB.data[adaptationB] = adaptation(valuesB[indexB]);
        adaptB.data[adaptationB - 1] = adaptation(valuesB[indexB - 1]);
        if (filtered != 0) {
            int direction = filtered > 0 ? -1 : 1;
            for (int index = 0; index < coefficientsA.length; index++) coefficientsA[index] += direction * adaptA.data[adaptationA - index];
            for (int index = 0; index < coefficientsB.length; index++) coefficientsB[index] += direction * adaptB.data[adaptationB - index];
        }
        scaledA = currentA + ((scaledA * 31) >> 5);
        lastA = currentA;
        predictionA.index++; predictionB.index++; adaptA.index++; adaptB.index++; position++;
        return scaledA;
    }
    private int adaptation(int value) { return value == 0 ? 0 : ((value >> 30) & 2) - 1; }
    @Override public void flush() {
        if (filter != null) filter.flush();
        Arrays.fill(coefficientsA, 0); Arrays.fill(coefficientsB, 0);
        predictionA.flush(); predictionB.flush(); adaptA.flush(); adaptB.flush();
        coefficientsA[0] = 360; coefficientsA[1] = 317; coefficientsA[2] = -109; coefficientsA[3] = 98;
        position = 0; lastA = 0; scaledA = 0; scaledB = 0;
    }
}
