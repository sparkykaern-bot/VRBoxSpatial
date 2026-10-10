package com.apollo.vrboxspatial;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Image;
import android.os.Bundle;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraConfig;
import com.google.ar.core.CameraConfigFilter;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableException;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements
        ArStereoRenderer.Listener,
        HandTracker.Listener,
        SpatialOverlayView.Host {

    private static final int REQ_CAMERA = 50;

    private FrameLayout root;
    private android.opengl.GLSurfaceView glView;
    private ArStereoRenderer renderer;
    private SpatialOverlayView overlay;
    private BrowserEngine browser;
    private HandTracker handTracker;
    private Session arSession;

    private boolean installRequested;
    private boolean uiBuilt;
    private boolean anchorsReady;
    private String cameraStatus = "Câmera AR: automática";
    private long lastAnchorMoveMs;

    private final Object windowLock = new Object();
    private final List<SpatialWindow> windows = new ArrayList<>();

    private volatile boolean rawHandVisible;
    private volatile float rawHandX, rawHandY, rawHandConfidence;
    private volatile int rawHandW, rawHandH;
    private volatile boolean rawHandPinch;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            buildUi();
        }
    }

    private void buildUi() {
        if (uiBuilt) return;
        uiBuilt = true;

        root = new FrameLayout(this);
        root.setBackgroundColor(android.graphics.Color.BLACK);
        setContentView(root);

        glView = new android.opengl.GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setPreserveEGLContextOnPause(true);
        renderer = new ArStereoRenderer(this);
        glView.setRenderer(renderer);
        glView.setRenderMode(android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(glView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Live browser surfaces are inserted above the camera surface and below the spatial UI.
        browser = new BrowserEngine(this, root, title -> {
            if (overlay != null) runOnUiThread(() -> overlay.setBrowserTitle(title));
        });

        overlay = new SpatialOverlayView(this, this, browser);
        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        handTracker = new HandTracker(this, this);

        createWindowModels();
        overlay.setWindows(windows);
        overlay.setCameraStatus(cameraStatus);
        overlay.setOpticsStatus(renderer.opticsStatus());

        root.setOnLongClickListener(v -> {
            requestUrlEntry();
            return true;
        });
    }

    private void createWindowModels() {
        synchronized (windowLock) {
            windows.clear();
            // Distances are deliberately around 1 meter: a large floating monitor rather than a
            // tiny panel meters away. depthMeters is also the stable drag plane for each window.
            windows.add(new SpatialWindow("browser-main", "YouTube / Navegador", SpatialWindow.Kind.BROWSER,
                    .98f, .64f, .95f));
            windows.add(new SpatialWindow("media", "YouTube", SpatialWindow.Kind.MEDIA,
                    .62f, .38f, 1.05f));
            windows.add(new SpatialWindow("notes", "Notas", SpatialWindow.Kind.NOTES,
                    .62f, .42f, 1.10f));
            windows.add(new SpatialWindow("system", "Sistema", SpatialWindow.Kind.SYSTEM,
                    .64f, .40f, 1.10f));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        if (!uiBuilt && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            buildUi();
        }
        if (!uiBuilt) return;
        if (!ensureArSession()) return;
        try {
            arSession.resume();
            renderer.resetSessionTexture();
            glView.onResume();
        } catch (CameraNotAvailableException e) {
            fallbackLegacy("A câmera não ficou disponível para o ARCore.");
        }
    }

    @Override protected void onPause() {
        if (glView != null) glView.onPause();
        if (arSession != null) arSession.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (handTracker != null) handTracker.close();
        if (browser != null) browser.destroy();
        synchronized (windowLock) {
            for (SpatialWindow w : windows) w.detach();
        }
        if (arSession != null) arSession.close();
        super.onDestroy();
    }

    private boolean ensureArSession() {
        if (arSession != null) return true;
        try {
            ArCoreApk.InstallStatus status = ArCoreApk.getInstance().requestInstall(this, !installRequested);
            if (status == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true;
                return false;
            }

            arSession = new Session(this);
            chooseBestCameraConfig(arSession);

            Config cfg = new Config(arSession);
            cfg.setFocusMode(Config.FocusMode.AUTO);
            cfg.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
            arSession.configure(cfg);

            if (overlay != null) {
                overlay.setCameraStatus(cameraStatus);
                overlay.setOpticsStatus(renderer.opticsStatus());
            }
            return true;
        } catch (UnavailableException | RuntimeException e) {
            fallbackLegacy("ARCore não está disponível neste aparelho.");
            return false;
        }
    }

    private void chooseBestCameraConfig(Session session) {
        try {
            CameraConfigFilter filter60 = new CameraConfigFilter(session);
            filter60.setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_60));
            List<CameraConfig> configs = session.getSupportedCameraConfigs(filter60);
            boolean asked60 = !configs.isEmpty();
            if (configs.isEmpty()) configs = session.getSupportedCameraConfigs(new CameraConfigFilter(session));
            if (configs.isEmpty()) {
                cameraStatus = "Câmera AR: padrão";
                return;
            }

            CameraConfig best = null;
            long bestScore = Long.MIN_VALUE;
            for (CameraConfig c : configs) {
                Size tex = c.getTextureSize();
                Size img = c.getImageSize();
                Range<Integer> fps = c.getFpsRange();
                long texArea = (long)tex.getWidth() * tex.getHeight();
                long imgArea = (long)img.getWidth() * img.getHeight();
                long score = texArea * 4L + imgArea;
                if (fps != null && fps.getUpper() >= 60) score += 10_000_000_000L;
                if (score > bestScore) {
                    best = c;
                    bestScore = score;
                }
            }

            if (best != null) {
                session.setCameraConfig(best);
                Size tex = best.getTextureSize();
                Range<Integer> fps = best.getFpsRange();
                int maxFps = fps == null ? (asked60 ? 60 : 30) : fps.getUpper();
                cameraStatus = String.format(Locale.US,
                        "Câmera AR: %dx%d até %d FPS",
                        tex.getWidth(), tex.getHeight(), maxFps);
            }
        } catch (Throwable t) {
            cameraStatus = "Câmera AR: modo compatível";
        }
    }

    private void fallbackLegacy(String reason) {
        runOnUiThread(() -> {
            Toast.makeText(this, reason + " Abrindo modo compatível.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, LegacyActivity.class));
            finish();
        });
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

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            buildUi();
        } else if (requestCode == REQ_CAMERA) {
            Toast.makeText(this, "A câmera é necessária para o passthrough.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override public Session session() { return arSession; }

    @Override public int displayRotation() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.Display d = getDisplay();
            return d == null ? Surface.ROTATION_0 : d.getRotation();
        }
        return getWindowManager().getDefaultDisplay().getRotation();
    }

    @Override public void onArFrame(Frame frame, Camera camera, int width, int height) {
        boolean tracking = camera.getTrackingState() == TrackingState.TRACKING;
        Pose displayPose = camera.getDisplayOrientedPose();
        if (tracking && !anchorsReady) createDefaultAnchors(displayPose);

        if (rawHandVisible && rawHandW > 0 && rawHandH > 0) {
            float[] in = {rawHandX * rawHandW, rawHandY * rawHandH};
            float[] out = new float[2];
            try {
                frame.transformCoordinates2d(
                        Coordinates2d.IMAGE_PIXELS,
                        in,
                        Coordinates2d.VIEW_NORMALIZED,
                        out);
                boolean hp = rawHandPinch;
                float conf = rawHandConfidence;
                runOnUiThread(() -> {
                    overlay.updateCameraPose(displayPose, tracking);
                    overlay.setCameraStatus(cameraStatus);
                    overlay.setOpticsStatus(renderer.opticsStatus());
                    overlay.setHand(out[0], out[1], hp, conf);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> overlay.updateCameraPose(displayPose, tracking));
            }
        } else {
            runOnUiThread(() -> {
                overlay.updateCameraPose(displayPose, tracking);
                overlay.setCameraStatus(cameraStatus);
                overlay.setOpticsStatus(renderer.opticsStatus());
                overlay.clearHand();
            });
        }
    }

    @Override public void onCameraImage(Frame frame) {
        if (handTracker == null) return;
        try {
            Image image = frame.acquireCameraImage();
            handTracker.submit(image, frame.getTimestamp());
        } catch (NotYetAvailableException ignored) {
        } catch (Throwable ignored) {
        }
    }

    @Override public void onRendererError(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private void createDefaultAnchors(Pose pose) {
        if (arSession == null) return;
        synchronized (windowLock) {
            if (windows.size() < 4) return;

            // Main screen centered and close. Side panels are slightly farther and offset so they
            // read as a spatial workspace instead of four flat cards on one plane.
            setAnchor(windows.get(0), pose.compose(Pose.makeTranslation(0f, -.02f, -.95f)));
            setAnchor(windows.get(1), pose.compose(Pose.makeTranslation(.67f, .02f, -1.05f)));
            setAnchor(windows.get(2), pose.compose(Pose.makeTranslation(-.72f, .28f, -1.10f)));
            setAnchor(windows.get(3), pose.compose(Pose.makeTranslation(.72f, .30f, -1.10f)));
            anchorsReady = true;
        }
        runOnUiThread(() -> overlay.setWindows(windows));
    }

    private void setAnchor(SpatialWindow w, Pose pose) {
        if (w.anchor != null) w.anchor.detach();
        w.anchor = arSession.createAnchor(Pose.makeTranslation(pose.tx(), pose.ty(), pose.tz()));
    }

    @Override public void onHand(float imageX, float imageY, int imageWidth, int imageHeight,
                                 boolean isPinch, float confidence) {
        rawHandX = imageX;
        rawHandY = imageY;
        rawHandW = imageWidth;
        rawHandH = imageHeight;
        rawHandPinch = isPinch;
        rawHandConfidence = confidence;
        rawHandVisible = true;
    }

    @Override public void onHandLost() {
        rawHandVisible = false;
        rawHandPinch = false;
    }

    @Override public void onHandStatus(String status) {
        if (overlay != null) runOnUiThread(() -> overlay.setHandStatus(status));
    }

    @Override public void moveWindowToScreen(SpatialWindow window, float nx, float ny) {
        if (glView == null || arSession == null || overlay == null) return;

        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastAnchorMoveMs < 75) return;
        lastAnchorMoveMs = now;

        final float[] ray = overlay.screenRay(nx, ny);
        final float depth = overlay.depthFor(window);
        final Pose pose = overlay.cameraPose();

        glView.queueEvent(() -> {
            // Use a fixed comfort depth. x/y follow the hand ray, z does not drift with hand noise.
            float rayZ = Math.max(.15f, -ray[2]);
            float scale = depth / rayZ;
            float[] local = {ray[0] * scale, ray[1] * scale, -depth};
            float[] world = pose.transformPoint(local);

            synchronized (windowLock) {
                if (window.anchor != null) window.anchor.detach();
                window.anchor = arSession.createAnchor(Pose.makeTranslation(world[0], world[1], world[2]));
                window.depthMeters = depth;
            }
            runOnUiThread(overlay::invalidate);
        });
    }

    @Override public void resetLayout() {
        if (glView == null || overlay == null) return;
        final Pose pose = overlay.cameraPose();
        glView.queueEvent(() -> {
            anchorsReady = false;
            synchronized (windowLock) {
                for (SpatialWindow w : windows) w.visible = true;
            }
            createDefaultAnchors(pose);
        });
    }

    @Override public void createExtraBrowser() {
        if (glView == null || arSession == null || overlay == null) return;
        final Pose pose = overlay.cameraPose();
        glView.queueEvent(() -> {
            SpatialWindow w;
            synchronized (windowLock) {
                w = new SpatialWindow(
                        "browser-" + System.currentTimeMillis(),
                        "Navegador",
                        SpatialWindow.Kind.BROWSER,
                        .82f,
                        .54f,
                        1.05f);
                Pose wp = pose.compose(Pose.makeTranslation(.35f, -.22f, -1.05f));
                w.anchor = arSession.createAnchor(Pose.makeTranslation(wp.tx(), wp.ty(), wp.tz()));
                windows.add(w);
            }
            runOnUiThread(() -> overlay.setWindows(windows));
        });
    }

    @Override public void requestUrlEntry() {
        runOnUiThread(() -> {
            EditText edit = new EditText(this);
            edit.setSingleLine(true);
            edit.setHint("Vídeo, canal, pesquisa ou site");
            edit.setTextColor(android.graphics.Color.WHITE);
            edit.setHintTextColor(0xffaab4c5);
            edit.setBackgroundColor(0xff1a2230);
            int pad = (int)(16 * getResources().getDisplayMetrics().density);
            edit.setPadding(pad, pad, pad, pad);

            new AlertDialog.Builder(this)
                    .setTitle("Abrir no navegador espacial")
                    .setView(edit)
                    .setPositiveButton("Abrir", (d,w) -> browser.open(edit.getText().toString()))
                    .setNeutralButton("YouTube", (d,w) -> browser.youtube())
                    .setNegativeButton("Cancelar", null)
                    .show();
        });
    }

    @Override public void cycleOptics() {
        if (renderer == null) return;
        renderer.cycleOptics();
        if (overlay != null) overlay.setOpticsStatus(renderer.opticsStatus());
        Toast.makeText(this, renderer.opticsStatus(), Toast.LENGTH_SHORT).show();
    }

    @Override public void onWindowVisibilityChanged() {
        if (overlay != null) overlay.invalidate();
    }
}
