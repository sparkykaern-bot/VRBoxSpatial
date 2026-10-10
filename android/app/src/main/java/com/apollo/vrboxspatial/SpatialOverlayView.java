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
import com.google.ar.core.TrackingState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SpatialOverlayView extends View {
    interface Host {
        void moveWindowToScreen(SpatialWindow window, float nx, float ny);
        void resetLayout();
        void createExtraBrowser();
        void requestUrlEntry();
        void cycleOptics();
        void onWindowVisibilityChanged();
    }

    private static final float IPD_METERS = 0.056f;
    private static final long INVALID_HOLD_MS = 520;

    private static final class Projected {
        final SpatialWindow win;
        final RectF rect;
        final float depth;
        Projected(SpatialWindow win, RectF rect, float depth) {
            this.win = win; this.rect = rect; this.depth = depth;
        }
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Host host;
    private final BrowserEngine browser;
    private final List<SpatialWindow> windows = new ArrayList<>();
    private final Map<String, RectF> smoothedRects = new HashMap<>();
    private final Map<String, RectF> lastValidRects = new HashMap<>();
    private final Map<String, Long> lastValidMs = new HashMap<>();
    private final Map<String, RectF> leftHitRects = new HashMap<>();

    private Pose cameraPose = Pose.IDENTITY;
    private boolean tracking;
    private float projectionX = 1.20f;
    private float projectionY = 1.75f;

    private boolean handVisible;
    private boolean handInitialized;
    private float handX = .5f, handY = .5f;
    private boolean pinch, prevPinch, openHand;
    private String handStatus = "Mão: iniciando";
    private String arStatus = "ARCore: iniciando";
    private String cameraStatus = "Câmera: automática";
    private String opticsStatus = "Calibração: padrão";
    private String browserTitle = "YouTube";

    private SpatialWindow dragged;
    private SpatialWindow resized;
    private float dragStartHandX, dragStartHandY;
    private boolean browserGesture;
    private RectF browserGestureRect;
    private float gestureStartX, gestureStartY, browserStartNx, browserStartNy;
    private float resizeStartW, resizeStartH;
    private float resizeStartX, resizeStartY;

    private float uiScale = 1.00f;
    private boolean showSettings;
    private boolean calibrationMode;
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
        smoothedRects.clear();
        lastValidRects.clear();
        lastValidMs.clear();
        leftHitRects.clear();
        postInvalidateOnAnimation();
    }

    void setProjectionScales(float x, float y) {
        if (Float.isFinite(x) && x > .25f && x < 8f) projectionX = x;
        if (Float.isFinite(y) && y > .25f && y < 8f) projectionY = y;
    }

    void updateCameraPose(Pose pose, boolean isTracking) {
        if (isTracking && pose != null) cameraPose = pose;
        tracking = isTracking;
        arStatus = tracking ? "ARCore: 6DoF estável" : "ARCore: mantendo última pose";
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
    }

    void setOpticsStatus(String status) {
        if (status != null && !status.isBlank()) opticsStatus = status;
        postInvalidateOnAnimation();
    }

    boolean isCalibrationMode() { return calibrationMode; }

    void setHand(float nx, float ny, boolean isPinch, boolean isOpenHand, float confidence) {
        float tx = clamp01(nx), ty = clamp01(ny);
        openHand = isOpenHand;
        if (!handInitialized) {
            handX = tx; handY = ty; handInitialized = true;
        } else {
            // HandTracker already performs temporal filtering. Keep only a light display filter so
            // the cursor remains responsive instead of accumulating latency in two places.
            float a = isPinch ? .58f : (isOpenHand ? .48f : .74f);
            handX += (tx - handX) * a;
            handY += (ty - handY) * a;
        }
        handVisible = true;
        pinch = isPinch;
        handleInteraction();
        postInvalidateOnAnimation();
    }

    void clearHand() {
        handVisible = false;
        pinch = false;
        openHand = false;
        handleInteraction();
        postInvalidateOnAnimation();
    }

    void setHandStatus(String status) {
        if (status != null) handStatus = status;
    }

    void setBrowserTitle(String title) {
        if (title != null && !title.isBlank()) browserTitle = title;
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int W = getWidth(), H = getHeight();
        if (W < 20 || H < 20) {
            browser.hide();
            return;
        }

        int eyeW = W / 2;
        leftHitRects.clear();
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
                Projected pr = project(w, eyeW, H, eyeSign, eye);
                if (pr == null) continue;
                if (eye == 0) leftHitRects.put(w.id, new RectF(pr.rect));
                boolean isLive = w == liveBrowser;
                RectF content = drawWindow(c, pr, ox, isLive);
                if (isLive && content != null) {
                    if (eye == 0) leftBrowserRect = content;
                    else rightBrowserRect = content;
                }
            }

            drawDock(c, ox, eyeW, H);
            if (showSettings) drawSettings(c, ox, eyeW, H);
            if (calibrationMode) drawCalibrationGuides(c, ox, eyeW, H);
            if (handVisible) drawHand(c, ox + handX * eyeW, handY * H, eyeSign);
            drawReticle(c, ox + eyeW / 2f, H / 2f);
        }

        browser.setViewport(leftBrowserRect, rightBrowserRect,
                liveBrowser != null && leftBrowserRect != null && rightBrowserRect != null);

        p.setColor(0x18ffffff);
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
                new int[]{0x30000000,0x00000000,0x00000000,0x30000000},
                new float[]{0f,.065f,.935f,1f}, Shader.TileMode.CLAMP));
        c.drawRect(ox, 0, ox + w, h, p);
        p.setShader(null);
    }

    private Projected project(SpatialWindow w, int eyeW, int h, float eyeSign, int eyeIndex) {
        String key = w.id + (eyeIndex == 0 ? ":L" : ":R");
        if (w.anchor == null || w.anchor.getTrackingState() != TrackingState.TRACKING || !tracking) {
            return heldProjection(w, key);
        }

        Pose eyePose = cameraPose.compose(Pose.makeTranslation(eyeSign * IPD_METERS * .5f, 0f, 0f));
        float[] world = w.anchor.getPose().getTranslation();
        float[] local = eyePose.inverse().transformPoint(world);
        float z = -local[2];
        if (z < .20f || z > 8f) return heldProjection(w, key);

        float fx = eyeW * .5f * projectionX;
        float fy = h * .5f * projectionY;
        float cx = eyeW * .5f + fx * (local[0] / z);
        float cy = h * .5f - fy * (local[1] / z);

        float distance = (float)Math.sqrt(local[0]*local[0] + local[1]*local[1] + local[2]*local[2]);
        float visualDepth = clamp(distance, .72f, 3.2f);
        float ww = fx * (w.widthMeters / visualDepth) * uiScale;
        float hh = fy * (w.heightMeters / visualDepth) * uiScale;

        float maxW = eyeW * (w.kind == SpatialWindow.Kind.BROWSER ? .82f : .58f);
        float maxH = h * (w.kind == SpatialWindow.Kind.BROWSER ? .66f : .48f);
        float maxFactor = Math.min(maxW / Math.max(1f, ww), maxH / Math.max(1f, hh));
        if (maxFactor < 1f) { ww *= maxFactor; hh *= maxFactor; }

        float minW = eyeW * (w.kind == SpatialWindow.Kind.BROWSER ? .50f : .22f);
        float minH = h * (w.kind == SpatialWindow.Kind.BROWSER ? .34f : .17f);
        float minFactor = Math.max(minW / Math.max(1f, ww), minH / Math.max(1f, hh));
        if (minFactor > 1f) { ww *= minFactor; hh *= minFactor; }

        RectF target = new RectF(cx-ww/2, cy-hh/2, cx+ww/2, cy+hh/2);
        if (w == dragged && pinch) {
            target.offset((handX-dragStartHandX)*eyeW, (handY-dragStartHandY)*h);
        }

        // Do not instantly discard a window near the edge. Only consider it truly out of view
        // when its whole rectangle is far beyond the lens image.
        float mx = eyeW * .32f;
        float my = h * .30f;
        if (target.right < -mx || target.left > eyeW + mx || target.bottom < -my || target.top > h + my) {
            return heldProjection(w, key);
        }

        RectF shown = smoothRect(key, target, w == dragged && pinch);
        lastValidRects.put(key, new RectF(shown));
        lastValidMs.put(key, SystemClock.elapsedRealtime());
        return new Projected(w, shown, distance);
    }

    private Projected heldProjection(SpatialWindow w, String key) {
        RectF old = lastValidRects.get(key);
        Long when = lastValidMs.get(key);
        if (old != null && when != null && SystemClock.elapsedRealtime() - when <= INVALID_HOLD_MS) {
            return new Projected(w, new RectF(old), w.depthMeters);
        }
        return null;
    }

    private RectF smoothRect(String key, RectF target, boolean immediate) {
        RectF old = smoothedRects.get(key);
        if (old == null || immediate) {
            RectF now = new RectF(target);
            smoothedRects.put(key, now);
            return new RectF(now);
        }

        if (!tracking) return new RectF(old);

        float jump = (float)Math.hypot(target.centerX()-old.centerX(), target.centerY()-old.centerY());
        float sizeJump = Math.abs(target.width()-old.width()) / Math.max(1f, old.width());

        // Strong stationary dead zone; adaptive response when the user actually moves their head.
        if (jump < 2.2f && sizeJump < .006f) return new RectF(old);

        float a;
        if (jump > 90f || sizeJump > .30f) a = .76f;
        else if (jump > 28f || sizeJump > .12f) a = .52f;
        else if (jump > 9f) a = .31f;
        else a = .16f;

        old.left += (target.left-old.left)*a;
        old.top += (target.top-old.top)*a;
        old.right += (target.right-old.right)*a;
        old.bottom += (target.bottom-old.bottom)*a;
        return new RectF(old);
    }

    private void clearSmoothing(String id) {
        smoothedRects.remove(id + ":L");
        smoothedRects.remove(id + ":R");
        lastValidRects.remove(id + ":L");
        lastValidRects.remove(id + ":R");
        lastValidMs.remove(id + ":L");
        lastValidMs.remove(id + ":R");
    }

    private RectF drawWindow(Canvas c, Projected pr, int ox, boolean liveBrowser) {
        RectF r = new RectF(pr.rect); r.offset(ox, 0);
        float radius = Math.min(27f, Math.max(12f, r.height() * .07f));

        float depthShade = clamp((1.65f - pr.depth) / 1.1f, 0f, 1f);
        int shadowAlpha = (int)(100 + depthShade * 55);
        p.setShadowLayer(30f + depthShade*10f, 0, 10f + depthShade*4f, (shadowAlpha << 24));
        p.setColor(pr.win.kind == SpatialWindow.Kind.BROWSER ? 0x240b111a : 0xdc101722);
        c.drawRoundRect(r, radius, radius, p); p.clearShadowLayer();

        stroke.setStrokeWidth(liveBrowser ? 2.3f : 1.6f);
        stroke.setColor(liveBrowser ? 0x9adcefff : 0x55cde8ff);
        c.drawRoundRect(r, radius, radius, stroke);

        // Glass-like top highlight helps the cards read as objects floating in space.
        p.setShader(new LinearGradient(r.left, r.top, r.left, r.top + r.height()*.28f,
                0x24ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, p);
        p.setShader(null);

        float headerH = clamp(r.height() * .13f, 34f, 54f);
        RectF header = new RectF(r.left, r.top, r.right, r.top + headerH);
        p.setColor(0xdd1b2532); c.drawRoundRect(header, radius, radius, p);
        c.drawRect(header.left, header.bottom-radius, header.right, header.bottom, p);
        p.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        p.setTextSize(clamp(headerH * .28f, 10f, 17f)); p.setColor(Color.WHITE);

        String title = pr.win.kind == SpatialWindow.Kind.BROWSER ? browserTitle : pr.win.title;
        if (pr.win.kind == SpatialWindow.Kind.BROWSER) {
            c.drawText("‹   ⌂   ↻   YT", header.left + 12, header.centerY() + 5, p);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(ellipsize(title, 22), header.right - 38, header.centerY() + 5, p);
            p.setTextAlign(Paint.Align.LEFT);
        } else c.drawText(ellipsize(title, 24), header.left + 14, header.centerY() + 5, p);
        p.setTypeface(android.graphics.Typeface.DEFAULT);
        p.setColor(0xffff6b7d); c.drawCircle(r.right - 18, header.centerY(), 4.8f, p);

        RectF body = new RectF(r.left + 6, header.bottom + 5, r.right - 6, r.bottom - 6);
        if (pr.win.kind == SpatialWindow.Kind.BROWSER) {
            p.setColor(0x05000000); c.drawRoundRect(body, 9, 9, p);
            if (!liveBrowser) {
                p.setColor(0xc0111722); c.drawRoundRect(body, 9, 9, p);
                p.setColor(0xccffffff); p.setTextSize(clamp(body.height()*.06f, 10f, 15f));
                c.drawText("Janela web secundária", body.left+16, body.centerY(), p);
            }
        } else if (pr.win.kind == SpatialWindow.Kind.MEDIA) drawMedia(c, body);
        else if (pr.win.kind == SpatialWindow.Kind.NOTES) drawNotes(c, body);
        else drawSystem(c, body, pr.depth);

        float handle = clamp(r.width() * .025f, 7f, 14f);
        p.setColor(0x88ffffff); c.drawCircle(r.right-handle*.8f, r.bottom-handle*.8f, handle*.22f, p);
        return pr.win.kind == SpatialWindow.Kind.BROWSER && liveBrowser ? body : null;
    }

    private void drawMedia(Canvas c, RectF b) {
        p.setColor(0x30000000); c.drawRoundRect(b, 12, 12, p);
        p.setColor(0xffff3344); RectF yt = new RectF(b.left+16,b.top+16,b.left+76,b.top+56);
        c.drawRoundRect(yt,12,12,p); p.setColor(Color.WHITE);
        p.setTextSize(clamp(b.height()*.15f,18f,32f)); c.drawText("▶",yt.left+20,yt.bottom-9,p);
        p.setTextSize(clamp(b.height()*.08f,12f,18f)); c.drawText("YouTube",b.left+90,b.top+43,p);
        p.setTextSize(clamp(b.height()*.05f,9f,13f)); p.setColor(0xc8ffffff);
        c.drawText("Pinça para abrir",b.left+18,b.centerY()+20,p);
    }

    private void drawNotes(Canvas c, RectF b) {
        p.setColor(0x25000000); c.drawRoundRect(b,12,12,p);
        p.setTextSize(clamp(b.height()*.08f,11f,16f)); p.setColor(Color.WHITE); c.drawText("NOTAS",b.left+14,b.top+27,p);
        p.setTextSize(clamp(b.height()*.055f,9f,13f)); p.setColor(0xc0ffffff);
        c.drawText("• Mão aberta = solta/trava",b.left+14,b.top+56,p);
        c.drawText("• Pinça no topo = mover",b.left+14,b.top+80,p);
        c.drawText("• RESET limpa o espaço",b.left+14,b.top+104,p);
    }

    private void drawSystem(Canvas c, RectF b, float depth) {
        p.setTextSize(clamp(b.height()*.064f,8f,13f)); p.setColor(0xeaffffff);
        float y=b.top+20; c.drawText(arStatus,b.left+9,y,p); y+=20;
        String gesture = openHand ? "ABERTA • TRAVADO" : (pinch ? "PINÇA" : "APONTE");
        c.drawText(handStatus + " • " + gesture,b.left+9,y,p); y+=20;
        c.drawText(cameraStatus,b.left+9,y,p); y+=20;
        c.drawText(String.format(Locale.US,"UI %d FPS • %.2f m • vIPD 56 mm",fps,depth),b.left+9,y,p);
        p.setColor(tracking?0xff60f2a7:0xffffc857); c.drawCircle(b.right-16,b.top+18,5,p);
    }

    private void drawDock(Canvas c, int ox, int eyeW, int H) {
        float w=Math.min(eyeW*.72f,470f), h=Math.min(58f,H*.10f);
        RectF r=new RectF(ox+eyeW/2f-w/2,H-h-12,ox+eyeW/2f+w/2,H-12);
        int base = handVisible ? 0xe0101721 : 0xa8101721;
        p.setShadowLayer(18,0,7,0x65000000); p.setColor(base); c.drawRoundRect(r,20,20,p); p.clearShadowLayer();
        stroke.setColor(handVisible ? 0x55cce9ff : 0x35cce9ff); stroke.setStrokeWidth(1.5f); c.drawRoundRect(r,20,20,stroke);
        String[] icons={"YT","WEB","+","RESET","CAL","⚙"};
        for(int i=0;i<icons.length;i++){
            float cx=r.left+(i+.5f)*r.width()/icons.length;
            p.setColor(i==0?0x4dff3344:(i==4&&calibrationMode?0x4d79f2c0:0x22ffffff));
            c.drawCircle(cx,r.centerY(),h*.29f,p); p.setColor(Color.WHITE); p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(Math.max(8f,h*.17f)); c.drawText(icons[i],cx,r.centerY()+4,p);
        }
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawSettings(Canvas c, int ox, int eyeW, int H) {
        RectF r=new RectF(ox+eyeW*.08f,H*.06f,ox+eyeW*.92f,H*.33f);
        p.setColor(0xe8111823); c.drawRoundRect(r,18,18,p); stroke.setColor(0x66d9eeff); c.drawRoundRect(r,18,18,stroke);
        p.setColor(Color.WHITE); p.setTextSize(Math.max(11f,H*.024f));
        c.drawText(calibrationMode?"CALIBRAÇÃO VR BOX":"AJUSTES VR BOX",r.left+14,r.top+26,p);
        p.setTextSize(Math.max(9f,H*.017f)); p.setColor(0xd0ffffff);
        c.drawText("Projeção ARCore real • vIPD 56 mm",r.left+14,r.top+52,p);
        c.drawText(ellipsize(opticsStatus,54),r.left+14,r.top+75,p);
        c.drawText("CAL: FUSÃO H → V → CENTRO → ZOOM",r.left+14,r.top+98,p);
        c.drawText("Volume − / + ajusta e salva",r.left+14,r.top+121,p);
    }

    private void drawCalibrationGuides(Canvas c, int ox, int eyeW, int H) {
        float cx = ox + eyeW*.5f;
        float cy = H*.5f;
        stroke.setStrokeWidth(1.6f);
        stroke.setColor(0xaa61e7ff);
        c.drawLine(cx-42,cy,cx+42,cy,stroke);
        c.drawLine(cx,cy-42,cx,cy+42,stroke);
        c.drawCircle(cx,cy,15,stroke);
        stroke.setColor(0x5561e7ff);
        c.drawLine(ox+eyeW*.25f,cy-10,ox+eyeW*.25f,cy+10,stroke);
        c.drawLine(ox+eyeW*.75f,cy-10,ox+eyeW*.75f,cy+10,stroke);
    }

    private void drawHand(Canvas c, float x, float y, float eyeSign) {
        float stereo=eyeSign*1.8f; float rad=pinch?12f:(openHand?10f:9f);
        p.setColor(pinch?0xff79f2c0:(openHand?0xffffd166:0xff81d4ff));
        p.setShadowLayer(13,0,0,pinch?0xff2bdc9a:(openHand?0xffffb000:0xff3cb7ff));
        c.drawCircle(x+stereo,y,rad,p); p.clearShadowLayer(); stroke.setColor(0xeeffffff); stroke.setStrokeWidth(1.8f);
        c.drawCircle(x+stereo,y,rad+4,stroke);
    }

    private void drawReticle(Canvas c,float x,float y){
        stroke.setColor(0x30ffffff);stroke.setStrokeWidth(1.2f);c.drawCircle(x,y,3f,stroke);
    }

    private void handleInteraction() {
        if (!handVisible) {
            finishActiveInteraction();
            prevPinch = false;
            return;
        }

        if (openHand) {
            finishActiveInteraction();
            prevPinch = false;
            return;
        }

        boolean down = pinch && !prevPinch;
        boolean up = !pinch && prevPinch;

        if (down) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (handleDockHit()) { prevPinch = pinch; return; }

            Projected hit = hitWindow(handX, handY);
            if (hit != null) {
                RectF r=hit.rect; float px=handX*(getWidth()/2f), py=handY*getHeight();
                float headerH=clamp(r.height()*.13f,34f,54f);
                if(px>r.right-r.width()*.12f && py>r.bottom-r.height()*.16f){
                    resized=hit.win; resizeStartW=hit.win.widthMeters; resizeStartH=hit.win.heightMeters;
                    resizeStartX=handX; resizeStartY=handY;
                } else if(py<r.top+headerH){
                    if(px>r.right-r.width()*.09f){hit.win.visible=false;host.onWindowVisibilityChanged();}
                    else if(hit.win.kind==SpatialWindow.Kind.BROWSER){
                        float rel=(px-r.left)/Math.max(1f,r.width());
                        if(rel<.08f)browser.back(); else if(rel<.16f)browser.home(); else if(rel<.24f)browser.reload();
                        else if(rel<.34f)browser.youtube(); else beginDrag(hit.win);
                    } else beginDrag(hit.win);
                } else if(hit.win.kind==SpatialWindow.Kind.BROWSER){
                    RectF content=browserContentRect(r);
                    if(content.contains(px,py)){
                        browserGesture=true; browserGestureRect=new RectF(content); gestureStartX=handX; gestureStartY=handY;
                        browserStartNx=clamp01((px-content.left)/Math.max(1f,content.width()));
                        browserStartNy=clamp01((py-content.top)/Math.max(1f,content.height()));
                    }
                } else if(hit.win.kind==SpatialWindow.Kind.MEDIA){browser.youtube();showBrowser();}
            }
        }

        if (pinch && dragged != null) invalidate();
        if (pinch && resized != null) {
            float dx=(handX-resizeStartX)*1.8f, dy=(handY-resizeStartY)*1.25f;
            resized.widthMeters=clamp(resizeStartW+dx,.44f,1.05f);
            resized.heightMeters=clamp(resizeStartH+dy,.28f,.66f); invalidate();
        }
        if (up) finishActiveInteraction();
        prevPinch = pinch;
    }

    private void finishActiveInteraction() {
        if (browserGesture) finishBrowserGesture();
        if (dragged != null) commitDrag(dragged);
        dragged=null; resized=null; browserGesture=false; browserGestureRect=null;
    }

    private void beginDrag(SpatialWindow w){dragged=w;dragStartHandX=handX;dragStartHandY=handY;}
    private void commitDrag(SpatialWindow w){host.moveWindowToScreen(w,handX,handY);clearSmoothing(w.id);}

    private RectF browserContentRect(RectF r){
        float hh=clamp(r.height()*.13f,34f,54f);
        return new RectF(r.left+6,r.top+hh+5,r.right-6,r.bottom-6);
    }

    private void finishBrowserGesture() {
        if(browserGestureRect==null)return;
        float px=handX*(getWidth()/2f),py=handY*getHeight();
        float endNx=clamp01((px-browserGestureRect.left)/Math.max(1f,browserGestureRect.width()));
        float endNy=clamp01((py-browserGestureRect.top)/Math.max(1f,browserGestureRect.height()));
        float moved=(float)Math.hypot(handX-gestureStartX,handY-gestureStartY);
        if(moved<.032f)browser.tap(browserStartNx,browserStartNy);
        else browser.drag(browserStartNx,browserStartNy,endNx,endNy);
    }

    private boolean handleDockHit() {
        int eyeW=getWidth()/2,H=getHeight();float w=Math.min(eyeW*.72f,470f),h=Math.min(58f,H*.10f);
        RectF r=new RectF(eyeW/2f-w/2,H-h-12,eyeW/2f+w/2,H-12);float px=handX*eyeW,py=handY*H;
        if(!r.contains(px,py))return false;
        int idx=(int)((px-r.left)/(r.width()/6f));idx=Math.max(0,Math.min(5,idx));
        if(idx==0){browser.youtube();showBrowser();}else if(idx==1)toggle(SpatialWindow.Kind.BROWSER);
        else if(idx==2)host.createExtraBrowser();else if(idx==3)host.resetLayout();
        else if(idx==4){calibrationMode=true;showSettings=true;host.cycleOptics();}
        else{showSettings=!showSettings;if(!showSettings)calibrationMode=false;}
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);return true;
    }

    private void showBrowser(){
        for(SpatialWindow w:windows)if(w.kind==SpatialWindow.Kind.BROWSER){w.visible=true;host.onWindowVisibilityChanged();return;}
    }

    private void toggle(SpatialWindow.Kind kind){
        for(SpatialWindow w:windows)if(w.kind==kind){w.visible=!w.visible;host.onWindowVisibilityChanged();return;}
    }

    private Projected hitWindow(float nx,float ny){
        int eyeW=getWidth()/2,H=getHeight();float px=nx*eyeW,py=ny*H;
        for(int i=windows.size()-1;i>=0;i--){
            SpatialWindow w=windows.get(i);if(!w.visible)continue;RectF r=leftHitRects.get(w.id);
            if(r!=null&&r.contains(px,py))return new Projected(w,new RectF(r),w.depthMeters);
        }
        return null;
    }

    float[] screenRay(float nx,float ny){
        float x=(clamp(nx,.08f,.92f)*2f-1f)/Math.max(.25f,projectionX);
        float y=-(clamp(ny,.08f,.90f)*2f-1f)/Math.max(.25f,projectionY);
        float len=(float)Math.sqrt(x*x+y*y+1f);
        return new float[]{x/len,y/len,-1f/len};
    }

    Pose cameraPose(){return cameraPose;}
    float depthFor(SpatialWindow w){
        if(w.kind==SpatialWindow.Kind.BROWSER)return 1.06f;
        if(w.kind==SpatialWindow.Kind.MEDIA)return 1.14f;
        return 1.14f;
    }

    private static String ellipsize(String s,int max){if(s==null)return"";return s.length()<=max?s:s.substring(0,Math.max(1,max-1))+"…";}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    private static float clamp01(float v){return clamp(v,0f,1f);}
}
