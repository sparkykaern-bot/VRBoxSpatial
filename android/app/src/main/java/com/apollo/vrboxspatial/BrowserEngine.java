package com.apollo.vrboxspatial;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

final class BrowserEngine {
    interface Listener { void onTitleChanged(String title); }

    private static final String HOME = "https://www.youtube.com/";
    private static final int LOGICAL_W = 1280;
    private static final int LOGICAL_H = 768;
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    private final Activity activity;
    private final Listener listener;
    private final WebView left;
    private final WebView right;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean running = true;
    private String currentUrl = HOME;

    BrowserEngine(Activity activity, FrameLayout root, Listener listener) {
        this.activity = activity;
        this.listener = listener;

        left = createWebView(false);
        right = createWebView(true);
        root.addView(left, new FrameLayout.LayoutParams(LOGICAL_W, LOGICAL_H));
        root.addView(right, new FrameLayout.LayoutParams(LOGICAL_W, LOGICAL_H));
        left.setVisibility(View.GONE);
        right.setVisibility(View.GONE);

        loadBoth(HOME);
        handler.post(mirrorMaintenance);
    }

    private WebView createWebView(final boolean mirrorEye) {
        WebView w = new WebView(activity);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        s.setTextZoom(90);
        s.setDefaultFontSize(14);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadsImagesAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(false);
        s.setUserAgentString(DESKTOP_UA);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);

        w.setInitialScale(0);
        w.setBackgroundColor(Color.BLACK);
        w.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setHorizontalScrollBarEnabled(false);
        w.setVerticalScrollBarEnabled(false);
        w.setPivotX(0f);
        w.setPivotY(0f);
        if (mirrorEye) {
            w.setClickable(false);
            w.setFocusable(false);
        }

        w.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                if (!mirrorEye && url != null && !url.isBlank()) {
                    currentUrl = url;
                    handler.postDelayed(BrowserEngine.this::syncMirrorUrl, 140);
                }
            }

            @Override public void onPageFinished(WebView view, String url) {
                tunePage(view);
                if (mirrorEye) muteMirror();
                else handler.postDelayed(BrowserEngine.this::syncMirrorState, 220);
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override public void onReceivedTitle(WebView view, String title) {
                if (!mirrorEye && listener != null && title != null && !title.isBlank()) {
                    listener.onTitleChanged(title);
                }
            }
        });
        return w;
    }

    private void tunePage(WebView view) {
        try {
            String js = "(function(){" +
                    "var m=document.querySelector('meta[name=viewport]');" +
                    "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
                    "m.setAttribute('content','width=1280,initial-scale=1,maximum-scale=1,user-scalable=no');" +
                    "document.documentElement.style.background='#000';" +
                    "if(document.body)document.body.style.background='#000';" +
                    "})();";
            view.evaluateJavascript(js, null);
        } catch (Throwable ignored) { }
    }

    void setViewport(RectF leftRect, RectF rightRect, boolean visible) {
        if (!running) return;
        if (!visible || leftRect == null || rightRect == null || leftRect.width() < 40 || rightRect.width() < 40) {
            hide();
            return;
        }
        applyTransform(left, leftRect);
        applyTransform(right, rightRect);
    }

    private void applyTransform(WebView view, RectF r) {
        float sx = Math.max(.05f, r.width() / LOGICAL_W);
        float sy = Math.max(.05f, r.height() / LOGICAL_H);
        view.setScaleX(sx);
        view.setScaleY(sy);
        view.setX(r.left);
        view.setY(r.top);
        if (view.getVisibility() != View.VISIBLE) view.setVisibility(View.VISIBLE);
    }

    void hide() {
        if (left.getVisibility() != View.GONE) left.setVisibility(View.GONE);
        if (right.getVisibility() != View.GONE) right.setVisibility(View.GONE);
    }

    void open(String input) {
        activity.runOnUiThread(() -> {
            String u = input == null ? "" : input.trim();
            if (u.isEmpty()) return;
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                if (u.contains(".") && !u.contains(" ")) u = "https://" + u;
                else u = "https://www.youtube.com/results?search_query=" + Uri.encode(u);
            }
            loadBoth(u);
        });
    }

    void youtube() { activity.runOnUiThread(() -> loadBoth(HOME)); }

    void back() {
        activity.runOnUiThread(() -> {
            if (left.canGoBack()) left.goBack();
            handler.postDelayed(this::syncMirrorUrl, 180);
        });
    }

    void reload() {
        activity.runOnUiThread(() -> {
            left.reload();
            right.reload();
        });
    }

    void home() { youtube(); }

    private void loadBoth(String url) {
        currentUrl = url;
        left.loadUrl(url);
        right.loadUrl(url);
    }

    private void syncMirrorUrl() {
        if (!running) return;
        String lu = left.getUrl();
        if (lu != null && !lu.isBlank()) currentUrl = lu;
        String ru = right.getUrl();
        if (currentUrl != null && !sameUrl(currentUrl, ru)) right.loadUrl(currentUrl);
    }

    void tap(float nx, float ny) {
        final float x = clamp01(nx) * LOGICAL_W;
        final float y = clamp01(ny) * LOGICAL_H;
        activity.runOnUiThread(() -> {
            dispatchTap(left, x, y);
            handler.postDelayed(this::syncMirrorUrl, 220);
            handler.postDelayed(this::syncMirrorState, 330);
        });
    }

    private void dispatchTap(WebView view, float x, float y) {
        long t = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 95, MotionEvent.ACTION_UP, x, y, 0);
        view.dispatchTouchEvent(down);
        view.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    void drag(float fromNx, float fromNy, float toNx, float toNy) {
        final float x0 = clamp01(fromNx) * LOGICAL_W;
        final float y0 = clamp01(fromNy) * LOGICAL_H;
        final float x1 = clamp01(toNx) * LOGICAL_W;
        final float y1 = clamp01(toNy) * LOGICAL_H;
        activity.runOnUiThread(() -> {
            dispatchDrag(left, x0, y0, x1, y1);
            handler.postDelayed(this::syncScroll, 100);
            handler.postDelayed(this::syncMirrorState, 180);
        });
    }

    private void dispatchDrag(WebView view, float x0, float y0, float x1, float y1) {
        long t = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x0, y0, 0);
        MotionEvent move1 = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_MOVE,
                x0 + (x1-x0)*.30f, y0 + (y1-y0)*.30f, 0);
        MotionEvent move2 = MotionEvent.obtain(t, t + 95, MotionEvent.ACTION_MOVE,
                x0 + (x1-x0)*.68f, y0 + (y1-y0)*.68f, 0);
        MotionEvent move3 = MotionEvent.obtain(t, t + 140, MotionEvent.ACTION_MOVE, x1, y1, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 180, MotionEvent.ACTION_UP, x1, y1, 0);
        view.dispatchTouchEvent(down);
        view.dispatchTouchEvent(move1);
        view.dispatchTouchEvent(move2);
        view.dispatchTouchEvent(move3);
        view.dispatchTouchEvent(up);
        down.recycle(); move1.recycle(); move2.recycle(); move3.recycle(); up.recycle();
    }

    private void syncScroll() {
        if (!running) return;
        right.scrollTo(left.getScrollX(), left.getScrollY());
    }

    private void syncMirrorState() {
        if (!running) return;
        syncMirrorUrl();
        syncScroll();
        muteMirror();
        try {
            left.evaluateJavascript(
                    "(function(){var v=document.querySelector('video');" +
                    "if(!v)return '';return [v.currentTime||0,v.paused?1:0,v.playbackRate||1].join('|');})()",
                    value -> {
                        if (!running || value == null) return;
                        String s = value.replace("\\\"", "").replace("\"", "").trim();
                        String[] parts = s.split("\\|");
                        if (parts.length < 3) return;
                        try {
                            double t = Double.parseDouble(parts[0]);
                            boolean paused = "1".equals(parts[1]);
                            double rate = Double.parseDouble(parts[2]);
                            String js = "(function(){var v=document.querySelector('video');if(!v)return;" +
                                    "if(Math.abs((v.currentTime||0)-" + t + ")>.25)v.currentTime=" + t + ";" +
                                    "v.playbackRate=" + rate + ";v.muted=true;" +
                                    (paused ? "v.pause();" : "v.play().catch(function(){});") +
                                    "})()";
                            right.evaluateJavascript(js, null);
                        } catch (Throwable ignored) { }
                    });
        } catch (Throwable ignored) { }
    }

    private final Runnable mirrorMaintenance = new Runnable() {
        @Override public void run() {
            if (!running) return;
            syncMirrorState();
            handler.postDelayed(this, 400);
        }
    };

    private void muteMirror() {
        if (!running) return;
        try {
            right.evaluateJavascript(
                    "(function(){document.querySelectorAll('video,audio').forEach(function(v){v.muted=true;});})();",
                    null);
        } catch (Throwable ignored) { }
    }

    void destroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        destroyOne(left);
        destroyOne(right);
    }

    private void destroyOne(WebView webView) {
        try {
            webView.stopLoading();
            if (webView.getParent() instanceof ViewGroup) ((ViewGroup)webView.getParent()).removeView(webView);
            webView.destroy();
        } catch (Throwable ignored) { }
    }

    private static boolean sameUrl(String a, String b) {
        if (a == null || b == null) return false;
        int ha = a.indexOf('#'); if (ha >= 0) a = a.substring(0, ha);
        int hb = b.indexOf('#'); if (hb >= 0) b = b.substring(0, hb);
        return a.equals(b);
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
}
