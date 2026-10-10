package com.apollo.vrboxspatial;

import android.content.Context;
import android.media.Image;
import android.os.SystemClock;
import android.util.Log;

import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.framework.image.MediaImageBuilder;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class HandTracker {
    interface Listener {
        void onHand(float imageX, float imageY, int imageWidth, int imageHeight,
                    boolean pinch, boolean openHand, float confidence);
        void onHandLost();
        void onHandStatus(String status);
    }

    private static final String TAG = "HandTracker";
    // The camera normally tops out near 30 FPS in ARCore, but 24 ms avoids artificially
    // throttling faster camera configurations and lets the busy/drop mechanism decide.
    private static final long MIN_FRAME_MS = 24;
    private static final long LOST_GRACE_MS = 130;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final Listener listener;
    private HandLandmarker landmarker;
    private long lastSubmitMs;
    private long lastTimestampMs;
    private long lastSeenMs;
    private boolean lostNotified = true;

    private boolean pinchState;
    private int pinchExitFrames;
    private boolean openState;
    private int openFrames;

    private boolean pointerReady;
    private float smoothX;
    private float smoothY;
    private float lastTargetX;
    private float lastTargetY;
    private float velocityX;
    private float velocityY;

    HandTracker(Context context, Listener listener) {
        this.listener = listener;
        executor.execute(() -> init(context.getApplicationContext()));
    }

    private void init(Context context) {
        try {
            BaseOptions base = BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .build();
            HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(0.32f)
                    .setMinHandPresenceConfidence(0.34f)
                    .setMinTrackingConfidence(0.32f)
                    .build();
            landmarker = HandLandmarker.createFromOptions(context, options);
            listener.onHandStatus("Mão: baixa latência • pinça rápida");
        } catch (Throwable t) {
            Log.e(TAG, "MediaPipe init failed", t);
            listener.onHandStatus("Mão: indisponível");
        }
    }

    void submit(Image image, long timestampNs) {
        long now = SystemClock.elapsedRealtime();
        if (landmarker == null || now - lastSubmitMs < MIN_FRAME_MS || !busy.compareAndSet(false, true)) {
            image.close();
            return;
        }
        lastSubmitMs = now;

        executor.execute(() -> {
            MPImage mp = null;
            try {
                int sourceW = image.getWidth();
                int sourceH = image.getHeight();
                mp = new MediaImageBuilder(image).build();
                long cameraTs = Math.max(1L, timestampNs / 1_000_000L);
                long tsMs = Math.max(lastTimestampMs + 1L, cameraTs);
                lastTimestampMs = tsMs;
                HandLandmarkerResult result = landmarker.detectForVideo(mp, tsMs);
                parse(result, sourceW, sourceH);
            } catch (Throwable t) {
                Log.w(TAG, "Hand frame failed", t);
                handleNoHand();
            } finally {
                if (mp != null) {
                    try { mp.close(); } catch (Throwable ignored) { }
                }
                try { image.close(); } catch (Throwable ignored) { }
                busy.set(false);
            }
        });
    }

    private void parse(HandLandmarkerResult result, int imageWidth, int imageHeight) {
        if (result == null || result.landmarks().isEmpty()) {
            handleNoHand();
            return;
        }
        List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> lm = result.landmarks().get(0);
        if (lm.size() < 21) {
            handleNoHand();
            return;
        }

        long now = SystemClock.elapsedRealtime();
        lastSeenMs = now;
        lostNotified = false;

        float wristX = lm.get(0).x();
        float wristY = lm.get(0).y();
        float palm = Math.max(0.028f, distance(lm, 0, 9));
        float pinchRatio = distance(lm, 4, 8) / palm;

        // Selection should feel instant: entering pinch is one-frame, leaving it is hysteretic.
        if (!pinchState && pinchRatio < 0.52f) {
            pinchState = true;
            pinchExitFrames = 0;
        } else if (pinchState) {
            if (pinchRatio > 0.70f) {
                pinchExitFrames++;
                if (pinchExitFrames >= 2) {
                    pinchState = false;
                    pinchExitFrames = 0;
                }
            } else {
                pinchExitFrames = 0;
            }
        }

        int extended = 0;
        if (fingerExtended(lm, 8, 6, wristX, wristY)) extended++;
        if (fingerExtended(lm, 12, 10, wristX, wristY)) extended++;
        if (fingerExtended(lm, 16, 14, wristX, wristY)) extended++;
        if (fingerExtended(lm, 20, 18, wristX, wristY)) extended++;

        boolean wantsOpen = !pinchState && extended >= 3 && pinchRatio > 0.72f;
        if (wantsOpen) {
            openFrames++;
            if (openFrames >= 2) openState = true;
        } else {
            openFrames = 0;
            openState = false;
        }
        if (pinchState) openState = false;

        // Index tip dominates the pointer. DIP only removes the highest-frequency landmark noise.
        float targetX = lm.get(8).x() * 0.91f + lm.get(7).x() * 0.09f;
        float targetY = lm.get(8).y() * 0.91f + lm.get(7).y() * 0.09f;

        if (!pointerReady) {
            smoothX = targetX;
            smoothY = targetY;
            lastTargetX = targetX;
            lastTargetY = targetY;
            velocityX = velocityY = 0f;
            pointerReady = true;
        } else {
            float rawVx = targetX - lastTargetX;
            float rawVy = targetY - lastTargetY;
            velocityX = velocityX * 0.42f + rawVx * 0.58f;
            velocityY = velocityY * 0.42f + rawVy * 0.58f;
            lastTargetX = targetX;
            lastTargetY = targetY;

            float error = (float)Math.hypot(targetX - smoothX, targetY - smoothY);
            float speed = (float)Math.hypot(velocityX, velocityY);
            float alpha = clamp(0.60f + error * 6.0f + speed * 3.0f, 0.60f, 0.92f);
            if (pinchState) alpha = clamp(alpha, 0.56f, 0.80f);
            if (openState) alpha = clamp(alpha, 0.52f, 0.76f);

            // Small forward prediction compensates for camera/inference latency. Clamp it so a
            // sudden landmark jump cannot throw the cursor across the whole interface.
            float predict = pinchState ? 0.22f : 0.38f;
            float px = targetX + clamp(velocityX * predict, -.035f, .035f);
            float py = targetY + clamp(velocityY * predict, -.035f, .035f);
            smoothX += (px - smoothX) * alpha;
            smoothY += (py - smoothY) * alpha;
        }

        float confidence = clamp(1f - Math.abs(pinchRatio - (pinchState ? .34f : .92f)) * .25f, .52f, 1f);
        listener.onHand(clamp01(smoothX), clamp01(smoothY), imageWidth, imageHeight,
                pinchState, openState, confidence);
    }

    private void handleNoHand() {
        long now = SystemClock.elapsedRealtime();
        if (lastSeenMs == 0 || now - lastSeenMs > LOST_GRACE_MS) {
            pinchState = false;
            openState = false;
            pinchExitFrames = 0;
            openFrames = 0;
            pointerReady = false;
            if (!lostNotified) {
                lostNotified = true;
                listener.onHandLost();
            }
        }
    }

    private static boolean fingerExtended(
            List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> lm,
            int tip, int pip, float wristX, float wristY) {
        float tipD = dist(lm.get(tip).x(), lm.get(tip).y(), wristX, wristY);
        float pipD = dist(lm.get(pip).x(), lm.get(pip).y(), wristX, wristY);
        return tipD > pipD * 1.09f;
    }

    private static float distance(
            List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> lm,
            int a, int b) {
        return dist(lm.get(a).x(), lm.get(a).y(), lm.get(b).x(), lm.get(b).y());
    }

    private static float dist(float ax, float ay, float bx, float by) {
        return (float)Math.hypot(ax - bx, ay - by);
    }

    void close() {
        executor.execute(() -> {
            if (landmarker != null) {
                try { landmarker.close(); } catch (Throwable ignored) { }
                landmarker = null;
            }
        });
        executor.shutdown();
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clamp01(float v) { return clamp(v, 0f, 1f); }
}
