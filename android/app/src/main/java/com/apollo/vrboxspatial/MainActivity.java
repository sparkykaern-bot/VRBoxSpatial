package com.apollo.vrboxspatial;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.InputStream;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {
    private static final int REQ_CAMERA = 10;
    private WebView webView;
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private long lastPoseMs = 0L;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        webView = new WebView(this);
        setContentView(webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setLoadsImagesAutomatically(true);
        s.setUserAgentString(s.getUserAgentString() + " VRBoxSpatial/0.1");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.getResources());
                    } else {
                        request.deny();
                    }
                });
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!url.startsWith("https://app.local/")) return null;
                String path = url.substring("https://app.local/".length());
                if (path.isEmpty()) path = "index.html";
                try {
                    InputStream in = getAssets().open(path);
                    String mime = path.endsWith(".html") ? "text/html" : path.endsWith(".js") ? "application/javascript" : "text/plain";
                    return new WebResourceResponse(mime, "UTF-8", in);
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, null);
                }
            }
        });

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }

        sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotationSensor == null) rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        webView.loadUrl("https://app.local/index.html");
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override protected void onResume() {
        super.onResume(); immersive();
        if (rotationSensor != null) sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        if (webView != null) webView.onResume();
    }

    @Override protected void onPause() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastPoseMs < 15) return;
        lastPoseMs = now;

        float[] rot = new float[9];
        float[] remap = new float[9];
        float[] ori = new float[3];
        SensorManager.getRotationMatrixFromVector(rot, event.values);
        SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remap);
        SensorManager.getOrientation(remap, ori);
        final float yaw = (float)Math.toDegrees(ori[0]);
        final float pitch = (float)Math.toDegrees(ori[1]);
        final float roll = (float)Math.toDegrees(ori[2]);
        final String js = String.format(Locale.US, "window.__nativePose&&window.__nativePose(%.4f,%.4f,%.4f)", yaw, pitch, roll);
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
