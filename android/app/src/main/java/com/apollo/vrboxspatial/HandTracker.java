package com.apollo.vrboxspatial;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.os.SystemClock;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.nio.ByteBuffer;
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
    private static final int OUT_W = 384;
    private static final int OUT_H = 288;
    private static final long MIN_FRAME_MS = 55;
    private static final long LOST_GRACE_MS = 240;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final Listener listener;
    private HandLandmarker landmarker;
    private long lastSubmitMs;
    private long lastSeenMs;
    private boolean lostNotified = true;

    private boolean pinchState;
    private int pinchTransitionFrames;
    private boolean openState;
    private int openTransitionFrames;

    private boolean pointerReady;
    private float smoothX;
    private float smoothY;

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
                    .setMinHandDetectionConfidence(0.40f)
                    .setMinHandPresenceConfidence(0.42f)
                    .setMinTrackingConfidence(0.38f)
                    .build();
            landmarker = HandLandmarker.createFromOptions(context, options);
            listener.onHandStatus("Mão: ativa • gesto estável");
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
            try {
                int sourceW = image.getWidth();
                int sourceH = image.getHeight();
                Bitmap bitmap = yuv420ToBitmap(image, OUT_W, OUT_H);
                image.close();
                MPImage mp = new BitmapImageBuilder(bitmap).build();
                long tsMs = Math.max(1L, timestampNs / 1_000_000L);
                HandLandmarkerResult result = landmarker.detectForVideo(mp, tsMs);
                parse(result, sourceW, sourceH);
                bitmap.recycle();
            } catch (Throwable t) {
                try { image.close(); } catch (Throwable ignored) { }
                Log.w(TAG, "Hand frame failed", t);
                handleNoHand();
            } finally {
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

        float wristX = lm.get(0).x(), wristY = lm.get(0).y();
        float palm = Math.max(0.035f, distance(lm, 0, 9));
        float pinchRatio = distance(lm, 4, 8) / palm;

        // Hysteresis: entering a pinch is deliberately harder than remaining pinched.
        boolean wantsPinch = pinchState ? pinchRatio < 0.58f : pinchRatio < 0.42f;
        if (wantsPinch != pinchState) {
            pinchTransitionFrames++;
            if (pinchTransitionFrames >= 2) {
                pinchState = wantsPinch;
                pinchTransitionFrames = 0;
            }
        } else {
            pinchTransitionFrames = 0;
        }

        int extended = 0;
        if (fingerExtended(lm, 8, 6, wristX, wristY)) extended++;
        if (fingerExtended(lm, 12, 10, wristX, wristY)) extended++;
        if (fingerExtended(lm, 16, 14, wristX, wristY)) extended++;
        if (fingerExtended(lm, 20, 18, wristX, wristY)) extended++;
        boolean wantsOpen = !pinchState && extended >= 3 && pinchRatio > 0.62f;
        if (wantsOpen != openState) {
            openTransitionFrames++;
            if (openTransitionFrames >= 2) {
                openState = wantsOpen;
                openTransitionFrames = 0;
            }
        } else {
            openTransitionFrames = 0;
        }
        if (pinchState) openState = false;

        // Index tip is responsive; mixing a little DIP position removes high-frequency jitter.
        float targetX = lm.get(8).x() * .78f + lm.get(7).x() * .22f;
        float targetY = lm.get(8).y() * .78f + lm.get(7).y() * .22f;
        if (!pointerReady) {
            smoothX = targetX;
            smoothY = targetY;
            pointerReady = true;
        } else {
            float motion = (float)Math.hypot(targetX - smoothX, targetY - smoothY);
            float alpha = clamp(0.18f + motion * 5.0f, 0.18f, 0.58f);
            if (pinchState) alpha = Math.min(alpha, 0.30f);
            if (openState) alpha = Math.min(alpha, 0.24f);
            smoothX += (targetX - smoothX) * alpha;
            smoothY += (targetY - smoothY) * alpha;
        }

        float confidence = clamp(1f - Math.abs(pinchRatio - (pinchState ? .30f : .85f)) * .35f, .45f, 1f);
        listener.onHand(clamp01(smoothX), clamp01(smoothY), imageWidth, imageHeight,
                pinchState, openState, confidence);
    }

    private void handleNoHand() {
        long now = SystemClock.elapsedRealtime();
        if (lastSeenMs == 0 || now - lastSeenMs > LOST_GRACE_MS) {
            pinchState = false;
            openState = false;
            pinchTransitionFrames = 0;
            openTransitionFrames = 0;
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
        return tipD > pipD * 1.12f;
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

    private static Bitmap yuv420ToBitmap(Image image, int outW, int outH) {
        int srcW = image.getWidth();
        int srcH = image.getHeight();
        Image.Plane[] p = image.getPlanes();
        ByteBuffer yb = p[0].getBuffer();
        ByteBuffer ub = p[1].getBuffer();
        ByteBuffer vb = p[2].getBuffer();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
        int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
        int[] argb = new int[outW * outH];
        for (int oy = 0; oy < outH; oy++) {
            int sy = Math.min(srcH - 1, oy * srcH / outH);
            int cy = sy >> 1;
            for (int ox = 0; ox < outW; ox++) {
                int sx = Math.min(srcW - 1, ox * srcW / outW);
                int cx = sx >> 1;
                int y = yb.get(yRow * sy + yPix * sx) & 0xff;
                int u = ub.get(uRow * cy + uPix * cx) & 0xff;
                int v = vb.get(vRow * cy + vPix * cx) & 0xff;
                int c = Math.max(0, y - 16);
                int d = u - 128;
                int e = v - 128;
                int r = clamp255((298 * c + 409 * e + 128) >> 8);
                int g = clamp255((298 * c - 100 * d - 208 * e + 128) >> 8);
                int b = clamp255((298 * c + 516 * d + 128) >> 8);
                argb[oy * outW + ox] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(argb, 0, outW, 0, 0, outW, outH);
        return bitmap;
    }

    private static int clamp255(int v) { return v < 0 ? 0 : Math.min(255, v); }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clamp01(float v) { return clamp(v, 0f, 1f); }
}
