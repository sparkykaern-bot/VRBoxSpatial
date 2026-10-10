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

    private static final String HOME = "https://m.youtube.com/";

    private final Activity activity;
    private final Listener listener;
    private final WebView left;
    private final WebView right;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean running = true;
    private String currentUrl = HOME;
    private int leftW = 1, leftH = 1, rightW = 1, rightH = 1;
    private int lastLX = Integer.MIN_VALUE, lastLY, lastLW, lastLH;
    private int lastRX = Integer.MIN_VALUE, lastRY, lastRW, lastRH;

    BrowserEngine(Activity activity, FrameLayout root, Listener listener) {
        this.activity = activity;
        this.listener = listener;

        left = createWebView(false);
        right = createWebView(true);
        root.addView(left, new FrameLayout.LayoutParams(1, 1));
        root.addView(right, new FrameLayout.LayoutParams(1, 1));
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
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadsImagesAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(false);

        String ua = s.getUserAgentString();
        if (ua != null) s.setUserAgentString(ua.replace("; wv", ""));

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);

        w.setBackgroundColor(Color.BLACK);
        w.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setScrollbarFadingEnabled(true);
        w.setHorizontalScrollBarEnabled(false);

        w.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                if (!mirrorEye && url != null && !url.isBlank()) {
                    currentUrl = url;
                    String other = right == null ? null : right.getUrl();
                    if (other == null || !sameUrl(other, url)) {
                        handler.postDelayed(() -> {
                            if (running && right != null && !sameUrl(right.getUrl(), currentUrl)) right.loadUrl(currentUrl);
                        }, 120);
                    }
                }
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (mirrorEye) muteMirror();
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

    void setViewport(RectF leftRect, RectF rightRect, boolean visible) {
        if (!running) return;
        if (!visible || leftRect == null || rightRect == null || leftRect.width() < 24 || rightRect.width() < 24) {
            hide();
            return;
        }
        apply(left, leftRect, true);
        apply(right, rightRect, false);
    }

    private void apply(WebView view, RectF r, boolean isLeft) {
        int x = Math.round(r.left);
        int y = Math.round(r.top);
        int w = Math.max(1, Math.round(r.width()));
        int h = Math.max(1, Math.round(r.height()));

        if (isLeft) {
            leftW = w; leftH = h;
            if (x == lastLX && y == lastLY && w == lastLW && h == lastLH && view.getVisibility() == View.VISIBLE) return;
            lastLX = x; lastLY = y; lastLW = w; lastLH = h;
        } else {
            rightW = w; rightH = h;
            if (x == lastRX && y == lastRY && w == lastRW && h == lastRH && view.getVisibility() == View.VISIBLE) return;
            lastRX = x; lastRY = y; lastRW = w; lastRH = h;
        }

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)view.getLayoutParams();
        lp.width = w;
        lp.height = h;
        view.setLayoutParams(lp);
        view.setX(x);
        view.setY(y);
        view.setVisibility(View.VISIBLE);
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
            if (right.canGoBack()) right.goBack();
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

    void tap(float nx, float ny) {
        float clampedX = clamp01(nx), clampedY = clamp01(ny);
        activity.runOnUiThread(() -> {
            dispatchTap(left, clampedX * Math.max(1, leftW), clampedY * Math.max(1, leftH));
            dispatchTap(right, clampedX * Math.max(1, rightW), clampedY * Math.max(1, rightH));
            handler.postDelayed(this::muteMirror, 100);
        });
    }

    private void dispatchTap(WebView view, float x, float y) {
        long t = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 55, MotionEvent.ACTION_UP, x, y, 0);
        view.dispatchTouchEvent(down);
        view.dispatchTouchEvent(up);
        down.recycle(); up.recycle();
    }

    void drag(float fromNx, float fromNy, float toNx, float toNy) {
        float x0 = clamp01(fromNx), y0 = clamp01(fromNy), x1 = clamp01(toNx), y1 = clamp01(toNy);
        activity.runOnUiThread(() -> {
            dispatchDrag(left, x0 * leftW, y0 * leftH, x1 * leftW, y1 * leftH);
            dispatchDrag(right, x0 * rightW, y0 * rightH, x1 * rightW, y1 * rightH);
        });
    }

    private void dispatchDrag(WebView view, float x0, float y0, float x1, float y1) {
        long t = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x0, y0, 0);
        MotionEvent move1 = MotionEvent.obtain(t, t + 35, MotionEvent.ACTION_MOVE, x0 + (x1-x0)*.5f, y0 + (y1-y0)*.5f, 0);
        MotionEvent move2 = MotionEvent.obtain(t, t + 70, MotionEvent.ACTION_MOVE, x1, y1, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 105, MotionEvent.ACTION_UP, x1, y1, 0);
        view.dispatchTouchEvent(down); view.dispatchTouchEvent(move1); view.dispatchTouchEvent(move2); view.dispatchTouchEvent(up);
        down.recycle(); move1.recycle(); move2.recycle(); up.recycle();
    }

    private final Runnable mirrorMaintenance = new Runnable() {
        @Override public void run() {
            if (!running) return;
            muteMirror();
            String lu = left.getUrl();
            String ru = right.getUrl();
            if (lu != null && !lu.isBlank() && !sameUrl(lu, ru)) {
                currentUrl = lu;
                right.loadUrl(lu);
            }
            handler.postDelayed(this, 1200);
        }
    };

    private void muteMirror() {
        if (!running || right == null) return;
        try {
            right.evaluateJavascript("(function(){document.querySelectorAll('video,audio').forEach(function(v){v.muted=true;});})();", null);
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
