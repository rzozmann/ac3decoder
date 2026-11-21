# AC-3 Java Sound SPI

A pure Java AC-3 (Dolby Digital) decoder implemented from **ATSC A/52:2018**
(`doc/A52-2018.pdf`). It plugs into `javax.sound.sampled` as a service provider, so any
Java Sound application can open and play `.ac3` files. There are no runtime dependencies.

```java
AudioInputStream ac3 = AudioSystem.getAudioInputStream(new File("movie.ac3"));
AudioFormat pcm = new AudioFormat(ac3.getFormat().getSampleRate(), 16,
        ac3.getFormat().getChannels(), true, false);
AudioInputStream decoded = AudioSystem.getAudioInputStream(pcm, ac3);   // -> SourceDataLine, WAV, ...
```

## Build and run

```bash
mvn package                                   # build + all tests (also plays the sample files, ~30 s)
mvn package -Dac3.playback.skip=true          # same, without audible playback
mvn test -Dtest=PlaybackTest -Dac3.playback.seconds=0    # play every reference file completely

java -jar target/ac3.decoder-1.0.0-SNAPSHOT.jar info   sample-1-5.1.ac3
java -jar target/ac3.decoder-1.0.0-SNAPSHOT.jar play   sample-1-5.1.ac3 [channels]
java -jar target/ac3.decoder-1.0.0-SNAPSHOT.jar decode sample-1-5.1.ac3 out.wav [channels]
```

Requires Java 11+. The tests use `ffmpeg` (if it is on the `PATH`) as the reference decoder.

## Features

| A/52 section | Implementation |
|---|---|
| 5.3 / 5.4 syntax: syncinfo, bsi, audblk, Annex D alternate bsi (`bsid` 6) | `Ac3FrameHeader`, `Ac3Decoder` |
| 6.1.2 synchronization, resync after damaged data | `Ac3FrameReader` |
| 7.1 exponents (D15/D25/D45, reuse) | `Ac3Decoder.decodeExponents` |
| 7.2 parametric bit allocation (integer, bit exact), delta bit allocation | `Ac3BitAllocation` |
| 7.3 mantissas: symmetric/asymmetric quantizers, grouping, dither | `Ac3Decoder.decodeMantissas`, `DitherGenerator` |
| 7.4 coupling incl. phase flags, 7.5 rematrixing | `Ac3Decoder.uncouple/rematrix` |
| 7.7 dynamic range (`dynrng`, partial compression) and heavy compression (`compr`) | `Ac3DecoderOptions` |
| 7.8 downmix Lo/Ro, Lt/Rt, mono (`cmixlev`/`surmixlev` or Annex D levels) | `Ac3Decoder.downmixMatrix` |
| 7.9 IMDCT 512 / 2×256 (block switching), window (Table 7.33), overlap/add | `Ac3Imdct`, `Fft` |
| 7.10 CRC1/CRC2 and the consistency checks; bad frames are muted | `Crc16`, `Ac3Decoder` |

All coding modes are supported: 1+1, 1/0, 2/0, 3/0, 2/1, 3/1, 2/2, 3/2, with or without LFE, at
32, 44.1 and 48 kHz and every bit rate. E-AC-3 (Annex E, `bsid` > 10) is not supported; those
frames are skipped.

The decoder outputs PCM as 16 bit signed or 32 bit float, in either byte order. Channels are
either the native channels (WAVE order L, R, C, LFE, Ls, Rs) or a stereo or mono downmix. Ask for
the format you want through `AudioSystem.getAudioInputStream(targetFormat, ac3Stream)`.

System properties for the SPI:

- `ac3.dither=standard|ffmpeg|none`
- `ac3.drc=0..1` (fraction of the dynamic range compression to apply)
- `ac3.heavyCompression=true`
- `ac3.stereoDownmix=loro|ltrt`

## Verification

The tests check the decoder in three independent ways:

1. **Against the specification** (`SpecificationTest`, `BlockSwitchingTest`). The tables are
   extracted from the PDF text, and the window is checked against Table 7.33. The tests also
   cover logadd, lowcomp, CRC, quantizer tables and dynrng/compr gains. The IMDCT is checked by
   a round trip through the forward transform of section 8.2.3.2: every long/short block
   transition reconstructs the input with an error below 3e-8.
2. **Against ffmpeg on the reference files** (`FfmpegReferenceTest`). Each `*.ac3` file in the
   project directory is decoded by `ffmpeg` and by this decoder, and the outputs are compared
   sample by sample. In dither mode `FFMPEG_COMPATIBLE`, the integer stages produce the same
   values as libavcodec, so only float rounding remains:

   | file | mode | frames | max abs error | SNR |
   |---|---|---|---|---|
   | ac3-audio-2-for-testing.ac3 | 2/0, 44.1 kHz, coupling and rematrixing | 6241 | 3.6e-7 | 139.9 dB |
   | sample-1-5.1.ac3 | 3/2+LFE, 48 kHz | 3003 | 1.2e-7 | 139.7 dB |
   | sample-2-5.1.ac3 | 3/2+LFE, 48 kHz | 463 | 1.2e-7 | 139.3 dB |

   The stereo (Lo/Ro) downmix matches `ffmpeg -downmix stereo` to the same precision. The mono
   downmix differs from ffmpeg only by a constant gain of √2. This decoder applies the extra ½
   scaling of Lo+Ro required by section 7.8.2; ffmpeg normalizes by 1/√2.
3. **Against ffmpeg on synthetic streams** (`SyntheticStreamTest`). The reference files and
   ffmpeg's AC-3 encoder never use block switching, `dynrng`, delta bit allocation or most
   channel modes. So `SyntheticStreamWriter` writes random but valid syncframes, with correct
   CRCs, that use these tools in all 8 coding modes, with and without LFE, at 32, 44.1 and
   48 kHz. Both decoders must accept every frame without error.
   - Without block switching, all 16 configurations match ffmpeg to float precision (≈139 dB).
   - With block switching, 12 of 16 configurations match exactly.

The default `STANDARD` dither follows section 7.3.4: an independent random sequence, applied
per channel after decoupling. Its output therefore differs from ffmpeg by the dither noise
alone, which is 70–85 dB below the signal on the 5.1 files.

### Known differences from ffmpeg

- **Mono downmix level.** See above: the spec asks for ½, ffmpeg uses 1/√2.
- **LFE gain in 1+1 mode.** The spec defines `dynrng` for Ch1 and `dynrng2` for Ch2, but says
  nothing about the LFE. ffmpeg applies `dynrng2` to it. The `FFMPEG_COMPATIBLE` mode does the
  same; the default mode uses `dynrng`.
- **Block switching in random synthetic streams.** In 4 of 16 synthetic configurations with
  block switching (2/0, 2/0+LFE, 3/0, 2/1+LFE), single blocks differ from ffmpeg. The test only
  bounds this case. Block switching on its own is not the cause: it is exact against the
  normative transform and matches ffmpeg at 139 dB on spec-encoded streams with all transitions.
  The cause has not been found. None of the reference files are affected.

