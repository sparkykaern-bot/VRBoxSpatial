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
        void onHand(float imageX, float imageY, int imageWidth, int imageHeight, boolean pinch, float confidence);
        void onHandLost();
        void onHandStatus(String status);
    }

    private static final String TAG = "HandTracker";
    private static final int OUT_W = 320;
    private static final int OUT_H = 240;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final Listener listener;
    private HandLandmarker landmarker;
    private long lastSubmitMs;

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
                    .setMinHandDetectionConfidence(0.45f)
                    .setMinHandPresenceConfidence(0.45f)
                    .setMinTrackingConfidence(0.4f)
                    .build();
            landmarker = HandLandmarker.createFromOptions(context, options);
            listener.onHandStatus("Mão: ativa");
        } catch (Throwable t) {
            Log.e(TAG, "MediaPipe init failed", t);
            listener.onHandStatus("Mão: indisponível");
        }
    }

    void submit(Image image, long timestampNs) {
        long now = SystemClock.elapsedRealtime();
        if (landmarker == null || now - lastSubmitMs < 70 || !busy.compareAndSet(false, true)) {
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
                listener.onHandLost();
            } finally {
                busy.set(false);
            }
        });
    }

    private void parse(HandLandmarkerResult result, int imageWidth, int imageHeight) {
        if (result == null || result.landmarks().isEmpty()) {
            listener.onHandLost();
            return;
        }
        List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> lm = result.landmarks().get(0);
        if (lm.size() <= 8) {
            listener.onHandLost();
            return;
        }
        float ix = lm.get(8).x();
        float iy = lm.get(8).y();
        float tx = lm.get(4).x();
        float ty = lm.get(4).y();
        float d = (float)Math.hypot(ix - tx, iy - ty);
        boolean pinch = d < 0.060f;
        float confidence = Math.max(0f, Math.min(1f, 1f - d / 0.14f));
        listener.onHand(ix, iy, imageWidth, imageHeight, pinch, confidence);
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
                int r = clamp((298 * c + 409 * e + 128) >> 8);
                int g = clamp((298 * c - 100 * d - 208 * e + 128) >> 8);
                int b = clamp((298 * c + 516 * d + 128) >> 8);
                argb[oy * outW + ox] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(argb, 0, outW, 0, 0, outW, outH);
        return bitmap;
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(255, v); }
}
