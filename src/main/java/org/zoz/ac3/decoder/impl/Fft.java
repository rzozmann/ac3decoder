package org.zoz.ac3.decoder.impl;

/**
 * In-place radix-2 complex inverse FFT without normalization:
 * {@code z[n] = sum_k Z[k] * (cos(2 pi k n / N) + j sin(2 pi k n / N))}
 * as required by the IMDCT steps of A/52 section 7.9.4.
 */
final class Fft {

    private final int n;
    private final int[] bitReverse;
    private final double[] cos;
    private final double[] sin;

    Fft(int n) {
        if (Integer.bitCount(n) != 1) {
            throw new IllegalArgumentException("size must be a power of two");
        }
        this.n = n;
        int bits = Integer.numberOfTrailingZeros(n);
        bitReverse = new int[n];
        for (int i = 0; i < n; i++) {
            bitReverse[i] = Integer.reverse(i) >>> (32 - bits);
        }
        cos = new double[n / 2];
        sin = new double[n / 2];
        for (int k = 0; k < n / 2; k++) {
            double a = 2.0 * Math.PI * k / n;
            cos[k] = Math.cos(a);
            sin[k] = Math.sin(a);
        }
    }

    void inverse(double[] re, double[] im) {
        for (int i = 0; i < n; i++) {
            int j = bitReverse[i];
            if (j > i) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1;
            int step = n / len;
            for (int i = 0; i < n; i += len) {
                for (int j = 0; j < half; j++) {
                    double wr = cos[j * step];
                    double wi = sin[j * step];
                    int a = i + j;
                    int b = a + half;
                    double tr = re[b] * wr - im[b] * wi;
                    double ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
    }
}
