package org.zoz.ac3.decoder.spi;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.spi.FormatConversionProvider;

import org.zoz.ac3.decoder.impl.Ac3Decoder;
import org.zoz.ac3.decoder.impl.Ac3DecoderOptions;
import org.zoz.ac3.decoder.impl.Ac3DecoderOptions.OutputMode;

/**
 * {@link FormatConversionProvider} decoding {@link Ac3Encoding#AC3} streams to PCM.
 *
 * <p>Target formats: {@code PCM_SIGNED} 16 bit or {@code PCM_FLOAT} 32 bit, little or big
 * endian, with the sample rate of the source and either the coded channel count (native
 * layout L, R, C, LFE, Ls, Rs) or a stereo (Lo/Ro) or mono downmix.</p>
 *
 * <p>Decoder options can be set with system properties:</p>
 * <ul>
 * <li>{@code ac3.dither} = {@code standard} (default) | {@code ffmpeg} | {@code none}</li>
 * <li>{@code ac3.drc} = fraction of dynamic range compression 0.0 .. 1.0 (default 1.0)</li>
 * <li>{@code ac3.heavyCompression} = {@code true} | {@code false}</li>
 * <li>{@code ac3.stereoDownmix} = {@code loro} (default) | {@code ltrt}</li>
 * </ul>
 */
public class Ac3FormatConversionProvider extends FormatConversionProvider {

    private static final AudioFormat.Encoding[] SOURCE = {Ac3Encoding.AC3};
    private static final AudioFormat.Encoding[] TARGET = {AudioFormat.Encoding.PCM_SIGNED, AudioFormat.Encoding.PCM_FLOAT};
    private static final AudioFormat.Encoding[] NONE = {};

    @Override
    public AudioFormat.Encoding[] getSourceEncodings() {
        return SOURCE.clone();
    }

    @Override
    public AudioFormat.Encoding[] getTargetEncodings() {
        return TARGET.clone();
    }

    @Override
    public AudioFormat.Encoding[] getTargetEncodings(AudioFormat sourceFormat) {
        return isAc3(sourceFormat) ? TARGET.clone() : NONE;
    }

    @Override
    public AudioFormat[] getTargetFormats(AudioFormat.Encoding targetEncoding, AudioFormat sourceFormat) {
        if (!isAc3(sourceFormat) || !isTarget(targetEncoding)) {
            return new AudioFormat[0];
        }
        float rate = sourceFormat.getSampleRate();
        Set<Integer> channelOptions = new LinkedHashSet<>();
        if (sourceFormat.getChannels() > 0) {
            channelOptions.add(sourceFormat.getChannels());
        }
        channelOptions.add(2);
        channelOptions.add(1);
        int bits = targetEncoding.equals(AudioFormat.Encoding.PCM_FLOAT) ? 32 : 16;
        List<AudioFormat> formats = new ArrayList<>();
        for (int ch : channelOptions) {
            for (boolean bigEndian : new boolean[] {false, true}) {
                formats.add(new AudioFormat(targetEncoding, rate, bits, ch, ch * bits / 8, rate, bigEndian));
            }
        }
        return formats.toArray(new AudioFormat[0]);
    }

    @Override
    public AudioInputStream getAudioInputStream(AudioFormat.Encoding targetEncoding, AudioInputStream sourceStream) {
        AudioFormat src = sourceStream.getFormat();
        if (!isAc3(src) || !isTarget(targetEncoding)) {
            throw new IllegalArgumentException("conversion not supported: " + src + " -> " + targetEncoding);
        }
        int bits = targetEncoding.equals(AudioFormat.Encoding.PCM_FLOAT) ? 32 : 16;
        int ch = src.getChannels() > 0 ? src.getChannels() : 2;
        float rate = src.getSampleRate();
        AudioFormat target = new AudioFormat(targetEncoding, rate, bits, ch, ch * bits / 8, rate, false);
        return getAudioInputStream(target, sourceStream);
    }

    @Override
    public AudioInputStream getAudioInputStream(AudioFormat targetFormat, AudioInputStream sourceStream) {
        AudioFormat src = sourceStream.getFormat();
        if (!isConversionSupported(targetFormat, src)) {
            throw new IllegalArgumentException("conversion not supported: " + src + " -> " + targetFormat);
        }
        int channels = targetFormat.getChannels();
        OutputMode mode;
        if (channels == src.getChannels()) {
            mode = OutputMode.NATIVE;
        } else if (channels == 2) {
            mode = "ltrt".equalsIgnoreCase(System.getProperty("ac3.stereoDownmix")) ? OutputMode.STEREO_LTRT : OutputMode.STEREO;
        } else {
            mode = OutputMode.MONO;
        }
        Ac3DecoderOptions options = optionsFromSystemProperties().outputMode(mode);
        int bits = targetFormat.getSampleSizeInBits();
        DecodedAc3InputStream pcm = new DecodedAc3InputStream(sourceStream, options, channels, bits,
            targetFormat.isBigEndian());
        float rate = src.getSampleRate();
        AudioFormat actual = new AudioFormat(targetFormat.getEncoding(), rate, bits, channels, channels * bits / 8,
            rate, targetFormat.isBigEndian());
        long frames = AudioSystem.NOT_SPECIFIED;
        Object syncframes = src.getProperty(Ac3Encoding.PROP_SYNCFRAMES);
        if (sourceStream.getFrameLength() != AudioSystem.NOT_SPECIFIED) {
            frames = sourceStream.getFrameLength() * Ac3Decoder.SAMPLES_PER_FRAME;
        } else if (syncframes instanceof Long n && n >= 0) {
            frames = n * Ac3Decoder.SAMPLES_PER_FRAME;
        }
        return new AudioInputStream(pcm, actual, frames);
    }

    @Override
    public boolean isConversionSupported(AudioFormat targetFormat, AudioFormat sourceFormat) {
        if (!isAc3(sourceFormat) || !isTarget(targetFormat.getEncoding())) {
            return false;
        }
        int bits = targetFormat.getSampleSizeInBits();
        boolean isFloat = targetFormat.getEncoding().equals(AudioFormat.Encoding.PCM_FLOAT);
        if (bits != AudioSystem.NOT_SPECIFIED && bits != (isFloat ? 32 : 16)) {
            return false;
        }
        float rate = targetFormat.getSampleRate();
        if (rate != AudioSystem.NOT_SPECIFIED && sourceFormat.getSampleRate() != AudioSystem.NOT_SPECIFIED
            && Math.abs(rate - sourceFormat.getSampleRate()) > 0.5f) {
            return false;
        }
        int ch = targetFormat.getChannels();
        return ch == 1 || ch == 2 || ch == sourceFormat.getChannels();
    }

    static Ac3DecoderOptions optionsFromSystemProperties() {
        Ac3DecoderOptions o = Ac3DecoderOptions.defaults();
        String dither = System.getProperty("ac3.dither", "standard");
        o.ditherMode(switch (dither.toLowerCase()) {
            case "ffmpeg" -> Ac3DecoderOptions.DitherMode.FFMPEG_COMPATIBLE;
            case "none" -> Ac3DecoderOptions.DitherMode.NONE;
            default -> Ac3DecoderOptions.DitherMode.STANDARD;
        });
        String drc = System.getProperty("ac3.drc");
        if (drc != null) {
            o.dynamicRangeScale(Double.parseDouble(drc));
        }
        o.heavyCompression(Boolean.getBoolean("ac3.heavyCompression"));
        return o;
    }

    private static boolean isAc3(AudioFormat f) {
        return f.getEncoding().equals(Ac3Encoding.AC3);
    }

    private static boolean isTarget(AudioFormat.Encoding e) {
        return e.equals(AudioFormat.Encoding.PCM_SIGNED) || e.equals(AudioFormat.Encoding.PCM_FLOAT);
    }

    /** Frame count of the decoded stream for a given number of syncframes. */
    static long pcmFrames(long syncframes) {
        return syncframes * Ac3Decoder.SAMPLES_PER_FRAME;
    }
}
