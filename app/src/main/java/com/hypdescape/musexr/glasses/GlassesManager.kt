package com.hypdescape.musexr.glasses

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wraps the Meta Wearables Device Access Toolkit (DAT) SDK: pairing with the glasses via the
 * Meta AI app, and opening a camera session/stream to capture a single photo on demand.
 *
 * Must be constructed in [ComponentActivity.onCreate], before the activity reaches STARTED,
 * since it registers an ActivityResultLauncher.
 */
class GlassesManager(private val activity: ComponentActivity) {

    companion object {
        // DAT SDK 0.8.0 can leave a stuck link lease after a BT severance (e.g. folding the
        // glasses), causing createSession() to fail with NO_ELIGIBLE_DEVICE even once the
        // glasses are reconnected. A short delay + retry recovers it without an app restart.
        // See https://github.com/facebook/meta-wearables-dat-android/issues/128.
        private const val STUCK_SESSION_MAX_ATTEMPTS = 3
        private const val STUCK_SESSION_RETRY_DELAY_MS = 500L
    }

    private val deviceSelector: DeviceSelector by lazy { AutoDeviceSelector() }

    val registrationState: StateFlow<RegistrationState>
        get() = Wearables.registrationState

    private var session: DeviceSession? = null
    private var stream: Stream? = null

    private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
    private val permissionMutex = Mutex()

    private val wearablesPermissionLauncher =
        activity.registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            permissionContinuation?.resume(result.getOrDefault(PermissionStatus.Denied))
            permissionContinuation = null
        }

    /** Must be called once, after Android runtime permissions (Bluetooth, Camera) are granted. */
    fun initialize() {
        Wearables.initialize(activity.application)
    }

    /** Launches the Meta AI app pairing flow. Observe [registrationState] for the result. */
    fun connect() {
        Wearables.startRegistration(activity)
    }

    fun disconnect() {
        stopSession()
        Wearables.startUnregistration(activity)
    }

    /**
     * Captures a single photo from the glasses camera. Ensures glasses camera permission is
     * granted and a streaming session is active, reusing them across calls for faster
     * subsequent captures.
     */
    suspend fun capturePhoto(): Result<Bitmap> {
        val outcome =
            try {
                withTimeoutOrNull(15_000) {
                    val permission = requestCameraPermission()
                    if (permission != PermissionStatus.Granted) {
                        return@withTimeoutOrNull Result.failure<Bitmap>(
                            IllegalStateException("Glasses camera permission denied")
                        )
                    }

                    val activeStream =
                        ensureStream()
                            ?: return@withTimeoutOrNull Result.failure<Bitmap>(
                                IllegalStateException("Could not start camera stream")
                            )

                    val photoData =
                        activeStream.capturePhoto().getOrNull()
                            ?: return@withTimeoutOrNull Result.failure<Bitmap>(
                                IllegalStateException("Photo capture failed")
                            )

                    Result.success(photoData.toBitmap())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        return outcome ?: Result.failure(IllegalStateException("Timed out waiting for glasses camera"))
    }

    /** Releases the camera stream and session. Call from onDestroy. */
    fun stopSession() {
        stream?.stop()
        stream = null
        session?.stop()
        session = null
    }

    private suspend fun requestCameraPermission(): PermissionStatus {
        val current = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
        if (current == PermissionStatus.Granted) return PermissionStatus.Granted

        return permissionMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                permissionContinuation = continuation
                continuation.invokeOnCancellation { permissionContinuation = null }
                wearablesPermissionLauncher.launch(Permission.CAMERA)
            }
        }
    }

    private suspend fun ensureStream(): Stream? {
        if (stream != null && session?.state?.value == DeviceSessionState.STARTED) {
            return stream
        }
        if (stream != null || session != null) {
            // Stale from a previous run (e.g. BT severed while the glasses were folded).
            stopSession()
        }

        val activeSession = createSessionWithRetry() ?: return null

        activeSession.state.first { it == DeviceSessionState.STARTED }

        val newStream =
            activeSession
                .addStream(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 15))
                .getOrNull() ?: return null
        newStream.start()
        newStream.state.first { it == StreamState.STREAMING }

        stream = newStream
        return newStream
    }

    /**
     * Creates a session, retrying if the SDK reports [DeviceSessionError.NO_ELIGIBLE_DEVICE] —
     * the signature of a stuck link lease after a BT severance. Any other error fails fast.
     */
    private suspend fun createSessionWithRetry(): DeviceSession? {
        repeat(STUCK_SESSION_MAX_ATTEMPTS) { attempt ->
            val result = Wearables.createSession(deviceSelector)
            result.getOrNull()?.let { created ->
                session = created
                created.start()
                return created
            }

            if (result.errorOrNull() != DeviceSessionError.NO_ELIGIBLE_DEVICE) return null
            if (attempt < STUCK_SESSION_MAX_ATTEMPTS - 1) {
                delay(STUCK_SESSION_RETRY_DELAY_MS)
            }
        }
        return null
    }

    private fun PhotoData.toBitmap(): Bitmap =
        when (this) {
            is PhotoData.Bitmap -> bitmap
            is PhotoData.HEIC -> {
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
}
