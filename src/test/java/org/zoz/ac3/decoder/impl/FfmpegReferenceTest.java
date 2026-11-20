package org.zoz.ac3.decoder.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.zoz.ac3.decoder.impl.Ac3DecoderOptions.DitherMode;
import org.zoz.ac3.decoder.impl.Ac3DecoderOptions.OutputMode;
import org.zoz.ac3.decoder.test.spi.TestFiles;

/**
 * Verifies the decoder against the ffmpeg AC-3 decoder (libavcodec/ac3dec.c), sample by sample,
 * for every reference file in the project directory.
 *
 * <p>With {@link DitherMode#FFMPEG_COMPATIBLE} the decoder uses the same pseudo random dither
 * sequence as ffmpeg. All integer stages (exponents, bit allocation, mantissas, coupling,
 * rematrixing) then produce identical values, and the only remaining difference is the floating
 * point rounding of the inverse transform, so the output must match within a few float ULPs.
 * With the standard dither the decoders differ only by their (independent) dither noise.</p>
 */
class FfmpegReferenceTest {

    /** Maximum absolute sample difference allowed with identical dither (float rounding only). */
    private static final double BIT_EXACT_TOLERANCE = 2e-6;
    /** Required signal to error ratio with identical dither. */
    private static final double BIT_EXACT_MIN_SNR_DB = 120;

    @BeforeAll
    static void requireFfmpeg() {
        assumeTrue(TestFiles.ffmpegAvailable(), "ffmpeg not installed - reference comparison skipped");
        assumeTrue(!TestFiles.referenceFiles().isEmpty(), "no *.ac3 reference files found");
    }

    static Stream<File> files() {
        return TestFiles.referenceFiles().stream();
    }

    static Stream<Arguments> filesAndDownmix() {
        List<File> files = TestFiles.referenceFiles();
        return files.stream().flatMap(f -> Stream.of(Arguments.of(f, OutputMode.STEREO), Arguments.of(f, OutputMode.MONO)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    void nativeChannelsMatchFfmpeg(File file) throws Exception {
        float[] ref = TestFiles.ffmpegDecode(file, null);
        Comparison c = compare(file, Ac3DecoderOptions.defaults().ditherMode(DitherMode.FFMPEG_COMPATIBLE), ref, 1.0);
        System.out.println("native   " + file.getName() + ": " + c);
        assertEquals(0, c.mutedFrames, "frames muted (CRC or bit stream error)");
        assertEquals(ref.length, c.samples, "number of decoded samples differs from ffmpeg");
        assertTrue(c.maxError <= BIT_EXACT_TOLERANCE, "max error " + c.maxError + " at sample " + c.maxErrorIndex);
        assertTrue(c.snrDb() >= BIT_EXACT_MIN_SNR_DB, "SNR " + c.snrDb() + " dB");
    }

    /**
     * Stereo (Lo/Ro, section 7.8.2) downmix and mono downmix compared to ffmpeg "-downmix". The
     * Lo/Ro coefficients are identical. For mono the standard requires "a further scaling of 1/2"
     * of Lo + Ro while ffmpeg normalizes Lo + Ro by 1/sqrt(2); this constant gain is compensated.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("filesAndDownmix")
    void downmixMatchesFfmpeg(File file, OutputMode mode) throws Exception {
        String layout = mode == OutputMode.MONO ? "mono" : "stereo";
        float[] ref = TestFiles.ffmpegDecode(file, layout);
        Ac3DecoderOptions opts = Ac3DecoderOptions.defaults().ditherMode(DitherMode.FFMPEG_COMPATIBLE).outputMode(mode);
        double gain = 1.0;
        if (mode == OutputMode.MONO && channels(file) > 1) {
            gain = Math.sqrt(2.0);
        }
        Comparison c = compare(file, opts, ref, gain);
        System.out.println(layout + "   " + file.getName() + ": " + c);
        assertEquals(ref.length, c.samples, "number of decoded samples differs from ffmpeg");
        assertTrue(c.maxError <= BIT_EXACT_TOLERANCE, "max error " + c.maxError + " at sample " + c.maxErrorIndex);
    }

    /**
     * Default decoder configuration (independent dither, as described in section 7.3.4). The
     * difference to ffmpeg is the dither noise of both decoders, which is far below the signal.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    void standardDitherCloseToFfmpeg(File file) throws Exception {
        float[] ref = TestFiles.ffmpegDecode(file, null);
        Comparison c = compare(file, Ac3DecoderOptions.defaults(), ref, 1.0);
        System.out.println("standard " + file.getName() + ": " + c);
        assertEquals(ref.length, c.samples);
        assertTrue(c.snrDb() > 30, "SNR " + c.snrDb() + " dB");
        // the difference is noise in zero-bit mantissas, quantized at the level of those bins
        assertTrue(c.maxError < 0.25, "max error " + c.maxError);
    }

    private static int channels(File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            Ac3FrameReader r = new Ac3FrameReader(in);
            Ac3FrameHeader h = r.next();
            return Ac3FrameHeader.parse(r.frameBuffer(), r.frameOffset(), h.getFrameBytes()).getChannels();
        }
    }

    static final class Comparison {
        long samples;
        long frames;
        long mutedFrames;
        double maxError;
        long maxErrorIndex = -1;
        double errorEnergy;
        double signalEnergy;
        String tools = "";
        long shortBlocks;
        long dynrngWords;
        long dbaBlocks;

        double snrDb() {
            return errorEnergy == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(signalEnergy / errorEnergy);
        }

        @Override
        public String toString() {
            return String.format("frames=%d muted=%d samples=%d maxErr=%.3g SNR=%.1f dB [%s]", frames, mutedFrames,
                samples, maxError, snrDb(), tools);
        }
    }

    static Comparison compare(File file, Ac3DecoderOptions options, float[] ref, double gain) throws IOException {
        Comparison c = new Comparison();
        Ac3Decoder decoder = new Ac3Decoder(options);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            Ac3FrameReader reader = new Ac3FrameReader(in);
            Ac3FrameHeader h;
            int idx = 0;
            while ((h = reader.next()) != null) {
                decoder.decodeFrame(reader.frameBuffer(), reader.frameOffset(), h.getFrameBytes());
                c.frames++;
                if (!decoder.isLastFrameValid()) {
                    c.mutedFrames++;
                }
                float[][] out = decoder.getOutput();
                int nch = decoder.getOutputChannels();
                for (int i = 0; i < Ac3Decoder.SAMPLES_PER_FRAME; i++) {
                    for (int ch = 0; ch < nch; ch++) {
                        if (idx < ref.length) {
                            double r = ref[idx];
                            double e = Math.abs(out[ch][i] * gain - r);
                            if (e > c.maxError) {
                                c.maxError = e;
                                c.maxErrorIndex = idx;
                            }
                            c.errorEnergy += e * e;
                            c.signalEnergy += r * r;
                        }
                        idx++;
                        c.samples++;
                    }
                }
            }
        }
        c.tools = decoder.getStatistics().toString();
        c.shortBlocks = decoder.getStatistics().getShortBlocks();
        c.dynrngWords = decoder.getStatistics().getDynrngWords();
        c.dbaBlocks = decoder.getStatistics().getDeltaBitAllocationBlocks();
        return c;
    }
}
