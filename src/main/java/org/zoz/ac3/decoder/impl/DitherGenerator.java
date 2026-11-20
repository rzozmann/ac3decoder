package org.zoz.ac3.decoder.impl;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Generators for the zero-bit mantissa dither of A/52 section 7.3.4: uniformly distributed
 * values in about -0.707 .. +0.707, in the {@link Ac3Tables#FRACTION_BITS} fixed point format.
 *
 * <p>The standard allows "any reasonably random sequence". Two generators are provided:</p>
 * <ul>
 * <li>{@link #xorshift(int)}: a simple independent 32-bit xorshift generator (default),</li>
 * <li>{@link #reference()}: the additive lagged Fibonacci generator with MD5 seeding of the
 *     ffmpeg reference decoder ({@code av_lfg_init(&dith_state, 0)}), which makes the decoder
 *     output reproducible sample by sample against ffmpeg.</li>
 * </ul>
 */
abstract class DitherGenerator {

    /** Next raw 32 bit random value. */
    abstract int nextInt();

    /** Next dither mantissa: {@code ((r >>> 8) * 181 >>> 8) - 5931008}, i.e. 0.707 * U(-1, 1) * 2^23. */
    final int nextMantissa() {
        long u = nextInt() & 0xffffffffL;
        return (int) ((((u >>> 8) * 181) >>> 8) - 5931008);
    }

    static DitherGenerator xorshift(int seed) {
        return new Xorshift(seed);
    }

    static DitherGenerator reference() {
        return new LaggedFibonacci(0);
    }

    private static final class Xorshift extends DitherGenerator {
        private int s;

        Xorshift(int seed) {
            s = seed == 0 ? 0x9E3779B9 : seed;
        }

        @Override
        int nextInt() {
            s ^= s << 13;
            s ^= s >>> 17;
            s ^= s << 5;
            return s;
        }
    }

    private static final class LaggedFibonacci extends DitherGenerator {
        private final int[] state = new int[64];
        private int index;

        LaggedFibonacci(int seed) {
            MessageDigest md5;
            try {
                md5 = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            byte[] tmp = new byte[16];
            for (int i = 8; i < 64; i += 4) {
                tmp[0] = (byte) seed;
                tmp[1] = (byte) (seed >>> 8);
                tmp[2] = (byte) (seed >>> 16);
                tmp[3] = (byte) (seed >>> 24);
                tmp[4] = (byte) i;
                tmp = md5.digest(tmp);
                for (int w = 0; w < 4; w++) {
                    state[i + w] = (tmp[4 * w] & 0xff) | (tmp[4 * w + 1] & 0xff) << 8
                        | (tmp[4 * w + 2] & 0xff) << 16 | (tmp[4 * w + 3] & 0xff) << 24;
                }
            }
            index = 0;
        }

        @Override
        int nextInt() {
            int a = state[(index - 24) & 63] + state[(index - 55) & 63];
            state[index & 63] = a;
            index++;
            return a;
        }
    }
}
