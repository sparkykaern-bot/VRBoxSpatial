package com.apollo.vrboxspatial;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.View;

import com.google.ar.core.Pose;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SpatialOverlayView extends View {
    interface Host {
        void moveWindowToScreen(SpatialWindow window, float nx, float ny);
        void resetLayout();
        void createExtraBrowser();
        void requestUrlEntry();
        void cycleOptics();
        void onWindowVisibilityChanged();
    }

    private static final float IPD_METERS = 0.064f;

    private static final class Projected {
        final SpatialWindow win;
        final RectF rect;
        final float depth;
        Projected(SpatialWindow win, RectF rect, float depth) {
            this.win = win;
            this.rect = rect;
            this.depth = depth;
        }
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Host host;
    private final BrowserEngine browser;
    private final List<SpatialWindow> windows = new ArrayList<>();

    private Pose cameraPose = Pose.IDENTITY;
    private boolean tracking;
    private boolean handVisible;
    private float handX = .5f, handY = .5f;
    private boolean pinch, prevPinch;
    private String handStatus = "Mão: iniciando";
    private String arStatus = "ARCore: iniciando";
    private String cameraStatus = "Câmera: automática";
    private String opticsStatus = "Lentes: padrão";
    private String browserTitle = "YouTube";

    private SpatialWindow dragged;
    private SpatialWindow resized;
    private boolean browserGesture;
    private float gestureStartX, gestureStartY, browserStartNx, browserStartNy;
    private float resizeStartW, resizeStartH;
    private float resizeStartX, resizeStartY;

    private float fovDeg = 88f;
    private float uiScale = 1.12f;
    private boolean showSettings;
    private long lastFrameMs;
    private int fps, frames;

    SpatialOverlayView(android.content.Context context, Host host, BrowserEngine browser) {
        super(context);
        this.host = host;
        this.browser = browser;
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f);
    }

    void setWindows(List<SpatialWindow> list) {
        windows.clear();
        windows.addAll(list);
        postInvalidateOnAnimation();
    }

    void updateCameraPose(Pose pose, boolean isTracking) {
        cameraPose = pose == null ? Pose.IDENTITY : pose;
        tracking = isTracking;
        arStatus = tracking ? "ARCore: 6DoF" : "ARCore: procurando ambiente";
        frames++;
        long now = SystemClock.elapsedRealtime();
        if (lastFrameMs == 0) lastFrameMs = now;
        if (now - lastFrameMs >= 1000) {
            fps = frames;
            frames = 0;
            lastFrameMs = now;
        }
        postInvalidateOnAnimation();
    }

    void setCameraStatus(String status) {
        if (status != null && !status.isBlank()) cameraStatus = status;
        postInvalidateOnAnimation();
    }

    void setOpticsStatus(String status) {
        if (status != null && !status.isBlank()) opticsStatus = status;
        postInvalidateOnAnimation();
    }

    void setHand(float nx, float ny, boolean isPinch, float confidence) {
        handVisible = true;
        handX = clamp01(nx);
        handY = clamp01(ny);
        pinch = isPinch;
        handleInteraction();
        postInvalidateOnAnimation();
    }

    void clearHand() {
        handVisible = false;
        pinch = false;
        handleInteraction();
        postInvalidateOnAnimation();
    }

    void setHandStatus(String status) {
        if (status != null) handStatus = status;
        postInvalidateOnAnimation();
    }

    void setBrowserTitle(String title) {
        if (title != null && !title.isBlank()) browserTitle = title;
        postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int W = getWidth(), H = getHeight();
        if (W < 20 || H < 20) {
            browser.hide();
            return;
        }

        int eyeW = W / 2;
        drawVignette(c, 0, eyeW, H);
        drawVignette(c, eyeW, W - eyeW, H);

        SpatialWindow liveBrowser = firstVisibleBrowser();
        RectF leftBrowserRect = null;
        RectF rightBrowserRect = null;

        for (int eye = 0; eye < 2; eye++) {
            float eyeSign = eye == 0 ? -1f : 1f;
            int ox = eye * eyeW;

            for (SpatialWindow w : windows) {
                if (!w.visible || w.anchor == null) continue;
                Projected pr = project(w, eyeW, H, eyeSign);
                if (pr == null) continue;
                boolean isLive = w == liveBrowser;
                RectF content = drawWindow(c, pr, ox, isLive);
                if (isLive && content != null) {
                    if (eye == 0) leftBrowserRect = content;
                    else rightBrowserRect = content;
                }
            }

            drawDock(c, ox, eyeW, H);
            if (showSettings) drawSettings(c, ox, eyeW, H);
            if (handVisible) drawHand(c, ox + handX * eyeW, handY * H, eyeSign);
            drawReticle(c, ox + eyeW / 2f, H / 2f);
        }

        browser.setViewport(leftBrowserRect, rightBrowserRect,
                liveBrowser != null && leftBrowserRect != null && rightBrowserRect != null);

        p.setColor(0x25ffffff);
        c.drawRect(eyeW - 1, 0, eyeW + 1, H, p);
    }

    private SpatialWindow firstVisibleBrowser() {
        SpatialWindow first = null;
        for (SpatialWindow w : windows) {
            if (!w.visible || w.kind != SpatialWindow.Kind.BROWSER || w.anchor == null) continue;
            if ("browser-main".equals(w.id)) return w;
            if (first == null) first = w;
        }
        return first;
    }

    private void drawVignette(Canvas c, int ox, int w, int h) {
        p.setShader(new LinearGradient(ox, 0, ox + w, 0,
                new int[]{0x66000000,0x08000000,0x08000000,0x66000000},
                new float[]{0f,.10f,.90f,1f}, Shader.TileMode.CLAMP));
        c.drawRect(ox, 0, ox + w, h, p);
        p.setShader(null);
    }

    private Projected project(SpatialWindow w, int eyeW, int h, float eyeSign) {
        if (w.anchor == null) return null;

        // Real stereo: each eye is an actual camera pose separated by human-scale IPD.
        // This replaces the old fake pixel offset, so window depth now comes from geometry.
        Pose eyePose = cameraPose.compose(Pose.makeTranslation(eyeSign * IPD_METERS * .5f, 0f, 0f));
        float[] world = w.anchor.getPose().getTranslation();
        float[] local = eyePose.inverse().transformPoint(world);
        float z = -local[2];
        if (z < .22f || z > 7f) return null;

        float hAngle = (float)Math.toDegrees(Math.atan2(local[0], z));
        float vAngle = (float)Math.toDegrees(Math.atan2(local[1], z));
        if (Math.abs(hAngle) > 67f || Math.abs(vAngle) > 53f) return null;

        float f = (float)(eyeW * .5 / Math.tan(Math.toRadians(fovDeg * .5)));
        float cx = eyeW * .5f + f * (local[0] / z);
        float cy = h * .5f - f * (local[1] / z);

        float distance = (float)Math.sqrt(local[0]*local[0] + local[1]*local[1] + local[2]*local[2]);
        float comfortDepth = clamp(distance, .68f, 3.2f);
        float ww = f * (w.widthMeters / comfortDepth) * uiScale;
        float hh = f * (w.heightMeters / comfortDepth) * uiScale;

        // Apple-like comfort sizing: the main browser should feel like a large floating monitor,
        // not a tiny card. Still clamp it so it never explodes into the user's face.
        float maxW = eyeW * (w.kind == SpatialWindow.Kind.BROWSER ? .88f : .68f);
        float maxH = h * (w.kind == SpatialWindow.Kind.BROWSER ? .78f : .62f);
        float maxFactor = Math.min(maxW / Math.max(1f, ww), maxH / Math.max(1f, hh));
        if (maxFactor < 1f) { ww *= maxFactor; hh *= maxFactor; }

        float minW = eyeW * (w.kind == SpatialWindow.Kind.BROWSER ? .48f : .24f);
        float minH = h * (w.kind == SpatialWindow.Kind.BROWSER ? .36f : .18f);
        float minFactor = Math.max(minW / Math.max(1f, ww), minH / Math.max(1f, hh));
        if (minFactor > 1f) { ww *= minFactor; hh *= minFactor; }

        if (cx + ww/2 < -60 || cx - ww/2 > eyeW + 60 || cy + hh/2 < -60 || cy - hh/2 > h + 60) return null;
        return new Projected(w, new RectF(cx-ww/2, cy-hh/2, cx+ww/2, cy+hh/2), distance);
    }

    private RectF drawWindow(Canvas c, Projected pr, int ox, boolean liveBrowser) {
        RectF r = new RectF(pr.rect);
        r.offset(ox, 0);
        float radius = Math.min(28f, Math.max(12f, r.height() * .075f));

        // Deep soft shadow makes the panel read as detached from the passthrough image.
        p.setShadowLayer(34f, 0, 12f, 0xaa000000);
        p.setColor(pr.win.kind == SpatialWindow.Kind.BROWSER ? 0x180b111a : 0xd80f1621);
        c.drawRoundRect(r, radius, radius, p);
        p.clearShadowLayer();

        stroke.setStrokeWidth(liveBrowser ? 2.4f : 1.8f);
        stroke.setColor(liveBrowser ? 0x99d9eeff : 0x66cde8ff);
        c.drawRoundRect(r, radius, radius, stroke);

        float headerH = clamp(r.height() * .145f, 38f, 64f);
        RectF header = new RectF(r.left, r.top, r.right, r.top + headerH);
        p.setColor(0xc51a2330);
        c.drawRoundRect(header, radius, radius, p);
        p.setColor(0xc51a2330);
        c.drawRect(header.left, header.bottom - radius, header.right, header.bottom, p);

        p.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        p.setTextSize(clamp(headerH * .28f, 11f, 19f));
        p.setColor(Color.WHITE);

        String title = pr.win.kind == SpatialWindow.Kind.BROWSER ? browserTitle : pr.win.title;
        if (pr.win.kind == SpatialWindow.Kind.BROWSER) {
            c.drawText("‹   ⌂   ↻   YT", header.left + 14, header.centerY() + 5, p);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(ellipsize(title, 24), header.right - 44, header.centerY() + 5, p);
            p.setTextAlign(Paint.Align.LEFT);
        } else {
            c.drawText(ellipsize(title, 26), header.left + 16, header.centerY() + 5, p);
        }
        p.setTypeface(android.graphics.Typeface.DEFAULT);

        p.setColor(0xffff6b7d);
        c.drawCircle(r.right - 20, header.centerY(), 5.5f, p);

        RectF body = new RectF(r.left + 7, header.bottom + 6, r.right - 7, r.bottom - 7);

        if (pr.win.kind == SpatialWindow.Kind.BROWSER) {
            // The actual live WebViews sit BELOW this transparent overlay. Do not paint an opaque
            // bitmap here: hardware video surfaces such as YouTube need to remain live.
            p.setColor(0x10000000);
            c.drawRoundRect(body, 10, 10, p);
            if (!liveBrowser) {
                p.setColor(0xaa111722);
                c.drawRoundRect(body, 10, 10, p);
                p.setColor(0xccffffff);
                p.setTextSize(clamp(body.height()*.06f, 11f, 17f));
                c.drawText("Outra janela web • use a principal para vídeo", body.left+18, body.centerY(), p);
            }
        } else if (pr.win.kind == SpatialWindow.Kind.MEDIA) {
            drawMedia(c, body);
        } else if (pr.win.kind == SpatialWindow.Kind.NOTES) {
            drawNotes(c, body);
        } else {
            drawSystem(c, body, pr.depth);
        }

        float handle = clamp(r.width() * .025f, 8f, 16f);
        p.setColor(0x99ffffff);
        c.drawCircle(r.right - handle*.8f, r.bottom - handle*.8f, handle*.22f, p);

        return pr.win.kind == SpatialWindow.Kind.BROWSER && liveBrowser ? body : null;
    }

    private void drawMedia(Canvas c, RectF b) {
        p.setColor(0x33000000);
        c.drawRoundRect(b, 14, 14, p);
        p.setColor(0xffff3344);
        RectF yt = new RectF(b.left+18, b.top+18, b.left+86, b.top+64);
        c.drawRoundRect(yt, 13, 13, p);
        p.setColor(Color.WHITE);
        p.setTextSize(clamp(b.height()*.16f, 20f, 38f));
        c.drawText("▶", yt.left+22, yt.bottom-10, p);
        p.setTextSize(clamp(b.height()*.085f, 13f, 21f));
        c.drawText("YouTube", b.left+102, b.top+48, p);
        p.setTextSize(clamp(b.height()*.055f, 10f, 15f));
        p.setColor(0xc8ffffff);
        c.drawText("Pinça neste painel para abrir vídeo real", b.left+20, b.centerY()+24, p);
    }

    private void drawNotes(Canvas c, RectF b) {
        p.setColor(0x28000000);
        c.drawRoundRect(b, 14, 14, p);
        p.setTextSize(clamp(b.height()*.085f, 12f, 18f));
        p.setColor(Color.WHITE);
        c.drawText("NOTAS", b.left+16, b.top+30, p);
        p.setTextSize(clamp(b.height()*.058f, 10f, 14f));
        p.setColor(0xc0ffffff);
        c.drawText("• Profundidade estéreo real por olho", b.left+16, b.top+62, p);
        c.drawText("• Janela principal em distância confortável", b.left+16, b.top+88, p);
        c.drawText("• RESET traz tudo de volta para perto", b.left+16, b.top+114, p);
    }

    private void drawSystem(Canvas c, RectF b, float depth) {
        p.setTextSize(clamp(b.height()*.07f, 9f, 14f));
        p.setColor(0xeaffffff);
        float y = b.top + 22;
        c.drawText(arStatus, b.left+10, y, p); y += 22;
        c.drawText(handStatus, b.left+10, y, p); y += 22;
        c.drawText(cameraStatus, b.left+10, y, p); y += 22;
        c.drawText(String.format(Locale.US, "UI %d FPS • %.2f m • IPD 64 mm", fps, depth), b.left+10, y, p);
        p.setColor(tracking ? 0xff60f2a7 : 0xffffc857);
        c.drawCircle(b.right-18, b.top+20, 6, p);
    }

    private void drawDock(Canvas c, int ox, int eyeW, int H) {
        float w = Math.min(eyeW*.86f, 560f);
        float h = Math.min(66f, H*.12f);
        RectF r = new RectF(ox+eyeW/2f-w/2, H-h-16, ox+eyeW/2f+w/2, H-16);
        p.setShadowLayer(24, 0, 8, 0x88000000);
        p.setColor(0xdd101721);
        c.drawRoundRect(r, 24, 24, p);
        p.clearShadowLayer();
        stroke.setColor(0x66cce9ff);
        stroke.setStrokeWidth(1.8f);
        c.drawRoundRect(r, 24, 24, stroke);

        String[] icons = {"YT", "WEB", "+", "RESET", "CAL", "⚙"};
        for (int i=0; i<icons.length; i++) {
            float cx = r.left + (i+.5f) * r.width()/icons.length;
            p.setColor(i==0 ? 0x55ff3344 : 0x28ffffff);
            c.drawCircle(cx, r.centerY(), h*.31f, p);
            p.setColor(Color.WHITE);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(Math.max(9f, h*.18f));
            c.drawText(icons[i], cx, r.centerY()+4, p);
        }
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawSettings(Canvas c, int ox, int eyeW, int H) {
        RectF r = new RectF(ox+eyeW*.07f, H*.07f, ox+eyeW*.93f, H*.34f);
        p.setColor(0xe5111823);
        c.drawRoundRect(r, 20, 20, p);
        stroke.setColor(0x77d9eeff);
        stroke.setStrokeWidth(1.8f);
        c.drawRoundRect(r, 20, 20, stroke);

        p.setColor(Color.WHITE);
        p.setTextSize(Math.max(12f, H*.027f));
        c.drawText("AJUSTES VR BOX", r.left+16, r.top+28, p);
        p.setTextSize(Math.max(9.5f, H*.019f));
        p.setColor(0xd0ffffff);
        c.drawText(String.format(Locale.US, "FOV %.0f° • UI %.0f%% • IPD virtual 64 mm", fovDeg, uiScale*100), r.left+16, r.top+56, p);
        c.drawText(ellipsize(opticsStatus, 48), r.left+16, r.top+80, p);
        c.drawText("CAL troca o perfil das lentes. Escolha o que funde a câmera sem visão dupla.", r.left+16, r.top+104, p);
    }

    private void drawHand(Canvas c, float x, float y, float eyeSign) {
        // Tiny stereo separation makes the cursor feel slightly in front of the window plane.
        float stereo = eyeSign * 3.0f;
        float rad = pinch ? 13f : 10f;
        p.setColor(pinch ? 0xff79f2c0 : 0xff81d4ff);
        p.setShadowLayer(16, 0, 0, pinch ? 0xff2bdc9a : 0xff3cb7ff);
        c.drawCircle(x + stereo, y, rad, p);
        p.clearShadowLayer();
        stroke.setColor(0xeeffffff);
        stroke.setStrokeWidth(2f);
        c.drawCircle(x + stereo, y, rad+5, stroke);
    }

    private void drawReticle(Canvas c, float x, float y) {
        stroke.setColor(0x44ffffff);
        stroke.setStrokeWidth(1.4f);
        c.drawCircle(x, y, 3.5f, stroke);
    }

    private void handleInteraction() {
        if (!handVisible) {
            if (prevPinch && browserGesture) finishBrowserGesture();
            dragged = null;
            resized = null;
            browserGesture = false;
            prevPinch = false;
            return;
        }

        boolean down = pinch && !prevPinch;
        boolean up = !pinch && prevPinch;

        if (down) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (handleDockHit()) {
                prevPinch = pinch;
                return;
            }

            Projected hit = hitWindow(handX, handY);
            if (hit != null) {
                RectF r = hit.rect;
                float px = handX * (getWidth()/2f);
                float py = handY * getHeight();
                float headerH = clamp(r.height()*.145f, 38f, 64f);

                if (px > r.right-r.width()*.12f && py > r.bottom-r.height()*.16f) {
                    resized = hit.win;
                    resizeStartW = hit.win.widthMeters;
                    resizeStartH = hit.win.heightMeters;
                    resizeStartX = handX;
                    resizeStartY = handY;
                } else if (py < r.top + headerH) {
                    if (px > r.right-r.width()*.09f) {
                        hit.win.visible = false;
                        host.onWindowVisibilityChanged();
                    } else if (hit.win.kind == SpatialWindow.Kind.BROWSER) {
                        float rel = (px-r.left)/Math.max(1f,r.width());
                        if (rel < .08f) browser.back();
                        else if (rel < .16f) browser.home();
                        else if (rel < .24f) browser.reload();
                        else if (rel < .34f) browser.youtube();
                        else dragged = hit.win;
                    } else {
                        dragged = hit.win;
                    }
                } else if (hit.win.kind == SpatialWindow.Kind.BROWSER) {
                    RectF content = browserContentRect(hit.rect);
                    browserGesture = true;
                    gestureStartX = handX;
                    gestureStartY = handY;
                    browserStartNx = (px-content.left)/Math.max(1f,content.width());
                    browserStartNy = (py-content.top)/Math.max(1f,content.height());
                } else if (hit.win.kind == SpatialWindow.Kind.MEDIA) {
                    browser.youtube();
                    showBrowser();
                }
            }
        }

        if (pinch && dragged != null) host.moveWindowToScreen(dragged, handX, handY);

        if (pinch && resized != null) {
            float dx = (handX-resizeStartX)*2.2f;
            float dy = (handY-resizeStartY)*1.6f;
            resized.widthMeters = clamp(resizeStartW+dx, .45f, 1.25f);
            resized.heightMeters = clamp(resizeStartH+dy, .28f, .82f);
            invalidate();
        }

        if (up) {
            if (browserGesture) finishBrowserGesture();
            dragged = null;
            resized = null;
            browserGesture = false;
        }
        prevPinch = pinch;
    }

    private RectF browserContentRect(RectF r) {
        float headerH = clamp(r.height()*.145f, 38f, 64f);
        return new RectF(r.left+7, r.top+headerH+6, r.right-7, r.bottom-7);
    }

    private void finishBrowserGesture() {
        Projected hit = findBrowserProjection();
        if (hit == null) return;
        RectF content = browserContentRect(hit.rect);
        float px = handX*(getWidth()/2f);
        float py = handY*getHeight();
        float endNx = (px-content.left)/Math.max(1f,content.width());
        float endNy = (py-content.top)/Math.max(1f,content.height());
        float moved = (float)Math.hypot(handX-gestureStartX, handY-gestureStartY);
        if (moved < .025f) browser.tap(browserStartNx, browserStartNy);
        else browser.drag(browserStartNx, browserStartNy, endNx, endNy);
    }

    private Projected findBrowserProjection() {
        int eyeW = Math.max(1, getWidth()/2);
        SpatialWindow w = firstVisibleBrowser();
        return w == null ? null : project(w, eyeW, getHeight(), -1f);
    }

    private boolean handleDockHit() {
        int eyeW = getWidth()/2;
        int H = getHeight();
        float w = Math.min(eyeW*.86f, 560f);
        float h = Math.min(66f, H*.12f);
        RectF r = new RectF(eyeW/2f-w/2, H-h-16, eyeW/2f+w/2, H-16);
        float px = handX*eyeW, py = handY*H;
        if (!r.contains(px,py)) return false;

        int idx = (int)((px-r.left)/(r.width()/6f));
        idx = Math.max(0, Math.min(5, idx));
        if (idx == 0) {
            browser.youtube();
            showBrowser();
        } else if (idx == 1) {
            toggle(SpatialWindow.Kind.BROWSER);
        } else if (idx == 2) {
            host.createExtraBrowser();
        } else if (idx == 3) {
            host.resetLayout();
        } else if (idx == 4) {
            host.cycleOptics();
        } else {
            showSettings = !showSettings;
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        return true;
    }

    private void showBrowser() {
        for (SpatialWindow w : windows) {
            if (w.kind == SpatialWindow.Kind.BROWSER) {
                w.visible = true;
                host.onWindowVisibilityChanged();
                return;
            }
        }
    }

    private void toggle(SpatialWindow.Kind kind) {
        for (SpatialWindow w : windows) {
            if (w.kind == kind) {
                w.visible = !w.visible;
                host.onWindowVisibilityChanged();
                return;
            }
        }
    }

    private Projected hitWindow(float nx, float ny) {
        int eyeW = getWidth()/2;
        int H = getHeight();
        float px = nx*eyeW, py = ny*H;
        for (int i=windows.size()-1; i>=0; i--) {
            SpatialWindow w = windows.get(i);
            if (!w.visible || w.anchor == null) continue;
            Projected pr = project(w, eyeW, H, -1f);
            if (pr != null && pr.rect.contains(px,py)) return pr;
        }
        return null;
    }

    float[] screenRay(float nx, float ny) {
        int eyeW = Math.max(1, getWidth()/2);
        int H = Math.max(1, getHeight());
        float f = (float)(eyeW*.5/Math.tan(Math.toRadians(fovDeg*.5)));
        float x = (nx*eyeW-eyeW*.5f)/f;
        float y = -(ny*H-H*.5f)/f;
        float len = (float)Math.sqrt(x*x+y*y+1f);
        return new float[]{x/len, y/len, -1f/len};
    }

    Pose cameraPose() { return cameraPose; }

    float depthFor(SpatialWindow w) {
        // Moving a window happens on a stable comfort plane instead of preserving a noisy z value
        // from a momentary hand pose. This prevents windows shooting meters away during a pinch.
        return clamp(w.depthMeters, .78f, 1.35f);
    }

    private static String ellipsize(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(1,max-1)) + "…";
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clamp01(float v) { return clamp(v,0f,1f); }
}
