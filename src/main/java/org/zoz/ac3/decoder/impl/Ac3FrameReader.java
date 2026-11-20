package org.zoz.ac3.decoder.impl;

import java.io.IOException;
import java.io.InputStream;

/**
 * Splits an AC-3 elementary stream into syncframes (A/52 section 6.1.2, synchronization).
 *
 * <p>The reader searches for the sync word 0x0B77 followed by a valid syncinfo. While the
 * stream is in sync, consecutive frames are accepted directly. After a loss of sync (garbage
 * data, corrupted frame headers) a candidate frame is only accepted if it is followed by another
 * sync word (or the end of the stream), which makes false synchronization on random data that
 * happens to contain 0x0B77 unlikely.</p>
 */
public final class Ac3FrameReader {

    /** Largest possible syncframe: 1920 words (640 kbps at 32 kHz). */
    public static final int MAX_FRAME_BYTES = 3840;

    private final InputStream in;
    private final byte[] buf = new byte[4 * MAX_FRAME_BYTES];
    private int pos;
    private int limit;
    private boolean eof;
    private boolean inSync;
    private long skippedBytes;
    private long streamOffset;
    private long frameOffset = -1;

    private Ac3FrameHeader header;
    private int frameStart;

    public Ac3FrameReader(InputStream in) {
        this.in = in;
    }

    /**
     * Advances to the next syncframe.
     *
     * @return the syncinfo of the next frame (bsi not parsed), or {@code null} at the end of the stream
     */
    public Ac3FrameHeader next() throws IOException {
        if (header != null) {
            pos = frameStart + header.frameBytes;
            header = null;
        }
        while (true) {
            if (!fill(Ac3FrameHeader.SYNCINFO_BYTES)) {
                discard(limit - pos); // trailing bytes shorter than a syncinfo
                return null;
            }
            Ac3FrameHeader h = Ac3FrameHeader.parseSyncInfo(buf, pos, limit - pos);
            if (h == null) {
                inSync = false;
                discard(1);
                continue;
            }
            int need = h.frameBytes + (inSync ? 0 : 2);
            boolean complete = fill(need);
            if (!complete && limit - pos < h.frameBytes) {
                // truncated last frame
                discard(limit - pos);
                return null;
            }
            if (!inSync && complete) {
                int next = pos + h.frameBytes;
                if ((((buf[next] & 0xff) << 8) | (buf[next + 1] & 0xff)) != Ac3Tables.SYNC_WORD) {
                    discard(1);
                    continue;
                }
            }
            inSync = true;
            header = h;
            frameStart = pos;
            frameOffset = streamOffset + pos;
            return h;
        }
    }

    /** Buffer holding the current frame (valid until the next call of {@link #next()}). */
    public byte[] frameBuffer() {
        return buf;
    }

    /** Offset of the current frame in {@link #frameBuffer()}. */
    public int frameOffset() {
        return frameStart;
    }

    /** Byte position of the current frame in the stream. */
    public long frameStreamOffset() {
        return frameOffset;
    }

    /** Number of bytes skipped while searching for synchronization. */
    public long getSkippedBytes() {
        return skippedBytes;
    }

    private void discard(int n) {
        pos += n;
        skippedBytes += n;
    }

    /** Ensures that at least {@code n} bytes are available at {@code pos}. */
    private boolean fill(int n) throws IOException {
        if (limit - pos >= n) {
            return true;
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, limit - pos);
            limit -= pos;
            streamOffset += pos;
            pos = 0;
        }
        while (limit < n && !eof) {
            int r = in.read(buf, limit, buf.length - limit);
            if (r < 0) {
                eof = true;
            } else {
                limit += r;
            }
        }
        return limit - pos >= n;
    }
}
