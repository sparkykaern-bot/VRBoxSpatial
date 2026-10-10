package com.apollo.vrboxspatial;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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

import java.util.concurrent.atomic.AtomicReference;

final class BrowserEngine {
    interface Listener { void onTitleChanged(String title); }

    private static final String HOME = "https://m.youtube.com/";
    private final Activity activity;
    private final WebView webView;
    private final AtomicReference<Bitmap> latest = new AtomicReference<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int width = 1024;
    private final int height = 640;
    private boolean running = true;

    BrowserEngine(Activity activity, FrameLayout root, Listener listener) {
        this.activity = activity;
        webView = new WebView(activity);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadsImagesAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        // Software compositing gives the off-screen capture a better chance of including
        // HTML5 video frames instead of only the page chrome on some Android devices.
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        webView.setBackgroundColor(Color.rgb(10, 12, 18));
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onReceivedTitle(WebView view, String title) {
                if (listener != null && title != null) listener.onTitleChanged(title);
            }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height);
        webView.setLayoutParams(lp);
        webView.setX(-5000f);
        webView.setY(0f);
        webView.setAlpha(0.01f);
        root.addView(webView);
        webView.loadUrl(HOME);
        handler.post(captureLoop);
    }

    private final Runnable captureLoop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (webView.getWidth() > 0 && webView.getHeight() > 0) {
                try {
                    Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(b);
                    c.drawColor(Color.rgb(10,12,18));
                    webView.draw(c);
                    Bitmap old = latest.getAndSet(b);
                    if (old != null && old != b && !old.isRecycled()) old.recycle();
                } catch (Throwable ignored) { }
            }
            // ~20 fps keeps the browser responsive without stealing too much time from ARCore.
            handler.postDelayed(this, 50);
        }
    };

    Bitmap bitmap() { return latest.get(); }
    int contentWidth() { return width; }
    int contentHeight() { return height; }

    void open(String url) {
        activity.runOnUiThread(() -> {
            String u = url == null ? "" : url.trim();
            if (u.isEmpty()) return;
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                if (u.contains(".") && !u.contains(" ")) u = "https://" + u;
                else u = "https://www.google.com/search?q=" + Uri.encode(u);
            }
            webView.loadUrl(u);
        });
    }

    void youtube() { open("https://m.youtube.com/"); }
    void back() { activity.runOnUiThread(() -> { if (webView.canGoBack()) webView.goBack(); }); }
    void reload() { activity.runOnUiThread(webView::reload); }
    void home() { open(HOME); }

    void tap(float nx, float ny) {
        final float x = clamp01(nx) * width;
        final float y = clamp01(ny) * height;
        activity.runOnUiThread(() -> {
            long t = android.os.SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(t, t + 45, MotionEvent.ACTION_UP, x, y, 0);
            webView.dispatchTouchEvent(down);
            webView.dispatchTouchEvent(up);
            down.recycle(); up.recycle();
        });
    }

    void drag(float fromNx, float fromNy, float toNx, float toNy) {
        final float x0 = clamp01(fromNx) * width, y0 = clamp01(fromNy) * height;
        final float x1 = clamp01(toNx) * width, y1 = clamp01(toNy) * height;
        activity.runOnUiThread(() -> {
            long t = android.os.SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x0, y0, 0);
            MotionEvent move = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_MOVE, x1, y1, 0);
            MotionEvent up = MotionEvent.obtain(t, t + 90, MotionEvent.ACTION_UP, x1, y1, 0);
            webView.dispatchTouchEvent(down);
            webView.dispatchTouchEvent(move);
            webView.dispatchTouchEvent(up);
            down.recycle(); move.recycle(); up.recycle();
        });
    }

    void destroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        Bitmap old = latest.getAndSet(null);
        if (old != null && !old.isRecycled()) old.recycle();
        webView.stopLoading();
        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent != null) parent.removeView(webView);
        webView.destroy();
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
}
