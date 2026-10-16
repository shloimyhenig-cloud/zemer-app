# mp3lame

Pure-JVM MP3 encoder used by **Build My Collection** to save downloads as real MP3 files.

- `src/main/java/com/zemer/lame/{mp3,mpg}` — an unmodified copy (only the package names changed) of
  the Java port of LAME 3.98 ("jump3r", by Ken Händel), taken from the `src/main/java` folder of the
  `lamejs` npm package. Licensed under the **GNU LGPL v2 or later**; each file keeps its original
  copyright header. The CLI (`Main`, `LameTest`), the Swing UI and the `javax.sound`-based
  `lowlevel` wrappers were left out because Android has neither.
- `src/main/kotlin/com/zemer/lame/Mp3StreamEncoder.kt` — streaming 16-bit PCM → CBR MP3 wrapper.
- `src/main/kotlin/com/zemer/lame/Id3v2Tag.kt` — ID3v2.3 tag writer (title, artist, album artist,
  album, year, track n/total, genre, front cover).

Note: this port's `lame_encode_buffer_int` expects samples in the ±32768 range (unlike C LAME, it
does not rescale 32-bit input); `Mp3StreamEncoder` feeds it accordingly and a round-trip level test
guards it.

Tests: `./gradlew :mp3lame:test`.
