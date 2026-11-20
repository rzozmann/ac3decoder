package org.zoz.ac3.decoder.impl;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;

/**
 * Writes syntactically valid AC-3 syncframes (A/52 section 5.3) with random content that use
 * coding tools the available reference files and the ffmpeg encoder never produce: block
 * switching (blksw), dynamic range words (dynrng), delta bit allocation, phase flags,
 * coupling band structures, all rematrixing band counts, D25/D45 exponent strategies, skip
 * fields, exponent reuse across blocks, and the dual mono mode.
 *
 * <p>The content is random (it is not encoded audio), but every syncframe is a valid AC-3
 * syncframe with correct CRCs that any compliant decoder decodes deterministically. Decoding
 * the stream with ffmpeg and with this decoder and comparing the output therefore verifies
 * those parts of the bit stream syntax and decoding process.</p>
 */
final class SyntheticStreamWriter {

    private final Random rnd;
    private final int acmod;
    private final boolean lfeon;
    private final int fscod;
    private final int frmsizecod;
    private final int nfchans;

    SyntheticStreamWriter(long seed, int acmod, boolean lfeon, int fscod, int frmsizecod) {
        this.rnd = new Random(seed);
        this.acmod = acmod;
        this.lfeon = lfeon;
        this.fscod = fscod;
        this.frmsizecod = frmsizecod;
        this.nfchans = Ac3Tables.NFCHANS[acmod];
    }

    /**
     * Writes {@code frames} syncframes.
     *
     * <p>The writer chooses random syntax elements. A candidate frame is only accepted if it fits
     * into the frame size and if it satisfies the bit stream consistency rules of section 7.10.2
     * that the random choices may violate (e.g. reusing exponents or delta bit allocation after a
     * bandwidth change). This is checked by parsing the candidate with the decoder: the decoder
     * must not report an error and must end every audio block exactly where the writer ended it.
     * Note that this only validates the syntax - the decoded output is verified against ffmpeg.</p>
     */
    byte[] write(int frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int f = 0; f < frames; f++) {
            byte[] frame = null;
            // retry with other random choices until the frame is valid
            for (int attempt = 0; attempt < 500 && frame == null; attempt++) {
                byte[] candidate;
                try {
                    candidate = tryFrame(attempt % 200);
                } catch (Ac3DecodeException e) {
                    candidate = null; // e.g. delta bit allocation exceeding 50 bands
                }
                if (candidate != null && consistent(out.toByteArray(), candidate)) {
                    frame = candidate;
                }
            }
            if (frame == null) {
                throw new IllegalStateException("could not build frame " + f);
            }
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    /**
     * A candidate is accepted if it decodes without error both on its own and after the frames
     * accepted so far, ending every block at the writer's bit position in both cases (a valid
     * syncframe must not depend on the state left by previous syncframes).
     */
    private boolean consistent(byte[] prefix, byte[] frame) {
        Ac3DecoderOptions opts = Ac3DecoderOptions.defaults().ditherMode(Ac3DecoderOptions.DitherMode.NONE);
        Ac3Decoder alone = new Ac3Decoder(opts);
        alone.decodeFrame(frame, 0, frame.length);
        if (!alone.isLastFrameValid() || !Arrays.equals(alone.blockEndBits, lastBlockEndBits)) {
            return false;
        }
        Ac3Decoder inStream = new Ac3Decoder(opts);
        int pos = 0;
        while (pos < prefix.length) {
            pos += inStream.decodeFrame(prefix, pos, prefix.length - pos).getFrameBytes();
        }
        inStream.decodeFrame(frame, 0, frame.length);
        return inStream.isLastFrameValid() && Arrays.equals(inStream.blockEndBits, lastBlockEndBits);
    }

    // ---------------------------------------------------------------------------------------

    static final class BitWriter {
        final byte[] buf;
        int pos;

        BitWriter(int bytes) {
            buf = new byte[bytes];
        }

        void put(int nbits, int value) {
            for (int i = nbits - 1; i >= 0; i--) {
                if (pos >= buf.length * 8) {
                    throw new IndexOutOfBoundsException("frame overflow");
                }
                if (((value >>> i) & 1) != 0) {
                    buf[pos >> 3] |= (byte) (0x80 >>> (pos & 7));
                }
                pos++;
            }
        }

        void bit(boolean b) {
            put(1, b ? 1 : 0);
        }
    }

    /** Coding tools disabled through the system property {@code ac3.synthetic.disable} (comma list). */
    private java.util.Set<String> disabled = java.util.Set.of(
        System.getProperty("ac3.synthetic.disable", "").split(","));

    /** Disables coding tools ("blksw", "dith", "dynrng", "cpl", "phs", "dba", "skip", "expreuse"). */
    SyntheticStreamWriter disable(String... tools) {
        disabled = java.util.Set.of(tools);
        return this;
    }

    private boolean on(String tool) {
        return !disabled.contains(tool);
    }

    /** Bit positions after each block of the last successfully built frame (diagnostics). */
    final int[] lastBlockEndBits = new int[6];

    /** Block state reused across blocks (as in the decoder). */
    private final int[][] exps = new int[7][256];
    private final int[] endmant = new int[7];
    private final int[] strtmant = new int[7];

    private byte[] tryFrame(int attempt) {
        int words = Ac3Tables.FRAME_SIZE_WORDS[frmsizecod >> 1][fscod] + (fscod == 1 && (frmsizecod & 1) != 0 ? 1 : 0);
        int frameBytes = words * 2;
        BitWriter w = new BitWriter(frameBytes);
        try {
            // syncinfo
            w.put(16, 0x0B77);
            w.put(16, 0); // crc1, filled in later
            w.put(2, fscod);
            w.put(6, frmsizecod);
            // bsi
            w.put(5, 8);
            w.put(3, 0);
            w.put(3, acmod);
            if ((acmod & 1) != 0 && acmod != 1) {
                w.put(2, rnd.nextInt(3));
            }
            if ((acmod & 4) != 0) {
                w.put(2, rnd.nextInt(3));
            }
            if (acmod == 2) {
                w.put(2, 0);
            }
            w.bit(lfeon);
            w.put(5, 27); // dialnorm
            w.bit(false); // compre
            w.bit(false); // langcode
            w.bit(false); // audprodie
            if (acmod == 0) {
                w.put(5, 27);
                w.bit(false);
                w.bit(false);
                w.bit(false);
            }
            w.bit(false); // copyrightb
            w.bit(true); // origbs
            w.bit(false); // timecod1e
            w.bit(false); // timecod2e
            w.bit(true); // addbsie
            w.put(6, 1);
            w.put(16, 0xABCD);
            // a lower SNR offset for later attempts makes the frame smaller. csnroffst = 0 is
            // avoided: section 7.2.2.1.1 zeroes the allocation only if *all* SNR offsets are 0,
            // whereas ffmpeg zeroes it per channel (csnroffst == 0 && fsnroffst == 0).
            int csnr = Math.max(1, 30 - attempt / 4 - rnd.nextInt(6));
            for (int blk = 0; blk < 6; blk++) {
                writeBlock(w, blk, csnr);
                lastBlockEndBits[blk] = w.pos;
            }
            // auxdata: remaining bits zero, auxdatae = 0 must be the bit before errorcheck
            int auxdataeBit = frameBytes * 8 - 17 - 1;
            if (w.pos > auxdataeBit) {
                return null;
            }
            w.pos = auxdataeBit;
            w.put(1, 0); // auxdatae
            w.put(1, 0); // crcrsv
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
        byte[] frame = w.buf;
        // crc1: register must be zero after the first 5/8 of the frame (section 7.10.1)
        int fiveEighths = ((words >> 1) + (words >> 3)) * 2;
        setCrc(frame, 2, 2, fiveEighths);
        // crc2: last word, covers the whole frame except the sync word
        setCrc(frame, frameBytes - 2, 2, frameBytes);
        if (!Crc16.checkCrc1(frame, 0, frameBytes) || !Crc16.checkCrc2(frame, 0, frameBytes)) {
            return null;
        }
        return frame;
    }

    /**
     * Sets the 16 bit word at {@code crcPos} such that the CRC register is zero after shifting in
     * bytes {@code from .. to} (the CRC is linear: solve for the 16 unknown bits).
     */
    static void setCrc(byte[] frame, int crcPos, int from, int to) {
        frame[crcPos] = 0;
        frame[crcPos + 1] = 0;
        int base = Crc16.compute(frame, from, to - from);
        int[] basis = new int[16];
        for (int b = 0; b < 16; b++) {
            // CRC (from a zero register) of a frame that only has bit b of the CRC word set
            byte[] e = new byte[frame.length];
            e[crcPos + (b >> 3)] = (byte) (0x80 >>> (b & 7));
            basis[b] = Crc16.compute(e, from, to - from);
        }
        // The register after shifting is linear in the 16 CRC word bits x_b:
        //   reg(x) = base XOR sum_b x_b * basis[b]
        // Solve reg(x) = 0, i.e. sum_b x_b * basis[b] = base, over GF(2).
        // Build a reduced basis: pivot[bit] = (vector with leading bit 'bit', combination of x_b).
        int[] pivVec = new int[16];
        int[] pivCombo = new int[16];
        boolean[] has = new boolean[16];
        for (int b = 0; b < 16; b++) {
            int v = basis[b];
            int combo = 1 << b;
            for (int bit = 15; bit >= 0 && v != 0; bit--) {
                if (((v >> bit) & 1) == 0) {
                    continue;
                }
                if (has[bit]) {
                    v ^= pivVec[bit];
                    combo ^= pivCombo[bit];
                } else {
                    has[bit] = true;
                    pivVec[bit] = v;
                    pivCombo[bit] = combo;
                    break;
                }
            }
        }
        int target = base;
        int sol = 0;
        for (int bit = 15; bit >= 0; bit--) {
            if (((target >> bit) & 1) != 0 && has[bit]) {
                target ^= pivVec[bit];
                sol ^= pivCombo[bit];
            }
        }
        if (target != 0) {
            throw new IllegalStateException("CRC not solvable");
        }
        // x_b is the bit with mask 0x80 >>> (b & 7) of byte crcPos + (b >> 3), as in the basis
        for (int b = 0; b < 16; b++) {
            if (((sol >> b) & 1) != 0) {
                frame[crcPos + (b >> 3)] |= (byte) (0x80 >>> (b & 7));
            }
        }
    }

    // per-frame persistent coupling layout
    private boolean cplinu;
    private final boolean[] chincpl = new boolean[5];
    private int cplbegf;
    private int cplendf;
    private int ncplbnd;
    private boolean phsflginu;

    private void writeBlock(BitWriter w, int blk, int csnr) {
        int lfeCh = nfchans;
        int nch = nfchans + (lfeon ? 1 : 0);
        for (int ch = 0; ch < nfchans; ch++) {
            w.bit(rnd.nextInt(3) == 0 && on("blksw")); // blksw: about one third short blocks
        }
        for (int ch = 0; ch < nfchans; ch++) {
            w.bit(rnd.nextBoolean() && on("dith")); // dithflag
        }
        int ndyn = acmod == 0 ? 2 : 1;
        for (int i = 0; i < ndyn; i++) {
            boolean dynrnge = rnd.nextBoolean() && on("dynrng");
            w.bit(dynrnge);
            if (dynrnge) {
                w.put(8, rnd.nextInt(256));
            }
        }
        // coupling strategy (new in block 0, then occasionally)
        if (blk == 0) {
            cplinu = false; // block state does not carry over from the previous frame / attempt
        }
        boolean cplstre = blk == 0 || rnd.nextInt(4) == 0;
        boolean prevCplinu = blk > 0 && cplinu;
        int prevBegf = cplbegf;
        int prevEndf = cplendf;
        boolean cplLayoutChanged = false;
        w.bit(cplstre);
        if (cplstre) {
            cplinu = acmod >= 2 && rnd.nextInt(4) != 0 && on("cpl");
            w.bit(cplinu);
            if (cplinu) {
                int coupled = 0;
                for (int ch = 0; ch < nfchans; ch++) {
                    chincpl[ch] = rnd.nextInt(4) != 0;
                    coupled += chincpl[ch] ? 1 : 0;
                }
                if (coupled < 2) {
                    chincpl[0] = true;
                    chincpl[1] = true;
                }
                for (int ch = 0; ch < nfchans; ch++) {
                    w.bit(chincpl[ch]);
                }
                if (acmod == 2) {
                    phsflginu = rnd.nextBoolean() && on("phs");
                    w.bit(phsflginu);
                }
                cplbegf = rnd.nextInt(8);
                cplendf = Math.max(cplbegf - 2, 0) + rnd.nextInt(16 - Math.max(cplbegf - 2, 0));
                cplendf = Math.min(cplendf, 15);
                if (cplbegf > cplendf + 2) {
                    cplendf = cplbegf;
                }
                w.put(4, cplbegf);
                w.put(4, cplendf);
                ncplbnd = 0;
                int nsub = 3 + cplendf - cplbegf;
                ncplbnd = 1;
                for (int bnd = 1; bnd < nsub; bnd++) {
                    boolean merge = rnd.nextBoolean();
                    w.bit(merge);
                    ncplbnd += merge ? 0 : 1;
                }
                cplLayoutChanged = true;
            } else {
                Arrays.fill(chincpl, false);
            }
        }
        strtmant[6] = cplbegf * 12 + 37;
        endmant[6] = (cplendf + 3) * 12 + 37;
        boolean newCplInfo = cplinu && (!prevCplinu || cplLayoutChanged || prevBegf != cplbegf || prevEndf != cplendf);
        if (cplinu) {
            boolean any = false;
            for (int ch = 0; ch < nfchans; ch++) {
                if (chincpl[ch]) {
                    boolean cplcoe = newCplInfo || rnd.nextBoolean();
                    w.bit(cplcoe);
                    if (cplcoe) {
                        any = true;
                        w.put(2, rnd.nextInt(2)); // mstrcplco
                        for (int bnd = 0; bnd < ncplbnd; bnd++) {
                            w.put(4, rnd.nextInt(8)); // cplcoexp (keep coordinates loud)
                            w.put(4, rnd.nextInt(16));
                        }
                    }
                }
            }
            if (acmod == 2 && phsflginu && any) {
                for (int bnd = 0; bnd < ncplbnd; bnd++) {
                    w.bit(rnd.nextBoolean());
                }
            }
        }
        if (acmod == 2) {
            boolean rematstr = blk == 0 || cplstre || rnd.nextBoolean();
            w.bit(rematstr);
            if (rematstr) {
                int n = !cplinu || cplbegf > 2 ? 4 : cplbegf == 0 ? 2 : 3;
                for (int i = 0; i < n; i++) {
                    w.bit(rnd.nextBoolean());
                }
            }
        }
        // exponent strategies
        int[] expstr = new int[7];
        if (cplinu) {
            expstr[6] = newCplInfo ? 1 + rnd.nextInt(3) : rnd.nextInt(4);
            w.put(2, expstr[6]);
        }
        for (int ch = 0; ch < nfchans; ch++) {
            // reuse only if the bandwidth does not change (coupled channels follow cplbegf)
            boolean mayReuse = on("expreuse") && blk > 0 && !cplstre;
            expstr[ch] = mayReuse ? rnd.nextInt(4) : 1 + rnd.nextInt(3);
            w.put(2, expstr[ch]);
        }
        if (lfeon) {
            expstr[lfeCh] = blk == 0 ? 1 : rnd.nextInt(2);
            w.put(1, expstr[lfeCh]);
        }
        for (int ch = 0; ch < nfchans; ch++) {
            if (expstr[ch] != 0) {
                if (chincpl[ch] && cplinu) {
                    endmant[ch] = strtmant[6];
                } else {
                    int chbwcod = 20 + rnd.nextInt(41);
                    w.put(6, chbwcod);
                    endmant[ch] = (chbwcod + 12) * 3 + 37;
                }
            }
        }
        if (cplinu && expstr[6] != 0) {
            int absexp = 2 + rnd.nextInt(6);
            w.put(4, absexp);
            int grp = expstr[6] == 3 ? 4 : expstr[6];
            int ngrps = (endmant[6] - strtmant[6]) / (3 * grp);
            writeExponents(w, absexp << 1, ngrps, grp, exps[6], strtmant[6]);
        }
        for (int ch = 0; ch < nfchans; ch++) {
            if (expstr[ch] != 0) {
                int absexp = 2 + rnd.nextInt(10);
                w.put(4, absexp);
                exps[ch][0] = absexp;
                int grp = expstr[ch] == 3 ? 4 : expstr[ch];
                int ngrps = (endmant[ch] - 1 + 3 * grp - 3) / (3 * grp);
                writeExponents(w, absexp, ngrps, grp, exps[ch], 1);
                w.put(2, rnd.nextInt(4)); // gainrng
            }
        }
        if (lfeon && expstr[lfeCh] != 0) {
            int absexp = 2 + rnd.nextInt(10);
            w.put(4, absexp);
            exps[lfeCh][0] = absexp;
            writeExponents(w, absexp, 2, 1, exps[lfeCh], 1);
        }
        strtmant[lfeCh] = 0;
        if (lfeon) {
            endmant[lfeCh] = 7;
        }
        // bit allocation parameters
        Ac3BitAllocation.Params p = params;
        boolean baie = blk == 0 || rnd.nextInt(3) == 0;
        w.bit(baie);
        if (baie) {
            int sd = rnd.nextInt(4);
            int fd = rnd.nextInt(4);
            int sg = rnd.nextInt(4);
            int db = rnd.nextInt(4);
            int fl = rnd.nextInt(8);
            w.put(2, sd);
            w.put(2, fd);
            w.put(2, sg);
            w.put(2, db);
            w.put(3, fl);
            p.sdecay = Ac3Tables.SLOW_DECAY[sd];
            p.fdecay = Ac3Tables.FAST_DECAY[fd];
            p.sgain = Ac3Tables.SLOW_GAIN[sg];
            p.dbknee = Ac3Tables.DB_PER_BIT[db];
            p.floor = Ac3Tables.FLOOR[fl];
            p.fscod = fscod;
        }
        boolean snroffste = blk == 0 || rnd.nextInt(3) == 0;
        w.bit(snroffste);
        if (snroffste) {
            this.csnr = csnr;
            w.put(6, csnr);
            if (cplinu) {
                fsnr[6] = rnd.nextInt(16);
                fgain[6] = rnd.nextInt(8);
                w.put(4, fsnr[6]);
                w.put(3, fgain[6]);
            }
            for (int ch = 0; ch < nch; ch++) {
                fsnr[ch] = rnd.nextInt(16);
                fgain[ch] = rnd.nextInt(8);
                w.put(4, fsnr[ch]);
                w.put(3, fgain[ch]);
            }
        }
        if (cplinu) {
            boolean cplleake = blk == 0 || !prevCplinu || rnd.nextBoolean();
            w.bit(cplleake);
            if (cplleake) {
                p.cplFastLeak = rnd.nextInt(8);
                p.cplSlowLeak = rnd.nextInt(8);
                w.put(3, p.cplFastLeak);
                w.put(3, p.cplSlowLeak);
            }
        }
        // delta bit allocation
        boolean deltbaie = blk == 0 || newCplInfo || rnd.nextInt(3) == 0;
        boolean dbaOn = on("dba");
        w.bit(deltbaie);
        if (deltbaie) {
            if (cplinu) {
                dba[6] = !dbaOn ? 2 : newCplInfo || blk == 0 ? 1 + rnd.nextInt(2) : rnd.nextInt(3);
                w.put(2, dba[6]);
            }
            for (int ch = 0; ch < nfchans; ch++) {
                // reuse is only allowed if the bandwidth (and thus the band range) is unchanged
                dba[ch] = !dbaOn ? 2 : blk == 0 || expstr[ch] != 0 ? 1 + rnd.nextInt(2) : rnd.nextInt(3);
                w.put(2, dba[ch]);
            }
            if (cplinu && dba[6] == 1) {
                writeDba(w, 6);
            }
            for (int ch = 0; ch < nfchans; ch++) {
                if (dba[ch] == 1) {
                    writeDba(w, ch);
                }
            }
        } else if (blk == 0) {
            Arrays.fill(dba, 2);
        }
        if (!cplinu) {
            dba[6] = 2;
        }
        boolean skiple = rnd.nextInt(5) == 0 && on("skip");
        w.bit(skiple);
        if (skiple) {
            int skipl = rnd.nextInt(4);
            w.put(9, skipl);
            for (int i = 0; i < skipl; i++) {
                w.put(8, 0);
            }
        }
        // bit allocation with the decoder routines, then random mantissas of matching sizes
        int[][] bap = new int[7][256];
        for (int ch = 0; ch < 7; ch++) {
            boolean active = ch == 6 ? cplinu : ch < nch;
            if (!active) {
                continue;
            }
            int start = ch == 6 ? strtmant[6] : 0;
            int end = endmant[ch];
            int[] psd = new int[256];
            int[] bndpsd = new int[50];
            int[] mask = new int[50];
            Ac3BitAllocation.psd(exps[ch], start, end, psd, bndpsd);
            Ac3BitAllocation.mask(p, bndpsd, start, end, Ac3Tables.FAST_GAIN[fgain[ch]], ch == 6, lfeon && ch == lfeCh,
                dba[ch], dbaN[ch], dbaOff[ch], dbaLen[ch], dbaVal[ch], mask);
            int snroffset = (((csnr - 15) << 4) + fsnr[ch]) << 2;
            Ac3BitAllocation.bap(mask, psd, start, end, snroffset, p.floor, bap[ch]);
        }
        int b1 = 0;
        int b2 = 0;
        int b4 = 0;
        boolean gotCpl = false;
        int[] order = new int[nch + 1];
        int n = 0;
        for (int ch = 0; ch < nfchans; ch++) {
            order[n++] = ch;
            if (cplinu && chincpl[ch] && !gotCpl) {
                order[n++] = 6;
                gotCpl = true;
            }
        }
        if (lfeon) {
            order[n++] = lfeCh;
        }
        for (int k = 0; k < n; k++) {
            int ch = order[k];
            int start = ch == 6 ? strtmant[6] : 0;
            for (int bin = start; bin < endmant[ch]; bin++) {
                switch (bap[ch][bin]) {
                    case 0 -> { }
                    case 1 -> {
                        if (b1 == 0) {
                            w.put(5, rnd.nextInt(27));
                        }
                        b1 = (b1 + 1) % 3;
                    }
                    case 2 -> {
                        if (b2 == 0) {
                            w.put(7, rnd.nextInt(125));
                        }
                        b2 = (b2 + 1) % 3;
                    }
                    case 3 -> w.put(3, rnd.nextInt(7));
                    case 4 -> {
                        if (b4 == 0) {
                            w.put(7, rnd.nextInt(121));
                        }
                        b4 = (b4 + 1) % 2;
                    }
                    case 5 -> w.put(4, rnd.nextInt(15));
                    default -> {
                        int q = Ac3Tables.QNTZTAB[bap[ch][bin]];
                        w.put(q, rnd.nextInt(1 << q));
                    }
                }
            }
        }
    }

    private final Ac3BitAllocation.Params params = new Ac3BitAllocation.Params();
    private int csnr;
    private final int[] fsnr = new int[7];
    private final int[] fgain = new int[7];
    private final int[] dba = new int[7];
    private final int[] dbaN = new int[7];
    private final int[][] dbaOff = new int[7][8];
    private final int[][] dbaLen = new int[7][8];
    private final int[][] dbaVal = new int[7][8];

    private void writeDba(BitWriter w, int ch) {
        int nseg = 1 + rnd.nextInt(3);
        dbaN[ch] = nseg;
        w.put(3, nseg - 1);
        int firstBand = ch == 6 ? Ac3Tables.MASKTAB[strtmant[6]] : 0;
        int band = firstBand;
        for (int seg = 0; seg < nseg; seg++) {
            int off = rnd.nextInt(6);
            int len = 1 + rnd.nextInt(5);
            // keep every segment inside the 50 bit allocation bands (section 7.10.2, checks 15/16)
            // (and strictly below band 50 at every segment start, as required by ffmpeg)
            off = Math.max(0, Math.min(off, 48 - band));
            len = Math.max(0, Math.min(len, 49 - band - off));
            band += off + len;
            dbaOff[ch][seg] = off;
            dbaLen[ch][seg] = len;
            dbaVal[ch][seg] = rnd.nextInt(8);
            w.put(5, off);
            w.put(4, len);
            w.put(3, dbaVal[ch][seg]);
        }
    }

    /** Writes random grouped exponents that stay within 0..24 (section 7.1.3). */
    private void writeExponents(BitWriter w, int absexp, int ngrps, int grpsize, int[] exp, int first) {
        int prev = absexp;
        int j = first;
        for (int g = 0; g < ngrps; g++) {
            int[] m = new int[3];
            for (int i = 0; i < 3; i++) {
                int d;
                do {
                    d = rnd.nextInt(5) - 2;
                } while (prev + d < 0 || prev + d > 24);
                prev += d;
                m[i] = d + 2;
                for (int k = 0; k < grpsize; k++) {
                    exp[j++] = prev;
                }
            }
            w.put(7, 25 * m[0] + 5 * m[1] + m[2]);
        }
    }
}
