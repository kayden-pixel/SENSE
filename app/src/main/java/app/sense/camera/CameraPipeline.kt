package app.sense.camera

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import app.sense.core.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class CameraMode {
    SILENT,
    VEIL,
    MOMENT,
}

data class VisionState(
    val active: Boolean = false,
    val faces: Int = 0,
    val tracked: Boolean = false,
    val privacy: PrivacyState = PrivacyState.CHECKING,
    val error: String? = null,
)

data class Clip(
    val file: File,
    val startedAt: Long,
    val requestedAt: Long,
    val clockUncertainty: Long,
    val sessionId: String = "",
)

class CameraPipeline(private val context: Context, private val parent: CoroutineScope) {
    val state = MutableStateFlow(VisionState())
    val frames = MutableSharedFlow<MouthFrame>(extraBufferCapacity = 8)
    private var provider: ProcessCameraProvider? = null
    private var detector: FaceDetector? = null
    private var analysis: ImageAnalysis? = null
    private var video: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var executor: java.util.concurrent.ExecutorService? = null
    private var scope: CoroutineScope? = null
    private var generation = 0
    @Volatile private var pendingDetector: FaceDetector? = null
    private var recordStop: Job? = null
    private var lastFrame = 0L
    private var privacy = PrivacyGate()
    private var lastResult = 0L

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    suspend fun bind(owner: LifecycleOwner, view: PreviewView, mode: CameraMode) {
        close()
        bindingOwner = view
        val gen = generation
        state.value = VisionState()
        val future = ProcessCameraProvider.getInstance(context)
        val p =
            suspendCancellableCoroutine<ProcessCameraProvider> { c ->
                future.addListener(
                    {
                        runCatching { future.get() }
                            .onSuccess { if (c.isActive) c.resume(it, null) }
                            .onFailure { if (c.isActive) c.resumeWith(Result.failure(it)) }
                    },
                    ContextCompat.getMainExecutor(context),
                )
            }
        if (gen != generation) return
        provider = p
        executor = Executors.newSingleThreadExecutor()
        scope =
            CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        if (mode == CameraMode.MOMENT) {
            val recorder =
                Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.HD,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                        )
                    )
                    .build()
            video = VideoCapture.withOutput(recorder)
            p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, video)
            state.value = VisionState(active = true)
            return
        }
        if (mode == CameraMode.VEIL)
            scope?.launch {
                while (isActive) {
                    delay(500)
                    if (SystemClock.elapsedRealtime() - lastResult > 1500) {
                        privacy.update(0, false, false)
                        state.value = state.value.copy(privacy = PrivacyState.CHECKING)
                    }
                }
            }
        val options =
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(.12f)
                .apply {
                    if (mode == CameraMode.SILENT)
                        setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                    else enableTracking()
                }
                .build()
        detector = FaceDetection.getClient(options)
        analysis =
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(640, 480))
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(executor!!) { image ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastFrame < 80) {
                            image.close()
                            return@setAnalyzer
                        }
                        lastFrame = now
                        val localDetector = detector
                        val media = image.image
                        if (gen != generation || localDetector == null || media == null) {
                            image.close()
                            return@setAnalyzer
                        }
                        pendingDetector = localDetector
                        try {
                            localDetector
                                .process(
                                    InputImage.fromMediaImage(
                                        media,
                                        image.imageInfo.rotationDegrees,
                                    )
                                )
                                .addOnCompleteListener(ContextCompat.getMainExecutor(context)) {
                                    task ->
                                    try {
                                        if (gen != generation) return@addOnCompleteListener
                                        val fresh = SystemClock.elapsedRealtime() - now <= 500
                                        val faces =
                                            if (task.isSuccessful && fresh) task.result
                                            else emptyList()
                                        val face =
                                            faces.maxByOrNull {
                                                it.boundingBox.width() * it.boundingBox.height()
                                            }
                                        val width =
                                            if (image.imageInfo.rotationDegrees % 180 == 0)
                                                image.width
                                            else image.height
                                        val centered =
                                            face != null &&
                                                abs(
                                                    face.boundingBox.centerX().toFloat() / width -
                                                        .5f
                                                ) < .22f &&
                                                abs(face.headEulerAngleY) < 25 &&
                                                abs(face.headEulerAngleZ) < 20
                                        val reliable = task.isSuccessful && fresh
                                        lastResult = SystemClock.elapsedRealtime()
                                        state.value =
                                            VisionState(
                                                true,
                                                faces.size,
                                                face != null,
                                                if (mode == CameraMode.VEIL)
                                                    privacy.update(faces.size, centered, reliable)
                                                else PrivacyState.CHECKING,
                                                if (reliable) null else "Face tracking unavailable",
                                            )
                                        if (mode == CameraMode.SILENT)
                                            frames.tryEmit(
                                                MouthFrame(
                                                    if (reliable && faces.size == 1 && centered)
                                                        mouth(face!!)
                                                    else null,
                                                    now,
                                                )
                                            )
                                    } finally {
                                        image.close()
                                        if (pendingDetector === localDetector)
                                            pendingDetector = null
                                        if (gen != generation) localDetector.close()
                                    }
                                }
                        } catch (e: Exception) {
                            image.close()
                            if (pendingDetector === localDetector) pendingDetector = null
                            if (gen == generation) {
                                frames.tryEmit(MouthFrame(null, now))
                                state.value =
                                    VisionState(active = true, error = "Face tracking unavailable")
                            } else localDetector.close()
                        }
                    }
                }
        p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        state.value = VisionState(active = true)
    }

    private fun mouth(face: Face): FloatArray? {
        val types =
            listOf(
                FaceContour.UPPER_LIP_TOP,
                FaceContour.UPPER_LIP_BOTTOM,
                FaceContour.LOWER_LIP_TOP,
                FaceContour.LOWER_LIP_BOTTOM,
            )
        val contours = types.map { face.getContour(it)?.points ?: return null }
        val all = contours.flatten()
        if (all.size < 20) return null
        val minX = all.minOf { it.x }
        val maxX = all.maxOf { it.x }
        val scale = maxX - minX
        if (scale < 10) return null
        val centerX = (minX + maxX) / 2
        val centerY = all.map { it.y }.average().toFloat()
        val values = ArrayList<Float>()
        contours.forEach { points ->
            repeat(8) { i ->
                val p = points[(i * (points.size - 1) / 7)]
                values.add((p.x - centerX) / scale)
                values.add((p.y - centerY) / scale)
            }
        }
        values.add(scale / face.boundingBox.width())
        return values.toFloatArray()
    }

    fun record(
        sessionId: String,
        requestedAt: Long,
        clock: ClockSnapshot,
        onComplete: (Clip) -> Unit,
        onError: (String) -> Unit,
    ) {
        val capture = video ?: error("Camera is not ready")
        check(recording == null) { "Capture already active" }
        val gen = generation
        val folder = File(context.filesDir, "clips").apply { mkdirs() }
        val file = File(folder, "moment-" + java.util.UUID.randomUUID() + ".mp4")
        var started = 0L
        var local: Recording? = null
        local =
            capture.output.prepareRecording(context, FileOutputOptions.Builder(file).build()).start(
                ContextCompat.getMainExecutor(context)
            ) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        if (gen != generation) {
                            local?.stop()
                        } else {
                            started = clock.serverTime(SystemClock.elapsedRealtime())
                            recordStop =
                                parent.launch {
                                    delay(4000)
                                    local?.stop()
                                }
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (gen == generation) {
                            recordStop?.cancel()
                            recordStop = null
                            recording = null
                        }
                        if (event.hasError() || gen != generation || started == 0L) {
                            file.delete()
                            if (gen == generation)
                                onError("Capture interrupted or failed (" + event.error + ")")
                        } else
                            onComplete(
                                Clip(file, started, requestedAt, clock.uncertaintyMs, sessionId)
                            )
                    }
                }
            }
        recording = local
    }

    private var bindingOwner: Any? = null

    fun close(owner: Any) {
        if (bindingOwner === owner) close()
    }

    fun close() {
        bindingOwner = null
        generation++
        privacy = PrivacyGate()
        lastFrame = 0
        frames.tryEmit(MouthFrame(null, SystemClock.elapsedRealtime()))
        lastResult = 0
        recordStop?.cancel()
        recordStop = null
        recording?.stop()
        recording = null
        analysis?.clearAnalyzer()
        analysis = null
        provider?.unbindAll()
        provider = null
        video = null
        scope?.cancel()
        scope = null
        detector?.takeUnless { it === pendingDetector }?.close()
        detector = null
        executor?.shutdown()
        executor = null
        state.value = VisionState()
    }
}
