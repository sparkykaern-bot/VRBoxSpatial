package com.apollo.vrboxspatial;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
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

    private static final String HOME = "https://m.youtube.com/";
    private static final int LOGICAL_W = 960;
    private static final int LOGICAL_H = 540;

    private final Activity activity;
    private final Listener listener;
    private final WebView left;
    private final WebView right;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean running = true;
    private String currentUrl = HOME;
    private final RectF lastLeft = new RectF(Float.NaN, Float.NaN, Float.NaN, Float.NaN);
    private final RectF lastRight = new RectF(Float.NaN, Float.NaN, Float.NaN, Float.NaN);

    BrowserEngine(Activity activity, FrameLayout root, Listener listener) {
        this.activity = activity;
        this.listener = listener;

        left = createWebView(false);
        right = createWebView(true);
        root.addView(left, new FrameLayout.LayoutParams(LOGICAL_W, LOGICAL_H));
        root.addView(right, new FrameLayout.LayoutParams(LOGICAL_W, LOGICAL_H));
        left.setVisibility(View.GONE);
        right.setVisibility(View.GONE);

        left.loadUrl(HOME);
        handler.postDelayed(() -> right.loadUrl(HOME), 180);
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
        s.setTextZoom(95);
        s.setDefaultFontSize(15);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadsImagesAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(false);

        String ua = WebSettings.getDefaultUserAgent(activity);
        if (ua != null) s.setUserAgentString(ua.replace("; wv", ""));

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);

        w.setInitialScale(100);
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
                    handler.postDelayed(BrowserEngine.this::syncMirrorUrl, 220);
                }
            }

            @Override public void onPageFinished(WebView view, String url) {
                tunePage(view);
                if (mirrorEye) muteMirror();
                else handler.postDelayed(BrowserEngine.this::syncMirrorState, 420);
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
                    "m.setAttribute('content','width=960,initial-scale=1,maximum-scale=1,user-scalable=no');" +
                    "document.documentElement.style.background='#000';" +
                    "if(document.body)document.body.style.background='#000';" +
                    "document.querySelectorAll('video').forEach(function(v){" +
                    "v.setAttribute('playsinline','true');v.setAttribute('webkit-playsinline','true');" +
                    "v.style.objectFit='contain';});" +
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
        if (!nearlySame(lastLeft, leftRect)) {
            lastLeft.set(leftRect);
            applyTransform(left, leftRect);
        } else if (left.getVisibility() != View.VISIBLE) {
            left.setVisibility(View.VISIBLE);
        }

        if (!nearlySame(lastRight, rightRect)) {
            lastRight.set(rightRect);
            applyTransform(right, rightRect);
        } else if (right.getVisibility() != View.VISIBLE) {
            right.setVisibility(View.VISIBLE);
        }
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
                else u = "https://m.youtube.com/results?search_query=" + Uri.encode(u);
            }
            loadBoth(u);
        });
    }

    void youtube() { activity.runOnUiThread(() -> loadBoth(HOME)); }

    void back() {
        activity.runOnUiThread(() -> {
            if (left.canGoBack()) left.goBack();
            handler.postDelayed(this::syncMirrorUrl, 260);
        });
    }

    void reload() {
        activity.runOnUiThread(() -> {
            left.reload();
            handler.postDelayed(right::reload, 140);
        });
    }

    void home() { youtube(); }

    private void loadBoth(String url) {
        currentUrl = url;
        left.loadUrl(url);
        handler.postDelayed(() -> {
            if (running) right.loadUrl(url);
        }, 140);
    }

    private void syncMirrorUrl() {
        if (!running) return;
        String lu = left.getUrl();
        if (lu != null && !lu.isBlank()) currentUrl = lu;
        String ru = right.getUrl();
        if (currentUrl != null && !sameUrl(currentUrl, ru)) right.loadUrl(currentUrl);
    }

    // DOM hit testing is much more reliable than dispatching Android MotionEvents into a WebView
    // that is currently scaled and translated inside a spatial window.
    void tap(float nx, float ny) {
        final float x = clamp01(nx);
        final float y = clamp01(ny);
        activity.runOnUiThread(() -> {
            String js = "(function(){" +
                    "var x=window.innerWidth*" + x + ",y=window.innerHeight*" + y + ";" +
                    "var e=document.elementFromPoint(x,y);if(!e)return 'none';" +
                    "var t=e.closest?e.closest('a,button,[role=button],input,video,[tabindex]'):e;" +
                    "if(!t)t=e;" +
                    "try{t.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,clientX:x,clientY:y}));" +
                    "t.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,clientX:x,clientY:y}));t.click();}catch(_){try{t.click();}catch(__){}}" +
                    "return (t.tagName||'ok');})();";
            left.evaluateJavascript(js, value -> {
                handler.postDelayed(this::syncMirrorUrl, 280);
                handler.postDelayed(this::syncMirrorState, 520);
            });
        });
    }

    // Spatial drag is used mainly for scrolling. Doing the scroll in page coordinates avoids the
    // mismatch between transformed view pixels and CSS pixels that caused missed/erratic swipes.
    void drag(float fromNx, float fromNy, float toNx, float toNy) {
        final float dy = clamp(fromNy - toNy, -1f, 1f);
        activity.runOnUiThread(() -> {
            String js = "(function(){window.scrollBy(0,window.innerHeight*" + (dy * 1.12f) + ");return window.scrollY;})();";
            left.evaluateJavascript(js, value -> {
                handler.postDelayed(this::syncScroll, 100);
                handler.postDelayed(this::syncMirrorState, 360);
            });
        });
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
                                    "if(Math.abs((v.currentTime||0)-" + t + ")>.85)v.currentTime=" + t + ";" +
                                    "v.playbackRate=" + rate + ";v.muted=true;" +
                                    (paused ? "if(!v.paused)v.pause();" : "if(v.paused)v.play().catch(function(){});") +
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
            handler.postDelayed(this, 900);
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

    private static boolean nearlySame(RectF a, RectF b) {
        return !Float.isNaN(a.left) &&
                Math.abs(a.left - b.left) < .8f &&
                Math.abs(a.top - b.top) < .8f &&
                Math.abs(a.right - b.right) < .8f &&
                Math.abs(a.bottom - b.bottom) < .8f;
    }

    private static boolean sameUrl(String a, String b) {
        if (a == null || b == null) return false;
        int ha = a.indexOf('#'); if (ha >= 0) a = a.substring(0, ha);
        int hb = b.indexOf('#'); if (hb >= 0) b = b.substring(0, hb);
        return a.equals(b);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clamp01(float v) { return clamp(v, 0f, 1f); }
}
