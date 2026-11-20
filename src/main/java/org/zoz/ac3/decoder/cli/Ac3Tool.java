package org.zoz.ac3.decoder.cli;

import java.io.File;
import java.io.IOException;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Command line tool using the AC-3 SPI exclusively through the Java Sound API.
 *
 * <pre>
 *   java -jar ac3-java-spi.jar info   file.ac3
 *   java -jar ac3-java-spi.jar play   file.ac3 [channels]
 *   java -jar ac3-java-spi.jar decode file.ac3 out.wav [channels]
 * </pre>
 */
public final class Ac3Tool {

    private Ac3Tool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: info <file.ac3> | play <file.ac3> [channels] | decode <file.ac3> <out.wav> [channels]");
            System.exit(2);
        }
        File in = new File(args[1]);
        switch (args[0]) {
            case "info" -> info(in);
            case "play" -> play(in, args.length > 2 ? Integer.parseInt(args[2]) : -1, -1);
            case "decode" -> decode(in, new File(args[2]), args.length > 3 ? Integer.parseInt(args[3]) : -1);
            default -> {
                System.err.println("unknown command " + args[0]);
                System.exit(2);
            }
        }
    }

    static void info(File in) throws UnsupportedAudioFileException, IOException {
        AudioFileFormat aff = AudioSystem.getAudioFileFormat(in);
        System.out.println("type:     " + aff.getType());
        System.out.println("format:   " + aff.getFormat());
        System.out.println("frames:   " + aff.getFrameLength());
        System.out.println("props:    " + aff.properties() + " " + aff.getFormat().properties());
    }

    /** Opens the file as 16 bit PCM through Java Sound, with the requested channel count (-1: native). */
    public static AudioInputStream openPcm(File in, int channels) throws UnsupportedAudioFileException, IOException {
        AudioInputStream ac3 = AudioSystem.getAudioInputStream(in);
        AudioFormat src = ac3.getFormat();
        int ch = channels > 0 ? channels : src.getChannels();
        AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, src.getSampleRate(), 16, ch, ch * 2,
            src.getSampleRate(), false);
        return AudioSystem.getAudioInputStream(pcm, ac3);
    }

    static void decode(File in, File out, int channels) throws UnsupportedAudioFileException, IOException {
        try (AudioInputStream pcm = openPcm(in, channels)) {
            AudioSystem.write(pcm, AudioFileFormat.Type.WAVE, out);
        }
    }

    /**
     * Plays the file. If the audio device does not accept the native channel count, a stereo
     * downmix is played instead.
     *
     * @param maxSeconds stop after this many seconds (-1: whole file)
     * @return number of PCM frames written to the line
     */
    public static long play(File in, int channels, double maxSeconds)
        throws UnsupportedAudioFileException, IOException, LineUnavailableException {
        AudioInputStream pcm = openPcm(in, channels);
        SourceDataLine line;
        try {
            line = openLine(pcm.getFormat());
        } catch (LineUnavailableException | IllegalArgumentException e) {
            if (pcm.getFormat().getChannels() <= 2) {
                pcm.close();
                throw e;
            }
            pcm.close();
            pcm = openPcm(in, 2);
            line = openLine(pcm.getFormat());
        }
        AudioFormat f = pcm.getFormat();
        long limitBytes = maxSeconds > 0 ? (long) (maxSeconds * f.getFrameRate()) * f.getFrameSize() : Long.MAX_VALUE;
        long written = 0;
        try (AudioInputStream s = pcm) {
            line.start();
            byte[] buf = new byte[f.getFrameSize() * 4096];
            int n;
            while (written < limitBytes && (n = s.read(buf, 0, (int) Math.min(buf.length, limitBytes - written))) > 0) {
                line.write(buf, 0, n);
                written += n;
            }
            line.drain();
        } finally {
            line.stop();
            line.close();
        }
        return written / f.getFrameSize();
    }

    private static SourceDataLine openLine(AudioFormat f) throws LineUnavailableException {
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, f));
        line.open(f, (int) (f.getFrameRate() / 4) * f.getFrameSize());
        return line;
    }
}
