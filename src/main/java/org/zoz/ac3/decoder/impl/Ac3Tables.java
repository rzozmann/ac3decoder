package org.zoz.ac3.decoder.impl;

/**
 * Constant tables of ATSC A/52:2018 (AC-3).
 *
 * <p>All integer tables were extracted programmatically from the text of the standard
 * (section 5.4 and section 7.2.3) and are reproduced here verbatim. The transform window
 * (Table 7.33) is computed at full precision as the Kaiser-Bessel-derived window with
 * alpha = 5 from which the 5-digit table of the standard was generated; the unit tests
 * verify that the computed window equals Table 7.33 to all published digits.</p>
 */
final class Ac3Tables {

    private Ac3Tables() {
    }

    /** Sync word, section 5.4.1.1. */
    static final int SYNC_WORD = 0x0B77;

    /** Number of audio blocks per syncframe. */
    static final int BLOCKS_PER_FRAME = 6;

    /** New PCM samples produced by each audio block. */
    static final int SAMPLES_PER_BLOCK = 256;

    /** Table 5.6: sample rate per fscod (fscod 3 is reserved). */
    static final int[] SAMPLE_RATES = {48000, 44100, 32000};

    /** Table 5.18: nominal bit rate (kbps) per frmsizecod/2. */
    static final int[] BIT_RATES_KBPS = {
        32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 576, 640,
    };

    /**
     * Table 5.18: words (16 bit) per syncframe, indexed [frmsizecod/2][fscod].
     * For 44.1 kHz one word must be added if frmsizecod is odd.
     */
    static final int[][] FRAME_SIZE_WORDS = {
        {64, 69, 96}, {80, 87, 120}, {96, 104, 144}, {112, 121, 168}, {128, 139, 192},
        {160, 174, 240}, {192, 208, 288}, {224, 243, 336}, {256, 278, 384}, {320, 348, 480},
        {384, 417, 576}, {448, 487, 672}, {512, 557, 768}, {640, 696, 960}, {768, 835, 1152},
        {896, 975, 1344}, {1024, 1114, 1536}, {1152, 1253, 1728}, {1280, 1393, 1920},
    };

    /** Table 5.8: number of full bandwidth channels per acmod. */
    static final int[] NFCHANS = {2, 1, 2, 3, 3, 4, 4, 5};

    /** Table 7.6 slowdec[]. */
    static final int[] SLOW_DECAY = {0x0f, 0x11, 0x13, 0x15};
    /** Table 7.7 fastdec[]. */
    static final int[] FAST_DECAY = {0x3f, 0x53, 0x67, 0x7b};
    /** Table 7.8 slowgain[]. */
    static final int[] SLOW_GAIN = {0x540, 0x4d8, 0x478, 0x410};
    /** Table 7.9 dbpbtab[]. */
    static final int[] DB_PER_BIT = {0x000, 0x700, 0x900, 0xb00};
    /**
     * Table 7.10 floortab[]. The last entry 0xf800 is a 16 bit two's complement value
     * (section 7.2.1: "signed two's complement arithmetic is employed"), i.e. -2048.
     */
    static final int[] FLOOR = {0x2f0, 0x2b0, 0x270, 0x230, 0x1f0, 0x170, 0x0f0, (short) 0xf800};
    /** Table 7.11 fastgain[]. */
    static final int[] FAST_GAIN = {0x080, 0x100, 0x180, 0x200, 0x280, 0x300, 0x380, 0x400};

    /** Table 7.12 bndtab[] (first bin of every band) with the end bin 253 appended. */
    static final int[] BAND_START = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
        17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 31, 34, 37, 40, 43,
        46, 49, 55, 61, 67, 73, 79, 85, 97, 109, 121, 133, 157, 181, 205, 229, 253,
    };

    /** Table 7.13 masktab[]: bin number to band number. */
    static final int[] MASKTAB = new int[256];

    static {
        for (int band = 0; band < 50; band++) {
            for (int bin = BAND_START[band]; bin < BAND_START[band + 1]; bin++) {
                MASKTAB[bin] = band;
            }
        }
        // bins 253..255 map to band 0 according to Table 7.13 (never used)
    }

    /** Table 7.14 latab[]: log-addition table. */
    static final int[] LATAB = {
        64, 63, 62, 61, 60, 59, 58, 57, 56, 55, 54, 53, 52, 52, 51, 50,
        49, 48, 47, 47, 46, 45, 44, 44, 43, 42, 41, 41, 40, 39, 38, 38,
        37, 36, 36, 35, 35, 34, 33, 33, 32, 32, 31, 30, 30, 29, 29, 28,
        28, 27, 27, 26, 26, 25, 25, 24, 24, 23, 23, 22, 22, 21, 21, 21,
        20, 20, 19, 19, 19, 18, 18, 18, 17, 17, 17, 16, 16, 16, 15, 15,
        15, 14, 14, 14, 13, 13, 13, 13, 12, 12, 12, 12, 11, 11, 11, 11,
        10, 10, 10, 10, 10, 9, 9, 9, 9, 9, 8, 8, 8, 8, 8, 8,
        7, 7, 7, 7, 7, 7, 6, 6, 6, 6, 6, 6, 6, 6, 5, 5,
        5, 5, 5, 5, 5, 5, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
        4, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 2,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    };

    /** Table 7.15 hth[fscod][band]: hearing threshold. */
    static final int[][] HTH = {
        {
            0x4d0, 0x4d0, 0x440, 0x400, 0x3e0, 0x3c0, 0x3b0, 0x3b0, 0x3a0, 0x3a0,
            0x3a0, 0x3a0, 0x3a0, 0x390, 0x390, 0x390, 0x380, 0x380, 0x370, 0x370,
            0x360, 0x360, 0x350, 0x350, 0x340, 0x340, 0x330, 0x320, 0x310, 0x300,
            0x2f0, 0x2f0, 0x2f0, 0x2f0, 0x300, 0x310, 0x340, 0x390, 0x3e0, 0x420,
            0x460, 0x490, 0x4a0, 0x460, 0x440, 0x440, 0x520, 0x800, 0x840, 0x840,
        },
        {
            0x4f0, 0x4f0, 0x460, 0x410, 0x3e0, 0x3d0, 0x3c0, 0x3b0, 0x3b0, 0x3a0,
            0x3a0, 0x3a0, 0x3a0, 0x3a0, 0x390, 0x390, 0x390, 0x380, 0x380, 0x380,
            0x370, 0x370, 0x360, 0x360, 0x350, 0x350, 0x340, 0x340, 0x320, 0x310,
            0x300, 0x2f0, 0x2f0, 0x2f0, 0x2f0, 0x300, 0x320, 0x350, 0x390, 0x3e0,
            0x420, 0x450, 0x4a0, 0x490, 0x460, 0x440, 0x480, 0x630, 0x840, 0x840,
        },
        {
            0x580, 0x580, 0x4b0, 0x450, 0x420, 0x3f0, 0x3e0, 0x3d0, 0x3c0, 0x3b0,
            0x3b0, 0x3b0, 0x3a0, 0x3a0, 0x3a0, 0x3a0, 0x3a0, 0x3a0, 0x3a0, 0x3a0,
            0x390, 0x390, 0x390, 0x390, 0x380, 0x380, 0x380, 0x370, 0x360, 0x350,
            0x340, 0x330, 0x320, 0x310, 0x300, 0x2f0, 0x2f0, 0x2f0, 0x300, 0x310,
            0x330, 0x350, 0x3c0, 0x410, 0x470, 0x4a0, 0x460, 0x440, 0x450, 0x4e0,
        },
    };

    /** Table 7.16 baptab[]. */
    static final int[] BAPTAB = {
        0, 1, 1, 1, 1, 1, 2, 2, 3, 3, 3, 4, 4, 5, 5, 6,
        6, 6, 6, 7, 7, 7, 7, 8, 8, 8, 8, 9, 9, 9, 9, 10,
        10, 10, 10, 11, 11, 11, 11, 12, 12, 12, 12, 13, 13, 13, 13, 14,
        14, 14, 14, 14, 14, 14, 14, 15, 15, 15, 15, 15, 15, 15, 15, 15,
    };

    /** Table 7.18 qntztab[]: mantissa bits for the asymmetric quantizers (bap 6..15). */
    static final int[] QNTZTAB = {0, 5, 7, 3, 7, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 16};

    /**
     * Fixed point format of decoded mantissas: a fractional value of 1.0 corresponds to
     * {@code 1 << 23} (two's complement, 24 bit range). The same representation is used by the
     * ffmpeg reference decoder, which allows bit exact comparison of the integer stages.
     */
    static final int FRACTION_BITS = 23;

    /**
     * Grouped mantissas (section 7.3.5) already dequantized: index = 5 bit group code, value =
     * the three mantissas a, b, c of a bap=1 triplet (Table 7.19).
     */
    static final int[][] B1_GROUPS = new int[32][3];
    /** bap=2 triplets (7 bit group code, Table 7.20). */
    static final int[][] B2_GROUPS = new int[128][3];
    /** bap=4 pairs (7 bit group code, Table 7.22). */
    static final int[][] B4_GROUPS = new int[128][2];
    /** bap=3 (Table 7.21), 3 bit code; code 7 is invalid and decodes as 0. */
    static final int[] B3_MANT = new int[8];
    /** bap=5 (Table 7.23), 4 bit code; code 15 is invalid and decodes as 0. */
    static final int[] B5_MANT = new int[16];

    static {
        for (int g = 0; g < 32; g++) {
            // decoder equations of section 7.3.5 (applied literally, also to the invalid codes 27..31)
            B1_GROUPS[g][0] = symmetricDequant(g / 9, 3);
            B1_GROUPS[g][1] = symmetricDequant((g % 9) / 3, 3);
            B1_GROUPS[g][2] = symmetricDequant((g % 9) % 3, 3);
        }
        for (int g = 0; g < 128; g++) {
            B2_GROUPS[g][0] = symmetricDequant(g / 25, 5);
            B2_GROUPS[g][1] = symmetricDequant((g % 25) / 5, 5);
            B2_GROUPS[g][2] = symmetricDequant((g % 25) % 5, 5);
            B4_GROUPS[g][0] = symmetricDequant(g / 11, 11);
            B4_GROUPS[g][1] = symmetricDequant(g % 11, 11);
        }
        for (int c = 0; c < 7; c++) {
            B3_MANT[c] = symmetricDequant(c, 7);
        }
        for (int c = 0; c < 15; c++) {
            B5_MANT[c] = symmetricDequant(c, 15);
        }
    }

    /** Rematrixing band boundaries, Tables 7.25 - 7.28 (upper bound of the last band is clipped). */
    static final int[] REMATRIX_BANDS = {13, 25, 37, 61, 253};

    /**
     * Symmetric quantizer reconstruction value (Tables 7.19 - 7.23): code {@code c} of an
     * {@code L}-level quantizer represents {@code (2c - (L-1)) / L}, e.g. bap=1: -2/3, 0, 2/3.
     * In {@link #FRACTION_BITS} fixed point this is {@code (c - L/2) * 2^24 / L} (truncated
     * towards zero, identical to the reference decoder).
     */
    static int symmetricDequant(int code, int levels) {
        return ((code - (levels >> 1)) << (FRACTION_BITS + 1)) / levels;
    }

    /** Table 5.9 clev (cmixlev 3 is reserved: the intermediate value -4.5 dB is used). */
    static final double[] CENTER_MIX_LEVEL = {db(-3.0), db(-4.5), db(-6.0), db(-4.5)};

    /** Table 5.10 slev (surmixlev 3 is reserved: -6 dB is used). */
    static final double[] SURROUND_MIX_LEVEL = {db(-3.0), db(-6.0), 0.0, db(-6.0)};

    /** Annex D Tables D2.3 and D2.5: Lt/Rt and Lo/Ro center mix level. */
    static final double[] XBSI_CENTER_MIX_LEVEL = {
        db(3.0), db(1.5), 1.0, db(-1.5), db(-3.0), db(-4.5), db(-6.0), 0.0,
    };

    /** Annex D Tables D2.4 and D2.6: Lt/Rt and Lo/Ro surround mix level (0-2 reserved -> 0.841). */
    static final double[] XBSI_SURROUND_MIX_LEVEL = {
        db(-1.5), db(-1.5), db(-1.5), db(-1.5), db(-3.0), db(-4.5), db(-6.0), 0.0,
    };

    /** Gain for a level given in multiples of 1.5 dB, using the exact powers of two the dB values denote. */
    static double db(double db) {
        // 3 dB is interpreted as a power ratio of 2 (amplitude sqrt(2)), as in Table 5.9 (0.707)
        return Math.pow(2.0, db / 6.0);
    }

    /** Transform window w[n], n = 0..255 (Table 7.33), computed at full precision. */
    static final double[] WINDOW = kbdWindow(5.0, 256);

    /**
     * Kaiser-Bessel-derived window, first half (n = 0..half-1) of a window of length 2*half.
     */
    static double[] kbdWindow(double alpha, int half) {
        double[] kaiser = new double[half + 1];
        for (int j = 0; j <= half; j++) {
            double r = (2.0 * j - half) / half;
            kaiser[j] = besselI0(Math.PI * alpha * Math.sqrt(Math.max(0.0, 1.0 - r * r)));
        }
        double total = 0;
        for (double k : kaiser) {
            total += k;
        }
        double[] w = new double[half];
        double sum = 0;
        for (int n = 0; n < half; n++) {
            sum += kaiser[n];
            w[n] = Math.sqrt(sum / total);
        }
        return w;
    }

    /** Modified Bessel function of the first kind, order 0 (power series). */
    static double besselI0(double x) {
        double sum = 1.0;
        double term = 1.0;
        double q = x * x / 4.0;
        for (int k = 1; k < 200; k++) {
            term *= q / ((double) k * k);
            sum += term;
            if (term < sum * 1e-17) {
                break;
            }
        }
        return sum;
    }
}
