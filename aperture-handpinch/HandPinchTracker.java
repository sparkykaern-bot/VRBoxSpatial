package com.igalia.wolvic;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.os.SystemClock;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Personal hand-control layer for Aperture Reality. */
public final class HandPinchTracker {
    public interface Listener {
        void onHand(float x, float y, boolean pinch, boolean openHand, float confidence);
        void onHandLost();
        void onStatus(String status);
    }

    private static final String TAG = "ApertureHand";
    // ~25 fps analysis. Frames are still dropped while inference is busy, so this
    // lowers latency without allowing a backlog to build up.
    private static final long MIN_FRAME_MS = 40L;
    private static final long LOST_GRACE_MS = 220L;
    // Briefly lock the cursor on pinch-down so touching thumb/index does not kick
    // the pointer off the button. After this period a held pinch becomes a drag.
    private static final long PINCH_LATCH_MS = 95L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final Listener listener;
    private HandLandmarker landmarker;
    private long lastSubmitMs, lastTimestampMs, lastSeenMs, pinchStartedMs;
    private boolean lostNotified = true;
    private boolean pinchState, openState, cursorReady;
    private boolean frameAnnounced, processingAnnounced, frameErrorAnnounced, detectionAnnounced;
    private int pinchReleaseFrames, pinchEnterFrames, openFrames;
    private float smoothX=.5f, smoothY=.5f, lastX=.5f, lastY=.5f, velX, velY;
    private float pinchAnchorX=.5f, pinchAnchorY=.5f;

    public HandPinchTracker(Context context, Listener listener) {
        this.listener = listener;
        executor.execute(() -> init(context.getApplicationContext()));
    }

    private void init(Context context) {
        try {
            BaseOptions base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build();
            HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(.25f)
                    .setMinHandPresenceConfidence(.25f)
                    .setMinTrackingConfidence(.25f)
                    .build();
            landmarker = HandLandmarker.createFromOptions(context, options);
            listener.onStatus("Mão: MediaPipe pronto");
        } catch (Throwable t) {
            Log.e(TAG, "MediaPipe init failed", t);
            listener.onStatus("Mão: MediaPipe indisponível");
        }
    }

    /** Takes ownership of image and always closes it. */
    public void submit(Image image, long timestampNs, int rotationDegrees) {
        long now = SystemClock.elapsedRealtime();
        if (landmarker == null || now-lastSubmitMs < MIN_FRAME_MS || !busy.compareAndSet(false,true)) {
            image.close();
            return;
        }
        lastSubmitMs = now;
        if (!frameAnnounced) {
            frameAnnounced = true;
            listener.onStatus("Mão: frame da câmera recebido");
        }

        executor.execute(() -> {
            MPImage mp = null;
            Bitmap bitmap = null;
            try {
                bitmap = yuv420ToRgbBitmap(image);
                if (bitmap == null) throw new IllegalStateException("YUV->RGB returned null");
                if (!processingAnnounced) {
                    processingAnnounced = true;
                    listener.onStatus("Mão: RGB chegando ao MediaPipe");
                }
                mp = new BitmapImageBuilder(bitmap).build();
                long ts = Math.max(lastTimestampMs+1L, Math.max(1L,timestampNs/1_000_000L));
                lastTimestampMs = ts;
                int rot = ((rotationDegrees%360)+360)%360;
                ImageProcessingOptions proc = ImageProcessingOptions.builder().setRotationDegrees(rot).build();
                parse(landmarker.detectForVideo(mp, proc, ts));
            } catch (Throwable t) {
                Log.w(TAG,"Hand RGB frame failed",t);
                if (!frameErrorAnnounced) {
                    frameErrorAnnounced = true;
                    listener.onStatus("Mão: erro ao processar frame RGB");
                }
                noHand();
            } finally {
                if (mp != null) try { mp.close(); } catch (Throwable ignored) {}
                if (bitmap != null && !bitmap.isRecycled()) try { bitmap.recycle(); } catch (Throwable ignored) {}
                try { image.close(); } catch (Throwable ignored) {}
                busy.set(false);
            }
        });
    }

    private static Bitmap yuv420ToRgbBitmap(Image image) {
        if (image.getFormat() != android.graphics.ImageFormat.YUV_420_888) {
            throw new IllegalArgumentException("Unexpected format " + image.getFormat());
        }
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) throw new IllegalStateException("Missing YUV planes");

        final int srcW = image.getWidth();
        final int srcH = image.getHeight();
        if (srcW <= 0 || srcH <= 0) throw new IllegalStateException("Invalid YUV size");

        final int maxW = 320;
        final int outW = Math.min(srcW, maxW);
        final int outH = Math.max(1, Math.round(srcH * (outW / (float)srcW)));
        final int[] argb = new int[outW * outH];

        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        final int yRow = planes[0].getRowStride();
        final int yPix = planes[0].getPixelStride();
        final int uRow = planes[1].getRowStride();
        final int uPix = planes[1].getPixelStride();
        final int vRow = planes[2].getRowStride();
        final int vPix = planes[2].getPixelStride();

        for (int oy=0; oy<outH; oy++) {
            int sy = Math.min(srcH-1, (int)((long)oy * srcH / outH));
            int uvY = sy >> 1;
            for (int ox=0; ox<outW; ox++) {
                int sx = Math.min(srcW-1, (int)((long)ox * srcW / outW));
                int uvX = sx >> 1;

                int yi = sy * yRow + sx * yPix;
                int ui = uvY * uRow + uvX * uPix;
                int vi = uvY * vRow + uvX * vPix;
                if (yi >= yBuf.limit() || ui >= uBuf.limit() || vi >= vBuf.limit()) continue;

                int y = (yBuf.get(yi) & 0xff) - 16;
                int u = (uBuf.get(ui) & 0xff) - 128;
                int v = (vBuf.get(vi) & 0xff) - 128;
                if (y < 0) y = 0;

                int r = (298*y + 409*v + 128) >> 8;
                int g = (298*y - 100*u - 208*v + 128) >> 8;
                int b = (298*y + 516*u + 128) >> 8;
                r = r < 0 ? 0 : Math.min(255, r);
                g = g < 0 ? 0 : Math.min(255, g);
                b = b < 0 ? 0 : Math.min(255, b);
                argb[oy*outW + ox] = 0xff000000 | (r<<16) | (g<<8) | b;
            }
        }

        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        out.setPixels(argb, 0, outW, 0, 0, outW, outH);
        return out;
    }

    private void parse(HandLandmarkerResult result) {
        if (result == null || result.landmarks().isEmpty()) { noHand(); return; }
        List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> lm = result.landmarks().get(0);
        if (lm.size() < 21) { noHand(); return; }
        final long now = SystemClock.elapsedRealtime();
        lastSeenMs = now;
        lostNotified = false;
        if (!detectionAnnounced) {
            detectionAnnounced = true;
            listener.onStatus("Mão: detectada");
        }

        float wx=lm.get(0).x(), wy=lm.get(0).y();
        float palmWidth = Math.max(.035f, distance(lm,5,17));
        float tipDistance = distance(lm,4,8);
        float pinchRatio = tipDistance / palmWidth;
        // Secondary measurement makes the gesture tolerant of a slightly sideways
        // hand, where the thumb can visually overlap the index before the 2D tips
        // become extremely close.
        float thumbToIndexDipRatio = distance(lm,4,7) / palmWidth;

        boolean wantsPinch = tipDistance < .064f || pinchRatio < .68f || thumbToIndexDipRatio < .60f;
        boolean wantsRelease = tipDistance > .086f && pinchRatio > .88f && thumbToIndexDipRatio > .76f;
        boolean wasPinching = pinchState;

        if (!pinchState) {
            if (wantsPinch) {
                if (++pinchEnterFrames >= 1) {
                    pinchState = true;
                    pinchEnterFrames = 0;
                    pinchReleaseFrames = 0;
                    pinchStartedMs = now;
                    pinchAnchorX = smoothX;
                    pinchAnchorY = smoothY;
                }
            } else {
                pinchEnterFrames = 0;
            }
        } else if (wantsRelease) {
            if (++pinchReleaseFrames >= 2) {
                pinchState = false;
                pinchReleaseFrames = 0;
                pinchEnterFrames = 0;
            }
        } else {
            pinchReleaseFrames = 0;
        }

        int extended=0;
        if (extended(lm,8,6,wx,wy)) extended++;
        if (extended(lm,12,10,wx,wy)) extended++;
        if (extended(lm,16,14,wx,wy)) extended++;
        if (extended(lm,20,18,wx,wy)) extended++;
        boolean wantsOpen=!pinchState && extended>=3 && pinchRatio>.92f;
        if (wantsOpen) { if (++openFrames>=2) openState=true; }
        else { openFrames=0; openState=false; }
        if (pinchState) openState=false;

        float tx=lm.get(8).x()*.91f + lm.get(7).x()*.09f;
        float ty=lm.get(8).y()*.91f + lm.get(7).y()*.09f;
        if (!cursorReady) {
            smoothX=lastX=tx; smoothY=lastY=ty; velX=velY=0; cursorReady=true;
            pinchAnchorX=smoothX; pinchAnchorY=smoothY;
        } else {
            float vx=tx-lastX, vy=ty-lastY;
            velX=velX*.50f+vx*.50f;
            velY=velY*.50f+vy*.50f;
            lastX=tx; lastY=ty;

            // A tiny dead-zone kills fingertip landmark shimmer while preserving
            // intentional motion. Pinch/drag gets a slightly larger dead-zone.
            float dx=tx-smoothX, dy=ty-smoothY;
            float motion=(float)Math.hypot(dx,dy);
            float deadzone=pinchState ? .0042f : .0026f;
            if (motion > deadzone) {
                float a=clamp(.57f+motion*5.4f,.57f,.94f);
                if (pinchState) a=clamp(a,.50f,.80f);
                float pred=pinchState?.08f:.16f;
                float targetX=tx+velX*pred;
                float targetY=ty+velY*pred;
                smoothX += (targetX-smoothX)*a;
                smoothY += (targetY-smoothY)*a;
            }
        }

        float outX=smoothX, outY=smoothY;
        if (pinchState && now-pinchStartedMs < PINCH_LATCH_MS) {
            // Click where the cursor was immediately before the fingers closed.
            outX=pinchAnchorX;
            outY=pinchAnchorY;
        } else if (wasPinching && !pinchState) {
            // Drop accumulated velocity on release so the reticle does not flick.
            velX*=.25f;
            velY*=.25f;
        }

        listener.onHand(clamp01(outX),clamp01(outY),pinchState,openState,.90f);
    }

    private void noHand() {
        long now=SystemClock.elapsedRealtime();
        if (lastSeenMs==0 || now-lastSeenMs>LOST_GRACE_MS) {
            pinchState=false; openState=false;
            pinchReleaseFrames=pinchEnterFrames=openFrames=0;
            cursorReady=false;
            detectionAnnounced=false;
            if (!lostNotified) { lostNotified=true; listener.onHandLost(); }
        }
    }

    private static boolean extended(List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> lm,int tip,int pip,float wx,float wy) {
        return dist(lm.get(tip).x(),lm.get(tip).y(),wx,wy) > dist(lm.get(pip).x(),lm.get(pip).y(),wx,wy)*1.10f;
    }
    private static float distance(List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> lm,int a,int b) {
        return dist(lm.get(a).x(),lm.get(a).y(),lm.get(b).x(),lm.get(b).y());
    }
    private static float dist(float ax,float ay,float bx,float by) { return (float)Math.hypot(ax-bx,ay-by); }
    private static float clamp(float v,float lo,float hi){ return Math.max(lo,Math.min(hi,v)); }
    private static float clamp01(float v){ return clamp(v,0f,1f); }

    public void close() {
        executor.execute(() -> { if (landmarker!=null) { try { landmarker.close(); } catch(Throwable ignored){} landmarker=null; } });
        executor.shutdown();
    }
}
