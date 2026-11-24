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
import io.github.onlinemo.meloxwatch.data.codec.jmac.tools.RollBufferShort;


/**
 * @author Dmitry Vaguine
 * @version 04.03.2004 14:51:31
 */
public abstract class NNFilter {

    public final static int NN_WINDOW_ELEMENTS = 512;

    public NNFilter(int order, int shift, int version) {
        if (order != 16 || shift != 11 || version != 3990)
            throw new JMACException("Unsupported bounded predictor profile");
        this.order = order;
        this.shift = shift;
        input.create(NN_WINDOW_ELEMENTS, order);
        deltaM.create(NN_WINDOW_ELEMENTS, order);
        m = new short[order];
    }

    public int decompress(int input) {
        RollBufferShort input_ = this.input;
        RollBufferShort delta = deltaM;
        int order = this.order;
        int shift = this.shift;
        short[] m_ = m;
        short[] inputData = input_.data;
        int inputIndex = input_.index;
        short[] deltaData = delta.data;
        int deltaIndex = delta.index;
        int dotProduct = calculateDotProductNoMMX(inputData, inputIndex - order, m_, 0);
        adaptNoMMX(m_, 0, deltaData, deltaIndex - order, input);
        int output = input + ((dotProduct + (1 << (shift - 1))) >> shift);
        inputData[inputIndex] = (short) ((output >= Short.MIN_VALUE && output <= Short.MAX_VALUE) ? output : (output >> 31) ^ 0x7FFF);

        int tempABS = Math.abs(output);

        if (tempABS > (runningAverage * 3))
            deltaData[deltaIndex] = (short) (((output >> 25) & 64) - 32);
        else if (tempABS > (runningAverage * 4) / 3)
            deltaData[deltaIndex] = (short) (((output >> 26) & 32) - 16);
        else if (tempABS > 0)
            deltaData[deltaIndex] = (short) (((output >> 27) & 16) - 8);
        else
            deltaData[deltaIndex] = 0;

        runningAverage += (tempABS - runningAverage) / 16;

        deltaData[deltaIndex - 1] >>= 1;
        deltaData[deltaIndex - 2] >>= 1;
        deltaData[deltaIndex - 8] >>= 1;
        if ((++input_.index) == orderPlusWindow) {
            System.arraycopy(inputData, input_.index - order, inputData, 0, order);
            input_.index = order;
        }
        if ((++delta.index) == orderPlusWindow) {
            System.arraycopy(deltaData, delta.index - order, deltaData, 0, order);
            delta.index = order;
        }

        return output;
    }

    public void flush() {
        Arrays.fill(m, (short) 0);
        input.flush();
        deltaM.flush();
        runningAverage = 0;
    }

    protected int order;
    protected int shift;
    protected int orderPlusWindow;
    private int runningAverage;

    private final RollBufferShort input = new RollBufferShort();
    private final RollBufferShort deltaM = new RollBufferShort();

    private final short[] m;

    protected abstract int calculateDotProductNoMMX(short[] a, int indexA, short[] b, int indexB);

    protected abstract void adaptNoMMX(short[] m, int indexM, short[] adapt, int indexA, int direction);
}
