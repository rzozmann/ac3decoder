package org.zoz.ac3.decoder.impl;

import static org.zoz.ac3.decoder.impl.Ac3Tables.B1_GROUPS;
import static org.zoz.ac3.decoder.impl.Ac3Tables.B2_GROUPS;
import static org.zoz.ac3.decoder.impl.Ac3Tables.B3_MANT;
import static org.zoz.ac3.decoder.impl.Ac3Tables.B4_GROUPS;
import static org.zoz.ac3.decoder.impl.Ac3Tables.B5_MANT;
import static org.zoz.ac3.decoder.impl.Ac3Tables.BLOCKS_PER_FRAME;
import static org.zoz.ac3.decoder.impl.Ac3Tables.QNTZTAB;
import static org.zoz.ac3.decoder.impl.Ac3Tables.SAMPLES_PER_BLOCK;

import java.util.Arrays;

import org.zoz.ac3.decoder.impl.Ac3DecoderOptions.DitherMode;
import org.zoz.ac3.decoder.impl.Ac3DecoderOptions.OutputMode;

/**
 * AC-3 syncframe decoder following ATSC A/52:2018 section 6 (decoding process) and section 7
 * (algorithmic details).
 *
 * <p>Decoding is performed in the following steps for every audio block (Figure 6.1):
 * unpack side information (5.4.3), decode exponents (7.1), parametric bit allocation (7.2),
 * unpack and dequantize mantissas (7.3), decoupling (7.4), rematrixing (7.5), dynamic range
 * compression (7.7), inverse transform with block switching, window and overlap/add (7.9) and
 * finally downmixing (7.8).</p>
 *
 * <p>Exponents, bit allocation, mantissa dequantization, decoupling and rematrixing are carried
 * out in 24 bit fixed point integer arithmetic ({@code 1.0 == 1 << 23}). This is the
 * representation the standard uses for its bit exact parts and it is identical to the one of
 * the ffmpeg reference decoder, which allows the integer stages to be compared bit by bit. The
 * transform is done in double precision floating point.</p>
 *
 * <p>Instances are not thread safe. One instance decodes one elementary stream; it keeps the
 * overlap/add state between consecutive syncframes.</p>
 */
public final class Ac3Decoder {

    /** Number of PCM samples per channel produced by one syncframe. */
    public static final int SAMPLES_PER_FRAME = BLOCKS_PER_FRAME * SAMPLES_PER_BLOCK;

    /** Maximum number of coded channels (5 fbw + lfe). */
    public static final int MAX_CHANNELS = 6;

    private static final int CPL = 6;
    private static final int NCH = 7;
    private static final double COEFF_SCALE = 1.0 / (1 << Ac3Tables.FRACTION_BITS);

    /** Channel roles, used for output ordering and downmixing. */
    static final int ROLE_L = 0;
    static final int ROLE_R = 1;
    static final int ROLE_C = 2;
    static final int ROLE_LFE = 3;
    static final int ROLE_LS = 4;
    static final int ROLE_RS = 5;
    static final int ROLE_S = 6;

    private final Ac3DecoderOptions options;
    private final DitherGenerator dither;
    private final Ac3Imdct imdct = new Ac3Imdct();

    // ---- frame configuration
    private Ac3FrameHeader header;
    private int acmod = -1;
    private int nfchans;
    private boolean lfeon;
    private int lfeCh;
    private int nchans;

    // ---- audio block state (persists across blocks of a frame)
    private final boolean[] blksw = new boolean[NCH];
    private final boolean[] dithflag = new boolean[NCH];
    private final double[] dynrngGain = {1.0, 1.0};

    private boolean cplinu;
    private final boolean[] chincpl = new boolean[NCH];
    private boolean phsflginu;
    private int cplBeginSubband;
    private int cplEndSubband;
    private int ncplbnd;
    private final int[] cplBandSizes = new int[18];
    private final int[][] cplco = new int[NCH][18];
    private final boolean[] phsflg = new boolean[18];

    private int nrematbd;
    private final boolean[] rematflg = new boolean[4];

    private final int[] expstr = new int[NCH];
    private final int[] strtmant = new int[NCH];
    private final int[] endmant = new int[NCH];
    private final int[][] exps = new int[NCH][256 + 4];

    private final Ac3BitAllocation.Params baParams = new Ac3BitAllocation.Params();
    private int csnroffst;
    private final int[] fsnroffst = new int[NCH];
    /** snroffset per channel (7.2.2.1), computed when the SNR offsets are transmitted. */
    private final int[] snroffset = new int[NCH];
    private final int[] fgain = new int[NCH];
    private final int[] dbaMode = new int[NCH];
    private final int[] dbaNsegs = new int[NCH];
    private final int[][] dbaOffsets = new int[NCH][8];
    private final int[][] dbaLengths = new int[NCH][8];
    private final int[][] dbaValues = new int[NCH][8];
    private final int[][] psd = new int[NCH][256];
    private final int[][] bndpsd = new int[NCH][50];
    private final int[][] mask = new int[NCH][50];
    private final int[][] bap = new int[NCH][256];

    // grouped mantissa state (section 7.3.5), reset for every block
    private int b1Pending;
    private int b2Pending;
    private int b4Pending;
    private final int[] b1Mant = new int[2];
    private final int[] b2Mant = new int[2];
    private int b4Mant;

    private final int[][] coeffs = new int[NCH][256];
    private final double[] transformCoeffs = new double[256];
    private final double[][] delay = new double[MAX_CHANNELS][256];
    /** Decoded PCM per coded channel (coded channel order of Table 5.8, then lfe). */
    private final float[][] pcm = new float[MAX_CHANNELS][SAMPLES_PER_FRAME];

    // ---- output
    private float[][] output = new float[0][];
    private int outputChannels;
    private boolean lastFrameValid;
    private String lastError;
    private long framesDecoded;
    private long framesMuted;
    private final Statistics statistics = new Statistics();
    /** Bit position (relative to the frame start) after each audio block of the last frame (tests). */
    final int[] blockEndBits = new int[BLOCKS_PER_FRAME];

    /** Counters of the coding tools seen in the decoded audio blocks (for diagnostics and tests). */
    public static final class Statistics {
        long blocks;
        long shortBlocks;
        long couplingBlocks;
        long phaseFlagBlocks;
        long rematrixBlocks;
        long deltaBitAllocationBlocks;
        long ditherChannelBlocks;
        long dynrngWords;

        public long getBlocks() {
            return blocks;
        }

        /** Channel blocks with blksw = 1 (two 256-sample transforms). */
        public long getShortBlocks() {
            return shortBlocks;
        }

        public long getCouplingBlocks() {
            return couplingBlocks;
        }

        public long getPhaseFlagBlocks() {
            return phaseFlagBlocks;
        }

        public long getRematrixBlocks() {
            return rematrixBlocks;
        }

        public long getDeltaBitAllocationBlocks() {
            return deltaBitAllocationBlocks;
        }

        public long getDitherChannelBlocks() {
            return ditherChannelBlocks;
        }

        public long getDynrngWords() {
            return dynrngWords;
        }

        @Override
        public String toString() {
            return "blocks=" + blocks + " shortChBlocks=" + shortBlocks + " cplBlocks=" + couplingBlocks
                + " phsflgBlocks=" + phaseFlagBlocks + " rematBlocks=" + rematrixBlocks + " dbaBlocks="
                + deltaBitAllocationBlocks + " ditherChBlocks=" + ditherChannelBlocks + " dynrng=" + dynrngWords;
        }
    }

    public Ac3Decoder() {
        this(Ac3DecoderOptions.defaults());
    }

    public Ac3Decoder(Ac3DecoderOptions options) {
        // fgain[] holds fastgain[] table values (Table 7.11); a channel whose fast gain code has
        // not been transmitted yet (e.g. coupling enabled in a block without snroffste) uses code 0
        Arrays.fill(fgain, Ac3Tables.FAST_GAIN[0]);
        this.options = options.copy();
        this.dither = switch (this.options.getDitherMode()) {
            case FFMPEG_COMPATIBLE -> DitherGenerator.reference();
            default -> DitherGenerator.xorshift(0x5EED1234);
        };
    }

    public Ac3DecoderOptions getOptions() {
        return options.copy();
    }

    /** Changes the output channel configuration; takes effect with the next decoded frame. */
    public void setOutputMode(OutputMode mode) {
        options.outputMode(mode);
    }

    /**
     * Decodes one complete syncframe.
     *
     * <p>If the syncframe fails the CRC check (if enabled) or turns out to be undecodable, the
     * output of this frame is muted (section 7.10: "Possible responses include muting") and the
     * frame is reported by {@link #isLastFrameValid()}.</p>
     *
     * @param buf buffer holding the syncframe
     * @param off offset of the sync word
     * @param len number of valid bytes from {@code off} (at least the frame size)
     * @return the header of the frame
     * @throws Ac3DecodeException if there is no decodable AC-3 header at {@code off} or the
     *     frame is truncated
     */
    public Ac3FrameHeader decodeFrame(byte[] buf, int off, int len) {
        Ac3FrameHeader h = Ac3FrameHeader.parse(buf, off, len);
        if (len < h.frameBytes) {
            throw new Ac3DecodeException("truncated syncframe: " + len + " of " + h.frameBytes + " bytes");
        }
        configure(h);
        framesDecoded++;
        lastError = null;
        boolean valid = true;
        if (options.isCrcCheck()) {
            if (!Crc16.checkCrc1(buf, off, h.frameBytes)) {
                valid = false;
                lastError = "crc1 mismatch";
            } else if (!Crc16.checkCrc2(buf, off, h.frameBytes)) {
                valid = false;
                lastError = "crc2 mismatch";
            }
        }
        if (valid) {
            try {
                BitReader br = new BitReader(buf, off, h.frameBytes);
                br.skip(h.audioBlockBitOffset);
                for (int blk = 0; blk < BLOCKS_PER_FRAME; blk++) {
                    decodeAudioBlock(br, blk);
                    blockEndBits[blk] = br.position() - off * 8;
                }
            } catch (Ac3DecodeException | ArrayIndexOutOfBoundsException e) {
                valid = false;
                lastError = e.getMessage();
            }
        }
        if (!valid) {
            framesMuted++;
            for (int ch = 0; ch < MAX_CHANNELS; ch++) {
                Arrays.fill(pcm[ch], 0f);
                Arrays.fill(delay[ch], 0.0);
            }
        }
        lastFrameValid = valid;
        produceOutput();
        return h;
    }

    /** True if the last frame decoded without errors (otherwise its output was muted). */
    public boolean isLastFrameValid() {
        return lastFrameValid;
    }

    /** Reason why the last frame was muted, or null. */
    public String getLastError() {
        return lastError;
    }

    public long getFramesDecoded() {
        return framesDecoded;
    }

    public long getFramesMuted() {
        return framesMuted;
    }

    public Statistics getStatistics() {
        return statistics;
    }

    /** Header of the last decoded frame. */
    public Ac3FrameHeader getHeader() {
        return header;
    }

    /** Number of channels of the last output (depends on the output mode and the frame). */
    public int getOutputChannels() {
        return outputChannels;
    }

    /**
     * Decoded PCM of the last frame, {@code [channel][sample]}, {@link #SAMPLES_PER_FRAME} samples per
     * channel, nominal range -1.0 .. +1.0 (not clipped). The arrays are reused by the next call.
     */
    public float[][] getOutput() {
        return output;
    }

    /**
     * Number of output channels the given header produces in the given output mode.
     */
    public static int outputChannelsFor(Ac3FrameHeader h, OutputMode mode) {
        return switch (mode) {
            case NATIVE -> h.getChannels();
            case STEREO, STEREO_LTRT -> 2;
            case MONO -> 1;
        };
    }

    /** Resets the decoder state (overlap buffers), e.g. after seeking. */
    public void reset() {
        for (double[] d : delay) {
            Arrays.fill(d, 0.0);
        }
        acmod = -1;
    }

    // ------------------------------------------------------------------------------------------

    private void configure(Ac3FrameHeader h) {
        if (h.acmod != acmod || h.lfeon != lfeon || header == null || header.sampleRate != h.sampleRate) {
            // channel configuration changed: overlap state no longer meaningful
            for (double[] d : delay) {
                Arrays.fill(d, 0.0);
            }
        }
        header = h;
        acmod = h.acmod;
        nfchans = h.nfchans;
        lfeon = h.lfeon;
        lfeCh = nfchans;
        nchans = nfchans + (lfeon ? 1 : 0);
        baParams.fscod = h.fscod;
        // LFE is never coupled, never block switched and has a fixed bandwidth
        if (lfeon) {
            chincpl[lfeCh] = false;
            blksw[lfeCh] = false;
            dithflag[lfeCh] = false;
            strtmant[lfeCh] = 0;
            endmant[lfeCh] = 7;
            dbaMode[lfeCh] = Ac3BitAllocation.DBA_NONE;
        }
    }

    /** Decodes audio block {@code blk} (syntax of Table 5.3). */
    private void decodeAudioBlock(BitReader br, int blk) {
        // ---- block switch and dither flags
        for (int ch = 0; ch < nfchans; ch++) {
            blksw[ch] = br.readBit();
        }
        for (int ch = 0; ch < nfchans; ch++) {
            dithflag[ch] = br.readBit();
        }

        // ---- dynamic range control (7.7.1)
        int ndynrng = acmod == Ac3FrameHeader.ACMOD_DUAL_MONO ? 2 : 1;
        for (int i = 0; i < ndynrng; i++) {
            if (br.readBit()) {
                statistics.dynrngWords++;
                dynrngGain[i] = dynrngToGain(br.read(8), options.getDynamicRangeScale());
            } else if (blk == 0) {
                dynrngGain[i] = 1.0; // '0000 0000' (0 dB) in block 0
            }
        }

        // ---- coupling strategy
        if (br.readBit()) {
            cplinu = br.readBit();
            if (cplinu) {
                if (acmod < Ac3FrameHeader.ACMOD_2_0) {
                    throw new Ac3DecodeException("coupling not allowed in 1/0 and 1+1 mode"); // 7.10.2 #22
                }
                for (int ch = 0; ch < nfchans; ch++) {
                    chincpl[ch] = br.readBit();
                }
                phsflginu = acmod == Ac3FrameHeader.ACMOD_2_0 && br.readBit();
                cplBeginSubband = br.read(4);
                cplEndSubband = br.read(4) + 3;
                if (cplBeginSubband >= cplEndSubband) {
                    throw new Ac3DecodeException("cplbegf > cplendf + 2"); // 7.10.2 #3
                }
                ncplbnd = 0;
                for (int sbnd = cplBeginSubband; sbnd < cplEndSubband; sbnd++) {
                    boolean merge = sbnd > cplBeginSubband && br.readBit(); // cplbndstrc[sbnd]
                    if (merge) {
                        cplBandSizes[ncplbnd - 1] += 12;
                    } else {
                        cplBandSizes[ncplbnd++] = 12;
                    }
                }
                strtmant[CPL] = cplBeginSubband * 12 + 37;
                endmant[CPL] = cplEndSubband * 12 + 37;
            } else {
                for (int ch = 0; ch < nfchans; ch++) {
                    chincpl[ch] = false;
                }
                phsflginu = false;
            }
        } else if (blk == 0) {
            throw new Ac3DecodeException("cplstre == 0 in block 0"); // 7.10.2 #1
        }

        // ---- coupling coordinates and phase flags (7.4.3)
        if (cplinu) {
            boolean coordsExist = false;
            for (int ch = 0; ch < nfchans; ch++) {
                if (!chincpl[ch]) {
                    continue;
                }
                if (br.readBit()) { // cplcoe[ch]
                    coordsExist = true;
                    int mstrcplco = 3 * br.read(2);
                    for (int bnd = 0; bnd < ncplbnd; bnd++) {
                        int cplcoexp = br.read(4);
                        int cplcomant = br.read(4);
                        // cplco_temp in 2^26 fixed point: mant/16 or (mant+16)/32
                        int c = cplcoexp == 15 ? cplcomant << 22 : (cplcomant + 16) << 21;
                        cplco[ch][bnd] = c >> (cplcoexp + mstrcplco);
                    }
                } else if (blk == 0) {
                    throw new Ac3DecodeException("coupling coordinates missing in block 0"); // 7.10.2 #4
                }
            }
            if (acmod == Ac3FrameHeader.ACMOD_2_0 && coordsExist) {
                for (int bnd = 0; bnd < ncplbnd; bnd++) {
                    phsflg[bnd] = phsflginu && br.readBit();
                }
            }
        }

        // ---- rematrixing (7.5)
        if (acmod == Ac3FrameHeader.ACMOD_2_0) {
            if (br.readBit()) { // rematstr
                nrematbd = 4; // Table 5.15
                if (cplinu && cplBeginSubband <= 2) {
                    nrematbd = cplBeginSubband == 0 ? 2 : 3;
                }
                for (int rbnd = 0; rbnd < nrematbd; rbnd++) {
                    rematflg[rbnd] = br.readBit();
                }
            } else if (blk == 0) {
                throw new Ac3DecodeException("rematstr == 0 in block 0"); // 7.10.2 #5
            }
        }

        // ---- exponent strategies (7.1.2) and channel bandwidth (7.1.3)
        if (cplinu) {
            expstr[CPL] = br.read(2);
        }
        for (int ch = 0; ch < nfchans; ch++) {
            expstr[ch] = br.read(2);
        }
        if (lfeon) {
            expstr[lfeCh] = br.read(1); // '1' = D15
        }
        if (blk == 0) {
            for (int ch = 0; ch < nchans; ch++) {
                if (expstr[ch] == 0) {
                    throw new Ac3DecodeException("exponent reuse in block 0"); // 7.10.2 #8, #10
                }
            }
            if (cplinu && expstr[CPL] == 0) {
                throw new Ac3DecodeException("coupling exponent reuse in block 0"); // 7.10.2 #6
            }
        }
        for (int ch = 0; ch < nfchans; ch++) {
            if (expstr[ch] != 0) {
                strtmant[ch] = 0;
                if (chincpl[ch]) {
                    endmant[ch] = strtmant[CPL];
                } else {
                    int chbwcod = br.read(6);
                    if (chbwcod > 60) {
                        throw new Ac3DecodeException("chbwcod > 60"); // 5.4.3.24, 7.10.2 #11
                    }
                    endmant[ch] = (chbwcod + 12) * 3 + 37;
                }
            }
        }

        // ---- exponents (7.1.3)
        if (cplinu && expstr[CPL] != 0) {
            int absexp = br.read(4) << 1; // cplabsexp
            int grpsize = groupSize(expstr[CPL]);
            int ngrps = (endmant[CPL] - strtmant[CPL]) / (3 * grpsize);
            decodeExponents(br, absexp, ngrps, grpsize, exps[CPL], strtmant[CPL]);
        }
        for (int ch = 0; ch < nfchans; ch++) {
            if (expstr[ch] != 0) {
                int absexp = br.read(4);
                int grpsize = groupSize(expstr[ch]);
                int ngrps = (endmant[ch] - 1 + 3 * grpsize - 3) / (3 * grpsize);
                exps[ch][0] = absexp;
                decodeExponents(br, absexp, ngrps, grpsize, exps[ch], 1);
                br.skip(2); // gainrng[ch]
            }
        }
        if (lfeon && expstr[lfeCh] != 0) {
            int absexp = br.read(4);
            exps[lfeCh][0] = absexp;
            decodeExponents(br, absexp, 2, 1, exps[lfeCh], 1); // nlfegrps = 2
        }

        // ---- bit allocation parametric information
        if (br.readBit()) { // baie
            baParams.sdecay = Ac3Tables.SLOW_DECAY[br.read(2)];
            baParams.fdecay = Ac3Tables.FAST_DECAY[br.read(2)];
            baParams.sgain = Ac3Tables.SLOW_GAIN[br.read(2)];
            baParams.dbknee = Ac3Tables.DB_PER_BIT[br.read(2)];
            baParams.floor = Ac3Tables.FLOOR[br.read(3)];
        } else if (blk == 0) {
            throw new Ac3DecodeException("baie == 0 in block 0"); // 7.10.2 #12
        }
        if (br.readBit()) { // snroffste
            csnroffst = br.read(6);
            if (cplinu) {
                fsnroffst[CPL] = br.read(4);
                fgain[CPL] = Ac3Tables.FAST_GAIN[br.read(3)];
                snroffset[CPL] = (((csnroffst - 15) << 4) + fsnroffst[CPL]) << 2;
            }
            for (int ch = 0; ch < nfchans; ch++) {
                fsnroffst[ch] = br.read(4);
                fgain[ch] = Ac3Tables.FAST_GAIN[br.read(3)];
                snroffset[ch] = (((csnroffst - 15) << 4) + fsnroffst[ch]) << 2;
            }
            if (lfeon) {
                fsnroffst[lfeCh] = br.read(4);
                fgain[lfeCh] = Ac3Tables.FAST_GAIN[br.read(3)];
                snroffset[lfeCh] = (((csnroffst - 15) << 4) + fsnroffst[lfeCh]) << 2;
            }
        } else if (blk == 0) {
            throw new Ac3DecodeException("snroffste == 0 in block 0"); // 7.10.2 #13
        }
        if (cplinu) {
            if (br.readBit()) { // cplleake
                baParams.cplFastLeak = br.read(3);
                baParams.cplSlowLeak = br.read(3);
            } else if (blk == 0) {
                throw new Ac3DecodeException("cplleake == 0 in block 0"); // 7.10.2 #14
            }
        }

        // ---- delta bit allocation (7.2.2.6)
        if (br.readBit()) { // deltbaie
            if (cplinu) {
                dbaMode[CPL] = br.read(2);
            }
            for (int ch = 0; ch < nfchans; ch++) {
                dbaMode[ch] = br.read(2);
            }
            if (cplinu && dbaMode[CPL] == Ac3BitAllocation.DBA_RESERVED) {
                throw new Ac3DecodeException("reserved cpldeltbae");
            }
            for (int ch = 0; ch < nfchans; ch++) {
                if (dbaMode[ch] == Ac3BitAllocation.DBA_RESERVED) {
                    throw new Ac3DecodeException("reserved deltbae");
                }
            }
            if (cplinu && dbaMode[CPL] == Ac3BitAllocation.DBA_NEW) {
                readDeltaBitAllocation(br, CPL);
            }
            for (int ch = 0; ch < nfchans; ch++) {
                if (dbaMode[ch] == Ac3BitAllocation.DBA_NEW) {
                    readDeltaBitAllocation(br, ch);
                }
            }
        } else if (blk == 0) {
            // 5.4.3.47: cpldeltbae and deltbae[ch] are set to '10' (perform no delta allocation)
            Arrays.fill(dbaMode, Ac3BitAllocation.DBA_NONE);
        }

        // ---- skip field
        if (br.readBit()) { // skiple
            br.skip(br.read(9) * 8);
        }

        // ---- statistics
        statistics.blocks++;
        for (int ch = 0; ch < nfchans; ch++) {
            if (blksw[ch]) {
                statistics.shortBlocks++;
            }
            if (dithflag[ch]) {
                statistics.ditherChannelBlocks++;
            }
        }
        if (cplinu) {
            statistics.couplingBlocks++;
            if (phsflginu) {
                statistics.phaseFlagBlocks++;
            }
        }
        if (acmod == Ac3FrameHeader.ACMOD_2_0) {
            for (int rbnd = 0; rbnd < nrematbd; rbnd++) {
                if (rematflg[rbnd]) {
                    statistics.rematrixBlocks++;
                    break;
                }
            }
        }
        for (int ch = 0; ch < nfchans; ch++) {
            if (dbaMode[ch] == Ac3BitAllocation.DBA_NEW || dbaMode[ch] == Ac3BitAllocation.DBA_REUSE
                || (cplinu && (dbaMode[CPL] == Ac3BitAllocation.DBA_NEW || dbaMode[CPL] == Ac3BitAllocation.DBA_REUSE))) {
                statistics.deltaBitAllocationBlocks++;
                break;
            }
        }

        // ---- bit allocation (7.2.2)
        computeBitAllocation();

        // ---- mantissas (7.3)
        b1Pending = 0;
        b2Pending = 0;
        b4Pending = 0;
        DitherMode ditherMode = options.getDitherMode();
        boolean gotCpl = false;
        for (int ch = 0; ch < nchans; ch++) {
            Arrays.fill(coeffs[ch], 0);
        }
        for (int ch = 0; ch < nfchans; ch++) {
            decodeMantissas(br, ch, 0, endmant[ch], ditherMode != DitherMode.NONE && dithflag[ch]);
            if (cplinu && chincpl[ch] && !gotCpl) {
                Arrays.fill(coeffs[CPL], 0);
                // the reference decoder always dithers the coupling channel and removes the
                // dither again for channels with dithflag == 0 (see uncouple())
                decodeMantissas(br, CPL, strtmant[CPL], endmant[CPL], ditherMode == DitherMode.FFMPEG_COMPATIBLE);
                gotCpl = true;
            }
        }
        if (lfeon) {
            // the lfe channel carries no dithflag; it is reconstructed without dither
            decodeMantissas(br, lfeCh, 0, 7, false);
        }

        // ---- decoupling (7.4), rematrixing (7.5)
        if (cplinu) {
            uncouple(ditherMode);
        }
        if (acmod == Ac3FrameHeader.ACMOD_2_0) {
            rematrix();
        }

        // ---- dynamic range compression, inverse transform, window and overlap/add (7.7, 7.9)
        for (int ch = 0; ch < nchans; ch++) {
            double gain = channelGain(ch) * COEFF_SCALE;
            int[] c = coeffs[ch];
            for (int bin = 0; bin < 256; bin++) {
                transformCoeffs[bin] = c[bin] * gain;
            }
            imdct.transform(transformCoeffs, blksw[ch], delay[ch], pcm[ch], blk * SAMPLES_PER_BLOCK);
        }
    }

    private static int groupSize(int strategy) {
        return strategy == 3 ? 4 : strategy; // D15: 1, D25: 2, D45: 4
    }

    /** Exponent decoding pseudo code of section 7.1.3. */
    private static void decodeExponents(BitReader br, int absexp, int ngrps, int grpsize, int[] exp, int first) {
        int prevexp = absexp;
        int j = first;
        for (int grp = 0; grp < ngrps; grp++) {
            int gexp = br.read(7);
            if (gexp > 124) {
                throw new Ac3DecodeException("grouped exponent > 124"); // 7.10.2 #17
            }
            int m1 = gexp / 25;
            int m2 = (gexp % 25) / 5;
            int m3 = (gexp % 25) % 5;
            for (int dexp : new int[] {m1 - 2, m2 - 2, m3 - 2}) {
                prevexp += dexp;
                if (prevexp < 0 || prevexp > 24) {
                    throw new Ac3DecodeException("exponent out of range");
                }
                for (int k = 0; k < grpsize; k++) {
                    exp[j++] = prevexp;
                }
            }
        }
    }

    private void readDeltaBitAllocation(BitReader br, int ch) {
        dbaNsegs[ch] = br.read(3) + 1;
        for (int seg = 0; seg < dbaNsegs[ch]; seg++) {
            dbaOffsets[ch][seg] = br.read(5);
            dbaLengths[ch][seg] = br.read(4);
            dbaValues[ch][seg] = br.read(3);
        }
    }

    private void computeBitAllocation() {
        // 7.2.2.1.1 special case: all SNR offsets zero -> all bap zero
        boolean allZero = csnroffst == 0;
        if (allZero) {
            if (cplinu && fsnroffst[CPL] != 0) {
                allZero = false;
            }
            for (int ch = 0; ch < nchans; ch++) {
                if (fsnroffst[ch] != 0) {
                    allZero = false;
                }
            }
        }
        if (cplinu) {
            allocate(CPL, allZero);
        }
        for (int ch = 0; ch < nchans; ch++) {
            allocate(ch, allZero);
        }
    }

    private void allocate(int ch, boolean allZero) {
        int start = strtmant[ch];
        int end = endmant[ch];
        if (end <= start) {
            return;
        }
        if (allZero) {
            Arrays.fill(bap[ch], start, end, 0);
            return;
        }
        Ac3BitAllocation.psd(exps[ch], start, end, psd[ch], bndpsd[ch]);
        Ac3BitAllocation.mask(baParams, bndpsd[ch], start, end, fgain[ch], ch == CPL, lfeon && ch == lfeCh,
            dbaMode[ch], dbaNsegs[ch], dbaOffsets[ch], dbaLengths[ch], dbaValues[ch], mask[ch]);
        Ac3BitAllocation.bap(mask[ch], psd[ch], start, end, snroffset[ch], baParams.floor, bap[ch]);
    }

    /** Unpacks and dequantizes the mantissas of one channel (7.3.2 - 7.3.5). */
    private void decodeMantissas(BitReader br, int ch, int start, int end, boolean ditherZeroBits) {
        int[] b = bap[ch];
        int[] e = exps[ch];
        int[] c = coeffs[ch];
        for (int bin = start; bin < end; bin++) {
            int mantissa;
            switch (b[bin]) {
                case 0:
                    mantissa = ditherZeroBits ? dither.nextMantissa() : 0;
                    break;
                case 1:
                    if (b1Pending > 0) {
                        mantissa = b1Mant[--b1Pending];
                    } else {
                        int[] g = B1_GROUPS[br.read(5)];
                        mantissa = g[0];
                        b1Mant[1] = g[1];
                        b1Mant[0] = g[2];
                        b1Pending = 2;
                    }
                    break;
                case 2:
                    if (b2Pending > 0) {
                        mantissa = b2Mant[--b2Pending];
                    } else {
                        int[] g = B2_GROUPS[br.read(7)];
                        mantissa = g[0];
                        b2Mant[1] = g[1];
                        b2Mant[0] = g[2];
                        b2Pending = 2;
                    }
                    break;
                case 3:
                    mantissa = B3_MANT[br.read(3)];
                    break;
                case 4:
                    if (b4Pending > 0) {
                        b4Pending = 0;
                        mantissa = b4Mant;
                    } else {
                        int[] g = B4_GROUPS[br.read(7)];
                        mantissa = g[0];
                        b4Mant = g[1];
                        b4Pending = 1;
                    }
                    break;
                case 5:
                    mantissa = B5_MANT[br.read(4)];
                    break;
                default: {
                    // asymmetric quantization: two's complement fraction of qntztab[bap] bits
                    int q = QNTZTAB[b[bin]];
                    mantissa = br.readSigned(q) << (24 - q);
                    break;
                }
            }
            c[bin] = mantissa >> e[bin];
        }
    }

    /** Reconstruction of the coupled channels, section 7.4.3: chmant = cplmant * cplco * 8. */
    private void uncouple(DitherMode ditherMode) {
        int[] cpl = coeffs[CPL];
        for (int ch = 0; ch < nfchans; ch++) {
            if (!chincpl[ch]) {
                continue;
            }
            int[] c = coeffs[ch];
            boolean ditherThis = dithflag[ch];
            int bin = strtmant[CPL];
            for (int bnd = 0; bnd < ncplbnd; bnd++) {
                // cplco is held in 2^26 fixed point: cplmant * cplco * 8 == (cplmant * cplco) >> 23
                long coord = cplco[ch][bnd];
                int bandEnd = bin + cplBandSizes[bnd];
                boolean negate = acmod == Ac3FrameHeader.ACMOD_2_0 && ch == 1 && phsflg[bnd];
                for (; bin < bandEnd; bin++) {
                    int m;
                    if (bap[CPL][bin] == 0) {
                        // zero bit mantissa: dither (7.3.4) or zero
                        m = switch (ditherMode) {
                            // dither generated per channel after decoupling -> uncorrelated
                            case STANDARD -> ditherThis ? dither.nextMantissa() >> exps[CPL][bin] : 0;
                            // coupling channel already dithered; removed for dithflag == 0
                            case FFMPEG_COMPATIBLE -> ditherThis ? cpl[bin] : 0;
                            case NONE -> 0;
                        };
                    } else {
                        m = cpl[bin];
                    }
                    int v = (int) ((m * coord) >> 23);
                    c[bin] = negate ? -v : v;
                }
            }
        }
    }

    /** Section 7.5.4. */
    private void rematrix() {
        int end = Math.min(endmant[0], endmant[1]);
        int[] left = coeffs[0];
        int[] right = coeffs[1];
        for (int bnd = 0; bnd < nrematbd; bnd++) {
            if (!rematflg[bnd]) {
                continue;
            }
            int bandEnd = Math.min(end, Ac3Tables.REMATRIX_BANDS[bnd + 1]);
            for (int i = Ac3Tables.REMATRIX_BANDS[bnd]; i < bandEnd; i++) {
                int l = left[i];
                left[i] = l + right[i];
                right[i] = l - right[i];
            }
        }
    }

    /** Gain applied to channel {@code ch}: dynrng (7.7.1) or compr (7.7.2). */
    private double channelGain(int ch) {
        int program = (acmod == Ac3FrameHeader.ACMOD_DUAL_MONO && ch == 1) ? 1 : 0;
        if (acmod == Ac3FrameHeader.ACMOD_DUAL_MONO && lfeon && ch == lfeCh
            && options.getDitherMode() == DitherMode.FFMPEG_COMPATIBLE) {
            // Not defined by A/52 (dynrng applies to Ch1, dynrng2 to Ch2). The reference decoder
            // applies dynrng2 to the lfe channel of a 1+1 stream; follow it in compatibility mode.
            program = 1;
        }
        if (options.isHeavyCompression()) {
            int compr = program == 0 ? header.compr : header.compr2;
            if (compr >= 0) {
                return comprToGain(compr);
            }
        }
        return dynrngGain[program];
    }

    /**
     * dynrng to linear gain (Table 7.29): X0X1X2.Y3..Y7, gain = 2^(X+1) * 0.1Y (base 2), optionally
     * reduced to a fraction of the compression ("partial compression").
     */
    static double dynrngToGain(int dynrng, double scale) {
        int x = (dynrng >> 5) - ((dynrng & 0x80) != 0 ? 8 : 0);
        double gain = Math.scalb((32 + (dynrng & 0x1f)) / 64.0, x + 1);
        return scale == 1.0 ? gain : Math.pow(gain, scale);
    }

    /** compr to linear gain (Table 7.30): X0..X3.Y4..Y7, gain = 2^(X+1) * 0.1Y (base 2). */
    static double comprToGain(int compr) {
        int x = (compr >> 4) - ((compr & 0x80) != 0 ? 16 : 0);
        return Math.scalb((16 + (compr & 0x0f)) / 32.0, x + 1);
    }

    // ---- output mapping and downmix (7.8) ----------------------------------------------------

    /** Roles of the coded channels of the current frame, in coded order (Table 5.8, then lfe). */
    int[] codedRoles() {
        int[] fbw = switch (acmod) {
            case Ac3FrameHeader.ACMOD_DUAL_MONO -> new int[] {ROLE_L, ROLE_R};
            case Ac3FrameHeader.ACMOD_1_0 -> new int[] {ROLE_C};
            case Ac3FrameHeader.ACMOD_2_0 -> new int[] {ROLE_L, ROLE_R};
            case Ac3FrameHeader.ACMOD_3_0 -> new int[] {ROLE_L, ROLE_C, ROLE_R};
            case Ac3FrameHeader.ACMOD_2_1 -> new int[] {ROLE_L, ROLE_R, ROLE_S};
            case Ac3FrameHeader.ACMOD_3_1 -> new int[] {ROLE_L, ROLE_C, ROLE_R, ROLE_S};
            case Ac3FrameHeader.ACMOD_2_2 -> new int[] {ROLE_L, ROLE_R, ROLE_LS, ROLE_RS};
            default -> new int[] {ROLE_L, ROLE_C, ROLE_R, ROLE_LS, ROLE_RS};
        };
        int[] roles = Arrays.copyOf(fbw, nchans);
        if (lfeon) {
            roles[lfeCh] = ROLE_LFE;
        }
        return roles;
    }

    /**
     * Native output order: L, R, C, LFE, then surround channels (the order of WAVE files and of
     * the reference decoder). Returns for every coded channel the output channel index.
     */
    static int[] nativeOrder(int[] roles) {
        int[] rank = new int[roles.length];
        for (int i = 0; i < roles.length; i++) {
            rank[i] = switch (roles[i]) {
                case ROLE_L -> 0;
                case ROLE_R -> 1;
                case ROLE_C -> 2;
                case ROLE_LFE -> 3;
                case ROLE_S -> 4;
                case ROLE_LS -> 5;
                default -> 6;
            };
        }
        int[] out = new int[roles.length];
        for (int i = 0; i < roles.length; i++) {
            int pos = 0;
            for (int j = 0; j < roles.length; j++) {
                if (rank[j] < rank[i]) {
                    pos++;
                }
            }
            out[i] = pos;
        }
        return out;
    }

    /**
     * Downmix coefficients {@code [out][coded]} for the stereo / mono output modes
     * (sections 7.8.1 and 7.8.2), normalized so that the sum of the absolute coefficients of
     * any output channel does not exceed 1.
     */
    double[][] downmixMatrix(OutputMode mode) {
        int[] roles = codedRoles();
        int n = roles.length;
        double clev;
        double slev;
        boolean ltrt = mode == OutputMode.STEREO_LTRT;
        boolean xbsi = header.bsid == 6 && header.xbsi1e;
        if (ltrt) {
            clev = xbsi ? Ac3Tables.XBSI_CENTER_MIX_LEVEL[header.ltrtcmixlev] : Ac3Tables.db(-3.0);
            slev = xbsi ? Ac3Tables.XBSI_SURROUND_MIX_LEVEL[header.ltrtsurmixlev] : Ac3Tables.db(-3.0);
        } else {
            clev = xbsi ? Ac3Tables.XBSI_CENTER_MIX_LEVEL[header.lorocmixlev]
                : header.cmixlev >= 0 ? Ac3Tables.CENTER_MIX_LEVEL[header.cmixlev] : Ac3Tables.db(-3.0);
            slev = xbsi ? Ac3Tables.XBSI_SURROUND_MIX_LEVEL[header.lorosurmixlev]
                : header.surmixlev >= 0 ? Ac3Tables.SURROUND_MIX_LEVEL[header.surmixlev] : Ac3Tables.db(-3.0);
        }
        double minus3 = Ac3Tables.db(-3.0);
        double[][] m = new double[2][n];
        for (int i = 0; i < n; i++) {
            switch (roles[i]) {
                case ROLE_L -> m[0][i] = 1.0;
                case ROLE_R -> m[1][i] = 1.0;
                case ROLE_C -> {
                    // a single (mono) center is mixed with -3 dB, three front channels use clev
                    double g = acmod == Ac3FrameHeader.ACMOD_1_0 ? minus3 : clev;
                    m[0][i] = g;
                    m[1][i] = g;
                }
                case ROLE_S -> {
                    double g = ltrt ? slev : slev * minus3;
                    m[0][i] = ltrt ? -g : g;
                    m[1][i] = g;
                }
                case ROLE_LS -> {
                    m[0][i] = ltrt ? -slev : slev;
                    if (ltrt) {
                        m[1][i] = slev;
                    }
                }
                case ROLE_RS -> {
                    if (ltrt) {
                        m[0][i] = -slev;
                    }
                    m[1][i] = slev;
                }
                default -> {
                    // LFE: downmixing of the lfe channel is optional and not done here
                }
            }
        }
        if (mode == OutputMode.MONO) {
            double[][] mono = new double[1][n];
            for (int i = 0; i < n; i++) {
                mono[0][i] = acmod == Ac3FrameHeader.ACMOD_1_0 && roles[i] == ROLE_C ? 1.0 : m[0][i] + m[1][i];
            }
            m = mono;
        }
        for (double[] row : m) {
            double sum = 0;
            for (double v : row) {
                sum += Math.abs(v);
            }
            if (sum > 1.0) {
                for (int i = 0; i < n; i++) {
                    row[i] /= sum;
                }
            }
        }
        return m;
    }

    private void produceOutput() {
        OutputMode mode = options.getOutputMode();
        int outCh = outputChannelsFor(header, mode);
        if (output.length != outCh) {
            output = new float[outCh][SAMPLES_PER_FRAME];
        }
        outputChannels = outCh;
        if (mode == OutputMode.NATIVE || (mode == OutputMode.STEREO && nchans == 2 && !lfeon)) {
            int[] order = nativeOrder(codedRoles());
            for (int ch = 0; ch < nchans; ch++) {
                System.arraycopy(pcm[ch], 0, output[order[ch]], 0, SAMPLES_PER_FRAME);
            }
            return;
        }
        double[][] m = downmixMatrix(mode);
        for (int o = 0; o < outCh; o++) {
            float[] dst = output[o];
            Arrays.fill(dst, 0f);
            for (int ch = 0; ch < nchans; ch++) {
                float g = (float) m[o][ch];
                if (g == 0f) {
                    continue;
                }
                float[] src = pcm[ch];
                for (int i = 0; i < SAMPLES_PER_FRAME; i++) {
                    dst[i] += g * src[i];
                }
            }
        }
    }
}
