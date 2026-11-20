package org.zoz.ac3.decoder.impl;

/**
 * MSB-first bit reader over a byte array (all AC-3 bit stream elements arrive most
 * significant bit first, section 5.3).
 *
 * <p>Reading past the end throws an {@link Ac3DecodeException}, which implements the
 * consistency check "bit stream unpacking continues past the end of the syncframe"
 * (section 7.10.2, condition 21).</p>
 */
final class BitReader {

    private final byte[] data;
    private final int endBit;
    private int pos;

    BitReader(byte[] data, int offset, int length) {
        this.data = data;
        this.pos = offset * 8;
        this.endBit = (offset + length) * 8;
    }

    /** Reads an unsigned value of {@code n} bits, 0 &lt;= n &lt;= 25. */
    int read(int n) {
        if (n == 0) {
            return 0;
        }
        if (pos + n > endBit) {
            throw new Ac3DecodeException("bit stream unpacking past the end of the syncframe");
        }
        int byteIndex = pos >>> 3;
        // gather 32 bits starting at byteIndex (zero padded beyond the array)
        int w = (get(byteIndex) << 24) | (get(byteIndex + 1) << 16) | (get(byteIndex + 2) << 8) | get(byteIndex + 3);
        int shift = pos & 7;
        pos += n;
        return (w << shift) >>> (32 - n);
    }

    /** Reads a two's complement signed value of {@code n} bits. */
    int readSigned(int n) {
        int v = read(n);
        return (v << (32 - n)) >> (32 - n);
    }

    boolean readBit() {
        return read(1) != 0;
    }

    void skip(int n) {
        if (pos + n > endBit) {
            throw new Ac3DecodeException("bit stream unpacking past the end of the syncframe");
        }
        pos += n;
    }

    int position() {
        return pos;
    }

    private int get(int i) {
        return i < data.length ? data[i] & 0xff : 0;
    }
}
