package com.snap.camerakit.reactnative

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.module.annotations.ReactModule
import com.snap.camerakit.MediaRecordingImageProcessors
import com.snap.camerakit.SafeRenderAreaProcessor
import com.snap.camerakit.Session
import com.snap.camerakit.Source
import com.snap.camerakit.common.Consumer
import com.snap.camerakit.invoke
import com.snap.camerakit.lenses.LensesComponent
import com.snap.camerakit.lenses.newBuilder
import com.snap.camerakit.support.camerax.CameraXImageProcessorSource
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@ReactModule(name = CameraKitContextModule.NAME)
class CameraKitContextModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext),
    Source<SafeRenderAreaProcessor> {

    var currentSession: Session? = null
        private set
    var setSafeRenderArea: Consumer<Rect>? = null
    var touchViewContainer = TouchViewContainer(reactApplicationContext.applicationContext)

    private var videoRecording: Closeable? = null
    private var videoRecordingPromise: Promise? = null
    private var currentVideoFile: File? = null
    private var pcmFile: File? = null
    private var audioRecorder: MixedAudioRecorder? = null

    private var mediaProjectionPromise: Promise? = null

    private var currentLenses = mapOf<String, LensesComponent.Lens>()
    private val imageProcessorSource: CameraXImageProcessorSource
        get() = reactApplicationContext.getNativeModule(CameraImageProcessorModule::class.java)!!.imageProcessorSource
    private val eventEmitter: CameraKitEventEmitter
        get() = reactApplicationContext.getNativeModule(CameraKitEventEmitter::class.java)!!

    private val activityEventListener: ActivityEventListener = object : BaseActivityEventListener() {
        override fun onActivityResult(activity: Activity?, requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode != MEDIA_PROJECTION_REQUEST_CODE) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && resultCode == Activity.RESULT_OK && data != null) {
                // Start foreground service (required on Android 14+ before calling getMediaProjection)
                val serviceIntent = Intent(reactApplicationContext, AudioCaptureService::class.java).apply {
                    putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    reactApplicationContext.startForegroundService(serviceIntent)
                } else {
                    reactApplicationContext.startService(serviceIntent)
                }
                mediaProjectionPromise?.resolve(true)
            } else {
                mediaProjectionPromise?.resolve(false)
            }
            mediaProjectionPromise = null
        }
    }

    init {
        reactContext.addActivityEventListener(activityEventListener)
    }

    @ReactMethod
    fun requestMediaProjection(promise: Promise) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            promise.resolve(false)
            return
        }
        if (mediaProjection != null) {
            promise.resolve(true)
            return
        }
        val activity = currentActivity
        if (activity == null) {
            promise.resolve(false)
            return
        }
        mediaProjectionPromise = promise
        val manager = activity.getSystemService(MediaProjectionManager::class.java)
        activity.startActivityForResult(manager.createScreenCaptureIntent(), MEDIA_PROJECTION_REQUEST_CODE)
    }

    @ReactMethod
    fun loadLensGroup(groupId: String, promise: Promise) {
        if (currentSession == null) {
            eventEmitter.sendWarning("Attempt to load lenses when session is not available.")
            promise.resolve(Arguments.makeNativeArray(emptyList<LensesComponent.Lens>()))
            return
        }

        currentSession!!.lenses.repository.get(LensesComponent.Repository.QueryCriteria.Available(groupId)) { result ->
            when (result) {
                LensesComponent.Repository.Result.None ->
                    promise.resolve(Arguments.makeNativeArray(emptyList<LensesComponent.Lens>()))
                is LensesComponent.Repository.Result.Some -> {
                    val lenses = result.lenses
                    this.currentLenses = this.currentLenses.plus(lenses.associateBy { it.id })
                    promise.resolve(Arguments.makeNativeArray(lenses.map { lens ->
                        Arguments.makeNativeMap(
                            mapOf(
                                "id" to lens.id,
                                "icons" to lens.icons.map { mapOf("imageUrl" to it.uri) },
                                "groupId" to lens.groupId,
                                "name" to lens.name,
                                "vendorData" to lens.vendorData,
                                "facingPreference" to lens.facingPreference?.name,
                                "previews" to lens.previews.map { mapOf("imageUrl" to it.uri) },
                                "snapcodes" to lens.snapcodes.associate {
                                    if (it.javaClass == LensesComponent.Lens.Media.DeepLink::class.java) "deepLink" to it.uri
                                    else "imageUrl" to it.uri
                                }
                            )
                        )
                    }))
                }
            }
        }
    }

    @ReactMethod
    fun applyLens(lensId: String, launchData: ReadableMap, promise: Promise) {
        if (currentSession == null) {
            eventEmitter.sendWarning("Attempt to apply the lens when session is not available.")
            promise.resolve(false)
            return
        }
        val lensObj = currentLenses[lensId]
        if (lensObj != null) {
            var lensLaunchData: LensesComponent.Lens.LaunchData = LensesComponent.Lens.LaunchData.Empty
            if (launchData.toHashMap().isNotEmpty() && launchData.hasKey(LAUNCH_PARAMS_KEY)) {
                val builder = LensesComponent.Lens.LaunchData.newBuilder()
                launchData.getMap(LAUNCH_PARAMS_KEY)?.toHashMap()?.forEach { (key, value) ->
                    when (value) {
                        is String -> builder.putString(key, value)
                        is ArrayList<*> -> when {
                            value.any { it is String } -> builder.putStrings(key, *value.filterIsInstance<String>().toTypedArray())
                            value.any { it is Number } -> builder.putNumbers(key, *value.filterIsInstance<Number>().toTypedArray())
                        }
                        is Number -> builder.putNumber(key, value)
                    }
                }
                lensLaunchData = builder.build()
            }
            currentSession!!.lenses.processor.apply(lensObj, lensLaunchData) { status ->
                promise.resolve(status)
            }
        } else {
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun removeLens(promise: Promise) {
        if (currentSession == null) {
            eventEmitter.sendWarning("Attempt to remove the lens when session is not available.")
            promise.resolve(false)
            return
        }
        currentSession!!.lenses.processor.clear { promise.resolve(it) }
    }

    @ReactMethod
    fun createNewSession(apiKey: String, promise: Promise) {
        if (currentSession != null) { promise.resolve(true); return }
        currentSession = Session(reactApplicationContext.applicationContext) {
            apiToken(apiKey)
            attachTo(touchViewContainer.touchViewStub, false)
            imageProcessorSource(imageProcessorSource)
            safeRenderAreaProcessorSource(this@CameraKitContextModule)
            handleErrorsWith { item -> eventEmitter.sendError(item) }
        }
        promise.resolve(true)
    }

    @ReactMethod
    fun closeSession(promise: Promise) {
        if (currentSession == null) { promise.resolve(true); return }
        Handler(reactApplicationContext.applicationContext.mainLooper).post {
            imageProcessorSource.stopPreview()
        }
        currentSession?.close()
        currentSession = null
        promise.resolve(true)
    }

    @ReactMethod
    fun takeSnapshot(format: String, quality: Int, promise: Promise) {
        imageProcessorSource.takeSnapshot { bitmap ->
            try {
                val compressFormat = CompressFormat.valueOf(format)
                val tempFile = File.createTempFile(
                    "snap-camera-kit-snapshot",
                    compressFormatToExtension(compressFormat),
                    reactApplicationContext.cacheDir
                )
                FileOutputStream(tempFile).use { bitmap.compress(compressFormat, quality, it) }
                promise.resolve(Arguments.makeNativeMap(mapOf("uri" to tempFile.toURI().toString())))
            } catch (error: Throwable) {
                promise.reject(error)
            }
        }
    }

    @ReactMethod
    fun takeVideo(promise: Promise) {
        if (videoRecording != null) {
            eventEmitter.sendWarning("Stop the previous recording before starting a new one.")
            return
        }
        if (currentSession == null) { promise.resolve(null); return }

        val ts = System.currentTimeMillis()
        val videoFile = File(reactApplicationContext.applicationContext.cacheDir, "ck_video_$ts.mp4")
        videoRecordingPromise = promise
        currentVideoFile = videoFile

        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) AudioCaptureService.mediaProjection else null

        if (projection != null) {
            // Video only — audio captured separately via AudioPlaybackCapture + mic mix
            videoRecording = MediaRecordingImageProcessors.connectOutput(
                currentSession!!.processor, videoFile, 1080, 1920, false
            )
            val pcm = File(reactApplicationContext.applicationContext.cacheDir, "ck_audio_$ts.pcm")
            pcmFile = pcm
            audioRecorder = MixedAudioRecorder(projection, pcm).also { it.start() }
        } else {
            // Fallback: mic audio only
            videoRecording = MediaRecordingImageProcessors.connectOutput(
                currentSession!!.processor, videoFile, 1080, 1920, true
            )
        }
    }

    @ReactMethod
    fun stopTakingVideo(promise: Promise) {
        if (videoRecording == null) {
            eventEmitter.sendWarning("Recording is not started.")
            return
        }
        try {
            videoRecording?.close()
            videoRecording = null

            audioRecorder?.stop()
            audioRecorder = null

            val videoFile = currentVideoFile
            val pcm = pcmFile
            val sampleRate = MixedAudioRecorder.CHANNEL_COUNT.let { 44100 }
            currentVideoFile = null
            pcmFile = null

            if (pcm != null && pcm.exists() && pcm.length() > 0 && videoFile != null) {
                val finalFile = File(
                    reactApplicationContext.applicationContext.cacheDir,
                    "ck_final_${System.currentTimeMillis()}.mp4"
                )
                Thread {
                    val ok = AudioVideoMuxer.mux(videoFile, pcm, 44100, finalFile)
                    videoFile.delete()
                    pcm.delete()
                    val uri = if (ok && finalFile.exists()) finalFile.toURI().toString()
                              else videoFile.toURI().toString()
                    videoRecordingPromise?.resolve(Arguments.makeNativeMap(mapOf("uri" to uri)))
                    videoRecordingPromise = null
                }.start()
            } else {
                videoRecordingPromise?.resolve(
                    Arguments.makeNativeMap(mapOf("uri" to videoFile?.toURI()?.toString()))
                )
                videoRecordingPromise = null
            }

            promise.resolve(true)
        } catch (error: IOException) {
            promise.reject(error)
        }
    }

    @ReactMethod
    fun setZoom(zoom: Double, promise: Promise) {
        reactApplicationContext.getNativeModule(CameraImageProcessorModule::class.java)?.setZoom(zoom.toFloat())
        promise.resolve(true)
    }

    @ReactMethod
    fun setTorch(enabled: Boolean, promise: Promise) {
        reactApplicationContext.getNativeModule(CameraImageProcessorModule::class.java)?.setTorch(enabled)
        promise.resolve(true)
    }

    private fun compressFormatToExtension(compressFormat: CompressFormat) = when (compressFormat) {
        CompressFormat.JPEG -> ".jpeg"
        CompressFormat.PNG -> ".png"
        else -> throw Error("$compressFormat is not supported")
    }

    override fun getName() = NAME

    companion object {
        internal const val NAME = "CameraKitContext"
        internal const val LAUNCH_PARAMS_KEY = "launchParams"
        private const val MEDIA_PROJECTION_REQUEST_CODE = 8472
    }

    override fun attach(processor: SafeRenderAreaProcessor): Closeable {
        processor.connectInput(object : SafeRenderAreaProcessor.Input {
            override fun subscribeTo(onSafeRenderAreaAvailable: Consumer<Rect>): Closeable {
                setSafeRenderArea = onSafeRenderAreaAvailable
                return Closeable { setSafeRenderArea = null }
            }
        })
        return Closeable { setSafeRenderArea = null }
    }
}
