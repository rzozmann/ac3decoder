package org.zoz.ac3.decoder.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Unit tests of individual decoding steps against the definitions of ATSC A/52:2018.
 */
class SpecificationTest {

    /** Table 7.33 (5 decimal digits) - the computed window must reproduce every entry. */
    private static final double[] TABLE_7_33_EXCERPT = {
        0.00014, 0.00024, 0.00037, 0.00051, 0.00067, 0.00086, 0.00107, 0.00130, 0.00157, 0.00187,
    };

    @Test
    void windowMatchesTable733() {
        double[] w = Ac3Tables.WINDOW;
        assertEquals(256, w.length);
        for (int i = 0; i < TABLE_7_33_EXCERPT.length; i++) {
            assertEquals(TABLE_7_33_EXCERPT[i], w[i], 0.5e-5, "w[" + i + "]");
        }
        assertEquals(0.44030, w[100], 0.5e-5);
        assertEquals(0.80747, w[140], 0.5e-5);
        assertEquals(0.99558, w[200], 0.5e-5);
        assertEquals(1.00000, w[255], 0.5e-5);
        // Princen-Bradley condition required for perfect reconstruction: w[n]^2 + w[255-n]^2 = 1
        for (int n = 0; n < 256; n++) {
            assertEquals(1.0, w[n] * w[n] + w[255 - n] * w[255 - n], 1e-12);
        }
    }

    @Test
    void frameSizesFollowTable518() {
        // 32 kbps @ 48 kHz: 64 words; 44.1 kHz: 69/70 words; 640 kbps @ 32 kHz: 1920 words
        assertEquals(128, frameBytes(0, 0));
        assertEquals(138, frameBytes(1, 0));
        assertEquals(140, frameBytes(1, 1));
        assertEquals(3840, frameBytes(2, 37));
        assertEquals(2788, frameBytes(1, 37));
        assertEquals(1536, frameBytes(0, 28)); // 384 kbps @ 48 kHz = 768 words
    }

    private static int frameBytes(int fscod, int frmsizecod) {
        byte[] b = {0x0B, 0x77, 0, 0, (byte) ((fscod << 6) | frmsizecod), (byte) (8 << 3)};
        Ac3FrameHeader h = Ac3FrameHeader.parseSyncInfo(b, 0, b.length);
        assertNotNull(h);
        return h.getFrameBytes();
    }

    @Test
    void reservedSyncInfoRejected() {
        assertNull(Ac3FrameHeader.parseSyncInfo(new byte[] {0x0B, 0x77, 0, 0, (byte) 0xC0, 0x40}, 0, 6)); // fscod 3
        assertNull(Ac3FrameHeader.parseSyncInfo(new byte[] {0x0B, 0x77, 0, 0, 38, 0x40}, 0, 6)); // frmsizecod 38
        assertNull(Ac3FrameHeader.parseSyncInfo(new byte[] {0x0B, 0x78, 0, 0, 0, 0x40}, 0, 6)); // no sync
        assertNull(Ac3FrameHeader.parseSyncInfo(new byte[] {0x0B, 0x77, 0, 0, 0, (byte) (16 << 3)}, 0, 6)); // E-AC-3
    }

    @Test
    void symmetricQuantizerTables() {
        double one = 1 << Ac3Tables.FRACTION_BITS;
        // Table 7.19: -2/3, 0, 2/3 ; group code 9a + 3b + c (7.3.5)
        int[] g = Ac3Tables.B1_GROUPS[2 * 9 + 1 * 3 + 0];
        assertEquals(2.0 / 3, g[0] / one, 1e-6);
        assertEquals(0.0, g[1] / one, 1e-6);
        assertEquals(-2.0 / 3, g[2] / one, 1e-6);
        // Table 7.20 code 4: 4/5, Table 7.22 code 0: -10/11, Table 7.23 code 14: 14/15
        assertEquals(4.0 / 5, Ac3Tables.B2_GROUPS[4 * 25][0] / one, 1e-6);
        assertEquals(-10.0 / 11, Ac3Tables.B4_GROUPS[0][0] / one, 1e-6);
        assertEquals(10.0 / 11, Ac3Tables.B4_GROUPS[10][1] / one, 1e-6);
        assertEquals(-6.0 / 7, Ac3Tables.B3_MANT[0] / one, 1e-6);
        assertEquals(14.0 / 15, Ac3Tables.B5_MANT[14] / one, 1e-6);
    }

    @Test
    void logAdditionAndLowComp() {
        // 7.2.2.3 logadd
        assertEquals(1000 + 64, Ac3BitAllocation.logadd(1000, 1000));
        assertEquals(1000 + Ac3Tables.LATAB[50], Ac3BitAllocation.logadd(1000, 900));
        assertEquals(1000 + Ac3Tables.LATAB[50], Ac3BitAllocation.logadd(900, 1000));
        assertEquals(3000, Ac3BitAllocation.logadd(3000, 0)); // address clipped to 255
        // calc_lowcomp
        assertEquals(384, Ac3BitAllocation.lowcomp(0, 100, 356, 3));
        assertEquals(320, Ac3BitAllocation.lowcomp(0, 100, 356, 10));
        assertEquals(256, Ac3BitAllocation.lowcomp(320, 500, 400, 10));
        assertEquals(192, Ac3BitAllocation.lowcomp(320, 400, 400, 25));
        assertEquals(0, Ac3BitAllocation.lowcomp(32, 500, 400, 5));
    }

    @Test
    void bitAllocationSpecialValues() {
        // flat full scale spectrum with a large SNR offset -> top of baptab; with a very low one -> 0
        int[] exp = new int[256];
        int[] psd = new int[256];
        int[] bndpsd = new int[50];
        int[] mask = new int[50];
        int[] bap = new int[256];
        Ac3BitAllocation.Params p = new Ac3BitAllocation.Params();
        p.sdecay = Ac3Tables.SLOW_DECAY[2];
        p.fdecay = Ac3Tables.FAST_DECAY[1];
        p.sgain = Ac3Tables.SLOW_GAIN[1];
        p.dbknee = Ac3Tables.DB_PER_BIT[2];
        p.floor = Ac3Tables.FLOOR[7];
        Ac3BitAllocation.psd(exp, 0, 253, psd, bndpsd);
        assertEquals(3072, psd[0]);
        assertEquals(3072, bndpsd[0]); // single bin band
        // band 28 has 3 bins: logadd(logadd(3072, 3072), 3072) = logadd(3136, 3072) = 3136 + latab[32]
        assertEquals(3136 + Ac3Tables.LATAB[32], bndpsd[28]);
        Ac3BitAllocation.mask(p, bndpsd, 0, 253, Ac3Tables.FAST_GAIN[4], false, false, Ac3BitAllocation.DBA_NONE, 0,
            null, null, null, mask);
        int csnroffst = 63;
        int fsnroffst = 15;
        Ac3BitAllocation.bap(mask, psd, 0, 253, (((csnroffst - 15) << 4) + fsnroffst) << 2, p.floor, bap);
        for (int i = 0; i < 253; i++) {
            assertTrue(bap[i] >= 0 && bap[i] <= 15);
        }
        Ac3BitAllocation.bap(mask, psd, 0, 253, -4000, p.floor, bap);
        for (int i = 0; i < 253; i++) {
            assertEquals(15, bap[i], "huge snr offset yields maximum resolution");
        }
    }

    @Test
    void floorTableIsSignedSixteenBit() {
        assertEquals(-2048, Ac3Tables.FLOOR[7]); // 0xf800
    }

    @Test
    void dynamicRangeWords() {
        // Table 7.29: '0000 0000' is X=0 (+6.02 dB) combined with Y=0.1 (base 2, -6.02 dB) = unity gain
        assertEquals(1.0, Ac3Decoder.dynrngToGain(0x00, 1.0), 1e-12);
        assertEquals(Math.pow(2, 4) * 63 / 64.0, Ac3Decoder.dynrngToGain(0x7f, 1.0), 1e-12); // +23.95 dB
        assertEquals(Math.pow(2, -3) * 0.5, Ac3Decoder.dynrngToGain(0x80, 1.0), 1e-12); // -24.08 dB
        assertEquals(1.0, Ac3Decoder.dynrngToGain(0x80, 0.0), 1e-12); // compression disabled
        assertEquals(1.0, Ac3Decoder.comprToGain(0x00), 1e-12);
        assertEquals(Math.pow(2, 8) * 31 / 32.0, Ac3Decoder.comprToGain(0x7f), 1e-9); // +47.89 dB
    }

    @Test
    void crcPolynomial() {
        // a message followed by its CRC must yield a zero register (section 7.10.1)
        Random rnd = new Random(1);
        byte[] msg = new byte[100];
        rnd.nextBytes(msg);
        int crc = Crc16.compute(msg, 0, 98);
        msg[98] = (byte) (crc >> 8);
        msg[99] = (byte) crc;
        assertEquals(0, Crc16.compute(msg, 0, 100));
        msg[10] ^= 4;
        assertTrue(Crc16.compute(msg, 0, 100) != 0);
    }

    @Test
    void bitReader() {
        byte[] b = {(byte) 0b1011_0111, (byte) 0b0000_1111, (byte) 0xff};
        BitReader r = new BitReader(b, 0, 3);
        assertEquals(1, r.read(1));
        assertEquals(0b011, r.read(3));
        assertEquals(0b0111_0000, r.read(8));
        assertEquals(-1, r.readSigned(4));
        assertEquals(0xff, r.read(8));
        assertThrows(Ac3DecodeException.class, () -> r.read(1));
    }

    /** IMDCT of section 7.9.4 must invert the forward transform of section 8.2.3.2 (TDAC). */
    @Test
    void imdctReconstructsSignal() {
        Random rnd = new Random(42);
        int blocks = 8;
        double[] signal = new double[(blocks + 1) * 256];
        for (int i = 256; i < blocks * 256; i++) {
            signal[i] = 0.5 * Math.sin(i * 0.05) + 0.1 * (rnd.nextDouble() - 0.5);
        }
        double[] w = Ac3Tables.WINDOW;
        Ac3Imdct imdct = new Ac3Imdct();
        double[] delay = new double[256];
        float[] out = new float[256];
        for (int b = 0; b < blocks; b++) {
            boolean shortBlock = (b % 3) == 1;
            double[] x = new double[512];
            for (int n = 0; n < 512; n++) {
                int idx = b * 256 + n - 256;
                double s = idx >= 0 && idx < signal.length ? signal[idx] : 0;
                x[n] = s * (n < 256 ? w[n] : w[511 - n]);
            }
            double[] coeffs = shortBlock ? forwardShort(x) : forward(x, 512, 0);
            imdct.transform(coeffs, shortBlock, delay, out, 0);
            // block b covers signal[(b-1)*256 .. (b+1)*256); overlap/add completes its first half
            if (b >= 1) {
                for (int n = 0; n < 256; n++) {
                    int idx = (b - 1) * 256 + n;
                    // output samples are float: allow float rounding only
                    assertEquals(signal[idx], out[n], 1e-6, "block " + b + " sample " + n);
                }
            }
        }
    }

    /** Forward transform of section 8.2.3.2 (encoder), alpha = 0 long, -1/+1 short. */
    private static double[] forward(double[] x, int n, int alpha) {
        double[] xd = new double[n / 2];
        for (int k = 0; k < n / 2; k++) {
            double s = 0;
            for (int i = 0; i < n; i++) {
                s += x[i] * Math.cos(2 * Math.PI / (4 * n) * (2 * i + 1) * (2 * k + 1)
                    + Math.PI / 4 * (2 * k + 1) * (1 + alpha));
            }
            xd[k] = -2.0 / n * s;
        }
        return xd;
    }

    private static double[] forwardShort(double[] x) {
        double[] a = new double[256];
        double[] b = new double[256];
        System.arraycopy(x, 0, a, 0, 256);
        System.arraycopy(x, 256, b, 0, 256);
        double[] x1 = forward(a, 256, -1);
        double[] x2 = forward(b, 256, 1);
        double[] out = new double[256];
        for (int k = 0; k < 128; k++) {
            out[2 * k] = x1[k];
            out[2 * k + 1] = x2[k];
        }
        return out;
    }

    @Test
    void decoderMutesCorruptFrame() throws Exception {
        java.io.File f = org.zoz.ac3.decoder.test.spi.TestFiles.referenceFiles().stream().findFirst().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(f != null, "no reference file");
        byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
        Ac3FrameHeader h = Ac3FrameHeader.parseSyncInfo(data, 0, data.length);
        byte[] frame = java.util.Arrays.copyOf(data, h.getFrameBytes());
        Ac3Decoder ok = new Ac3Decoder();
        ok.decodeFrame(frame, 0, frame.length);
        assertTrue(ok.isLastFrameValid());
        // damage within the first 5/8 of the frame -> crc1 fails
        byte[] bad1 = frame.clone();
        bad1[frame.length / 2] ^= 0x10;
        Ac3Decoder d1 = new Ac3Decoder();
        d1.decodeFrame(bad1, 0, bad1.length);
        assertFalse(d1.isLastFrameValid());
        assertEquals("crc1 mismatch", d1.getLastError());
        for (float[] ch : d1.getOutput()) {
            assertArrayEquals(new float[ch.length], ch, "muted");
        }
        // damage within the last 3/8 -> crc1 passes, crc2 fails
        byte[] bad2 = frame.clone();
        bad2[frame.length - 10] ^= 0x10;
        Ac3Decoder d2 = new Ac3Decoder();
        d2.decodeFrame(bad2, 0, bad2.length);
        assertFalse(d2.isLastFrameValid());
        assertEquals("crc2 mismatch", d2.getLastError());
        // with CRC checking disabled the decoder still decodes (the damage hits aux data or mantissas)
        Ac3Decoder d3 = new Ac3Decoder(Ac3DecoderOptions.defaults().crcCheck(false));
        d3.decodeFrame(bad2, 0, bad2.length);
        assertEquals(1, d3.getFramesDecoded());
    }

    @Test
    void crcValidOnAllReferenceFrames() throws Exception {
        for (java.io.File f : org.zoz.ac3.decoder.test.spi.TestFiles.referenceFiles()) {
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            int pos = 0;
            int frames = 0;
            while (pos + 6 <= data.length) {
                Ac3FrameHeader h = Ac3FrameHeader.parseSyncInfo(data, pos, data.length - pos);
                assertNotNull(h, f.getName() + ": no syncinfo at " + pos);
                if (pos + h.getFrameBytes() > data.length) {
                    break;
                }
                assertTrue(Crc16.checkCrc1(data, pos, h.getFrameBytes()), f.getName() + " crc1 frame " + frames);
                assertTrue(Crc16.checkCrc2(data, pos, h.getFrameBytes()), f.getName() + " crc2 frame " + frames);
                pos += h.getFrameBytes();
                frames++;
            }
            assertTrue(frames > 0);
        }
    }
}
