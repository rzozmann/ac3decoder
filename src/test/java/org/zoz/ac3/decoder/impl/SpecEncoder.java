package org.zoz.ac3.decoder.impl;

import java.io.ByteArrayOutputStream;

/**
 * Minimal AC-3 encoder written directly from A/52 section 8 (encoding) for tests: 1/0 mono at
 * 48 kHz, 640 kbps, D15 exponents, no coupling, maximum SNR offset (so that almost all mantissas
 * use the 16 bit quantizer) and a caller defined block switching pattern.
 *
 * <p>It is used to verify the transform with block switching against a known input signal,
 * independently of any decoder implementation: forward transform (section 8.2.3.2, with
 * alpha = -1 / +1 for the two short transforms) and windowing (Table 7.33) are taken from the
 * standard, so a correct decoder must reproduce the input signal up to the quantization noise.</p>
 */
final class SpecEncoder {

    private static final int FSCOD = 0;
    private static final int FRMSIZECOD = 36; // 640 kbps
    private static final int FRAME_BYTES = 2560;

    private SpecEncoder() {
    }

    /**
     * Encodes {@code pcm} (length a multiple of 1536, values in -1..1).
     *
     * @param shortBlock block switching decision for audio block {@code i} of the whole stream
     */
    static byte[] encode(double[] pcm, java.util.function.IntPredicate shortBlock) {
        int blocks = pcm.length / 256;
        double[] w = Ac3Tables.WINDOW;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[][] exps = new int[6][256];
        int[][] mant = new int[6][256];
        boolean[] blksw = new boolean[6];
        for (int frameStart = 0; frameStart < blocks; frameStart += 6) {
            for (int b = 0; b < 6; b++) {
                int blk = frameStart + b;
                // block blk covers samples (blk-1)*256 .. (blk+1)*256 (one block of look-back)
                double[] x = new double[512];
                for (int n = 0; n < 512; n++) {
                    int idx = (blk - 1) * 256 + n;
                    double s = idx >= 0 && idx < pcm.length ? pcm[idx] : 0;
                    x[n] = s * (n < 256 ? w[n] : w[511 - n]);
                }
                blksw[b] = shortBlock.test(blk);
                double[] coeffs = blksw[b] ? forwardShort(x) : forward(x, 512, 0);
                for (int k = 0; k < 256; k++) {
                    // exponent: number of leading zeros of |c| as a fraction (0..24), section 8.2.7
                    double c = coeffs[k];
                    int e = 0;
                    while (e < 24 && Math.abs(c) * (1 << (e + 1)) < 1.0) {
                        e++;
                    }
                    exps[b][k] = e;
                    // 16 bit two's complement mantissa of c * 2^e, section 8.2.13
                    long m = Math.round(c * (1L << e) * 32768.0);
                    mant[b][k] = (int) Math.max(-32768, Math.min(32767, m));
                }
            }
            out.writeBytes(frame(exps, mant, blksw));
        }
        return out.toByteArray();
    }

    /** Forward transform of section 8.2.3.2. */
    static double[] forward(double[] x, int n, int alpha) {
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

    /** Two short transforms (alpha -1 and +1), coefficients interleaved (section 7.9.2). */
    static double[] forwardShort(double[] x) {
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

    private static byte[] frame(int[][] exps, int[][] mant, boolean[] blksw) {
        SyntheticStreamWriter.BitWriter w = new SyntheticStreamWriter.BitWriter(FRAME_BYTES);
        w.put(16, 0x0B77);
        w.put(16, 0);
        w.put(2, FSCOD);
        w.put(6, FRMSIZECOD);
        w.put(5, 8); // bsid
        w.put(3, 0); // bsmod
        w.put(3, 1); // acmod 1/0
        w.put(1, 0); // lfeon
        w.put(5, 31); // dialnorm
        w.put(1, 0); // compre
        w.put(1, 0); // langcode
        w.put(1, 0); // audprodie
        w.put(1, 0); // copyrightb
        w.put(1, 1); // origbs
        w.put(1, 0); // timecod1e
        w.put(1, 0); // timecod2e
        w.put(1, 0); // addbsie
        int chbwcod = Integer.getInteger("ac3.spec.chbwcod", 30);
        int endmant = (chbwcod + 12) * 3 + 37; // 163 bins, about 15 kHz
        Ac3BitAllocation.Params p = new Ac3BitAllocation.Params();
        p.fscod = FSCOD;
        p.sdecay = Ac3Tables.SLOW_DECAY[0];
        p.fdecay = Ac3Tables.FAST_DECAY[0];
        p.sgain = Ac3Tables.SLOW_GAIN[0];
        p.dbknee = Ac3Tables.DB_PER_BIT[0];
        p.floor = Ac3Tables.FLOOR[7];
        for (int blk = 0; blk < 6; blk++) {
            w.put(1, blksw[blk] ? 1 : 0);
            w.put(1, 0); // dithflag
            w.put(1, 0); // dynrnge
            w.put(1, blk == 0 ? 1 : 0); // cplstre
            if (blk == 0) {
                w.put(1, 0); // cplinu
            }
            w.put(2, 1); // chexpstr D15 (new exponents in every block)
            w.put(6, chbwcod);
            // exponents: absolute + differential groups; exponent differences limited to +-2
            int[] e = smoothExponents(exps[blk], endmant);
            w.put(4, e[0]);
            int ngrps = (endmant - 1) / 3;
            for (int g = 0; g < ngrps; g++) {
                int d0 = e[1 + 3 * g] - e[3 * g] + 2;
                int d1 = e[2 + 3 * g] - e[1 + 3 * g] + 2;
                int d2 = e[3 + 3 * g] - e[2 + 3 * g] + 2;
                w.put(7, 25 * d0 + 5 * d1 + d2);
            }
            w.put(2, 0); // gainrng
            w.put(1, blk == 0 ? 1 : 0); // baie
            if (blk == 0) {
                w.put(2, 0);
                w.put(2, 0);
                w.put(2, 0);
                w.put(2, 0);
                w.put(3, 7);
            }
            w.put(1, blk == 0 ? 1 : 0); // snroffste
            if (blk == 0) {
                w.put(6, 63);
                w.put(4, 15);
                w.put(3, 0);
            }
            w.put(1, 0); // deltbaie (block 0: no delta bit allocation)
            w.put(1, 0); // skiple
            // bit allocation exactly as the decoder will compute it
            int[] psd = new int[256];
            int[] bndpsd = new int[50];
            int[] mask = new int[50];
            int[] bap = new int[256];
            Ac3BitAllocation.psd(e, 0, endmant, psd, bndpsd);
            Ac3BitAllocation.mask(p, bndpsd, 0, endmant, Ac3Tables.FAST_GAIN[0], false, false,
                Ac3BitAllocation.DBA_NONE, 0, null, null, null, mask);
            Ac3BitAllocation.bap(mask, psd, 0, endmant, (((63 - 15) << 4) + 15) << 2, p.floor, bap);
            // quantize the mantissas with the allocated quantizer (section 8.2.13, Tables 7.18 - 7.23)
            int[] group1 = new int[3];
            int[] group2 = new int[3];
            int[] group4 = new int[2];
            int n1 = 0;
            int n2 = 0;
            int n4 = 0;
            for (int k = 0; k < endmant; k++) {
                // normalized mantissa in -1..1 for the transmitted (smoothed) exponent
                double v = mant[blk][k] / 32768.0 * Math.pow(2, e[k] - exps[blk][k]);
                int b = bap[k];
                switch (b) {
                    case 0 -> { }
                    case 1 -> {
                        group1[n1++] = symmetricCode(v, 3);
                        if (n1 == 3) {
                            w.put(5, 9 * group1[0] + 3 * group1[1] + group1[2]);
                            n1 = 0;
                        }
                    }
                    case 2 -> {
                        group2[n2++] = symmetricCode(v, 5);
                        if (n2 == 3) {
                            w.put(7, 25 * group2[0] + 5 * group2[1] + group2[2]);
                            n2 = 0;
                        }
                    }
                    case 3 -> w.put(3, symmetricCode(v, 7));
                    case 4 -> {
                        group4[n4++] = symmetricCode(v, 11);
                        if (n4 == 2) {
                            w.put(7, 11 * group4[0] + group4[1]);
                            n4 = 0;
                        }
                    }
                    case 5 -> w.put(4, symmetricCode(v, 15));
                    default -> {
                        int q = Ac3Tables.QNTZTAB[b];
                        long m = Math.round(v * (1L << (q - 1)));
                        m = Math.max(-(1L << (q - 1)), Math.min((1L << (q - 1)) - 1, m));
                        w.put(q, (int) m & ((1 << q) - 1));
                    }
                }
            }
            // incomplete groups are padded with dummy mantissas (section 7.3.5)
            if (n1 > 0) {
                w.put(5, 9 * group1[0] + 3 * (n1 > 1 ? group1[1] : 1) + 1);
            }
            if (n2 > 0) {
                w.put(7, 25 * group2[0] + 5 * (n2 > 1 ? group2[1] : 2) + 2);
            }
            if (n4 > 0) {
                w.put(7, 11 * group4[0] + 5);
            }
        }
        int auxdataeBit = FRAME_BYTES * 8 - 18;
        if (w.pos > auxdataeBit) {
            throw new IllegalStateException("frame too large: " + w.pos);
        }
        w.pos = auxdataeBit;
        w.put(1, 0);
        w.put(1, 0);
        byte[] frame = w.buf;
        int words = FRAME_BYTES / 2;
        SyntheticStreamWriter.setCrc(frame, 2, 2, ((words >> 1) + (words >> 3)) * 2);
        SyntheticStreamWriter.setCrc(frame, FRAME_BYTES - 2, 2, FRAME_BYTES);
        return frame;
    }

    /** Code of the nearest level of a symmetric {@code levels}-level quantizer (Tables 7.19 - 7.23). */
    private static int symmetricCode(double v, int levels) {
        // level c represents (2c - (levels - 1)) / levels
        int c = (int) Math.round((v * levels + (levels - 1)) / 2.0);
        return Math.max(0, Math.min(levels - 1, c));
    }

    /**
     * Exponents may only change by +-2 from bin to bin (section 7.1.3). Exponents are lowered
     * (never raised, which would overflow the mantissa) until the constraint holds.
     */
    private static int[] smoothExponents(int[] exp, int end) {
        int[] e = new int[end];
        System.arraycopy(exp, 0, e, 0, end);
        e[0] = Math.min(e[0], 15); // absolute exponent is 4 bits
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int k = 1; k < end; k++) {
                if (e[k] > e[k - 1] + 2) {
                    e[k] = e[k - 1] + 2;
                    changed = true;
                }
                if (e[k - 1] > e[k] + 2) {
                    e[k - 1] = e[k] + 2;
                    changed = true;
                }
            }
        }
        return e;
    }
}
