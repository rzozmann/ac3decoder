package org.zoz.ac3.decoder.test.spi;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Locates the reference AC-3 files and produces reference PCM with the ffmpeg command line
 * decoder (if installed).
 */
public final class TestFiles {

    private TestFiles() {
    }

    /** Directory with the reference *.ac3 files (system property ac3.testdata.dir, default: project dir). */
    public static Path dataDir() {
        return Path.of(System.getProperty("ac3.testdata.dir", "."));
    }

    public static Path workDir() throws IOException {
        Path dir = Path.of(System.getProperty("ac3.work.dir", "target/ac3-test"));
        Files.createDirectories(dir);
        return dir;
    }

    /** All *.ac3 files in the data directory, sorted by name. */
    public static List<File> referenceFiles() {
        File[] files = dataDir().toFile().listFiles((d, n) -> n.toLowerCase().endsWith(".ac3"));
        List<File> list = new ArrayList<>(files == null ? List.of() : Arrays.asList(files));
        list.sort(null);
        return list;
    }

    private static Boolean ffmpegAvailable;

    public static synchronized boolean ffmpegAvailable() {
        if (ffmpegAvailable == null) {
            try {
                Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                ffmpegAvailable = p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
            } catch (IOException | InterruptedException e) {
                ffmpegAvailable = false;
            }
        }
        return ffmpegAvailable;
    }

    /**
     * Decodes the file with ffmpeg and returns the error log (empty if ffmpeg decoded every frame
     * without complaint).
     */
    public static String ffmpegErrors(File ac3) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("ffmpeg", "-v", "error", "-nostdin", "-i", ac3.getAbsolutePath(), "-f", "null", "-")
            .redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor(10, TimeUnit.MINUTES);
        return log;
    }

    /**
     * Decodes {@code ac3} with ffmpeg to interleaved 32 bit float PCM (cached in the work dir).
     *
     * @param downmix ffmpeg "-downmix" layout ("stereo", "mono") or null for the native layout
     */
    public static float[] ffmpegDecode(File ac3, String downmix) throws IOException, InterruptedException {
        String name = ac3.getName().replaceAll("\\.ac3$", "") + (downmix == null ? "" : "-" + downmix) + ".f32";
        Path out = workDir().resolve(name);
        if (!Files.exists(out) || Files.getLastModifiedTime(out).compareTo(Files.getLastModifiedTime(ac3.toPath())) < 0) {
            List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-v", "error", "-y"));
            if (downmix != null) {
                cmd.addAll(List.of("-downmix", downmix));
            }
            cmd.addAll(List.of("-i", ac3.getAbsolutePath(), "-f", "f32le", "-acodec", "pcm_f32le", out.toString()));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String log = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
                throw new IOException("ffmpeg failed: " + log);
            }
        }
        byte[] bytes = Files.readAllBytes(out);
        FloatBuffer fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] pcm = new float[fb.remaining()];
        fb.get(pcm);
        return pcm;
    }
}
