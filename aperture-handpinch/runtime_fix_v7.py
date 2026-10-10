#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: runtime_fix_v7.py <aperture-root>")

root = Path(sys.argv[1]).resolve()

def patch(rel, old, new):
    p = root / rel
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"anchor not found in {rel}: {old[:200]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")

# ---------------------------------------------------------------------------
# Hand analysis: target one inference opportunity per display frame. The busy
# gate still drops frames if MediaPipe is slower, so latency never queues up.
# Lower RGB analysis resolution leaves more thermal/CPU budget for 60Hz VR.
# ---------------------------------------------------------------------------
tracker = "app/src/universal3dof/java/com/igalia/wolvic/HandPinchTracker.java"
patch(tracker,
      "    private static final long MIN_FRAME_MS = 40L;",
      "    private static final long MIN_FRAME_MS = 16L;")
patch(tracker,
      "        final int maxW = 320;",
      "        final int maxW = 256;")

# ---------------------------------------------------------------------------
# Passthrough camera: request the best advertised AE FPS range containing 60.
# Exact [60,60] wins; otherwise prefer the containing range with the highest
# lower bound (e.g. [30,60] over [15,60]). If 60 is unavailable, fall back to
# the camera's highest advertised range rather than breaking the session.
# ---------------------------------------------------------------------------
cam = "app/src/universal3dof/java/com/igalia/wolvic/PassthroughCameraManager.java"
patch(cam,
      "import android.util.Size;\n",
      "import android.util.Size;\nimport android.util.Range;\n")

fps_helper_anchor = "    private void createCameraPreviewSession() {\n"
fps_helper = '''    private Range<Integer> choosePreferredCameraFpsRange() {
        if (mCameraCharacteristics == null) return null;
        try {
            Range<Integer>[] ranges = mCameraCharacteristics.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges == null || ranges.length == 0) return null;

            Range<Integer> best60 = null;
            Range<Integer> bestAny = null;
            for (Range<Integer> r : ranges) {
                if (r == null) continue;
                if (bestAny == null || r.getUpper() > bestAny.getUpper() ||
                        (r.getUpper().equals(bestAny.getUpper()) && r.getLower() > bestAny.getLower())) {
                    bestAny = r;
                }
                if (r.getLower() <= 60 && r.getUpper() >= 60) {
                    if (best60 == null || r.getLower() > best60.getLower() ||
                            (r.getLower().equals(best60.getLower()) && r.getUpper() < best60.getUpper())) {
                        best60 = r;
                    }
                }
            }
            return best60 != null ? best60 : bestAny;
        } catch (Throwable t) {
            Log.w(TAG, "Could not inspect camera FPS ranges", t);
            return null;
        }
    }

    private void createCameraPreviewSession() {
'''
patch(cam, fps_helper_anchor, fps_helper)

patch(cam,
'''            // Auto-focus mode if supported
            if (mCameraCharacteristics != null) {''',
'''            // Prefer a real 60fps camera stream when Camera2 advertises it.
            Range<Integer> preferredFps = choosePreferredCameraFpsRange();
            if (preferredFps != null) {
                try {
                    previewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, preferredFps);
                    Log.i(TAG, "Passthrough FPS target range: " + preferredFps);
                } catch (Throwable t) {
                    Log.w(TAG, "Camera rejected requested FPS range " + preferredFps, t);
                }
            }

            // Auto-focus mode if supported
            if (mCameraCharacteristics != null) {''')

# ---------------------------------------------------------------------------
# Display/render: explicitly request a 60Hz presentation path, keep continuous
# GLSurfaceView rendering, sample head orientation faster than 60Hz, and expose
# the actual rendered FPS counter so the headset test can verify pacing.
# ---------------------------------------------------------------------------
act = "app/src/universal3dof/java/com/igalia/wolvic/PlatformActivity.java"
patch(act,
'''        super.onCreate(savedInstanceState);
        // Force landscape orientation for 3DOF mobile VR''',
'''        super.onCreate(savedInstanceState);
        try {
            android.view.WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.preferredRefreshRate = 60.0f;
            getWindow().setAttributes(attrs);
        } catch (Throwable t) {
            Log.w(LOGTAG, "Could not request 60Hz display mode", t);
        }
        // Force landscape orientation for 3DOF mobile VR''')

patch(act,
'''        if (mFrameRate != null) {
            mFrameRate.setVisibility(View.GONE);
        }''',
'''        if (mFrameRate != null) {
            mFrameRate.setVisibility(View.VISIBLE);
            mFrameRate.setText("VR -- FPS");
            mFrameRate.setTextSize(11.0f);
            mFrameRate.setBackgroundColor(0x88000000);
        }''')

patch(act,
'''        mView.setEGLContextClientVersion(3);
        mView.setEGLConfigChooser(8, 8, 8, 0, 16, 0);''',
'''        mView.setEGLContextClientVersion(3);
        mView.setEGLConfigChooser(8, 8, 8, 0, 16, 0);
        mView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);''')

patch(act,
'''                    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
                        Log.d(LOGTAG, "Universal3DOF onSurfaceCreated");''',
'''                    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
                        Log.d(LOGTAG, "Universal3DOF onSurfaceCreated");
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            try {
                                android.view.Surface renderSurface = mView.getHolder().getSurface();
                                if (renderSurface != null && renderSurface.isValid()) {
                                    renderSurface.setFrameRate(60.0f,
                                            android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
                                }
                            } catch (Throwable t) {
                                Log.w(LOGTAG, "Could not set Surface 60fps pacing", t);
                            }
                        }''')

patch(act,
'''                                    mFrameRate.setText(String.valueOf(value));''',
'''                                    mFrameRate.setText("VR " + value + " FPS");''')

patch(act,
'''            mSensorRegistered = mSensorManager.registerListener(this, mSelectedSensor, SensorManager.SENSOR_DELAY_GAME);''',
'''            // ~125Hz IMU sampling gives the 60Hz renderer a fresh orientation every frame.
            mSensorRegistered = mSensorManager.registerListener(this, mSelectedSensor, 8000);''')

# ---------------------------------------------------------------------------
# Native pointer: MediaPipe inference may not always finish at 60Hz. Keep a
# target coordinate from inference and interpolate the actual ray every render
# frame. This makes the visible pointer animate at display cadence without a
# backlog or fake click events.
# ---------------------------------------------------------------------------
cpp = "app/src/universal3dof/cpp/DeviceDelegate3DOF.cpp"
patch(cpp,
'''  float handPointerX = 0.5f;
  float handPointerY = 0.5f;''',
'''  float handPointerX = 0.5f;
  float handPointerY = 0.5f;
  float handPointerTargetX = 0.5f;
  float handPointerTargetY = 0.5f;''')

old_hand = '''void DeviceDelegate3DOF::HandPointer(const bool aActive, const float aX, const float aY) {
  const bool changed = (m.handPointerActive != aActive);
  m.handPointerActive = aActive;
  m.handPointerX = std::max(0.0f, std::min(1.0f, aX));
  m.handPointerY = std::max(0.0f, std::min(1.0f, aY));

  if (m.controller && changed) {'''
new_hand = '''void DeviceDelegate3DOF::HandPointer(const bool aActive, const float aX, const float aY) {
  const bool changed = (m.handPointerActive != aActive);
  const float targetX = std::max(0.0f, std::min(1.0f, aX));
  const float targetY = std::max(0.0f, std::min(1.0f, aY));
  if (aActive && !m.handPointerActive) {
    m.handPointerX = targetX;
    m.handPointerY = targetY;
  }
  m.handPointerTargetX = targetX;
  m.handPointerTargetY = targetY;
  m.handPointerActive = aActive;

  if (m.controller && changed) {'''
patch(cpp, old_hand, new_hand)

patch(cpp,
'''    if (m.handPointerActive) {
      const float hx = std::max(0.0f, std::min(1.0f, m.handPointerX));''',
'''    if (m.handPointerActive) {
      // Update the visible ray every render frame, even between MediaPipe results.
      // 0.72 converges fast enough to keep added latency very small while hiding
      // the staircase of a slower inference stream.
      m.handPointerX += (m.handPointerTargetX - m.handPointerX) * 0.72f;
      m.handPointerY += (m.handPointerTargetY - m.handPointerY) * 0.72f;
      const float hx = std::max(0.0f, std::min(1.0f, m.handPointerX));''')

print("Aperture 60 FPS target pipeline v7 applied")
