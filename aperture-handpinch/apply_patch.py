#!/usr/bin/env python3
from pathlib import Path
import shutil
import sys

if len(sys.argv) != 3:
    raise SystemExit("usage: apply_patch.py <aperture-root> <HandPinchTracker.java>")

root = Path(sys.argv[1]).resolve()
tracker_src = Path(sys.argv[2]).resolve()

def load(rel):
    p = root / rel
    return p, p.read_text(encoding="utf-8")

def save(p, text):
    p.write_text(text, encoding="utf-8")

def replace_once(rel, old, new):
    p, text = load(rel)
    if old not in text:
        raise RuntimeError(f"patch anchor not found in {rel}: {old[:100]!r}")
    save(p, text.replace(old, new, 1))

# ---- Gradle / identity -------------------------------------------------------
gradle = "app/build.gradle"
replace_once(gradle, "dependencies {\n", "dependencies {\n    implementation 'com.google.mediapipe:tasks-vision:0.10.35'\n")
replace_once(
    gradle,
    '        universal3dof {\n            dimension "platform"\n            applicationIdSuffix ".u3dof"\n            resValue "string", "app_name", "ApertureReality 3DOF"',
    '        universal3dof {\n            dimension "platform"\n            applicationIdSuffix ".u3dof.handpinch"\n            resValue "string", "app_name", "Aperture Hand Pinch"'
)

# ---- Add Java tracker --------------------------------------------------------
java_dir = root / "app/src/universal3dof/java/com/igalia/wolvic"
java_dir.mkdir(parents=True, exist_ok=True)
shutil.copy2(tracker_src, java_dir / "HandPinchTracker.java")

# ---- Camera2: share the passthrough camera with MediaPipe --------------------
cam = "app/src/universal3dof/java/com/igalia/wolvic/PassthroughCameraManager.java"
replace_once(
    cam,
    "import android.graphics.SurfaceTexture;\n",
    "import android.graphics.SurfaceTexture;\nimport android.graphics.ImageFormat;\nimport android.media.Image;\nimport android.media.ImageReader;\n"
)
replace_once(
    cam,
    "import java.util.Collections;\n",
    "import java.util.Collections;\nimport java.util.ArrayList;\nimport java.util.List;\n"
)
replace_once(
    cam,
    "    private Handler mBackgroundHandler;\n",
    "    private Handler mBackgroundHandler;\n"
    "    private ImageReader mHandImageReader;\n"
    "    private FrameListener mFrameListener;\n"
    "    private int mSensorOrientation = 90;\n\n"
    "    public interface FrameListener {\n"
    "        void onFrame(Image image, int sensorOrientation, long timestampNs);\n"
    "    }\n\n"
    "    public synchronized void setFrameListener(FrameListener listener) {\n"
    "        mFrameListener = listener;\n"
    "    }\n"
)
replace_once(
    cam,
    "            mCameraCharacteristics = bestChars;\n            mCameraFovX = bestFovX;",
    "            mCameraCharacteristics = bestChars;\n"
    "            if (bestChars != null) {\n"
    "                Integer orientation = bestChars.get(CameraCharacteristics.SENSOR_ORIENTATION);\n"
    "                if (orientation != null) mSensorOrientation = orientation;\n"
    "            }\n"
    "            mCameraFovX = bestFovX;"
)
replace_once(
    cam,
    "            mSurface = new Surface(mSurfaceTexture);\n\n            final String targetCameraId",
    "            mSurface = new Surface(mSurfaceTexture);\n"
    "            createHandImageReader();\n\n"
    "            final String targetCameraId"
)
replace_once(
    cam,
    "            previewRequestBuilder.addTarget(mSurface);\n\n            // Auto-focus mode if supported",
    "            previewRequestBuilder.addTarget(mSurface);\n"
    "            final List<Surface> outputSurfaces = new ArrayList<>();\n"
    "            outputSurfaces.add(mSurface);\n"
    "            if (mHandImageReader != null) {\n"
    "                Surface handSurface = mHandImageReader.getSurface();\n"
    "                outputSurfaces.add(handSurface);\n"
    "                previewRequestBuilder.addTarget(handSurface);\n"
    "            }\n\n"
    "            // Auto-focus mode if supported"
)
replace_once(
    cam,
    "            mCameraDevice.createCaptureSession(Collections.singletonList(mSurface),",
    "            mCameraDevice.createCaptureSession(outputSurfaces,"
)
replace_once(
    cam,
    "                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {\n                            Log.e(TAG, \"Camera capture session configuration failed\");\n                        }",
    "                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {\n"
    "                            Log.e(TAG, \"Camera capture session configuration failed\");\n"
    "                            if (mHandImageReader != null) {\n"
    "                                Log.w(TAG, \"Retrying passthrough without hand ImageReader\");\n"
    "                                closeHandImageReader();\n"
    "                                createCameraPreviewSession();\n"
    "                            }\n"
    "                        }"
)
replace_once(
    cam,
    "    private final float[] mRotationMatrix = new float[16];\n",
    "    private void createHandImageReader() {\n"
    "        closeHandImageReader();\n"
    "        try {\n"
    "            Size chosen = new Size(640, 480);\n"
    "            if (mCameraCharacteristics != null) {\n"
    "                StreamConfigurationMap map = mCameraCharacteristics.get(\n"
    "                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);\n"
    "                Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);\n"
    "                if (sizes != null && sizes.length > 0) {\n"
    "                    double targetArea = 640.0 * 360.0;\n"
    "                    double targetRatio = 16.0 / 9.0;\n"
    "                    double best = Double.MAX_VALUE;\n"
    "                    for (Size s : sizes) {\n"
    "                        long area = (long)s.getWidth() * s.getHeight();\n"
    "                        if (area > 1280L * 720L) continue;\n"
    "                        double ratio = (double)Math.max(s.getWidth(), s.getHeight()) / Math.min(s.getWidth(), s.getHeight());\n"
    "                        double score = Math.abs(area - targetArea) / targetArea + Math.abs(ratio - targetRatio) * 2.0;\n"
    "                        if (score < best) { best = score; chosen = s; }\n"
    "                    }\n"
    "                }\n"
    "            }\n"
    "            mHandImageReader = ImageReader.newInstance(chosen.getWidth(), chosen.getHeight(),\n"
    "                    ImageFormat.YUV_420_888, 2);\n"
    "            mHandImageReader.setOnImageAvailableListener(reader -> {\n"
    "                Image image = null;\n"
    "                try { image = reader.acquireLatestImage(); } catch (Throwable ignored) {}\n"
    "                if (image == null) return;\n"
    "                FrameListener listener = mFrameListener;\n"
    "                if (listener != null) {\n"
    "                    listener.onFrame(image, mSensorOrientation, image.getTimestamp());\n"
    "                } else {\n"
    "                    image.close();\n"
    "                }\n"
    "            }, mBackgroundHandler);\n"
    "            Log.i(TAG, \"Hand tracking stream: \" + chosen.getWidth() + \"x\" + chosen.getHeight());\n"
    "        } catch (Throwable t) {\n"
    "            Log.w(TAG, \"Could not create hand tracking stream\", t);\n"
    "            closeHandImageReader();\n"
    "        }\n"
    "    }\n\n"
    "    private void closeHandImageReader() {\n"
    "        if (mHandImageReader != null) {\n"
    "            try { mHandImageReader.close(); } catch (Throwable ignored) {}\n"
    "            mHandImageReader = null;\n"
    "        }\n"
    "    }\n\n"
    "    private final float[] mRotationMatrix = new float[16];\n"
)
replace_once(
    cam,
    "        stopBackgroundThread();\n        mIsRunning = false;",
    "        closeHandImageReader();\n        stopBackgroundThread();\n        mIsRunning = false;"
)

# ---- PlatformActivity: feed frames + pinch events into native controller -----
act = "app/src/universal3dof/java/com/igalia/wolvic/PlatformActivity.java"
replace_once(
    act,
    "    private com.igalia.wolvic.telemetry.ApertureControlReceiver mApertureReceiver;\n",
    "    private com.igalia.wolvic.telemetry.ApertureControlReceiver mApertureReceiver;\n"
    "    private HandPinchTracker mHandPinchTracker;\n"
    "    private volatile boolean mHandTriggerDown = false;\n"
)
replace_once(
    act,
    "        setContentView(R.layout.noapi_layout);\n        mFrameRate = findViewById(R.id.frame_rate_text);",
    "        setContentView(R.layout.noapi_layout);\n"
    "        setupHandPinchControl();\n"
    "        mFrameRate = findViewById(R.id.frame_rate_text);"
)
replace_once(
    act,
    "    private void initializeSensors() {\n",
    "    private void setupHandPinchControl() {\n"
    "        mHandPinchTracker = new HandPinchTracker(this, new HandPinchTracker.Listener() {\n"
    "            @Override public void onHand(float x, float y, boolean pinch, boolean openHand, float confidence) {\n"
    "                final float px = Math.max(0.02f, Math.min(0.98f, x));\n"
    "                final float py = Math.max(0.02f, Math.min(0.98f, y));\n"
    "                queueRunnable(() -> handPointer(true, px, py));\n"
    "                setHandTrigger(pinch && !openHand);\n"
    "            }\n"
    "            @Override public void onHandLost() {\n"
    "                setHandTrigger(false);\n"
    "                queueRunnable(() -> handPointer(false, 0.5f, 0.5f));\n"
    "            }\n"
    "            @Override public void onStatus(String status) { Log.i(\"ApertureHand\", status); }\n"
    "        });\n"
    "        PassthroughCameraManager.getInstance().setFrameListener((image, sensorOrientation, timestampNs) -> {\n"
    "            HandPinchTracker tracker = mHandPinchTracker;\n"
    "            if (tracker == null) { image.close(); return; }\n"
    "            int rotation = (mCurrentLandscapeMode == 1) ? 270 : 90;\n"
    "            tracker.submit(image, timestampNs, rotation);\n"
    "        });\n"
    "    }\n\n"
    "    private synchronized void setHandTrigger(boolean down) {\n"
    "        if (mHandTriggerDown == down) return;\n"
    "        mHandTriggerDown = down;\n"
    "        queueRunnable(() -> touchEvent(down, 0.0f, 0.0f));\n"
    "    }\n\n"
    "    private void releaseHandControl() {\n"
    "        setHandTrigger(false);\n"
    "        queueRunnable(() -> handPointer(false, 0.5f, 0.5f));\n"
    "    }\n\n"
    "    private void initializeSensors() {\n"
)
replace_once(
    act,
    "    private native void touchEvent(boolean aDown, float aX, float aY);\n    private native void keyEvent(int aKeyCode, boolean aDown);",
    "    private native void touchEvent(boolean aDown, float aX, float aY);\n"
    "    private native void handPointer(boolean active, float normalizedX, float normalizedY);\n"
    "    private native void keyEvent(int aKeyCode, boolean aDown);"
)
replace_once(
    act,
    "        super.onDestroy();\n        synchronized (mRenderLock) {",
    "        releaseHandControl();\n"
    "        PassthroughCameraManager.getInstance().setFrameListener(null);\n"
    "        if (mHandPinchTracker != null) {\n"
    "            mHandPinchTracker.close();\n"
    "            mHandPinchTracker = null;\n"
    "        }\n\n"
    "        super.onDestroy();\n"
    "        synchronized (mRenderLock) {"
)
replace_once(
    act,
    "    private void disablePassthroughInternal() {\n        mPassthroughActive = false;\n        PassthroughCameraManager.getInstance().stop();",
    "    private void disablePassthroughInternal() {\n"
    "        mPassthroughActive = false;\n"
    "        releaseHandControl();\n"
    "        PassthroughCameraManager.getInstance().stop();"
)

# ---- C++ controller ray follows the fingertip -------------------------------
hdr = "app/src/universal3dof/cpp/DeviceDelegate3DOF.h"
replace_once(
    hdr,
    "  void TouchEvent(const bool aDown, const float aX, const float aY);\n  void KeyEvent(const int32_t aKeyCode, const bool aDown);",
    "  void TouchEvent(const bool aDown, const float aX, const float aY);\n"
    "  void HandPointer(const bool aActive, const float aX, const float aY);\n"
    "  void KeyEvent(const int32_t aKeyCode, const bool aDown);"
)

cpp = "app/src/universal3dof/cpp/DeviceDelegate3DOF.cpp"
replace_once(
    cpp,
    "  bool dwellTriggerActive;\n  vrb::Quaternion lastHeadOrientation;\n  uint64_t frameCount;",
    "  bool dwellTriggerActive;\n"
    "  bool handPointerActive = false;\n"
    "  float handPointerX = 0.5f;\n"
    "  float handPointerY = 0.5f;\n"
    "  vrb::Quaternion lastHeadOrientation;\n"
    "  uint64_t frameCount;"
)
replace_once(
    cpp,
    "  if (m.controller) {\n    m.controller->SetTransform(kControllerIndex, headTransform);\n  }",
    "  if (m.controller) {\n"
    "    vrb::Matrix controllerTransform = headTransform;\n"
    "    if (m.handPointerActive) {\n"
    "      const float hx = std::max(0.0f, std::min(1.0f, m.handPointerX));\n"
    "      const float hy = std::max(0.0f, std::min(1.0f, m.handPointerY));\n"
    "      const float nx = (hx - 0.5f) * 2.0f;\n"
    "      const float ny = (0.5f - hy) * 2.0f;\n"
    "      const float yaw = -nx * 0.62f;\n"
    "      const float pitch = ny * 0.46f;\n"
    "      vrb::Quaternion qYaw(0.0f, std::sin(yaw * 0.5f), 0.0f, std::cos(yaw * 0.5f));\n"
    "      vrb::Quaternion qPitch(std::sin(pitch * 0.5f), 0.0f, 0.0f, std::cos(pitch * 0.5f));\n"
    "      vrb::Quaternion qPointer = (qYaw * qPitch).Normalize();\n"
    "      controllerTransform = headTransform.PostMultiply(vrb::Matrix::Rotation(qPointer));\n"
    "    }\n"
    "    m.controller->SetTransform(kControllerIndex, controllerTransform);\n"
    "  }"
)
replace_once(
    cpp,
    "void DeviceDelegate3DOF::TouchEvent(const bool aDown, const float aX, const float aY) {",
    "void DeviceDelegate3DOF::HandPointer(const bool aActive, const float aX, const float aY) {\n"
    "  m.handPointerActive = aActive;\n"
    "  m.handPointerX = std::max(0.0f, std::min(1.0f, aX));\n"
    "  m.handPointerY = std::max(0.0f, std::min(1.0f, aY));\n"
    "  if (!aActive && m.controller) {\n"
    "    m.controller->SetSelectFactor(kControllerIndex, 0.0f);\n"
    "  }\n"
    "}\n\n"
    "void DeviceDelegate3DOF::TouchEvent(const bool aDown, const float aX, const float aY) {"
)

native = "app/src/universal3dof/cpp/native-lib.cpp"
replace_once(
    native,
    "JNI_METHOD(void, keyEvent)\n(JNIEnv*, jobject, jint aKeyCode, jboolean aDown) {",
    "JNI_METHOD(void, handPointer)\n"
    "(JNIEnv*, jobject, jboolean aActive, jfloat aX, jfloat aY) {\n"
    "  if (sAppContext && sAppContext->mDevice) {\n"
    "    sAppContext->mDevice->HandPointer(aActive, aX, aY);\n"
    "  }\n"
    "}\n\n"
    "JNI_METHOD(void, keyEvent)\n"
    "(JNIEnv*, jobject, jint aKeyCode, jboolean aDown) {"
)

print("Aperture hand-pinch patches applied successfully")
