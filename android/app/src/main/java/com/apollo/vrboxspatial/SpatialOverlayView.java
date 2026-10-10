package com.apollo.vrboxspatial;

import android.graphics.Bitmap;
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
        void onWindowVisibilityChanged();
    }

    private static final class Projected {
        final SpatialWindow win;
        final RectF rect;
        final float depth;
        Projected(SpatialWindow win, RectF rect, float depth) { this.win = win; this.rect = rect; this.depth = depth; }
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
    private SpatialWindow dragged;
    private SpatialWindow resized;
    private boolean browserGesture;
    private float gestureStartX, gestureStartY, browserStartNx, browserStartNy;
    private float resizeStartW, resizeStartH;
    private float resizeStartX, resizeStartY;
    private float fovDeg = 84f;
    private float ipdPx = 18f;
    private float uiScale = 1f;
    private boolean showSettings;
    private long lastFrameMs;
    private int fps, frames;
    private String browserTitle = "Navegador";

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
        if (now - lastFrameMs >= 1000) { fps = frames; frames = 0; lastFrameMs = now; }
        postInvalidateOnAnimation();
    }

    void setHand(float nx, float ny, boolean isPinch, float confidence) {
        handVisible = true;
        handX = clamp01(nx); handY = clamp01(ny);
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

    void setHandStatus(String status) { handStatus = status; postInvalidateOnAnimation(); }
    void setBrowserTitle(String title) { if (title != null && !title.isBlank()) browserTitle = title; }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int W = getWidth(), H = getHeight();
        if (W < 10 || H < 10) return;
        int eyeW = W / 2;

        drawVignette(c, 0, eyeW, H);
        drawVignette(c, eyeW, W - eyeW, H);

        for (int eye = 0; eye < 2; eye++) {
            float sign = eye == 0 ? -1f : 1f;
            int ox = eye * eyeW;
            for (SpatialWindow w : windows) {
                if (!w.visible || w.anchor == null) continue;
                Projected pr = project(w, eyeW, H, sign);
                if (pr != null) drawWindow(c, pr, ox);
            }
            drawDock(c, ox, eyeW, H);
            if (showSettings) drawSettings(c, ox, eyeW, H);
            if (handVisible) drawHand(c, ox + handX * eyeW, handY * H);
            drawReticle(c, ox + eyeW / 2f, H / 2f);
        }
        p.setColor(0x33ffffff);
        c.drawRect(eyeW - 1, 0, eyeW + 1, H, p);
    }

    private void drawVignette(Canvas c, int ox, int w, int h) {
        p.setShader(new LinearGradient(ox, 0, ox + w, 0,
                new int[]{0x55000000,0x00000000,0x00000000,0x55000000},
                new float[]{0f,.12f,.88f,1f}, Shader.TileMode.CLAMP));
        c.drawRect(ox,0,ox+w,h,p); p.setShader(null);
    }

    private Projected project(SpatialWindow w, int eyeW, int h, float eyeSign) {
        if (w.anchor == null) return null;
        float[] world = w.anchor.getPose().getTranslation();
        float[] local = cameraPose.inverse().transformPoint(world);
        float depth = -local[2];
        if (depth < .16f || depth > 8f) return null;
        float f = (float)(eyeW * .5 / Math.tan(Math.toRadians(fovDeg * .5)));
        float cx = eyeW * .5f + f * (local[0] / depth) + eyeSign * (ipdPx / Math.max(.45f, depth));
        float cy = h * .5f - f * (local[1] / depth);
        float ww = f * (w.widthMeters / depth) * uiScale;
        float hh = f * (w.heightMeters / depth) * uiScale;
        if (ww < 60f || hh < 50f) return null;
        return new Projected(w, new RectF(cx-ww/2, cy-hh/2, cx+ww/2, cy+hh/2), depth);
    }

    private void drawWindow(Canvas c, Projected pr, int ox) {
        RectF r = new RectF(pr.rect); r.offset(ox,0);
        float radius = Math.min(24f, r.height()*.08f);
        p.setShadowLayer(30f,0,10f,0x88000000);
        p.setColor(0xd9151a24); c.drawRoundRect(r,radius,radius,p); p.clearShadowLayer();
        stroke.setColor(0x66d7e7ff); stroke.setStrokeWidth(1.8f); c.drawRoundRect(r,radius,radius,stroke);
        RectF header = new RectF(r.left,r.top,r.right,r.top+r.height()*.16f);
        p.setColor(0x2dffffff); c.drawRoundRect(header,radius,radius,p);
        p.setColor(0x10ffffff); c.drawRect(header.left, header.bottom-radius, header.right, header.bottom, p);

        float text = Math.max(11f, Math.min(22f,r.height()*.065f));
        p.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        p.setTextSize(text); p.setColor(Color.WHITE);
        String title = pr.win.kind == SpatialWindow.Kind.BROWSER ? browserTitle : pr.win.title;
        title = ellipsize(title, 26);
        c.drawText(title,r.left+16,r.top+header.height()*.66f,p);
        p.setTypeface(android.graphics.Typeface.DEFAULT);

        float dotY = r.top + header.height()/2f;
        p.setColor(0xff78d7ff); c.drawCircle(r.right-44,dotY,5,p);
        p.setColor(0xffff6b7d); c.drawCircle(r.right-22,dotY,5,p);

        RectF body = new RectF(r.left+8,header.bottom+7,r.right-8,r.bottom-8);
        if (pr.win.kind == SpatialWindow.Kind.BROWSER) drawBrowser(c, body);
        else if (pr.win.kind == SpatialWindow.Kind.MEDIA) drawMedia(c,body);
        else if (pr.win.kind == SpatialWindow.Kind.NOTES) drawNotes(c,body);
        else drawSystem(c,body,pr.depth);

        float handle = Math.max(10f, Math.min(24f,r.width()*.035f));
        p.setColor(0x99ffffff);
        c.drawCircle(r.right-handle*.65f,r.bottom-handle*.65f,handle*.22f,p);
    }

    private void drawBrowser(Canvas c, RectF body) {
        float barH = Math.min(44f, body.height()*.16f);
        RectF bar = new RectF(body.left,body.top,body.right,body.top+barH);
        p.setColor(0x22ffffff); c.drawRoundRect(bar,12,12,p);
        p.setTextSize(Math.max(10f,barH*.38f)); p.setColor(0xccffffff);
        c.drawText("‹   ⌂   ↻   pesquisar / abrir site",bar.left+12,bar.centerY()+5,p);
        RectF content = browserContentRect(body);
        Bitmap b = browser.bitmap();
        if (b != null && !b.isRecycled()) {
            p.setAlpha(242); c.drawBitmap(b,null,content,p); p.setAlpha(255);
        } else {
            p.setColor(0xff111621); c.drawRoundRect(content,10,10,p);
            p.setTextSize(15); p.setColor(0xaaffffff); c.drawText("Carregando navegador…",content.left+18,content.centerY(),p);
        }
    }

    private RectF browserContentRect(RectF body) {
        float barH = Math.min(44f, body.height()*.16f);
        return new RectF(body.left, body.top+barH+6, body.right, body.bottom);
    }

    private void drawMedia(Canvas c, RectF b) {
        p.setColor(0x22ffffff); c.drawRoundRect(b,14,14,p);
        p.setTextSize(Math.max(18f,b.height()*.18f)); p.setColor(Color.WHITE); c.drawText("▶",b.left+20,b.centerY()+8,p);
        p.setTextSize(Math.max(13f,b.height()*.08f)); c.drawText("Mídia espacial",b.left+70,b.centerY()-8,p);
        p.setTextSize(Math.max(10f,b.height()*.055f)); p.setColor(0xaaffffff); c.drawText("painel preparado para vídeo/áudio",b.left+70,b.centerY()+18,p);
        p.setColor(0x44ffffff); c.drawRoundRect(new RectF(b.left+20,b.bottom-30,b.right-20,b.bottom-22),4,4,p);
    }

    private void drawNotes(Canvas c, RectF b) {
        p.setColor(0x22ffffff); c.drawRoundRect(b,14,14,p);
        p.setTextSize(Math.max(11f,b.height()*.08f)); p.setColor(0xeaffffff);
        c.drawText("NOTAS",b.left+16,b.top+28,p);
        p.setTextSize(Math.max(10f,b.height()*.062f)); p.setColor(0xbaffffff);
        c.drawText("• Janela ancorada no quarto",b.left+16,b.top+58,p);
        c.drawText("• Pinça no topo para mover",b.left+16,b.top+84,p);
        c.drawText("• Pinça no canto para redimensionar",b.left+16,b.top+110,p);
    }

    private void drawSystem(Canvas c, RectF b, float depth) {
        p.setTextSize(Math.max(10f,b.height()*.09f)); p.setColor(0xeaffffff);
        c.drawText(arStatus,b.left+10,b.top+24,p);
        c.drawText(handStatus,b.left+10,b.top+50,p);
        c.drawText(String.format(Locale.US,"FPS: %d  •  %.2f m",fps,depth),b.left+10,b.top+76,p);
        p.setColor(tracking?0xff60f2a7:0xffffc857); c.drawCircle(b.right-18,b.top+20,6,p);
    }

    private void drawDock(Canvas c, int ox, int eyeW, int H) {
        float w = Math.min(eyeW*.66f,420f), h = Math.min(62f,H*.12f);
        RectF r = new RectF(ox+eyeW/2f-w/2,H-h-18,ox+eyeW/2f+w/2,H-18);
        p.setShadowLayer(22,0,8,0x77000000); p.setColor(0xcc101620); c.drawRoundRect(r,22,22,p); p.clearShadowLayer();
        stroke.setColor(0x55b9dcff); c.drawRoundRect(r,22,22,stroke);
        String[] icons={"WEB","+","NOTA","RESET","⚙"};
        for(int i=0;i<icons.length;i++){
            float cx=r.left+(i+.5f)*r.width()/icons.length;
            p.setColor(0x26ffffff); c.drawCircle(cx,r.centerY(),h*.33f,p);
            p.setColor(Color.WHITE); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(Math.max(10,h*.20f));
            c.drawText(icons[i],cx,r.centerY()+4,p);
        }
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawSettings(Canvas c,int ox,int eyeW,int H){
        RectF r=new RectF(ox+eyeW*.1f,H*.11f,ox+eyeW*.9f,H*.31f);
        p.setColor(0xdd111722);c.drawRoundRect(r,18,18,p);stroke.setColor(0x55ffffff);c.drawRoundRect(r,18,18,stroke);
        p.setColor(Color.WHITE);p.setTextSize(Math.max(12,H*.026f));c.drawText("AJUSTES VR",r.left+16,r.top+28,p);
        p.setTextSize(Math.max(10,H*.021f));p.setColor(0xbaffffff);
        c.drawText(String.format(Locale.US,"FOV %.0f°   •   Paralaxe %.0f   •   UI %.0f%%",fovDeg,ipdPx,uiScale*100),r.left+16,r.top+55,p);
        c.drawText("Toque no painel pelo celular para alternar valores.",r.left+16,r.top+80,p);
    }

    private void drawHand(Canvas c,float x,float y){
        float rad=pinch?13:10;
        p.setStyle(Paint.Style.FILL);p.setColor(pinch?0xff79f2c0:0xff81d4ff);p.setShadowLayer(16,0,0,pinch?0xff2bdc9a:0xff3cb7ff);
        c.drawCircle(x,y,rad,p);p.clearShadowLayer();
        stroke.setColor(0xeeffffff);stroke.setStrokeWidth(2);c.drawCircle(x,y,rad+5,stroke);
    }

    private void drawReticle(Canvas c,float x,float y){
        stroke.setColor(0x55ffffff);stroke.setStrokeWidth(1.5f);c.drawCircle(x,y,3.5f,stroke);
    }

    private void handleInteraction(){
        if(!handVisible){
            if(prevPinch && browserGesture) finishBrowserGesture();
            dragged=null;resized=null;browserGesture=false;prevPinch=false;return;
        }
        boolean down=pinch&&!prevPinch, up=!pinch&&prevPinch;
        if(down){
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if(handleDockHit()) { prevPinch=pinch; return; }
            Projected hit=hitWindow(handX,handY);
            if(hit!=null){
                RectF r=hit.rect;
                float px=handX*(getWidth()/2f),py=handY*getHeight();
                if(px>r.right-r.width()*.12f && py>r.bottom-r.height()*.18f){
                    resized=hit.win; resizeStartW=hit.win.widthMeters;resizeStartH=hit.win.heightMeters;resizeStartX=handX;resizeStartY=handY;
                } else if(py<r.top+r.height()*.18f){
                    if(px>r.right-r.width()*.1f){ hit.win.visible=false;host.onWindowVisibilityChanged(); }
                    else { dragged=hit.win; }
                } else if(hit.win.kind==SpatialWindow.Kind.BROWSER){
                    RectF body=new RectF(r.left+8,r.top+r.height()*.16f+7,r.right-8,r.bottom-8);
                    float barH=Math.min(44f,body.height()*.16f);
                    if(py<body.top+barH){
                        float rel=(px-body.left)/Math.max(1,body.width());
                        if(rel<.10f) browser.back();
                        else if(rel<.20f) browser.home();
                        else if(rel<.30f) browser.reload();
                        else host.requestUrlEntry();
                    }else{
                        RectF content=browserContentRect(body);
                        browserGesture=true;gestureStartX=handX;gestureStartY=handY;
                        browserStartNx=(px-content.left)/Math.max(1,content.width());
                        browserStartNy=(py-content.top)/Math.max(1,content.height());
                    }
                }
            }
        }
        if(pinch && dragged!=null) host.moveWindowToScreen(dragged,handX,handY);
        if(pinch && resized!=null){
            float dx=(handX-resizeStartX)*2.4f,dy=(handY-resizeStartY)*1.8f;
            resized.widthMeters=clamp(resizeStartW+dx,.35f,1.6f);
            resized.heightMeters=clamp(resizeStartH+dy,.22f,1.05f);
        }
        if(up){
            if(browserGesture) finishBrowserGesture();
            dragged=null;resized=null;browserGesture=false;
        }
        prevPinch=pinch;
    }

    private void finishBrowserGesture(){
        Projected hit=findBrowserProjection();
        if(hit==null)return;
        RectF r=hit.rect;
        RectF body=new RectF(r.left+8,r.top+r.height()*.16f+7,r.right-8,r.bottom-8);
        RectF content=browserContentRect(body);
        float px=handX*(getWidth()/2f),py=handY*getHeight();
        float endNx=(px-content.left)/Math.max(1,content.width());
        float endNy=(py-content.top)/Math.max(1,content.height());
        float moved=(float)Math.hypot(handX-gestureStartX,handY-gestureStartY);
        if(moved<.025f) browser.tap(browserStartNx,browserStartNy);
        else browser.drag(browserStartNx,browserStartNy,endNx,endNy);
    }

    private Projected findBrowserProjection(){
        int eyeW=getWidth()/2;
        for(SpatialWindow w:windows) if(w.visible&&w.kind==SpatialWindow.Kind.BROWSER&&w.anchor!=null) return project(w,eyeW,getHeight(),-1f);
        return null;
    }

    private boolean handleDockHit(){
        int eyeW=getWidth()/2,H=getHeight();
        float w=Math.min(eyeW*.66f,420f),h=Math.min(62f,H*.12f);
        RectF r=new RectF(eyeW/2f-w/2,H-h-18,eyeW/2f+w/2,H-18);
        float px=handX*eyeW,py=handY*H;
        if(!r.contains(px,py))return false;
        int idx=(int)((px-r.left)/(r.width()/5f));idx=Math.max(0,Math.min(4,idx));
        if(idx==0)toggle(SpatialWindow.Kind.BROWSER);
        else if(idx==1)host.createExtraBrowser();
        else if(idx==2)toggle(SpatialWindow.Kind.NOTES);
        else if(idx==3)host.resetLayout();
        else { showSettings=!showSettings; if(showSettings){fovDeg=fovDeg>=96?76:fovDeg+4;ipdPx=ipdPx>=26?12:ipdPx+2;} }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        return true;
    }

    private void toggle(SpatialWindow.Kind kind){
        for(SpatialWindow w:windows)if(w.kind==kind){w.visible=!w.visible;host.onWindowVisibilityChanged();return;}
    }

    private Projected hitWindow(float nx,float ny){
        int eyeW=getWidth()/2,H=getHeight();float px=nx*eyeW,py=ny*H;
        for(int i=windows.size()-1;i>=0;i--){
            SpatialWindow w=windows.get(i);if(!w.visible||w.anchor==null)continue;
            Projected pr=project(w,eyeW,H,-1f);if(pr!=null&&pr.rect.contains(px,py))return pr;
        }return null;
    }

    float[] screenRay(float nx,float ny){
        int eyeW=Math.max(1,getWidth()/2),H=Math.max(1,getHeight());
        float f=(float)(eyeW*.5/Math.tan(Math.toRadians(fovDeg*.5)));
        float x=(nx*eyeW-eyeW*.5f)/f;float y=-(ny*H-H*.5f)/f;
        float len=(float)Math.sqrt(x*x+y*y+1);return new float[]{x/len,y/len,-1f/len};
    }

    Pose cameraPose(){return cameraPose;}
    float depthFor(SpatialWindow w){
        if(w.anchor==null)return Math.max(.6f,w.depthMeters);
        float[] local=cameraPose.inverse().transformPoint(w.anchor.getPose().getTranslation());
        return clamp(-local[2],.4f,4f);
    }

    private static String ellipsize(String s,int max){return s.length()<=max?s:s.substring(0,max-1)+"…";}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    private static float clamp01(float v){return clamp(v,0,1);}
}
