# whisper.cpp-kmp

A [whisper.cpp](https://github.com/ggml-org/whisper.cpp) speech-to-text binding for
Kotlin Multiplatform. Transcribe audio on-device on **Android, iOS, and JVM/desktop**
from one Kotlin API, with results delivered as a coroutine [`Flow`] of segments.

Built on whisper.cpp **v1.8.6** (vendored as a git submodule), so it ships the 1.8.x
engine — flash attention on by default and optional **VAD** (Voice Activity Detection) —
rather than the older 1.7.1 that the JVM-only [whisper-jni](https://github.com/GiviMAD/whisper-jni)
bundles.

## Targets

| Target            | Backend                                            |
|-------------------|----------------------------------------------------|
| Android           | JNI + NDK/CMake (`arm64-v8a`, `x86_64`)            |
| JVM / desktop     | JNI, whisper.cpp linked static (Win/Linux/macOS)  |
| iOS / macOS       | Kotlin/Native cinterop, Metal-accelerated          |
| Wasm (`wasmJs`)   | stub — pending an emscripten whisper.cpp build      |

## Install

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.kmpile:whisper-cpp-kmp:<version>")
        }
    }
}
```

On iOS add **Accelerate.framework** and **Metal.framework** in Xcode.

## Usage

Audio must be **16 kHz mono PCM** as normalized `float` samples in `[-1, 1]`.

```kotlin
import com.kmpile.whisper.WhisperContext
import com.kmpile.whisper.TranscribeParams

val ctx = WhisperContext.fromFile("ggml-base.en.bin")
ctx.use {
    it.transcribe(samples, TranscribeParams(language = "en"))
        .collect { seg -> println("[${seg.startMs}-${seg.endMs}ms] ${seg.text}") }
}
```

Collect everything at once:

```kotlin
val segments = ctx.transcribeAll(samples)            // suspends until done
val text = segments.joinToString("") { it.text }
```

Cancelling the collecting coroutine aborts the in-flight transcription.

### VAD (whisper.cpp 1.8.x)

Skip silence and reduce hallucinations on long audio by supplying a VAD model:

```kotlin
import com.kmpile.whisper.VadParams

it.transcribe(
    samples,
    TranscribeParams(vad = VadParams(modelPath = "ggml-silero-v5.1.2.bin")),
).collect { /* ... */ }
```

## Building the natives

whisper.cpp is a submodule — clone with `--recurse-submodules` (or run
`git submodule update --init`).

- **Desktop**: built automatically by Gradle (`:core` invokes CMake; needs `cmake`
  and a C/C++ toolchain on `PATH`). CI can bundle prebuilt libs with
  `-PwhisperkmpPrebuiltNatives=true`.
- **Android**: built by `:native` via NDK + CMake (NDK ≥ 28).
- **iOS/macOS**: run `native/cpp/build-ios.sh` on a Mac, then build the `:core`
  apple targets.

## Versioning & releases

Versions track upstream whisper.cpp release tags (e.g. `v1.8.6`); binding-only
revisions on the same upstream append a numeric suffix (`v1.8.6.1`), which
Maven and Gradle order correctly: `v1.8.6 < v1.8.6.1 < v1.8.7`.
[sync-whispercpp.yml](.github/workflows/sync-whispercpp.yml) polls upstream
daily and bumps the pinned submodule.

Every push to `main` publishes a SNAPSHOT of the next version (e.g.
`v1.8.6.1-SNAPSHOT`) to the [Central Portal snapshots
repo](https://central.sonatype.com/repository/maven-snapshots/) via
[publish.yml](.github/workflows/publish.yml). Consume it with:

```kotlin
repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}
configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}
```

Immutable releases to Maven Central are currently disabled — snapshots are
the only published artifacts (see the header comment in publish.yml for how
to re-enable releases).

## License

MIT — see [LICENSE.md](LICENSE.md). whisper.cpp is MIT-licensed by its authors.
