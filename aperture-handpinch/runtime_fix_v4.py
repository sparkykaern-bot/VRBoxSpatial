#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: runtime_fix_v4.py <aperture-root>")

root = Path(sys.argv[1]).resolve()

def patch(rel, old, new):
    p = root / rel
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"anchor not found in {rel}: {old[:160]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")

act = "app/src/universal3dof/java/com/igalia/wolvic/PlatformActivity.java"

patch(
    act,
    "    private HandPinchTracker mHandPinchTracker;\n    private volatile boolean mHandTriggerDown = false;",
    "    private HandPinchTracker mHandPinchTracker;\n"
    "    private android.widget.TextView mHandDebugText;\n"
    "    private volatile boolean mHandTriggerDown = false;"
)

patch(
    act,
    "    private void setupHandPinchControl() {\n        mHandPinchTracker = new HandPinchTracker(this, new HandPinchTracker.Listener() {",
    "    private void setupHandPinchControl() {\n"
    "        mHandDebugText = new android.widget.TextView(this);\n"
    "        mHandDebugText.setText(\"Mão: inicializando...\");\n"
    "        mHandDebugText.setTextColor(android.graphics.Color.WHITE);\n"
    "        mHandDebugText.setTextSize(13f);\n"
    "        mHandDebugText.setGravity(android.view.Gravity.CENTER);\n"
    "        mHandDebugText.setPadding(16, 8, 16, 8);\n"
    "        mHandDebugText.setBackgroundColor(0xAA000000);\n"
    "        android.widget.FrameLayout.LayoutParams handLp = new android.widget.FrameLayout.LayoutParams(\n"
    "                android.view.ViewGroup.LayoutParams.MATCH_PARENT,\n"
    "                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);\n"
    "        handLp.gravity = android.view.Gravity.TOP;\n"
    "        addContentView(mHandDebugText, handLp);\n\n"
    "        mHandPinchTracker = new HandPinchTracker(this, new HandPinchTracker.Listener() {"
)

old_status = '''            @Override public void onStatus(String status) {
                Log.i("ApertureHand", status);
                runOnUiThread(() -> android.widget.Toast.makeText(PlatformActivity.this, status, android.widget.Toast.LENGTH_SHORT).show());
            }'''
new_status = '''            @Override public void onStatus(String status) {
                Log.i("ApertureHand", status);
                runOnUiThread(() -> {
                    if (mHandDebugText != null) mHandDebugText.setText(status);
                    android.widget.Toast.makeText(PlatformActivity.this, status, android.widget.Toast.LENGTH_SHORT).show();
                });
            }'''
patch(act, old_status, new_status)

# Keep the diagnostic visible even while the hand is not found. When a real hand
# arrives the tracker will replace this with "Mão: detectada".
patch(
    act,
    "            @Override public void onHandLost() {\n                setHandTrigger(false);\n                queueRunnable(() -> handPointer(false, 0.5f, 0.5f));\n            }",
    "            @Override public void onHandLost() {\n"
    "                setHandTrigger(false);\n"
    "                queueRunnable(() -> handPointer(false, 0.5f, 0.5f));\n"
    "                runOnUiThread(() -> {\n"
    "                    if (mHandDebugText != null) mHandDebugText.setText(\"Mão: procurando...\");\n"
    "                });\n"
    "            }"
)

print("Aperture RGB hand diagnostics v4 applied")
