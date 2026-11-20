package org.zoz.ac3.decoder.spi;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.spi.AudioFileReader;

import org.zoz.ac3.decoder.impl.Ac3DecodeException;
import org.zoz.ac3.decoder.impl.Ac3Decoder;
import org.zoz.ac3.decoder.impl.Ac3FrameHeader;

/**
 * {@link AudioFileReader} for raw AC-3 elementary streams (*.ac3).
 *
 * <p>The returned {@link AudioInputStream} delivers the compressed stream in the
 * {@link Ac3Encoding#AC3} encoding; one frame of the stream corresponds to one syncframe.
 * Convert it to PCM with {@link AudioSystem#getAudioInputStream(AudioFormat.Encoding, AudioInputStream)},
 * which uses {@link Ac3FormatConversionProvider}.</p>
 *
 * <p>Detection requires the first syncframe to start within the first {@value #MAX_LEADING_BYTES}
 * bytes and to be followed by a second sync word, so that arbitrary files are not mistaken
 * for AC-3.</p>
 */
public class Ac3AudioFileReader extends AudioFileReader {

    /** Maximum number of bytes of leading garbage before the first syncframe. */
    static final int MAX_LEADING_BYTES = 4096;
    private static final int PROBE_BYTES = MAX_LEADING_BYTES + 2 * 3840 + 2;

    @Override
    public AudioFileFormat getAudioFileFormat(InputStream stream) throws UnsupportedAudioFileException, IOException {
        return probe(stream, null).fileFormat;
    }

    @Override
    public AudioFileFormat getAudioFileFormat(URL url) throws UnsupportedAudioFileException, IOException {
        try (InputStream in = new BufferedInputStream(url.openStream())) {
            return probe(in, null).fileFormat;
        }
    }

    @Override
    public AudioFileFormat getAudioFileFormat(File file) throws UnsupportedAudioFileException, IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            return probe(in, file).fileFormat;
        }
    }

    @Override
    public AudioInputStream getAudioInputStream(InputStream stream) throws UnsupportedAudioFileException, IOException {
        Probe p = probe(stream, null);
        stream.skipNBytes(p.offset);
        return new AudioInputStream(stream, p.fileFormat.getFormat(), AudioSystem.NOT_SPECIFIED);
    }

    @Override
    public AudioInputStream getAudioInputStream(URL url) throws UnsupportedAudioFileException, IOException {
        InputStream in = new BufferedInputStream(url.openStream());
        try {
            return getAudioInputStream(in);
        } catch (UnsupportedAudioFileException | IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    @Override
    public AudioInputStream getAudioInputStream(File file) throws UnsupportedAudioFileException, IOException {
        InputStream in = new BufferedInputStream(Files.newInputStream(file.toPath()));
        try {
            Probe p = probe(in, file);
            in.skipNBytes(p.offset);
            // The length of the compressed stream is not announced: AudioInputStream would limit
            // the readable bytes to frameLength * frameSize, but AC-3 frames at 44.1 kHz differ in
            // size. The exact number of syncframes (if known) is carried as a format property.
            return new AudioInputStream(in, p.fileFormat.getFormat(), AudioSystem.NOT_SPECIFIED);
        } catch (UnsupportedAudioFileException | IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    private static final class Probe {
        AudioFileFormat fileFormat;
        int offset;
    }

    /**
     * Inspects the start of the stream (mark/reset) and builds the file format.
     *
     * @param file the file the stream reads from, if any (used to count the syncframes)
     */
    private Probe probe(InputStream stream, File file) throws UnsupportedAudioFileException, IOException {
        if (!stream.markSupported()) {
            throw new IOException("mark/reset not supported by the stream");
        }
        stream.mark(PROBE_BYTES);
        byte[] buf = new byte[PROBE_BYTES];
        int n;
        try {
            n = stream.readNBytes(buf, 0, PROBE_BYTES);
        } finally {
            stream.reset();
        }
        for (int off = 0; off <= Math.min(MAX_LEADING_BYTES, n - Ac3FrameHeader.SYNCINFO_BYTES); off++) {
            Ac3FrameHeader sync = Ac3FrameHeader.parseSyncInfo(buf, off, n - off);
            if (sync == null) {
                continue;
            }
            int next = off + sync.getFrameBytes();
            boolean followed = next + 1 < n
                && (((buf[next] & 0xff) << 8) | (buf[next + 1] & 0xff)) == 0x0B77;
            boolean single = next == n; // stream with exactly one frame
            if (!followed && !single) {
                continue;
            }
            Ac3FrameHeader h;
            try {
                h = Ac3FrameHeader.parse(buf, off, n - off);
            } catch (Ac3DecodeException e) {
                continue;
            }
            Probe p = new Probe();
            p.offset = off;
            long syncframes = file == null ? -1 : countSyncframes(file, off);
            long streamBytes = file == null ? -1 : file.length() - off;
            p.fileFormat = createFileFormat(h, syncframes, streamBytes);
            return p;
        }
        throw new UnsupportedAudioFileException("not an AC-3 stream");
    }

    /**
     * Counts the syncframes of a file by hopping from header to header (6 bytes read per frame).
     *
     * @return the exact number of complete syncframes, or -1 if the file contains anything
     *     other than consecutive syncframes (in that case the length is not known exactly)
     */
    static long countSyncframes(File file, long offset) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            long pos = offset;
            long count = 0;
            byte[] hdr = new byte[Ac3FrameHeader.SYNCINFO_BYTES];
            while (pos + Ac3FrameHeader.SYNCINFO_BYTES <= length) {
                raf.seek(pos);
                raf.readFully(hdr);
                Ac3FrameHeader h = Ac3FrameHeader.parseSyncInfo(hdr, 0, hdr.length);
                if (h == null) {
                    return -1; // not a plain sequence of syncframes
                }
                if (pos + h.getFrameBytes() > length) {
                    break; // truncated last frame, dropped by the decoder
                }
                pos += h.getFrameBytes();
                count++;
            }
            return count;
        }
    }

    static AudioFormat createFormat(Ac3FrameHeader h) {
        return createFormat(h, -1);
    }

    /**
     * Compressed format of the stream. Frame size is not specified because the size of AC-3
     * syncframes at 44.1 kHz varies by one 16 bit word; one frame of the format is one syncframe.
     */
    static AudioFormat createFormat(Ac3FrameHeader h, long syncframes) {
        Map<String, Object> props = new HashMap<>();
        props.put(Ac3Encoding.PROP_BITRATE, h.getBitRate());
        props.put(Ac3Encoding.PROP_CODING_MODE, h.getAudioCodingModeName());
        props.put(Ac3Encoding.PROP_BSID, h.getBsid());
        props.put(Ac3Encoding.PROP_BSMOD, h.getBsmod());
        props.put(Ac3Encoding.PROP_DIALNORM, h.getDialogueNormalization());
        if (syncframes >= 0) {
            props.put(Ac3Encoding.PROP_SYNCFRAMES, syncframes);
        }
        float frameRate = h.getSampleRate() / (float) Ac3Decoder.SAMPLES_PER_FRAME;
        return new AudioFormat(Ac3Encoding.AC3, h.getSampleRate(), AudioSystem.NOT_SPECIFIED, h.getChannels(),
            AudioSystem.NOT_SPECIFIED, frameRate, true, props);
    }

    private static AudioFileFormat createFileFormat(Ac3FrameHeader h, long syncframes, long streamBytes) {
        AudioFormat format = createFormat(h, syncframes);
        Map<String, Object> props = new HashMap<>();
        int frames = AudioSystem.NOT_SPECIFIED;
        int bytes = AudioSystem.NOT_SPECIFIED;
        long frameCount = syncframes;
        if (frameCount < 0 && streamBytes > 0) {
            // estimate from the constant bit rate: average syncframe = bitrate * 1536 / 8 / fs bytes
            frameCount = Math.round(streamBytes * 8.0 * h.getSampleRate() / ((double) h.getBitRate() * Ac3Decoder.SAMPLES_PER_FRAME));
        }
        if (frameCount >= 0) {
            frames = (int) Math.min(Integer.MAX_VALUE, frameCount);
            props.put(Ac3Encoding.PROP_DURATION, frameCount * Ac3Decoder.SAMPLES_PER_FRAME * 1_000_000L / h.getSampleRate());
        }
        if (streamBytes > 0) {
            bytes = (int) Math.min(Integer.MAX_VALUE, streamBytes);
        }
        props.put(Ac3Encoding.PROP_BITRATE, h.getBitRate());
        final int byteLength = bytes;
        return new AudioFileFormat(Ac3Encoding.AC3_FILE, format, frames, props) {
            @Override
            public int getByteLength() {
                return byteLength;
            }
        };
    }
}
