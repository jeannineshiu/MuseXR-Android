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
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
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
        stream?.let { return it }

        val activeSession = session ?: Wearables.createSession(deviceSelector).getOrNull()?.also {
            session = it
            it.start()
        } ?: return null

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
