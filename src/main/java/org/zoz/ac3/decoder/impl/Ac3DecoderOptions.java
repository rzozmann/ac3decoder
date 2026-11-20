package org.zoz.ac3.decoder.impl;

/**
 * Configuration of an {@link Ac3Decoder}.
 */
public final class Ac3DecoderOptions {

    /** Generation of the zero-bit mantissa dither (A/52 section 7.3.4). */
    public enum DitherMode {
        /**
         * Dither as described by the standard: an independent pseudo random sequence, applied to
         * every channel with {@code dithflag == 1} after the channels have been extracted from
         * the coupling channel, so that the dither of coupled channels is uncorrelated.
         */
        STANDARD,
        /**
         * Dither identical to the ffmpeg reference decoder (lagged Fibonacci generator with seed 0,
         * coupling channel dithered before de-coupling). Produces output that matches ffmpeg sample
         * by sample; used for verification.
         */
        FFMPEG_COMPATIBLE,
        /** No dither: zero-bit mantissas are reconstructed as zero. */
        NONE,
    }

    /** Output channel configuration. */
    public enum OutputMode {
        /** All coded channels, ordered L, R, C, LFE, surround(s) (WAVE / SMPTE channel order). */
        NATIVE,
        /** Conventional Lo/Ro stereo downmix (section 7.8.2), LFE omitted. */
        STEREO,
        /** Lt/Rt matrix surround compatible stereo downmix (section 7.8.2), LFE omitted. */
        STEREO_LTRT,
        /** Mono downmix (Lo + Ro), LFE omitted. */
        MONO,
    }

    private DitherMode ditherMode = DitherMode.STANDARD;
    private OutputMode outputMode = OutputMode.NATIVE;
    private double dynamicRangeScale = 1.0;
    private boolean heavyCompression;
    private boolean crcCheck = true;

    public static Ac3DecoderOptions defaults() {
        return new Ac3DecoderOptions();
    }

    public DitherMode getDitherMode() {
        return ditherMode;
    }

    public Ac3DecoderOptions ditherMode(DitherMode mode) {
        this.ditherMode = mode;
        return this;
    }

    public OutputMode getOutputMode() {
        return outputMode;
    }

    public Ac3DecoderOptions outputMode(OutputMode mode) {
        this.outputMode = mode;
        return this;
    }

    public double getDynamicRangeScale() {
        return dynamicRangeScale;
    }

    /**
     * Fraction of the dynamic range control (dynrng, section 7.7.1) to apply: 1.0 (default, as
     * required by the standard) applies the full compression, 0.0 reproduces the full dynamic range.
     */
    public Ac3DecoderOptions dynamicRangeScale(double scale) {
        if (scale < 0 || scale > 1) {
            throw new IllegalArgumentException("dynamic range scale must be in 0..1");
        }
        this.dynamicRangeScale = scale;
        return this;
    }

    public boolean isHeavyCompression() {
        return heavyCompression;
    }

    /** Use compr (heavy compression, section 7.7.2) instead of dynrng when present. */
    public Ac3DecoderOptions heavyCompression(boolean enable) {
        this.heavyCompression = enable;
        return this;
    }

    public boolean isCrcCheck() {
        return crcCheck;
    }

    /** Verify crc1 and crc2 (section 7.10.1) and mute frames failing the check. */
    public Ac3DecoderOptions crcCheck(boolean enable) {
        this.crcCheck = enable;
        return this;
    }

    Ac3DecoderOptions copy() {
        return new Ac3DecoderOptions().ditherMode(ditherMode).outputMode(outputMode)
            .dynamicRangeScale(dynamicRangeScale).heavyCompression(heavyCompression).crcCheck(crcCheck);
    }
}
