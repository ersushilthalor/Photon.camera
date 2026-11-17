package com.hinnka.mycamera

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.hinnka.mycamera.camera.BurstGyroRecorder
import com.hinnka.mycamera.camera.CameraDiscovery
import com.hinnka.mycamera.camera.CameraUtils
import com.hinnka.mycamera.lut.LutProcessor
import com.hinnka.mycamera.lut.MgcEisHardwareBufferRenderer
import com.hinnka.mycamera.ml.DnCNNDenoiseEstimator
import com.hinnka.mycamera.processor.GlesYuvHardwareBufferInput
import com.hinnka.mycamera.processor.GlesYuvTiming
import com.hinnka.mycamera.processor.MgcSpatialStrengthMapGenerator
import com.hinnka.mycamera.raw.DcpProfile
import com.hinnka.mycamera.raw.MgcFullResolutionDenoise
import com.hinnka.mycamera.raw.MgcRaisrUpscale
import com.hinnka.mycamera.raw.RawLegacyAutoExposureMatcher
import com.hinnka.mycamera.stabilization.MgcEisNativeEngine
import com.hinnka.mycamera.stabilization.RealtimeStabilizationCoordinator
import com.hinnka.mycamera.utils.BuglyHelper
import com.hinnka.mycamera.utils.DirectBufferAllocator
import com.hinnka.mycamera.utils.LargeDirectBuffer
import com.hinnka.mycamera.utils.LocationManager
import com.hinnka.mycamera.utils.NativeLibraryLoader
import com.hinnka.mycamera.utils.OrientationObserver
import com.hinnka.mycamera.utils.ShutterSoundPlayer
import com.hinnka.mycamera.utils.VibrationHelper
import com.hinnka.mycamera.video.VideoAudioInputManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MyCameraApplication::class)
class StartupCrashResilienceTest {

    @Test
    fun mainActivityStartsWithoutNativeLibraryOrCameras() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
        controller.pause().stop().destroy()
    }

    @Test
    fun nativeLoadersDegradeGracefullyWithoutNativeLib() {
        assertFalse(NativeLibraryLoader.ensureLoaded())
        assertFalse(NativeLibraryLoader.isAvailable)

        val direct = LargeDirectBuffer.allocate(64)
        assertEquals(64, direct.capacity())
        LargeDirectBuffer.free(direct)

        val allocated = DirectBufferAllocator.allocateDirect(32)
        assertEquals(32, allocated.capacity())
        DirectBufferAllocator.freeNative(allocated)

        val lutProcessor = LutProcessor()
        assertFalse(lutProcessor.applyLutToBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), ByteBuffer.allocateDirect(4), 1, 1f))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val eisEngine = MgcEisNativeEngine(context.cacheDir)
        assertFalse(eisEngine.start(640, 480, frontFacing = false, strength = 1f, lookaheadFrames = 4))
        eisEngine.stop()

        assertEquals(0, GlesYuvTiming.counterBits())
        assertFalse(GlesYuvHardwareBufferInput.isSupported())
        assertNull(MgcEisHardwareBufferRenderer().render(null, null, 64, 64, 0, FloatArray(16)))
        assertNull(DcpProfile.parse(ByteArray(16)))
        assertFalse(MgcFullResolutionDenoise.denoise(ByteBuffer.allocateDirect(16), 2, 2, 0f, 0f, 0f))
        assertNull(MgcRaisrUpscale.upscale(ByteBuffer.allocateDirect(16), 2, 2, 2))
        assertNull(MgcSpatialStrengthMapGenerator.compute(ByteBuffer.allocateDirect(16), 2, 2, 0f))
        assertNull(RawLegacyAutoExposureMatcher.legacyHdrNetReference(context, emptyList(), ByteArray(16), 2, 2, 0f, 100f))
        assertNull(DnCNNDenoiseEstimator.denoise(ByteBuffer.allocateDirect(16), 2, 2, 0f))
    }

    @Test
    fun systemServicesAndHelpersDoNotThrowOnLaunchEnvironment() {
        val app = ApplicationProvider.getApplicationContext<Application>()

        BuglyHelper.init(app)
        BuglyHelper.setUserScene(app, 1)
        BuglyHelper.putUserData(app, "k", "v")
        BuglyHelper.log("Tag", "msg")
        BuglyHelper.error("Tag", "err", RuntimeException("test"))

        val locationManager = LocationManager(app)
        assertNull(locationManager.getCurrentLocation())
        locationManager.stopUpdates()

        OrientationObserver.observe(app)

        val soundPlayer = ShutterSoundPlayer(app)
        soundPlayer.playShutter(false)
        soundPlayer.release()

        val vibrationHelper = VibrationHelper(app)
        vibrationHelper.vibrateClick()

        val audioInputManager = VideoAudioInputManager(app)
        assertTrue(audioInputManager.availableInputs.value.isEmpty() || audioInputManager.availableInputs.value.isNotEmpty())
        audioInputManager.release()

        val gyroRecorder = BurstGyroRecorder(app)
        gyroRecorder.start(null)
        gyroRecorder.stop()
        gyroRecorder.release()

        val stabCoordinator = RealtimeStabilizationCoordinator(app)
        assertNotNull(stabCoordinator)

        val discovered = CameraDiscovery.discoverAllCameras(app)
        assertNotNull(discovered)
        val cameras = CameraUtils.getAllCameras(app)
        assertNotNull(cameras)
    }
}
