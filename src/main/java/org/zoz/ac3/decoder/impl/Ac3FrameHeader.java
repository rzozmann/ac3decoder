package org.zoz.ac3.decoder.impl;

/**
 * Parsed syncinfo() and bsi() of an AC-3 syncframe (A/52 sections 5.3.1, 5.3.2, 5.4.1, 5.4.2 and
 * the alternate bit stream syntax of Annex D for {@code bsid == 6}).
 */
public final class Ac3FrameHeader {

    /** Audio coding modes, Table 5.8. */
    public static final int ACMOD_DUAL_MONO = 0;
    public static final int ACMOD_1_0 = 1;
    public static final int ACMOD_2_0 = 2;
    public static final int ACMOD_3_0 = 3;
    public static final int ACMOD_2_1 = 4;
    public static final int ACMOD_3_1 = 5;
    public static final int ACMOD_2_2 = 6;
    public static final int ACMOD_3_2 = 7;

    /** Minimum number of bytes needed by {@link #parseSyncInfo}. */
    public static final int SYNCINFO_BYTES = 6;

    // syncinfo
    int crc1;
    int fscod;
    int frmsizecod;
    int frameBytes;
    int sampleRate;
    int bitRate;

    // bsi
    int bsid;
    int bsmod;
    int acmod;
    int cmixlev = -1;
    int surmixlev = -1;
    int dsurmod;
    boolean lfeon;
    int dialnorm;
    int compr = -1;
    int dialnorm2;
    int compr2 = -1;
    int langcod = -1;
    int langcod2 = -1;
    int mixlevel = -1;
    int roomtyp = -1;
    int mixlevel2 = -1;
    int roomtyp2 = -1;
    boolean copyright;
    boolean original;
    int timecod1 = -1;
    int timecod2 = -1;

    // Annex D alternate syntax (bsid == 6)
    boolean xbsi1e;
    int dmixmod;
    int ltrtcmixlev;
    int ltrtsurmixlev;
    int lorocmixlev;
    int lorosurmixlev;
    boolean xbsi2e;
    int dsurexmod;
    int dheadphonmod;
    int adconvtyp;

    int nfchans;
    /** Bit position (relative to the frame start) of the first audio block. */
    int audioBlockBitOffset;

    private Ac3FrameHeader() {
    }

    /**
     * Parses only the syncinfo of the frame starting at {@code off}.
     *
     * @return the header with syncinfo fields set, or {@code null} if there is no valid
     *     AC-3 syncinfo at that position
     */
    public static Ac3FrameHeader parseSyncInfo(byte[] buf, int off, int len) {
        if (len < SYNCINFO_BYTES) {
            return null;
        }
        if ((((buf[off] & 0xff) << 8) | (buf[off + 1] & 0xff)) != Ac3Tables.SYNC_WORD) {
            return null;
        }
        Ac3FrameHeader h = new Ac3FrameHeader();
        h.crc1 = ((buf[off + 2] & 0xff) << 8) | (buf[off + 3] & 0xff);
        h.fscod = (buf[off + 4] & 0xff) >>> 6;
        h.frmsizecod = buf[off + 4] & 0x3f;
        if (h.fscod == 3 || h.frmsizecod > 37) {
            return null; // reserved sample rate code / invalid frame size code
        }
        // bsid is the first element of bsi; E-AC-3 (bsid 11..16) uses a different syncinfo
        h.bsid = (buf[off + 5] & 0xff) >>> 3;
        if (h.bsid > 10) {
            return null;
        }
        int words = Ac3Tables.FRAME_SIZE_WORDS[h.frmsizecod >> 1][h.fscod];
        if (h.fscod == 1 && (h.frmsizecod & 1) != 0) {
            words++;
        }
        h.frameBytes = words * 2;
        h.sampleRate = Ac3Tables.SAMPLE_RATES[h.fscod];
        h.bitRate = Ac3Tables.BIT_RATES_KBPS[h.frmsizecod >> 1] * 1000;
        return h;
    }

    /**
     * Parses syncinfo and bsi of the frame starting at {@code off}.
     *
     * @throws Ac3DecodeException if the header is not a decodable AC-3 header
     */
    public static Ac3FrameHeader parse(byte[] buf, int off, int len) {
        Ac3FrameHeader h = parseSyncInfo(buf, off, len);
        if (h == null) {
            throw new Ac3DecodeException("no valid AC-3 syncinfo");
        }
        if (h.bsid > 8) {
            // section 5.4.2.1: decoders built to this standard shall mute if bsid > 8
            throw new Ac3DecodeException("unsupported bsid " + h.bsid);
        }
        BitReader br = new BitReader(buf, off, Math.min(len, h.frameBytes));
        br.skip(40);
        h.bsid = br.read(5);
        h.bsmod = br.read(3);
        h.acmod = br.read(3);
        h.nfchans = Ac3Tables.NFCHANS[h.acmod];
        if ((h.acmod & 1) != 0 && h.acmod != 1) {
            h.cmixlev = br.read(2);
        }
        if ((h.acmod & 4) != 0) {
            h.surmixlev = br.read(2);
        }
        if (h.acmod == ACMOD_2_0) {
            h.dsurmod = br.read(2);
        }
        h.lfeon = br.readBit();
        h.dialnorm = br.read(5);
        if (br.readBit()) {
            h.compr = br.read(8);
        }
        if (br.readBit()) {
            h.langcod = br.read(8);
        }
        if (br.readBit()) {
            h.mixlevel = br.read(5);
            h.roomtyp = br.read(2);
        }
        if (h.acmod == ACMOD_DUAL_MONO) {
            h.dialnorm2 = br.read(5);
            if (br.readBit()) {
                h.compr2 = br.read(8);
            }
            if (br.readBit()) {
                h.langcod2 = br.read(8);
            }
            if (br.readBit()) {
                h.mixlevel2 = br.read(5);
                h.roomtyp2 = br.read(2);
            }
        }
        h.copyright = br.readBit();
        h.original = br.readBit();
        if (h.bsid == 6) {
            // Annex D, Table D2.1
            h.xbsi1e = br.readBit();
            if (h.xbsi1e) {
                h.dmixmod = br.read(2);
                h.ltrtcmixlev = br.read(3);
                h.ltrtsurmixlev = br.read(3);
                h.lorocmixlev = br.read(3);
                h.lorosurmixlev = br.read(3);
            }
            h.xbsi2e = br.readBit();
            if (h.xbsi2e) {
                h.dsurexmod = br.read(2);
                h.dheadphonmod = br.read(2);
                h.adconvtyp = br.read(1);
                br.skip(8); // xbsi2
                br.skip(1); // encinfo
            }
        } else {
            if (br.readBit()) {
                h.timecod1 = br.read(14);
            }
            if (br.readBit()) {
                h.timecod2 = br.read(14);
            }
        }
        if (br.readBit()) {
            int addbsil = br.read(6);
            br.skip((addbsil + 1) * 8);
        }
        h.audioBlockBitOffset = br.position() - off * 8;
        return h;
    }

    public int getFrameBytes() {
        return frameBytes;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    /** Nominal bit rate in bits per second (Table 5.18). */
    public int getBitRate() {
        return bitRate;
    }

    public int getFscod() {
        return fscod;
    }

    public int getFrmsizecod() {
        return frmsizecod;
    }

    public int getBsid() {
        return bsid;
    }

    public int getBsmod() {
        return bsmod;
    }

    public int getAcmod() {
        return acmod;
    }

    public boolean isLfeOn() {
        return lfeon;
    }

    /** Number of full bandwidth channels (nfchans). */
    public int getFullBandwidthChannels() {
        return nfchans;
    }

    /** Total number of coded channels (nchans = nfchans + lfeon). */
    public int getChannels() {
        return nfchans + (lfeon ? 1 : 0);
    }

    /** Dialogue level in dB below full scale (section 5.4.2.8, reserved 0 is interpreted as 31). */
    public int getDialogueNormalization() {
        return dialnorm == 0 ? 31 : dialnorm;
    }

    public boolean isCopyright() {
        return copyright;
    }

    public boolean isOriginal() {
        return original;
    }

    /** Human readable audio coding mode, e.g. "3/2" or "1+1". */
    public String getAudioCodingModeName() {
        return switch (acmod) {
            case ACMOD_DUAL_MONO -> "1+1";
            case ACMOD_1_0 -> "1/0";
            case ACMOD_2_0 -> "2/0";
            case ACMOD_3_0 -> "3/0";
            case ACMOD_2_1 -> "2/1";
            case ACMOD_3_1 -> "3/1";
            case ACMOD_2_2 -> "2/2";
            default -> "3/2";
        } + (lfeon ? "+LFE" : "");
    }

    @Override
    public String toString() {
        return "AC-3 " + sampleRate + " Hz " + getAudioCodingModeName() + " " + (bitRate / 1000)
            + " kbps bsid=" + bsid + " frame=" + frameBytes + " bytes";
    }
}
