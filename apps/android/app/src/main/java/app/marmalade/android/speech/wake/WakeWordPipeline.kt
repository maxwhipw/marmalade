package app.marmalade.android.speech.wake

import android.content.Context
import android.util.Log
import app.marmalade.android.speech.MicCapture
import app.marmalade.android.speech.MicCaptureException
import app.marmalade.android.speech.openMicCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * In-repo replacement for `xyz.rementia:openwakeword` (closed-source AAR,
 * unknown license, removed per an internal wake-pipeline spec). Owns the
 * mic, runs a Silero VAD gate to skip the expensive openWakeWord chain
 * during silence, and requires 2-of-3 hop confirmation before emitting a
 * detection.
 *
 * Drop-in API for [app.marmalade.android.service.HotwordService]: same
 * constructor shape (context, models, cooldown, scope) and the same
 * `detections` / `start()` / `stop()` surface the AAR's `WakeWordEngine`
 * exposed, so that call site's diff stays minimal.
 *
 * Architecture ported from `dscripka/openWakeWord` (Apache-2.0) — see
 * CREDITS.md. No code, decompiled or otherwise, was copied from the retired
 * AAR.
 *
 * Audio: the shared mic seam ([openMicCapture], KMP increment 3f) — 16kHz
 * mono float hops from `AudioRecord(source = VOICE_RECOGNITION)`, 80ms hops
 * (1280 samples), no hardware NS/AGC (the wake models don't want them; the
 * CDD requires the VOICE_RECOGNITION route itself to be flat and free of
 * telephony-tuned DSP).
 */
class WakeWordPipeline(
    private val context: Context,
    private val models: List<WakeModel>,
    private val cooldownMs: Long,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "WakeWordPipeline"
        // Sample rate now lives with the mic seam (MIC_SAMPLE_RATE = 16kHz).
        const val HOP_SAMPLES = MelWindowBuffer.HOP_SAMPLES // 1280 = 80ms @ 16kHz
        private const val VAD_WINDOW_SAMPLES = SileroVad.WINDOW_SIZE_SAMPLES // 512 = 32ms @ 16kHz

        private const val MELSPEC_ASSET = "melspectrogram.onnx"
        private const val EMBEDDING_ASSET = "embedding_model.onnx"
        private const val VAD_ASSET = "silero_vad.onnx"

        /** Log gated-vs-executed chain-run counters at DEBUG roughly this often. */
        private const val COUNTER_LOG_INTERVAL_MS = 60_000L
    }

    private var capture: MicCapture? = null
    private var readJob: Job? = null

    private var vad: SileroVad? = null
    private var melModel: MelSpectrogramModel? = null
    private var embeddingModel: EmbeddingModel? = null
    private var classifiers: Map<String, ClassifierModel> = emptyMap()

    private var chain: OpenWakeWordChain? = null
    private var vadGate: VadGate? = null
    private var confirmationTracker: ConfirmationTracker? = null

    private var gatedHops = 0
    private var executedHops = 0
    private var lastCounterLogMs = 0L

    private val detectionsFlow = MutableSharedFlow<WakeDetection>(
        replay = 0,
        extraBufferCapacity = 4,
    )

    /** Confirmed wake-word detections, post VAD-gate and post multi-hop confirmation. */
    val detections: Flow<WakeDetection> get() = detectionsFlow

    /**
     * Loads the ONNX sessions (once — a released pipeline reloads, but a
     * pipeline that's merely been [stop]ped and restarted reuses its already-
     * loaded sessions rather than leaking the old ones), opens the mic, and
     * starts the hop loop. Safe to call again after [stop] to resume.
     *
     * Every call also resets the chain's mel/embedding buffers, the VAD gate,
     * and the confirmation tracker, so a restart (e.g. HotwordService's
     * routine stop()/start() mic handoff to STT) never scores stale
     * pre-handoff audio against fresh audio. With the gate-open backfill
     * design (see [OpenWakeWordChain.backfillOnGateOpen]), a reset mel buffer
     * just needs to warm back up (~0.76s of hops = 76 mel frames), which is
     * no worse than the old engine's behavior on every restart.
     */
    fun start() {
        if (readJob != null) {
            Log.d(TAG, "start() called while already running; ignoring")
            return
        }

        if (melModel == null) {
            try {
                loadSessions()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load wake-word ONNX sessions", e)
                releaseSessions()
                return
            }
        }
        chain?.reset()
        vadGate?.reset()
        confirmationTracker?.reset()

        val cap = try {
            openMicCapture(hopSamples = HOP_SAMPLES)
        } catch (e: MicCaptureException) {
            Log.e(TAG, "Failed to open mic for wake-word pipeline", e)
            releaseSessions()
            return
        }

        capture = cap
        gatedHops = 0
        executedHops = 0
        lastCounterLogMs = System.currentTimeMillis()

        readJob = scope.launch(Dispatchers.Default) {
            runHopLoop(cap)
        }
        Log.i(TAG, "Wake-word pipeline started (${models.size} model(s))")
    }

    /** Stops the hop loop and releases the mic. ONNX sessions stay loaded until [release]. */
    fun stop() {
        readJob?.cancel()
        readJob = null
        // Closing the capture is also what unblocks a hop read the cancel
        // can't interrupt (AudioRecord.read blocks in JNI).
        capture?.close()
        capture = null
        Log.i(TAG, "Wake-word pipeline stopped")
    }

    /** Stops the loop (if running) and closes all ONNX sessions. */
    fun release() {
        stop()
        releaseSessions()
    }

    private fun loadSessions() {
        val melBytes = readAsset(MELSPEC_ASSET)
        val embedBytes = readAsset(EMBEDDING_ASSET)
        val vadBytes = readAsset(VAD_ASSET)

        val melSession = MelSpectrogramModel(melBytes)
        val embedSession = EmbeddingModel(embedBytes)
        val vadSession = SileroVad(vadBytes)
        val classifierSessions = models.associate { model ->
            model.assetFilename to ClassifierModel(readAsset(model.assetFilename))
        }

        melModel = melSession
        embeddingModel = embedSession
        vad = vadSession
        classifiers = classifierSessions

        chain = OpenWakeWordChain(
            models = models,
            melInfer = { hop -> melSession.infer(hop) },
            embedInfer = { window -> embedSession.infer(window) },
            classifierInfer = { assetFilename, window ->
                classifierSessions.getValue(assetFilename).infer(window)
            },
        )
        vadGate = VadGate()
        confirmationTracker = ConfirmationTracker(models, cooldownMs)
    }

    private fun releaseSessions() {
        chain = null
        vadGate = null
        confirmationTracker = null
        melModel?.close(); melModel = null
        embeddingModel?.close(); embeddingModel = null
        vad?.close(); vad = null
        classifiers.values.forEach { it.close() }
        classifiers = emptyMap()
    }

    private fun readAsset(name: String): ByteArray =
        context.assets.open(name).use { it.readBytes() }

    /**
     * Hop loop. Mel inference (`pushMelOnly`) runs on EVERY hop regardless of
     * gate state — mel is the cheap stage, and keeping [MelWindowBuffer]'s
     * mel history continuous across gate transitions is what lets a
     * closed->open edge backfill fresh embeddings from real (not stale)
     * audio instead of concatenating pre-silence embeddings with post-
     * silence ones. Embedding + classifier inference (the expensive stages)
     * only run while the gate is open — that's the actual battery win, not
     * skipping mel.
     *
     * `gatedHops` / `executedHops` count hops where embedding+classifier were
     * skipped vs run, not mel — mel always runs, so it isn't gate-relevant to
     * that counter's purpose (VAD gate battery savings on the expensive
     * stages).
     */
    private suspend fun runHopLoop(capture: MicCapture) {
        val vadInstance = vad ?: return
        val gate = vadGate ?: return
        val chainInstance = chain ?: return
        val tracker = confirmationTracker ?: return

        var wasOpen = false

        try {
            capture.hops.collect { hopFloats ->
                val now = System.currentTimeMillis()

                val speechProb = runVadOverHop(vadInstance, hopFloats)
                val open = gate.offer(speechProb, now)

                // Cheap stage: always runs, keeps mel history warm/continuous.
                val newMelFrames = chainInstance.pushMelOnly(hopFloats)

                if (!open) {
                    if (wasOpen) {
                        // open -> closed edge: drop embeddings so the next
                        // open edge never mixes them with backfilled ones.
                        chainInstance.clearEmbeddingsOnGateClose()
                    }
                    wasOpen = false
                    gatedHops++
                    maybeLogCounters(now)
                    return@collect
                }

                executedHops++
                val scores = if (!wasOpen) {
                    // closed -> open edge: backfill 16 embedding windows from
                    // the warm mel buffer and classify immediately, instead
                    // of waiting ~1.2s for the incremental path to refill.
                    chainInstance.backfillOnGateOpen()
                } else {
                    chainInstance.advanceEmbeddingsAndClassify(newMelFrames.size)
                }
                wasOpen = true

                if (scores.isNotEmpty()) {
                    tracker.offer(scores, now)?.let { detection ->
                        Log.i(TAG, "Wake word confirmed: '${detection.modelName}' (score=${detection.score})")
                        detectionsFlow.emit(detection)
                    }
                }
                maybeLogCounters(now)
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                Log.e(TAG, "Wake-word hop loop error", e)
            }
        }
    }

    /**
     * Runs the VAD in its native 512-sample windows across the 1280-sample
     * hop (2.5 windows) and returns the max probability observed, so a short
     * speech onset anywhere in the hop is not diluted by silence in the rest
     * of it. The leftover partial window at the hop boundary is dropped
     * (re-evaluated as part of the next hop's lead-in) rather than carried
     * across hops — simpler statefulness and a 12ms rounding loss is
     * immaterial for a gating decision.
     */
    private fun runVadOverHop(vadInstance: SileroVad, hopFloats: FloatArray): Float {
        var maxProb = 0f
        var offset = 0
        while (offset + VAD_WINDOW_SAMPLES <= hopFloats.size) {
            val window = hopFloats.copyOfRange(offset, offset + VAD_WINDOW_SAMPLES)
            val prob = vadInstance.speechProbability(window)
            if (prob > maxProb) maxProb = prob
            offset += VAD_WINDOW_SAMPLES
        }
        return maxProb
    }

    private fun maybeLogCounters(nowMs: Long) {
        if (nowMs - lastCounterLogMs < COUNTER_LOG_INTERVAL_MS) return
        Log.d(TAG, "Hop counters: gated=$gatedHops executed=$executedHops (VAD gate savings)")
        lastCounterLogMs = nowMs
    }
}
