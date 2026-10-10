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
    private static final long MIN_FRAME_MS = 32;   // ~30 FPS when inference keeps up.
    private static final long LOST_GRACE_MS = 160;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final Listener listener;
    private HandLandmarker landmarker;
    private long lastSubmitMs;
    private long lastTimestampMs;
    private long lastSeenMs;
    private boolean lostNotified = true;

    private boolean pinchState;
    private int pinchEnterFrames;
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
                    .setMinHandDetectionConfidence(0.35f)
                    .setMinHandPresenceConfidence(0.36f)
                    .setMinTrackingConfidence(0.34f)
                    .build();
            landmarker = HandLandmarker.createFromOptions(context, options);
            listener.onHandStatus("Mão: rápida • ~30 FPS");
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

                // No YUV -> Bitmap conversion. MediaPipe can wrap the camera Image directly,
                // which removes the most expensive CPU step from the previous versions.
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
        float palm = Math.max(0.030f, distance(lm, 0, 9));
        float pinchRatio = distance(lm, 4, 8) / palm;

        // Fast hysteresis: react quickly when fingers touch, but require a clearer separation
        // before releasing so natural landmark noise cannot flicker the gesture.
        if (!pinchState) {
            if (pinchRatio < 0.47f) {
                pinchEnterFrames++;
                if (pinchEnterFrames >= 1) {
                    pinchState = true;
                    pinchEnterFrames = 0;
                    pinchExitFrames = 0;
                }
            } else {
                pinchEnterFrames = 0;
            }
        } else {
            if (pinchRatio > 0.64f) {
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

        boolean wantsOpen = !pinchState && extended >= 3 && pinchRatio > 0.68f;
        if (wantsOpen) {
            openFrames++;
            if (openFrames >= 2) openState = true;
        } else {
            openFrames = 0;
            openState = false;
        }
        if (pinchState) openState = false;

        // Responsive pointer: index tip drives the cursor, DIP contributes a little stability.
        float targetX = lm.get(8).x() * 0.86f + lm.get(7).x() * 0.14f;
        float targetY = lm.get(8).y() * 0.86f + lm.get(7).y() * 0.14f;

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
            velocityX = velocityX * 0.55f + rawVx * 0.45f;
            velocityY = velocityY * 0.55f + rawVy * 0.45f;
            lastTargetX = targetX;
            lastTargetY = targetY;

            float motion = (float)Math.hypot(targetX - smoothX, targetY - smoothY);
            float alpha = clamp(0.48f + motion * 5.5f, 0.48f, 0.86f);
            if (pinchState) alpha = clamp(alpha, 0.44f, 0.68f);
            if (openState) alpha = clamp(alpha, 0.40f, 0.62f);

            // Tiny prediction reduces perceived cursor latency without letting fast movements
            // overshoot dramatically.
            float predict = pinchState ? 0.20f : 0.30f;
            float predictedX = targetX + velocityX * predict;
            float predictedY = targetY + velocityY * predict;
            smoothX += (predictedX - smoothX) * alpha;
            smoothY += (predictedY - smoothY) * alpha;
        }

        float confidence = clamp(1f - Math.abs(pinchRatio - (pinchState ? .32f : .90f)) * .28f, .50f, 1f);
        listener.onHand(clamp01(smoothX), clamp01(smoothY), imageWidth, imageHeight,
                pinchState, openState, confidence);
    }

    private void handleNoHand() {
        long now = SystemClock.elapsedRealtime();
        if (lastSeenMs == 0 || now - lastSeenMs > LOST_GRACE_MS) {
            pinchState = false;
            openState = false;
            pinchEnterFrames = 0;
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
        return tipD > pipD * 1.10f;
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
