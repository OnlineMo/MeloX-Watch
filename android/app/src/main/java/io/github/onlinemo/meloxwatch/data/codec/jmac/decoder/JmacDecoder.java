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
/* Modified 2026-11-22: bounded modern APE decode from JMAC APEDecompress and
 * Prepare.unprepare at 0cedfd83a3005c1c4e4dd5fa7cb3796c79b765f3. Full CRC frames
 * remain private until verified. Seek reconstructs one frame without an unbounded skip allocation. */
package io.github.onlinemo.meloxwatch.data.codec.jmac.decoder;

import java.io.IOException;
import java.util.Arrays;
import io.github.onlinemo.meloxwatch.data.codec.jmac.prediction.PredictorDecompress3950toCurrent;
import io.github.onlinemo.meloxwatch.data.codec.jmac.tools.Crc32;
import io.github.onlinemo.meloxwatch.data.codec.jmac.tools.File;
import io.github.onlinemo.meloxwatch.data.codec.jmac.tools.JMACException;

/** Pure Java 3990/Fast/Normal PCM16 core. The app performs the structural/container admission. */
public final class JmacDecoder implements AutoCloseable {
    private static final int MAX_FRAME_BLOCKS = 294912;
    private static final long WORK_NANOS = 2_000_000_000L;
    private final File input;
    private final int channels, blockBytes, blocksPerFrame, lastFrameBlocks, totalBlocks;
    private final int[] seekBytes;
    private final byte[] framePcm;
    private final UnBitArray bits;
    private final UnBitArrayState stateX = new UnBitArrayState(), stateY = new UnBitArrayState();
    private final PredictorDecompress3950toCurrent predictorX, predictorY;
    private int decodedFrame = -1, nextBlock;
    private boolean closed;

    public JmacDecoder(File input, int version, int compression, int channels,
                       int blocksPerFrame, int lastFrameBlocks, int totalBlocks, int[] seekBytes) {
        if (input == null || version != 3990 || (compression != 1000 && compression != 2000) ||
            channels < 1 || channels > 2 || blocksPerFrame < 1 || blocksPerFrame > MAX_FRAME_BLOCKS ||
            lastFrameBlocks < 1 || lastFrameBlocks > blocksPerFrame || totalBlocks < 1 ||
            seekBytes == null || seekBytes.length < 1 || seekBytes.length > 16384 ||
            (seekBytes.length - 1L) * blocksPerFrame + lastFrameBlocks != totalBlocks) {
            throw new JMACException("Invalid admitted APE configuration");
        }
        this.input = input;
        this.channels = channels;
        this.blockBytes = channels * 2;
        this.blocksPerFrame = blocksPerFrame;
        this.lastFrameBlocks = lastFrameBlocks;
        this.totalBlocks = totalBlocks;
        this.seekBytes = seekBytes.clone();
        framePcm = new byte[blocksPerFrame * blockBytes];
        bits = new UnBitArray(input, version);
        predictorX = new PredictorDecompress3950toCurrent(compression, version);
        predictorY = new PredictorDecompress3950toCurrent(compression, version);
    }

    public void seek(int block) throws IOException {
        requireOpen();
        if (block < 0 || block > totalBlocks) throw new JMACException("Invalid PCM seek");
        decodedFrame = -1;
        if (block < totalBlocks) decodeFrame(block / blocksPerFrame, deadline());
        nextBlock = block;
    }

    public int readFrames(byte[] output, int offset, int requested) throws IOException {
        requireOpen();
        if (requested < 0 || requested > 512 || offset < 0 || offset > output.length - requested * blockBytes) {
            throw new JMACException("Invalid bounded PCM read");
        }
        int remaining = Math.min(requested, totalBlocks - nextBlock);
        int written = 0;
        long deadline = deadline();
        while (remaining > 0) {
            checkWork(deadline);
            int frame = nextBlock / blocksPerFrame;
            if (decodedFrame != frame) decodeFrame(frame, deadline);
            int within = nextBlock % blocksPerFrame;
            int frameBlocks = frame == seekBytes.length - 1 ? lastFrameBlocks : blocksPerFrame;
            int count = Math.min(remaining, frameBlocks - within);
            if (count <= 0) throw new JMACException("APE decode made no progress");
            System.arraycopy(framePcm, within * blockBytes, output, offset + written * blockBytes, count * blockBytes);
            nextBlock += count;
            remaining -= count;
            written += count;
        }
        input.checkCancellation();
        return written;
    }

    private void decodeFrame(int frame, long deadline) throws IOException {
        checkWork(deadline);
        decodedFrame = -1;
        if (frame < 0 || frame >= seekBytes.length) throw new JMACException("Invalid APE frame");
        int remainder = (seekBytes[frame] - seekBytes[0]) & 3;
        bits.fillAndResetBitArray(seekBytes[frame] - remainder, remainder * 8);
        long storedCrc = bits.decodeValue(DecodeValueMethod.DECODE_VALUE_METHOD_UNSIGNED_INT);
        int special = 0;
        if ((storedCrc & 0x8000_0000L) != 0) special = (int) bits.decodeValue(DecodeValueMethod.DECODE_VALUE_METHOD_UNSIGNED_INT);
        storedCrc &= 0x7fff_ffffL;
        if ((special & ~7) != 0 || (channels == 1 && (special & ~1) != 0)) throw new JMACException("Invalid special frame");
        predictorX.flush(); predictorY.flush();
        bits.flushState(stateX); bits.flushState(stateY); bits.flushBitArray();
        Crc32 crc = new Crc32();
        int blocks = frame == seekBytes.length - 1 ? lastFrameBlocks : blocksPerFrame;
        int previousX = 0;
        int output = 0;
        for (int block = 0; block < blocks; block++) {
            if ((block & 255) == 0) checkWork(deadline);
            int x = 0, y = 0;
            if (channels == 1) {
                if ((special & 1) == 0) x = predictorX.decompressValue(bits.decodeValueRange(stateX));
                short sample = (short) x;
                framePcm[output++] = (byte) sample;
                framePcm[output++] = (byte) (sample >> 8);
                crc.append(sample);
            } else {
                if ((special & 3) != 3) {
                    if ((special & 4) != 0) x = predictorX.decompressValue(bits.decodeValueRange(stateX));
                    else {
                        int residualY = bits.decodeValueRange(stateY);
                        int residualX = bits.decodeValueRange(stateX);
                        y = predictorY.decompressValue(residualY, previousX);
                        x = predictorX.decompressValue(residualX, y);
                        previousX = x;
                    }
                }
                short first = (short) (x - y / 2);
                short second = (short) (first + y);
                framePcm[output++] = (byte) first;
                framePcm[output++] = (byte) (first >> 8);
                framePcm[output++] = (byte) second;
                framePcm[output++] = (byte) (second >> 8);
                crc.append(first, second);
            }
        }
        bits.finalize_();
        if (crc.checksum() != storedCrc) throw new JMACException("Invalid APE frame checksum");
        checkWork(deadline);
        decodedFrame = frame;
    }

    private void requireOpen() throws IOException {
        input.checkCancellation();
        if (closed) throw new IOException("APE decoder closed");
    }
    private long deadline() { return System.nanoTime() + WORK_NANOS; }
    private void checkWork(long deadline) throws IOException {
        requireOpen();
        if (System.nanoTime() - deadline >= 0) throw new BudgetExceededException();
    }

    public static final class BudgetExceededException extends IOException {
        public BudgetExceededException() { super("APE decode work limit"); }
    }
    @Override public void close() {
        closed = true;
        decodedFrame = -1;
        Arrays.fill(framePcm, (byte) 0);
    }
}
