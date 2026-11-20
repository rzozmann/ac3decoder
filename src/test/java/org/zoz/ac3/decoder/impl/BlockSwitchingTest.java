package org.zoz.ac3.decoder.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.zoz.ac3.decoder.test.spi.TestFiles;

/**
 * Verifies block switching (A/52 section 7.9) with a known signal: a stream encoded by
 * {@link SpecEncoder} (forward transform and window of the standard, alternating long/short
 * blocks including all transitions) must decode to the original signal.
 */
class BlockSwitchingTest {

    @Test
    void decodedSignalEqualsEncoderInput() throws Exception {
        int frames = 4;
        double[] pcm = new double[frames * 1536];
        for (int i = 0; i < pcm.length; i++) {
            double t = i / 48000.0;
            // tone + noise + clicks: broadband content in every block
            // band limited content (below the 15 kHz coded bandwidth): tone, noise-like
            // multi-tone and short 3 kHz bursts (transients)
            double burstPos = (i % 1100) / 48000.0;
            double burst = burstPos < 0.002 ? 0.5 * Math.sin(Math.PI * burstPos / 0.002) * Math.sin(2 * Math.PI * 3000 * t) : 0;
            double multi = 0;
            for (int k = 1; k <= 8; k++) {
                multi += 0.01 * Math.sin(2 * Math.PI * (k * 1370 + 17) * t + k);
            }
            pcm[i] = 0.3 * Math.sin(2 * Math.PI * 1000 * t) + multi + burst;
        }
        // patterns covering long->short, short->short, short->long and long->long transitions
        java.util.function.IntPredicate pattern = blk -> (blk % 5 == 1) || (blk % 5 == 2) || (blk % 7 == 4);
        byte[] stream = SpecEncoder.encode(pcm, pattern);
        Path file = TestFiles.workDir().resolve("spec-encoder-blksw.ac3");
        Files.write(file, stream);

        Ac3Decoder decoder = new Ac3Decoder(Ac3DecoderOptions.defaults().ditherMode(Ac3DecoderOptions.DitherMode.NONE));
        float[] decoded = new float[pcm.length];
        int pos = 0;
        int frame = 0;
        while (pos < stream.length) {
            Ac3FrameHeader h = decoder.decodeFrame(stream, pos, stream.length - pos);
            assertTrue(decoder.isLastFrameValid(), "frame valid: " + decoder.getLastError());
            System.arraycopy(decoder.getOutput()[0], 0, decoded, frame * 1536, 1536);
            pos += h.getFrameBytes();
            frame++;
        }
        assertTrue(decoder.getStatistics().getShortBlocks() > 0);
        // the decoder output is delayed by one block (256 samples) relative to the encoder input
        double maxErr = 0;
        double errEnergy = 0;
        double sigEnergy = 0;
        for (int i = 256; i < pcm.length; i++) {
            double e = decoded[i] - pcm[i - 256];
            maxErr = Math.max(maxErr, Math.abs(e));
            errEnergy += e * e;
            sigEnergy += pcm[i - 256] * pcm[i - 256];
        }
        double snr = 10 * Math.log10(sigEnergy / errEnergy);
        System.out.printf("spec encoder round trip with block switching: maxErr=%.3g SNR=%.1f dB%n", maxErr, snr);
        // The minimal test encoder limits exponent changes to +-2 per bin (D15) by lowering
        // exponents, which costs precision in interleaved short blocks; the reconstruction is
        // limited by that quantization, not by the transform (see exactTransformRoundTrip).
        assertTrue(snr > 30, "reconstruction SNR " + snr + " dB");
        assertEquals(frames, frame);

        // the identical stream decoded by ffmpeg must give the same output
        if (TestFiles.ffmpegAvailable()) {
            float[] ref = TestFiles.ffmpegDecode(file.toFile(), null);
            assertEquals(decoded.length, ref.length);
            double e2 = 0;
            double s2 = 0;
            for (int i = 0; i < ref.length; i++) {
                double d = decoded[i] - ref[i];
                e2 += d * d;
                s2 += (double) ref[i] * ref[i];
            }
            double snrFfmpeg = 10 * Math.log10(s2 / e2);
            System.out.printf("spec encoder stream, this decoder vs ffmpeg: SNR=%.1f dB%n", snrFfmpeg);
            assertTrue(snrFfmpeg > 120, "agreement with ffmpeg " + snrFfmpeg + " dB");
        }
    }

    /**
     * Inverse transform with block switching against the forward transform of section 8.2.3.2
     * without quantization: every long/short combination must reconstruct the input exactly.
     */
    @Test
    void exactTransformRoundTrip() {
        int blocks = 40;
        double[] pcm = new double[blocks * 256];
        java.util.Random rnd = new java.util.Random(5);
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = 0.5 * Math.sin(i * 0.031) + 0.2 * (rnd.nextDouble() - 0.5);
        }
        double[] w = Ac3Tables.WINDOW;
        Ac3Imdct imdct = new Ac3Imdct();
        double[] delay = new double[256];
        float[] out = new float[256];
        double maxErr = 0;
        for (int blk = 0; blk < blocks; blk++) {
            // pseudo random block switching: all four transitions occur
            boolean shortBlock = ((blk * 7919) >> 3 & 1) == 1;
            double[] x = new double[512];
            for (int n = 0; n < 512; n++) {
                int idx = (blk - 1) * 256 + n;
                double s = idx >= 0 && idx < pcm.length ? pcm[idx] : 0;
                x[n] = s * (n < 256 ? w[n] : w[511 - n]);
            }
            double[] c = shortBlock ? SpecEncoder.forwardShort(x) : SpecEncoder.forward(x, 512, 0);
            imdct.transform(c, shortBlock, delay, out, 0);
            if (blk >= 1) {
                for (int n = 0; n < 256; n++) {
                    maxErr = Math.max(maxErr, Math.abs(out[n] - pcm[(blk - 1) * 256 + n]));
                }
            }
        }
        System.out.printf("exact transform round trip with block switching: maxErr=%.3g%n", maxErr);
        assertTrue(maxErr < 1e-6, "max error " + maxErr);
    }
}
