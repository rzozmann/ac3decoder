package org.zoz.ac3.decoder.impl;

/**
 * CRC of section 7.10.1, generator polynomial x^16 + x^15 + x^2 + 1, register reset to zero,
 * data shifted in MSB first. A syncframe (or its first 5/8) is valid if the register is zero
 * after all covered bits, the sync word excluded, have been shifted in.
 */
public final class Crc16 {

    private static final int POLY = 0x8005;
    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i << 8;
            for (int b = 0; b < 8; b++) {
                c = (c & 0x8000) != 0 ? (c << 1) ^ POLY : c << 1;
            }
            TABLE[i] = c & 0xffff;
        }
    }

    private Crc16() {
    }

    /** CRC register after shifting {@code len} bytes starting at {@code off} into a zeroed register. */
    public static int compute(byte[] data, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc = ((crc << 8) ^ TABLE[((crc >>> 8) ^ data[i]) & 0xff]) & 0xffff;
        }
        return crc;
    }

    /** Checks crc1 (first 5/8 of the frame) of a complete syncframe starting at {@code off}. */
    public static boolean checkCrc1(byte[] frame, int off, int frameBytes) {
        int words = frameBytes >> 1;
        int fiveEighthsBytes = ((words >> 1) + (words >> 3)) * 2;
        return compute(frame, off + 2, fiveEighthsBytes - 2) == 0;
    }

    /** Checks crc2 (whole frame excluding the sync word) of a complete syncframe. */
    public static boolean checkCrc2(byte[] frame, int off, int frameBytes) {
        return compute(frame, off + 2, frameBytes - 2) == 0;
    }
}
