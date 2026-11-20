package org.zoz.ac3.decoder.spi;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;

/** Java Sound constants of the AC-3 SPI. */
public final class Ac3Encoding {

    /** Encoding of AC-3 (Dolby Digital) compressed audio. */
    public static final AudioFormat.Encoding AC3 = new AudioFormat.Encoding("AC3");

    /** Raw AC-3 elementary stream file type, extension "ac3". */
    public static final AudioFileFormat.Type AC3_FILE = new AudioFileFormat.Type("AC3", "ac3");

    /** {@link AudioFormat} property: nominal bit rate in bits per second (Integer). */
    public static final String PROP_BITRATE = "bitrate";
    /** {@link AudioFormat} property: audio coding mode, e.g. "3/2+LFE" (String). */
    public static final String PROP_CODING_MODE = "ac3.acmod";
    /** {@link AudioFormat} property: bit stream identification (Integer). */
    public static final String PROP_BSID = "ac3.bsid";
    /** {@link AudioFormat} property: bit stream mode (Integer). */
    public static final String PROP_BSMOD = "ac3.bsmod";
    /** {@link AudioFormat} property: dialogue normalization in dB below full scale (Integer). */
    public static final String PROP_DIALNORM = "ac3.dialnorm";
    /** {@link AudioFormat} property: exact number of syncframes of the stream, if known (Long). */
    public static final String PROP_SYNCFRAMES = "ac3.syncframes";
    /** {@link AudioFileFormat} property: duration in microseconds (Long). */
    public static final String PROP_DURATION = "duration";

    private Ac3Encoding() {
    }
}
