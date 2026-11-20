package org.zoz.ac3.decoder.test.spi;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.stream.Stream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.zoz.ac3.decoder.cli.Ac3Tool;

/**
 * Plays every reference *.ac3 file through the default audio device using only the Java Sound
 * API (AudioSystem -> AC-3 SPI -> SourceDataLine).
 *
 * <p>Configuration (Maven properties / system properties):</p>
 * <ul>
 * <li>{@code ac3.playback.seconds} - seconds to play per file (default 6, 0 = whole file)</li>
 * <li>{@code ac3.playback.skip=true} - skip the audible tests (e.g. on CI machines)</li>
 * </ul>
 * Example: {@code mvn test -Dtest=PlaybackTest -Dac3.playback.seconds=0}
 */
class PlaybackTest {

    @BeforeAll
    static void requireAudioDevice() {
        assumeFalse(Boolean.getBoolean("ac3.playback.skip"), "playback disabled (ac3.playback.skip=true)");
        assumeTrue(!TestFiles.referenceFiles().isEmpty(), "no *.ac3 reference files found");
        AudioFormat stereo = new AudioFormat(48000, 16, 2, true, false);
        boolean available;
        try {
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, stereo));
            line.open(stereo);
            line.close();
            available = true;
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            available = false;
        }
        assumeTrue(available, "no audio output device available");
    }

    static Stream<File> files() {
        return TestFiles.referenceFiles().stream();
    }

    private static double seconds() {
        return Double.parseDouble(System.getProperty("ac3.playback.seconds", "6"));
    }

    /** Plays the file with its native channel layout (falls back to a stereo downmix if unsupported). */
    @ParameterizedTest(name = "native {0}")
    @MethodSource("files")
    void playNative(File file) throws Exception {
        double seconds = seconds();
        try (AudioInputStream ac3 = AudioSystem.getAudioInputStream(file)) {
            System.out.printf("playing %s (%s, %d ch) for %s%n", file.getName(), ac3.getFormat().getProperty("ac3.acmod"),
                ac3.getFormat().getChannels(), seconds > 0 ? seconds + " s" : "full length");
        }
        long t0 = System.nanoTime();
        long frames = Ac3Tool.play(file, -1, seconds > 0 ? seconds : -1);
        double elapsed = (System.nanoTime() - t0) / 1e9;
        float rate = AudioSystem.getAudioFileFormat(file).getFormat().getSampleRate();
        double played = frames / rate;
        System.out.printf("  played %.2f s of audio in %.2f s%n", played, elapsed);
        assertTrue(frames > 0, "audio was written to the line");
        if (seconds > 0) {
            assertTrue(played >= Math.min(seconds, durationSeconds(file)) - 0.1, "requested duration was played");
        }
        // real time playback: the decoder must be faster than real time, the line paces the writes
        assertTrue(elapsed >= played * 0.8, "audio line consumed the data in (approximately) real time");
    }

    /** Plays the beginning of the file as stereo downmix (Lo/Ro, A/52 section 7.8.2). */
    @ParameterizedTest(name = "stereo downmix {0}")
    @MethodSource("files")
    void playStereoDownmix(File file) throws Exception {
        double seconds = Math.min(3, seconds() > 0 ? seconds() : 3);
        long frames = Ac3Tool.play(file, 2, seconds);
        assertTrue(frames > 0);
    }

    private static double durationSeconds(File file) throws Exception {
        Long us = (Long) AudioSystem.getAudioFileFormat(file).getProperty("duration");
        return us == null ? Double.MAX_VALUE : us / 1e6;
    }
}
