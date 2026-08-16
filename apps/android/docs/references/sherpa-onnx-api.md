# Sherpa-ONNX Android API Reference

_Source: `~/sherpa-onnx/android/SherpaOnnxAar/` — actual Kotlin source, use as authoritative._
_Full source also at `~/sherpa-onnx/android/` — includes complete demo apps._

Native library: `System.loadLibrary("sherpa-onnx-jni")` — included in the AAR.
All classes live in package `com.k2fsa.sherpa.onnx`.

---

## 1. Wake Word — KeywordSpotter

### Config

```kotlin
data class KeywordSpotterConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OnlineModelConfig = OnlineModelConfig(),  // see §4
    var maxActivePaths: Int = 4,
    var keywordsFile: String = "keywords.txt",  // path relative to assets OR absolute path
    var keywordsScore: Float = 1.5f,
    var keywordsThreshold: Float = 0.25f,
    var numTrailingBlanks: Int = 2,
)
```

**keywords.txt format** (one keyword per line, use `/` as alternate separator):
```
hey marmalade
marmalade
ok marmalade
```

### Usage

```kotlin
// Initialize (assets-based)
val config = KeywordSpotterConfig(
    featConfig = getFeatureConfig(sampleRate = 16000, featureDim = 80),
    modelConfig = OnlineModelConfig(
        transducer = OnlineTransducerModelConfig(
            encoder = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/encoder-epoch-12-avg-2-chunk-16-left-64.onnx",
            decoder = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
            joiner  = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/joiner-epoch-12-avg-2-chunk-16-left-64.onnx",
        ),
        tokens = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/tokens.txt",
        modelType = "zipformer2",
    ),
    keywordsFile = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/keywords.txt",
)
val kws = KeywordSpotter(assetManager = context.assets, config = config)

// Create stream — can override keywords at runtime with a "/" separated string:
val stream = kws.createStream("hey marmalade/marmalade")

// Feed audio loop (call from background thread, 100ms chunks recommended):
// AudioRecord produces SHORT PCM; normalize to [-1, 1] floats
val samples = FloatArray(ret) { buffer[it] / 32768.0f }
stream.acceptWaveform(samples, sampleRate = 16000)
while (kws.isReady(stream)) {
    kws.decode(stream)
    val result = kws.getResult(stream)
    if (result.keyword.isNotBlank()) {
        kws.reset(stream)   // ← MUST reset immediately after detection
        // → wake word detected: result.keyword
    }
}

// Cleanup
stream.release()
kws.release()
```

### Result

```kotlin
data class KeywordSpotterResult(
    val keyword: String,        // detected keyword string; blank = no detection
    val tokens: Array<String>,
    val timestamps: FloatArray,
)
```

### Model download
```
https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models
→ sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2  (English)
→ sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01.tar.bz2 (Chinese)
```
Place extracted model folder under `app/src/main/assets/`.

---

## 2. Streaming STT — OnlineRecognizer

### Config

```kotlin
data class EndpointRule(
    var mustContainNonSilence: Boolean,
    var minTrailingSilence: Float,   // seconds
    var minUtteranceLength: Float,   // seconds
)

data class EndpointConfig(
    var rule1: EndpointRule = EndpointRule(false, 2.4f, 0.0f),  // trailing silence
    var rule2: EndpointRule = EndpointRule(true, 1.4f, 0.0f),   // silence after speech
    var rule3: EndpointRule = EndpointRule(false, 0.0f, 20.0f), // max utterance length
)

data class OnlineRecognizerConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OnlineModelConfig = OnlineModelConfig(),
    var endpointConfig: EndpointConfig = EndpointConfig(),
    var enableEndpoint: Boolean = true,
    var decodingMethod: String = "greedy_search",   // or "modified_beam_search"
    var maxActivePaths: Int = 4,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
    // ... (other fields rarely needed for initial scope)
)
```

### Usage

```kotlin
val config = OnlineRecognizerConfig(
    featConfig = getFeatureConfig(sampleRate = 16000, featureDim = 80),
    modelConfig = OnlineModelConfig(
        transducer = OnlineTransducerModelConfig(
            encoder = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/encoder-epoch-99-avg-1.onnx",
            decoder = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/decoder-epoch-99-avg-1.onnx",
            joiner  = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/joiner-epoch-99-avg-1.onnx",
        ),
        tokens = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/tokens.txt",
        modelType = "zipformer2",
        numThreads = 2,
        provider = "cpu",  // or "nnapi" for hardware acceleration on Pixel
    ),
    enableEndpoint = true,
    endpointConfig = EndpointConfig(
        rule1 = EndpointRule(false, 2.4f, 0.0f),  // 2400ms silence = end of speech
    ),
)
val recognizer = OnlineRecognizer(assetManager = context.assets, config = config)

// Create stream per utterance
val stream = recognizer.createStream()

// Feed audio (same pattern as KWS):
stream.acceptWaveform(samples, sampleRate = 16000)
while (recognizer.isReady(stream)) {
    recognizer.decode(stream)
}
val partialResult = recognizer.getResult(stream).text  // stream partial to UI

// Detect endpoint (user finished speaking):
if (recognizer.isEndpoint(stream)) {
    recognizer.reset(stream)  // reset for next utterance
    val finalText = recognizer.getResult(stream).text
}

stream.release()
recognizer.release()
```

### Result

```kotlin
data class OnlineRecognizerResult(
    val text: String,              // recognized text
    val tokens: Array<String>,
    val timestamps: FloatArray,
    val ysProbs: FloatArray,
)
```

---

## 3. VAD — Vad (Silero)

### Config

```kotlin
data class SileroVadModelConfig(
    var model: String = "",             // "silero_vad.onnx"
    var threshold: Float = 0.5F,        // speech detection threshold
    var minSilenceDuration: Float = 0.25F,  // seconds
    var minSpeechDuration: Float = 0.25F,   // seconds
    var windowSize: Int = 512,          // samples per window (512 for 16kHz)
    var maxSpeechDuration: Float = 5.0F,    // seconds
)

data class VadModelConfig(
    var sileroVadModelConfig: SileroVadModelConfig = SileroVadModelConfig(),
    var sampleRate: Int = 16000,
    var numThreads: Int = 1,
    var provider: String = "cpu",
    var debug: Boolean = false,
)
```

### Usage

```kotlin
val vadConfig = VadModelConfig(
    sileroVadModelConfig = SileroVadModelConfig(
        model = "silero_vad.onnx",
        threshold = 0.5F,
        minSilenceDuration = 0.25F,
        minSpeechDuration = 0.25F,
        windowSize = 512,
        maxSpeechDuration = 30.0F,  // increase for longer utterances
    ),
    sampleRate = 16000,
    numThreads = 1,
    provider = "cpu",
)
val vad = Vad(assetManager = context.assets, config = vadConfig)

// Feed audio in chunks:
vad.acceptWaveform(samples)

// Check if speech is currently detected:
val speaking = vad.isSpeechDetected()

// Get completed speech segments from the queue:
while (!vad.empty()) {
    val segment = vad.front()  // SpeechSegment(start: Int, samples: FloatArray)
    vad.pop()
    // segment.samples → send to STT
}

// Flush at end of stream:
vad.flush()

vad.release()
```

### Model download
```
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
```

---

## 4. TTS — OfflineTts (Piper/VITS)

### Config

```kotlin
data class OfflineTtsVitsModelConfig(
    var model: String = "",       // path to .onnx model
    var lexicon: String = "",     // path to lexicon.txt (optional for EN)
    var tokens: String = "",      // path to tokens.txt
    var dataDir: String = "",     // path to espeak-ng-data/ dir
    var noiseScale: Float = 0.667f,
    var noiseScaleW: Float = 0.8f,
    var lengthScale: Float = 1.0f,   // speed: >1 = slower, <1 = faster
)

data class OfflineTtsConfig(
    var model: OfflineTtsModelConfig = OfflineTtsModelConfig(),
    var maxNumSentences: Int = 1,
    var silenceScale: Float = 0.2f,
)
```

### Usage

```kotlin
val ttsConfig = OfflineTtsConfig(
    model = OfflineTtsModelConfig(
        vits = OfflineTtsVitsModelConfig(
            model   = "en_US-libritts-medium/en_US-libritts_r-medium.onnx",
            tokens  = "en_US-libritts-medium/tokens.txt",
            dataDir = "en_US-libritts-medium/espeak-ng-data",
        ),
        numThreads = 2,
        provider = "cpu",
    ),
)
val tts = OfflineTts(assetManager = context.assets, config = ttsConfig)

// Synchronous generation (run on background thread):
val audio: GeneratedAudio = tts.generate(
    text = "Hello from Marmalade!",
    sid = 0,      // speaker ID (libritts has multiple speakers)
    speed = 1.0f
)
// audio.samples: FloatArray (normalized -1..1)
// audio.sampleRate: Int

// Streaming generation (chunks arrive as they're generated):
tts.generateWithCallback(text = "...", sid = 0, speed = 1.0f) { samples ->
    playAudioChunk(samples)  // play incrementally
    1  // return 1 to continue, 0 to abort
}

val sampleRate = tts.sampleRate()
val numSpeakers = tts.numSpeakers()  // libritts-medium: 904 speakers

tts.release()
```

### Model download (libritts)
```
https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
→ vits-piper-en_US-libritts_r-medium.tar.bz2
```

---

## 5. Shared Config Helpers

```kotlin
data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
    var dither: Float = 0.0f
)

fun getFeatureConfig(sampleRate: Int, featureDim: Int): FeatureConfig

data class OnlineModelConfig(
    var transducer: OnlineTransducerModelConfig = OnlineTransducerModelConfig(),
    var tokens: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",   // "cpu" | "nnapi"
    var modelType: String = "",     // "zipformer2"
    // ... other fields for non-transducer models (unused in initial scope)
)
```

---

## 6. Audio Recording Pattern (16kHz Mono PCM16)

```kotlin
val sampleRate = 16000
val bufferSize = AudioRecord.getMinBufferSize(
    sampleRate,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT
)
val audioRecord = AudioRecord(
    MediaRecorder.AudioSource.MIC,
    sampleRate,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    bufferSize * 2
)
// Read 100ms chunks in a background thread:
val chunkSize = sampleRate / 10  // 1600 samples = 100ms
val shortBuffer = ShortArray(chunkSize)
audioRecord.read(shortBuffer, 0, chunkSize)
val floatSamples = FloatArray(chunkSize) { shortBuffer[it] / 32768.0f }
```

---

## 7. Notes

- All Sherpa-ONNX classes call `System.loadLibrary("sherpa-onnx-jni")` in their companion objects — handled automatically by the AAR.
- Use `assetManager` constructor for bundled assets; use `newFromFile` path for downloaded models.
- KWS and STT can run concurrently (separate instances, separate threads).
- VAD runs on the same audio stream as STT — feed the same samples to both.
- For NNAPI: set `provider = "nnapi"` in `OnlineModelConfig`. Pixel 8a Tensor chip benefits significantly. Fallback to CPU if init fails.
- STT endpoint detection (`isEndpoint`) is based on `endpointConfig` rules. Rule 1 (2.4s trailing silence) maps to the 2400ms silence delay setting.
