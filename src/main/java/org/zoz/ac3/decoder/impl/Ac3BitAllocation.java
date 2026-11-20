package org.zoz.ac3.decoder.impl;

import static org.zoz.ac3.decoder.impl.Ac3Tables.BAND_START;
import static org.zoz.ac3.decoder.impl.Ac3Tables.BAPTAB;
import static org.zoz.ac3.decoder.impl.Ac3Tables.HTH;
import static org.zoz.ac3.decoder.impl.Ac3Tables.LATAB;
import static org.zoz.ac3.decoder.impl.Ac3Tables.MASKTAB;

/**
 * Parametric bit allocation of A/52 section 7.2.2 (vertical execution). All arithmetic is
 * integer arithmetic exactly as specified.
 */
final class Ac3BitAllocation {

    /** Delta bit allocation modes, Table 5.16. */
    static final int DBA_REUSE = 0;
    static final int DBA_NEW = 1;
    static final int DBA_NONE = 2;
    static final int DBA_RESERVED = 3;

    private Ac3BitAllocation() {
    }

    /** Parameters common to all channels of an audio block. */
    static final class Params {
        int fscod;
        int sdecay;
        int fdecay;
        int sgain;
        int dbknee;
        int floor;
        int cplFastLeak;
        int cplSlowLeak;
    }

    /**
     * 7.2.2.2 exponent mapping into PSD and 7.2.2.3 PSD integration.
     */
    static void psd(int[] exp, int start, int end, int[] psd, int[] bndpsd) {
        for (int bin = start; bin < end; bin++) {
            psd[bin] = 3072 - (exp[bin] << 7);
        }
        int j = start;
        int k = MASKTAB[start];
        int lastbin;
        do {
            lastbin = Math.min(BAND_START[k + 1], end);
            int v = psd[j++];
            for (; j < lastbin; j++) {
                v = logadd(v, psd[j]);
            }
            bndpsd[k] = v;
            k++;
        } while (end > lastbin);
    }

    static int logadd(int a, int b) {
        int c = a - b;
        int address = Math.min(Math.abs(c) >> 1, 255);
        return c >= 0 ? a + LATAB[address] : b + LATAB[address];
    }

    /**
     * 7.2.2.4 excitation function, 7.2.2.5 masking curve and 7.2.2.6 delta bit allocation.
     *
     * @param coupling true for the coupling channel (uses the leak initialization)
     * @param lfe true for the lfe channel
     */
    static void mask(Params p, int[] bndpsd, int start, int end, int fgain, boolean coupling, boolean lfe,
                     int dbaMode, int dbaNsegs, int[] dbaOffsets, int[] dbaLengths, int[] dbaValues, int[] mask) {
        int[] excite = new int[50];
        int bndstrt = MASKTAB[start];
        int bndend = MASKTAB[end - 1] + 1;
        int lowcomp = 0;
        int fastleak = 0;
        int slowleak = 0;
        int begin;
        if (bndstrt == 0 && !coupling) {
            lowcomp = lowcomp(lowcomp, bndpsd[0], bndpsd[1], 0);
            excite[0] = bndpsd[0] - fgain - lowcomp;
            lowcomp = lowcomp(lowcomp, bndpsd[1], bndpsd[2], 1);
            excite[1] = bndpsd[1] - fgain - lowcomp;
            begin = 7;
            for (int bin = 2; bin < 7; bin++) {
                boolean lastLfeBin = lfe && bin == 6; // (bndend == 7) && (bin == 6)
                if (!lastLfeBin) {
                    lowcomp = lowcomp(lowcomp, bndpsd[bin], bndpsd[bin + 1], bin);
                }
                fastleak = bndpsd[bin] - fgain;
                slowleak = bndpsd[bin] - p.sgain;
                excite[bin] = fastleak - lowcomp;
                if (!lastLfeBin && bndpsd[bin] <= bndpsd[bin + 1]) {
                    begin = bin + 1;
                    break;
                }
            }
            int end1 = Math.min(bndend, 22);
            for (int bin = begin; bin < end1; bin++) {
                if (!(lfe && bin == 6)) {
                    lowcomp = lowcomp(lowcomp, bndpsd[bin], bndpsd[bin + 1], bin);
                }
                fastleak -= p.fdecay;
                fastleak = Math.max(fastleak, bndpsd[bin] - fgain);
                slowleak -= p.sdecay;
                slowleak = Math.max(slowleak, bndpsd[bin] - p.sgain);
                excite[bin] = Math.max(fastleak - lowcomp, slowleak);
            }
            begin = 22;
        } else {
            begin = bndstrt;
            fastleak = (p.cplFastLeak << 8) + 768;
            slowleak = (p.cplSlowLeak << 8) + 768;
        }
        for (int bin = begin; bin < bndend; bin++) {
            fastleak -= p.fdecay;
            fastleak = Math.max(fastleak, bndpsd[bin] - fgain);
            slowleak -= p.sdecay;
            slowleak = Math.max(slowleak, bndpsd[bin] - p.sgain);
            excite[bin] = Math.max(fastleak, slowleak);
        }
        // 7.2.2.5 compute masking curve
        int[] hth = HTH[p.fscod];
        for (int bin = bndstrt; bin < bndend; bin++) {
            if (bndpsd[bin] < p.dbknee) {
                excite[bin] += (p.dbknee - bndpsd[bin]) >> 2;
            }
            mask[bin] = Math.max(excite[bin], hth[bin]);
        }
        // 7.2.2.6 apply delta bit allocation
        if (dbaMode == DBA_REUSE || dbaMode == DBA_NEW) {
            // Band counting starts at the first band of the channel, which is band 0 for fbw
            // channels. For the coupling channel the offset is relative to its first band
            // (cpldeltoffst refers to the coupling bands of section 7.4.2), as in the ffmpeg
            // reference decoder.
            int band = bndstrt;
            for (int seg = 0; seg < dbaNsegs; seg++) {
                band += dbaOffsets[seg];
                int delta = dbaValues[seg] >= 4 ? (dbaValues[seg] - 3) << 7 : (dbaValues[seg] - 4) << 7;
                if (band + dbaLengths[seg] > 50) {
                    // consistency checks 15/16 of section 7.10.2
                    throw new Ac3DecodeException("delta bit allocation exceeds 50 bands");
                }
                for (int k = 0; k < dbaLengths[seg]; k++) {
                    mask[band++] += delta;
                }
            }
        }
    }

    static int lowcomp(int a, int b0, int b1, int bin) {
        if (bin < 7) {
            if (b0 + 256 == b1) {
                a = 384;
            } else if (b0 > b1) {
                a = Math.max(0, a - 64);
            }
        } else if (bin < 20) {
            if (b0 + 256 == b1) {
                a = 320;
            } else if (b0 > b1) {
                a = Math.max(0, a - 64);
            }
        } else {
            a = Math.max(0, a - 128);
        }
        return a;
    }

    /** 7.2.2.7 compute bit allocation. The mask array is not modified. */
    static void bap(int[] mask, int[] psd, int start, int end, int snroffset, int floor, int[] bap) {
        int i = start;
        int j = MASKTAB[start];
        int lastbin;
        do {
            lastbin = Math.min(BAND_START[j + 1], end);
            int m = mask[j] - snroffset - floor;
            if (m < 0) {
                m = 0;
            }
            m &= 0x1fe0;
            m += floor;
            for (; i < lastbin; i++) {
                int address = (psd[i] - m) >> 5;
                address = Math.min(63, Math.max(0, address));
                bap[i] = BAPTAB[address];
            }
            j++;
        } while (end > lastbin);
    }
}
