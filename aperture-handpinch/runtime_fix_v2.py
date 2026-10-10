#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: runtime_fix_v2.py <aperture-root>")

root = Path(sys.argv[1]).resolve()

def patch(rel, old, new):
    p = root / rel
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"anchor not found in {rel}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")

cam = "app/src/universal3dof/java/com/igalia/wolvic/PassthroughCameraManager.java"

# Keep an application context only for visible diagnostics.
patch(cam,
"    private int mSensorOrientation = 90;\n",
"    private int mSensorOrientation = 90;\n    private Context mAppContext;\n")

patch(cam,
"    public synchronized void start(Context context) {\n        if (mIsRunning) {",
"    public synchronized void start(Context context) {\n        mAppContext = context.getApplicationContext();\n        if (mIsRunning) {")

# On phones where the ultra-wide physical camera rejects PRIVATE + YUV outputs,
# prefer the default/main rear camera for the hand-enabled build. It is much more
# likely to support a preview SurfaceTexture and a simultaneous YUV analysis stream.
patch(cam,
"            if (bestCameraId == null) {\n                bestCameraId = defaultBackCameraId;\n                bestChars = defaultChars;\n            }",
"            if (mFrameListener != null && defaultBackCameraId != null && defaultChars != null) {\n                bestCameraId = defaultBackCameraId;\n                bestChars = defaultChars;\n                try {\n                    float[] focalLengths = defaultChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);\n                    android.util.SizeF sensorSize = defaultChars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);\n                    if (focalLengths != null && focalLengths.length > 0 && sensorSize != null && focalLengths[0] > 0) {\n                        float f = focalLengths[0];\n                        float w = Math.max(sensorSize.getWidth(), sensorSize.getHeight());\n                        float h = Math.min(sensorSize.getWidth(), sensorSize.getHeight());\n                        bestFovX = 2.0f * (float)Math.atan(w / (2.0f * f));\n                        bestFovY = 2.0f * (float)Math.atan(h / (2.0f * f));\n                    }\n                } catch (Throwable ignored) {}\n                Log.i(TAG, \"Hand mode: preferring main rear camera \" + bestCameraId);\n            }\n            if (bestCameraId == null) {\n                bestCameraId = defaultBackCameraId;\n                bestChars = defaultChars;\n            }")

# Lower the preview bandwidth so the phone can sustain preview + YUV analysis.
patch(cam,
"                mSurfaceTexture.setDefaultBufferSize(1920, 1080);",
"                mSurfaceTexture.setDefaultBufferSize(1280, 720);")

# Select an actually advertised low-resolution YUV size rather than assuming 640x480 exists.
patch(cam,
"                if (sizes != null && sizes.length > 0) {\n                    double targetArea = 640.0 * 360.0;\n                    double targetRatio = 16.0 / 9.0;",
"                if (sizes != null && sizes.length > 0) {\n                    chosen = sizes[0];\n                    double targetArea = 320.0 * 240.0;\n                    double targetRatio = 4.0 / 3.0;")
patch(cam,
"                        if (area > 1280L * 720L) continue;\n",
"")

# Tell the tester whether the extra camera stream really survived Camera2 session setup.
patch(cam,
"                                Log.i(TAG, \"Camera preview session repeating request started\");",
"                                Log.i(TAG, \"Camera preview session repeating request started\");\n                                if (mHandImageReader != null && mFrameListener != null && mAppContext != null) {\n                                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->\n                                            android.widget.Toast.makeText(mAppContext, \"Mão: stream da câmera ativo\", android.widget.Toast.LENGTH_SHORT).show());\n                                }")

patch(cam,
"                            Log.e(TAG, \"Camera capture session configuration failed\");\n                            if (mHandImageReader != null) {",
"                            Log.e(TAG, \"Camera capture session configuration failed\");\n                            if (mHandImageReader != null && mAppContext != null) {\n                                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->\n                                        android.widget.Toast.makeText(mAppContext, \"Mão: câmera recusou o stream de rastreamento\", android.widget.Toast.LENGTH_LONG).show());\n                            }\n                            if (mHandImageReader != null) {")

tracker = "app/src/universal3dof/java/com/igalia/wolvic/HandPinchTracker.java"
patch(tracker,
"    private boolean pinchState, openState, cursorReady;\n",
"    private boolean pinchState, openState, cursorReady;\n    private boolean detectionAnnounced;\n")
patch(tracker,
"                    .setMinHandDetectionConfidence(.34f)\n                    .setMinHandPresenceConfidence(.35f)\n                    .setMinTrackingConfidence(.32f)",
"                    .setMinHandDetectionConfidence(.25f)\n                    .setMinHandPresenceConfidence(.25f)\n                    .setMinTrackingConfidence(.25f)")
patch(tracker,
"        lostNotified = false;\n\n        float wx=lm.get(0).x(), wy=lm.get(0).y();",
"        lostNotified = false;\n        if (!detectionAnnounced) {\n            detectionAnnounced = true;\n            listener.onStatus(\"Mão: detectada\");\n        }\n\n        float wx=lm.get(0).x(), wy=lm.get(0).y();")

act = "app/src/universal3dof/java/com/igalia/wolvic/PlatformActivity.java"
patch(act,
"            @Override public void onStatus(String status) { Log.i(\"ApertureHand\", status); }",
"            @Override public void onStatus(String status) {\n                Log.i(\"ApertureHand\", status);\n                runOnUiThread(() -> android.widget.Toast.makeText(PlatformActivity.this, status, android.widget.Toast.LENGTH_SHORT).show());\n            }")

print("Aperture hand runtime compatibility fixes v2 applied")
