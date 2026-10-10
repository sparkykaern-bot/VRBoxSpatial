#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: runtime_fix_v6.py <aperture-root>")

root = Path(sys.argv[1]).resolve()

def patch(rel, old, new):
    p = root / rel
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"anchor not found in {rel}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")

# Haptic feedback permission for pinch-down confirmation.
manifest = "app/src/main/AndroidManifest.xml"
patch(
    manifest,
    '    <uses-permission android:name="android.permission.CAMERA"/>\n',
    '    <uses-permission android:name="android.permission.CAMERA"/>\n'
    '    <uses-permission android:name="android.permission.VIBRATE"/>\n'
)

act = "app/src/universal3dof/java/com/igalia/wolvic/PlatformActivity.java"

patch(
    act,
    "    private android.widget.TextView mHandDebugText;\n    private volatile boolean mHandTriggerDown = false;",
    "    private android.widget.TextView mHandDebugText;\n"
    "    private volatile boolean mHandTriggerDown = false;\n"
    "    private volatile long mHandTriggerStartedMs = 0L;"
)

# Compact status pill instead of a full-width diagnostic banner.
patch(
    act,
    "        android.widget.FrameLayout.LayoutParams handLp = new android.widget.FrameLayout.LayoutParams(\n"
    "                android.view.ViewGroup.LayoutParams.MATCH_PARENT,\n"
    "                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);\n"
    "        handLp.gravity = android.view.Gravity.TOP;\n",
    "        android.widget.FrameLayout.LayoutParams handLp = new android.widget.FrameLayout.LayoutParams(\n"
    "                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,\n"
    "                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);\n"
    "        handLp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;\n"
    "        handLp.topMargin = 10;\n"
)

old_hand = '''            @Override public void onHand(float x, float y, boolean pinch, boolean openHand, float confidence) {
                final float px = Math.max(0.02f, Math.min(0.98f, x));
                final float py = Math.max(0.02f, Math.min(0.98f, y));
                queueRunnable(() -> handPointer(true, px, py));
                final boolean trigger = pinch && !openHand;
                setHandTrigger(trigger);
                runOnUiThread(() -> {
                    if (mHandDebugText != null) {
                        mHandDebugText.setText(trigger
                                ? "Mão: detectada | PINÇA: APERTADA"
                                : "Mão: detectada | PINÇA: SOLTA");
                        mHandDebugText.setBackgroundColor(trigger ? 0xCC1B5E20 : 0xAA000000);
                    }
                });
            }'''

new_hand = '''            @Override public void onHand(float x, float y, boolean pinch, boolean openHand, float confidence) {
                final float px = Math.max(0.02f, Math.min(0.98f, x));
                final float py = Math.max(0.02f, Math.min(0.98f, y));
                queueRunnable(() -> handPointer(true, px, py));
                final boolean trigger = pinch && !openHand;
                setHandTrigger(trigger);
                final long heldMs = trigger && mHandTriggerStartedMs > 0L
                        ? Math.max(0L, android.os.SystemClock.elapsedRealtime() - mHandTriggerStartedMs)
                        : 0L;
                runOnUiThread(() -> {
                    if (mHandDebugText != null) {
                        if (trigger) {
                            mHandDebugText.setText(heldMs > 260L
                                    ? "✦ PINÇA • SEGURANDO / ARRASTAR"
                                    : "✦ PINÇA • CLIQUE");
                            mHandDebugText.setBackgroundColor(heldMs > 260L ? 0xCC0D47A1 : 0xCC1B5E20);
                        } else {
                            mHandDebugText.setText("Mão ativa • junte polegar + indicador");
                            mHandDebugText.setBackgroundColor(0xAA000000);
                        }
                    }
                });
            }'''
patch(act, old_hand, new_hand)

old_trigger = '''    private synchronized void setHandTrigger(boolean down) {
        if (mHandTriggerDown == down) return;
        mHandTriggerDown = down;
        Log.i("ApertureHand", down ? "PINCH_TRIGGER_DOWN" : "PINCH_TRIGGER_UP");
        queueRunnable(() -> touchEvent(down, 0.0f, 0.0f));
    }'''

new_trigger = '''    private synchronized void setHandTrigger(boolean down) {
        if (mHandTriggerDown == down) return;
        mHandTriggerDown = down;
        if (down) {
            mHandTriggerStartedMs = android.os.SystemClock.elapsedRealtime();
            handHapticClick();
        } else {
            mHandTriggerStartedMs = 0L;
        }
        Log.i("ApertureHand", down ? "PINCH_TRIGGER_DOWN" : "PINCH_TRIGGER_UP");
        queueRunnable(() -> touchEvent(down, 0.0f, 0.0f));
    }

    private void handHapticClick() {
        try {
            android.os.Vibrator vibrator = (android.os.Vibrator)getSystemService(android.content.Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(18L, 115));
            } else {
                vibrator.vibrate(18L);
            }
        } catch (Throwable ignored) {}
    }'''
patch(act, old_trigger, new_trigger)

cpp = "app/src/universal3dof/cpp/DeviceDelegate3DOF.cpp"

# Make hand motion precise near the centre, but still able to reach the edges of
# wide browser panels without exaggerated wrist movement.
old_map = '''      const float nx = (hx - 0.5f) * 2.0f;
      const float ny = (0.5f - hy) * 2.0f;
      const float yaw = -nx * 0.62f;
      const float pitch = ny * 0.46f;'''
new_map = '''      const float nxRaw = (hx - 0.5f) * 2.0f;
      const float nyRaw = (0.5f - hy) * 2.0f;
      const float nx = std::copysign(std::pow(std::abs(nxRaw), 1.12f), nxRaw);
      const float ny = std::copysign(std::pow(std::abs(nyRaw), 1.10f), nyRaw);
      const float yaw = -nx * 0.66f;
      const float pitch = ny * 0.50f;'''
patch(cpp, old_map, new_map)

# The Wolvic select factor is also used to render interaction feedback. Fill it
# while pinching so the user can see that the native trigger is really down.
old_touch = '''void DeviceDelegate3DOF::TouchEvent(const bool aDown, const float aX, const float aY) {
  if (m.controller) {
    m.dwellTimer = 0.0f;
    m.controller->SetSelectFactor(kControllerIndex, 0.0f);
    m.controller->SetButtonState(kControllerIndex, ControllerDelegate::BUTTON_TRIGGER, 0, aDown, aDown);
    if (aDown) {
      m.controller->SetSelectActionStart(kControllerIndex);
    } else {
      m.controller->SetSelectActionStop(kControllerIndex);
    }
  }
}'''
new_touch = '''void DeviceDelegate3DOF::TouchEvent(const bool aDown, const float aX, const float aY) {
  if (m.controller) {
    m.dwellTimer = 0.0f;
    m.controller->SetSelectFactor(kControllerIndex, aDown ? 1.0f : 0.0f);
    m.controller->SetButtonState(kControllerIndex, ControllerDelegate::BUTTON_TRIGGER, 0, aDown, aDown);
    if (aDown) {
      m.controller->SetSelectActionStart(kControllerIndex);
    } else {
      m.controller->SetSelectActionStop(kControllerIndex);
    }
  }
}'''
patch(cpp, old_touch, new_touch)

print("Aperture interaction polish v6 applied")
