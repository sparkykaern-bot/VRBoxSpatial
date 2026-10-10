#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: runtime_fix_v5.py <aperture-root>")

root = Path(sys.argv[1]).resolve()

def patch(rel, old, new):
    p = root / rel
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"anchor not found in {rel}: {old[:160]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")

act = "app/src/universal3dof/java/com/igalia/wolvic/PlatformActivity.java"

old = '''            @Override public void onHand(float x, float y, boolean pinch, boolean openHand, float confidence) {
                final float px = Math.max(0.02f, Math.min(0.98f, x));
                final float py = Math.max(0.02f, Math.min(0.98f, y));
                queueRunnable(() -> handPointer(true, px, py));
                setHandTrigger(pinch && !openHand);
            }'''

new = '''            @Override public void onHand(float x, float y, boolean pinch, boolean openHand, float confidence) {
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
patch(act, old, new)

# Keep trigger state transitions explicit in logcat too.
old2 = '''    private synchronized void setHandTrigger(boolean down) {
        if (mHandTriggerDown == down) return;
        mHandTriggerDown = down;
        queueRunnable(() -> touchEvent(down, 0.0f, 0.0f));
    }'''
new2 = '''    private synchronized void setHandTrigger(boolean down) {
        if (mHandTriggerDown == down) return;
        mHandTriggerDown = down;
        Log.i("ApertureHand", down ? "PINCH_TRIGGER_DOWN" : "PINCH_TRIGGER_UP");
        queueRunnable(() -> touchEvent(down, 0.0f, 0.0f));
    }'''
patch(act, old2, new2)

print("Aperture pinch diagnostics v5 applied")
