package org.zoz.ac3.decoder.spi;

import java.io.IOException;
import java.io.InputStream;

import org.zoz.ac3.decoder.impl.Ac3Decoder;
import org.zoz.ac3.decoder.impl.Ac3DecoderOptions;
import org.zoz.ac3.decoder.impl.Ac3FrameHeader;
import org.zoz.ac3.decoder.impl.Ac3FrameReader;

/**
 * Byte stream of interleaved PCM decoded from an AC-3 elementary stream.
 *
 * <p>Supported sample formats are 16 bit signed integer (with rounding and saturation, as
 * required by section 7.9.4: "the arithmetic processing in the overlap/add processing must use
 * saturation arithmetic") and 32 bit IEEE float, both in either byte order. If the channel
 * configuration of the stream changes, the output keeps the channel count of the first frame:
 * missing channels are filled with silence and extra channels are dropped.</p>
 */
public final class DecodedAc3InputStream extends InputStream {

    private final Ac3FrameReader reader;
    private final Ac3Decoder decoder;
    private final int channels;
    private final boolean floatOutput;
    private final boolean bigEndian;
    private final int bytesPerSample;
    private final InputStream source;

    private final byte[] pcm;
    private int pcmPos;
    private int pcmLimit;
    private boolean eof;
    private boolean closed;

    /**
     * @param source compressed AC-3 elementary stream (positioned at or before the first syncframe)
     * @param options decoder options; the output mode determines the channel layout
     * @param channels number of output channels
     * @param sampleSizeInBits 16 (signed integer) or 32 (float)
     */
    public DecodedAc3InputStream(InputStream source, Ac3DecoderOptions options, int channels,
                                 int sampleSizeInBits, boolean bigEndian) {
        if (sampleSizeInBits != 16 && sampleSizeInBits != 32) {
            throw new IllegalArgumentException("unsupported sample size " + sampleSizeInBits);
        }
        this.source = source;
        this.reader = new Ac3FrameReader(source);
        this.decoder = new Ac3Decoder(options);
        this.channels = channels;
        this.floatOutput = sampleSizeInBits == 32;
        this.bigEndian = bigEndian;
        this.bytesPerSample = sampleSizeInBits / 8;
        this.pcm = new byte[Ac3Decoder.SAMPLES_PER_FRAME * channels * bytesPerSample];
    }

    /** The decoder used by this stream (for statistics such as muted frames). */
    public Ac3Decoder getDecoder() {
        return decoder;
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        int n = read(b, 0, 1);
        return n <= 0 ? -1 : b[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("stream closed");
        }
        if (len == 0) {
            return 0;
        }
        int total = 0;
        while (len > 0) {
            if (pcmPos == pcmLimit && !decodeNextFrame()) {
                break;
            }
            int n = Math.min(len, pcmLimit - pcmPos);
            System.arraycopy(pcm, pcmPos, b, off, n);
            pcmPos += n;
            off += n;
            len -= n;
            total += n;
        }
        return total == 0 ? -1 : total;
    }

    @Override
    public int available() {
        return pcmLimit - pcmPos;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        source.close();
    }

    private boolean decodeNextFrame() throws IOException {
        if (eof) {
            return false;
        }
        Ac3FrameHeader sync;
        while (true) {
            sync = reader.next();
            if (sync == null) {
                eof = true;
                return false;
            }
            try {
                decoder.decodeFrame(reader.frameBuffer(), reader.frameOffset(), sync.getFrameBytes());
                break;
            } catch (RuntimeException e) {
                // undecodable header (e.g. bsid > 8): skip the frame (section 7.10, frame dropped)
            }
        }
        float[][] out = decoder.getOutput();
        int available = Math.min(channels, decoder.getOutputChannels());
        int p = 0;
        for (int i = 0; i < Ac3Decoder.SAMPLES_PER_FRAME; i++) {
            for (int ch = 0; ch < channels; ch++) {
                float v = ch < available ? out[ch][i] : 0f;
                if (floatOutput) {
                    int bits = Float.floatToRawIntBits(v);
                    p = put(bits, 4, p);
                } else {
                    p = put(toShort(v), 2, p);
                }
            }
        }
        pcmPos = 0;
        pcmLimit = p;
        return true;
    }

    private int put(int v, int bytes, int p) {
        if (bigEndian) {
            for (int k = bytes - 1; k >= 0; k--) {
                pcm[p++] = (byte) (v >> (8 * k));
            }
        } else {
            for (int k = 0; k < bytes; k++) {
                pcm[p++] = (byte) (v >> (8 * k));
            }
        }
        return p;
    }

    /** Converts to 16 bit with rounding and saturation. */
    static int toShort(float v) {
        int s = Math.round(v * 32768f);
        return s > 32767 ? 32767 : Math.max(s, -32768);
    }
}
