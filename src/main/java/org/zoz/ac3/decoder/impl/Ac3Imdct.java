package org.zoz.ac3.decoder.impl;

/**
 * Inverse transform, windowing and overlap/add of A/52 section 7.9.4, implemented literally
 * with the N/4 (long block) and N/8 (block switched) point complex IFFT algorithm of the
 * standard, N = 512.
 */
final class Ac3Imdct {

    private static final int N = 512;
    private static final double[] W = Ac3Tables.WINDOW;

    private final double[] xcos1 = new double[N / 4];
    private final double[] xsin1 = new double[N / 4];
    private final double[] xcos2 = new double[N / 8];
    private final double[] xsin2 = new double[N / 8];
    private final Fft fft128 = new Fft(N / 4);
    private final Fft fft64 = new Fft(N / 8);

    private final double[] re = new double[N / 4];
    private final double[] im = new double[N / 4];
    private final double[] re2 = new double[N / 8];
    private final double[] im2 = new double[N / 8];
    private final double[] yr = new double[N / 4];
    private final double[] yi = new double[N / 4];
    private final double[] yr2 = new double[N / 8];
    private final double[] yi2 = new double[N / 8];
    private final double[] x = new double[N];

    Ac3Imdct() {
        for (int k = 0; k < N / 4; k++) {
            double a = 2.0 * Math.PI * (8 * k + 1) / (8.0 * N);
            xcos1[k] = -Math.cos(a);
            xsin1[k] = -Math.sin(a);
        }
        for (int k = 0; k < N / 8; k++) {
            double a = 2.0 * Math.PI * (8 * k + 1) / (4.0 * N);
            xcos2[k] = -Math.cos(a);
            xsin2[k] = -Math.sin(a);
        }
    }

    /**
     * Transforms one audio block of one channel.
     *
     * @param coeffs 256 transform coefficients X[k] (fractional values)
     * @param shortBlocks blksw[ch]: true for two 256-sample transforms
     * @param delay 256-sample overlap state of the channel (updated)
     * @param out receives 256 PCM samples starting at {@code outOff}
     */
    void transform(double[] coeffs, boolean shortBlocks, double[] delay, float[] out, int outOff) {
        if (shortBlocks) {
            shortTransforms(coeffs);
        } else {
            longTransform(coeffs);
        }
        // 6) overlap and add
        for (int n = 0; n < N / 2; n++) {
            out[outOff + n] = (float) (2.0 * (x[n] + delay[n]));
            delay[n] = x[N / 2 + n];
        }
    }

    /** Section 7.9.4.1, 512-sample IMDCT. */
    private void longTransform(double[] xk) {
        // 2) pre-IFFT complex multiply
        for (int k = 0; k < N / 4; k++) {
            double a = xk[N / 2 - 2 * k - 1];
            double b = xk[2 * k];
            re[k] = a * xcos1[k] - b * xsin1[k];
            im[k] = b * xcos1[k] + a * xsin1[k];
        }
        // 3) complex IFFT
        fft128.inverse(re, im);
        // 4) post-IFFT complex multiply
        for (int n = 0; n < N / 4; n++) {
            yr[n] = re[n] * xcos1[n] - im[n] * xsin1[n];
            yi[n] = im[n] * xcos1[n] + re[n] * xsin1[n];
        }
        // 5) windowing and de-interleaving
        for (int n = 0; n < N / 8; n++) {
            x[2 * n] = -yi[N / 8 + n] * W[2 * n];
            x[2 * n + 1] = yr[N / 8 - n - 1] * W[2 * n + 1];
            x[N / 4 + 2 * n] = -yr[n] * W[N / 4 + 2 * n];
            x[N / 4 + 2 * n + 1] = yi[N / 4 - n - 1] * W[N / 4 + 2 * n + 1];
            x[N / 2 + 2 * n] = -yr[N / 8 + n] * W[N / 2 - 2 * n - 1];
            x[N / 2 + 2 * n + 1] = yi[N / 8 - n - 1] * W[N / 2 - 2 * n - 2];
            x[3 * N / 4 + 2 * n] = yi[n] * W[N / 4 - 2 * n - 1];
            x[3 * N / 4 + 2 * n + 1] = -yr[N / 4 - n - 1] * W[N / 4 - 2 * n - 2];
        }
    }

    /** Section 7.9.4.2, two 256-sample IMDCTs. */
    private void shortTransforms(double[] xk) {
        // 1) de-interleave X1[k] = X[2k], X2[k] = X[2k+1]; 2) pre-IFFT complex multiply
        for (int k = 0; k < N / 8; k++) {
            double a1 = xk[2 * (N / 4 - 2 * k - 1)];
            double b1 = xk[2 * (2 * k)];
            double a2 = xk[2 * (N / 4 - 2 * k - 1) + 1];
            double b2 = xk[2 * (2 * k) + 1];
            re[k] = a1 * xcos2[k] - b1 * xsin2[k];
            im[k] = b1 * xcos2[k] + a1 * xsin2[k];
            re2[k] = a2 * xcos2[k] - b2 * xsin2[k];
            im2[k] = b2 * xcos2[k] + a2 * xsin2[k];
        }
        // 3) complex IFFTs (the 128 point buffers are used for z1 with only 64 entries)
        System.arraycopy(re, 0, yr2, 0, N / 8);
        System.arraycopy(im, 0, yi2, 0, N / 8);
        fft64.inverse(yr2, yi2);
        fft64.inverse(re2, im2);
        // 4) post-IFFT complex multiply: y1 -> yr/yi, y2 -> re2/im2 (in place)
        for (int n = 0; n < N / 8; n++) {
            double zr1 = yr2[n];
            double zi1 = yi2[n];
            yr[n] = zr1 * xcos2[n] - zi1 * xsin2[n];
            yi[n] = zi1 * xcos2[n] + zr1 * xsin2[n];
            double zr2 = re2[n];
            double zi2 = im2[n];
            re2[n] = zr2 * xcos2[n] - zi2 * xsin2[n];
            im2[n] = zi2 * xcos2[n] + zr2 * xsin2[n];
        }
        double[] yr1 = yr;
        double[] yi1 = yi;
        double[] y2r = re2;
        double[] y2i = im2;
        // 5) windowing and de-interleaving
        for (int n = 0; n < N / 8; n++) {
            x[2 * n] = -yi1[n] * W[2 * n];
            x[2 * n + 1] = yr1[N / 8 - n - 1] * W[2 * n + 1];
            x[N / 4 + 2 * n] = -yr1[n] * W[N / 4 + 2 * n];
            x[N / 4 + 2 * n + 1] = yi1[N / 8 - n - 1] * W[N / 4 + 2 * n + 1];
            x[N / 2 + 2 * n] = -y2r[n] * W[N / 2 - 2 * n - 1];
            x[N / 2 + 2 * n + 1] = y2i[N / 8 - n - 1] * W[N / 2 - 2 * n - 2];
            x[3 * N / 4 + 2 * n] = y2i[n] * W[N / 4 - 2 * n - 1];
            x[3 * N / 4 + 2 * n + 1] = -y2r[N / 8 - n - 1] * W[N / 4 - 2 * n - 2];
        }
    }
}
