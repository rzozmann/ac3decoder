package org.zoz.ac3.decoder.test.sampled;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.zoz.ac3.decoder.impl.Ac3Decoder;
import org.zoz.ac3.decoder.spi.Ac3AudioFileReader;
import org.zoz.ac3.decoder.spi.Ac3Encoding;
import org.zoz.ac3.decoder.test.spi.TestFiles;

/**
 * Tests the service provider exclusively through the public Java Sound API
 * ({@link AudioSystem}), i.e. via the META-INF/services registration.
 */
class Ac3SpiTest {

    @BeforeAll
    static void requireFiles() {
        assumeTrue(!TestFiles.referenceFiles().isEmpty(), "no *.ac3 reference files found");
    }

    static Stream<File> files() {
        return TestFiles.referenceFiles().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    void fileFormatDetected(File file) throws Exception {
        AudioFileFormat aff = AudioSystem.getAudioFileFormat(file);
        assertEquals(Ac3Encoding.AC3_FILE, aff.getType());
        AudioFormat f = aff.getFormat();
        assertEquals(Ac3Encoding.AC3, f.getEncoding());
        assertTrue(f.getSampleRate() == 48000 || f.getSampleRate() == 44100 || f.getSampleRate() == 32000);
        assertTrue(f.getChannels() >= 1 && f.getChannels() <= 6);
        assertTrue(aff.getFrameLength() > 0);
        assertTrue((Long) aff.getProperty(Ac3Encoding.PROP_DURATION) > 0);
        System.out.println(file.getName() + ": " + f + " frames=" + aff.getFrameLength() + " duration="
            + aff.getProperty(Ac3Encoding.PROP_DURATION) + "us props=" + f.properties());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    void decodeToPcmViaAudioSystem(File file) throws Exception {
        try (AudioInputStream ac3 = AudioSystem.getAudioInputStream(file)) {
            AudioFormat src = ac3.getFormat();
            assertTrue(AudioSystem.isConversionSupported(AudioFormat.Encoding.PCM_SIGNED, src));
            AudioFormat target = new AudioFormat(src.getSampleRate(), 16, src.getChannels(), true, false);
            assertTrue(AudioSystem.isConversionSupported(target, src));
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(target, ac3)) {
                assertEquals(target.getChannels(), pcm.getFormat().getChannels());
                long expectedFrames = pcm.getFrameLength();
                assertTrue(expectedFrames > 0, "decoded length is announced");
                int nch = target.getChannels();
                double[] energy = new double[nch];
                byte[] buf = new byte[target.getFrameSize() * 4096];
                long bytes = 0;
                int n;
                while ((n = pcm.read(buf)) > 0) {
                    for (int i = 0; i + 1 < n; i += 2) {
                        int ch = (int) (((bytes + i) / 2) % nch);
                        short s = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
                        energy[ch] += (double) s * s;
                    }
                    bytes += n;
                }
                long frames = bytes / target.getFrameSize();
                assertEquals(0, frames % Ac3Decoder.SAMPLES_PER_FRAME, "whole syncframes decoded");
                assertEquals(expectedFrames, frames, "frame length announced by the stream");
                // the front left/right channels carry signal in every reference file
                for (int ch = 0; ch < Math.min(2, nch); ch++) {
                    double rmsDb = 10 * Math.log10(energy[ch] / frames / (32768.0 * 32768.0));
                    System.out.printf("  %s ch%d rms %.1f dBFS%n", file.getName(), ch, rmsDb);
                    assertTrue(rmsDb > -60, "channel " + ch + " is not silent");
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    void stereoAndMonoAndFloatTargets(File file) throws Exception {
        for (int channels : new int[] {2, 1}) {
            for (AudioFormat.Encoding enc : new AudioFormat.Encoding[] {AudioFormat.Encoding.PCM_SIGNED,
                AudioFormat.Encoding.PCM_FLOAT}) {
                try (AudioInputStream ac3 = AudioSystem.getAudioInputStream(file)) {
                    int bits = enc == AudioFormat.Encoding.PCM_FLOAT ? 32 : 16;
                    float rate = ac3.getFormat().getSampleRate();
                    AudioFormat target = new AudioFormat(enc, rate, bits, channels, channels * bits / 8, rate, true);
                    try (AudioInputStream pcm = AudioSystem.getAudioInputStream(target, ac3)) {
                        assertTrue(target.matches(pcm.getFormat()) && pcm.getFormat().matches(target),
                            "target format honoured: " + pcm.getFormat());
                        byte[] first = pcm.readNBytes(Ac3Decoder.SAMPLES_PER_FRAME * 10 * target.getFrameSize());
                        assertEquals(Ac3Decoder.SAMPLES_PER_FRAME * 10 * target.getFrameSize(), first.length);
                    }
                }
            }
        }
    }

    @Test
    void writeWaveFile() throws Exception {
        File file = TestFiles.referenceFiles().get(TestFiles.referenceFiles().size() - 1);
        Path wav = TestFiles.workDir().resolve(file.getName().replace(".ac3", ".wav"));
        try (AudioInputStream ac3 = AudioSystem.getAudioInputStream(file);
             AudioInputStream pcm = AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, ac3)) {
            AudioSystem.write(pcm, AudioFileFormat.Type.WAVE, wav.toFile());
        }
        AudioFileFormat written = AudioSystem.getAudioFileFormat(wav.toFile());
        assertEquals(AudioFileFormat.Type.WAVE, written.getType());
        assertTrue(written.getFrameLength() > 0);
        assertEquals(0, written.getFrameLength() % Ac3Decoder.SAMPLES_PER_FRAME);
    }

    @Test
    void rejectsNonAc3Data() {
        byte[] noise = new byte[20000];
        new java.util.Random(3).nextBytes(noise);
        assertThrows(UnsupportedAudioFileException.class,
            () -> new Ac3AudioFileReader().getAudioFileFormat(new BufferedInputStream(new ByteArrayInputStream(noise))));
    }

    /** Leading garbage and a corrupted frame in the middle must not stop decoding (resync). */
    @Test
    void resynchronizesAfterGarbage() throws Exception {
        for (File file : TestFiles.referenceFiles()) {
            byte[] data = Files.readAllBytes(file.toPath());
            // frame boundaries of the first 20 syncframes (sizes vary at 44.1 kHz)
            int frames = 20;
            int[] starts = new int[frames + 1];
            for (int i = 0; i < frames; i++) {
                starts[i + 1] = starts[i] + Ac3FrameHeaderAccess.frameBytes(data, starts[i]);
            }
            byte[] garbage = {0x0B, 0x77, 1, 2, 3, 0x0B, 0x77, 0x55, 0, 0};
            byte[] lostFrameHeader = Arrays.copyOfRange(data, starts[10], starts[10] + 40); // partial frame
            byte[] input = concat(garbage, Arrays.copyOf(data, starts[10]), lostFrameHeader,
                Arrays.copyOfRange(data, starts[10], starts[frames]));
            // payload damage in frame 5 -> CRC error -> frame muted, but still produces samples
            input[garbage.length + starts[5] + 100] ^= 0x40;
            try (InputStream in = new BufferedInputStream(new ByteArrayInputStream(input));
                 AudioInputStream ac3 = AudioSystem.getAudioInputStream(in);
                 AudioInputStream pcm = AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, ac3)) {
                byte[] all = pcm.readAllBytes();
                assertEquals((long) frames * Ac3Decoder.SAMPLES_PER_FRAME * pcm.getFormat().getFrameSize(), all.length,
                    file.getName() + ": all 20 intact syncframes decoded");
            }
        }
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    /** Small helper to read the frame size of the syncframe at {@code off}. */
    static final class Ac3FrameHeaderAccess {
        static int frameBytes(byte[] data, int off) {
            return org.zoz.ac3.decoder.impl.Ac3FrameHeader.parseSyncInfo(data, off, data.length - off).getFrameBytes();
        }
    }

    @Test
    void streamWithoutMarkSupportViaFileReader() throws Exception {
        File file = TestFiles.referenceFiles().get(0);
        try (InputStream raw = new FileInputStream(file)) {
            assertThrows(java.io.IOException.class, () -> new Ac3AudioFileReader().getAudioInputStream(raw));
        }
    }
}
