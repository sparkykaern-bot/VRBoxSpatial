package com.apollo.vrboxspatial;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import com.google.ar.core.Camera;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

final class ArStereoRenderer implements GLSurfaceView.Renderer {
    interface Listener {
        Session session();
        int displayRotation();
        void onArFrame(Frame frame, Camera camera, int width, int height);
        void onCameraImage(Frame frame);
        void onRendererError(String message);
    }

    private static final String TAG = "ArStereoRenderer";
    private final Listener listener;
    private int textureId = -1;
    private int program;
    private int aPos, uTex, uK1, uK2, uZoom, uEyeShift, uVerticalShift;
    private int uUv0, uUv1, uUv2, uUv3;
    private int surfaceW = 1, surfaceH = 1;
    private boolean textureBoundToSession;

    private final float[] ndc = {-1f,-1f, 1f,-1f, -1f,1f, 1f,1f};
    private final float[] uv = new float[8];
    private final FloatBuffer posBuf = direct(ndc);

    // Optics presets. Because generic VR Box shells have different lenses and phone-to-lens
    // distances, CAL cycles through practical correction strengths instead of pretending one
    // hard-coded profile is perfect for every headset.
    private volatile int opticsPreset = 2;
    private volatile float lensK1 = .18f;
    private volatile float lensK2 = .045f;
    private volatile float cameraZoom = 1.10f;
    private volatile float fusionShift = 0.000f;
    private volatile float verticalShift = 0.000f;

    ArStereoRenderer(Listener listener) { this.listener = listener; }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        textureId = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        program = link(VERT, FRAG);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        uK1 = GLES20.glGetUniformLocation(program, "uK1");
        uK2 = GLES20.glGetUniformLocation(program, "uK2");
        uZoom = GLES20.glGetUniformLocation(program, "uZoom");
        uEyeShift = GLES20.glGetUniformLocation(program, "uEyeShift");
        uVerticalShift = GLES20.glGetUniformLocation(program, "uVerticalShift");
        uUv0 = GLES20.glGetUniformLocation(program, "uUv0");
        uUv1 = GLES20.glGetUniformLocation(program, "uUv1");
        uUv2 = GLES20.glGetUniformLocation(program, "uUv2");
        uUv3 = GLES20.glGetUniformLocation(program, "uUv3");
        textureBoundToSession = false;
    }

    @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceW = Math.max(2, width);
        surfaceH = Math.max(1, height);
    }

    @Override public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        Session session = listener.session();
        if (session == null || textureId <= 0) return;
        try {
            // Critical for phone VR: each half of the screen is a complete eye image. Asking
            // ARCore to crop the camera for the full 2:1 phone screen and then squeezing that
            // into each half gives the wrong aspect ratio. Geometry is therefore calculated for
            // ONE EYE (half width), then that same correctly-cropped camera image is drawn twice.
            int eyeW = Math.max(1, surfaceW / 2);
            session.setDisplayGeometry(listener.displayRotation(), eyeW, surfaceH);

            if (!textureBoundToSession) {
                session.setCameraTextureName(textureId);
                textureBoundToSession = true;
            }

            Frame frame = session.update();
            Camera camera = frame.getCamera();
            frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                    ndc,
                    Coordinates2d.TEXTURE_NORMALIZED,
                    uv);

            drawEye(0, 0, eyeW, surfaceH, -1f);
            drawEye(eyeW, 0, surfaceW - eyeW, surfaceH, 1f);

            listener.onArFrame(frame, camera, surfaceW, surfaceH);
            if (camera.getTrackingState() == TrackingState.TRACKING) listener.onCameraImage(frame);
        } catch (CameraNotAvailableException e) {
            listener.onRendererError("Câmera indisponível para ARCore");
        } catch (Throwable t) {
            Log.w(TAG, "AR frame error", t);
        }
    }

    private void drawEye(int x, int y, int w, int h, float eyeSign) {
        GLES20.glViewport(x, y, w, h);
        GLES20.glUseProgram(program);
        posBuf.position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf);

        GLES20.glUniform1f(uK1, lensK1);
        GLES20.glUniform1f(uK2, lensK2);
        GLES20.glUniform1f(uZoom, cameraZoom);
        GLES20.glUniform1f(uEyeShift, eyeSign * fusionShift);
        GLES20.glUniform1f(uVerticalShift, verticalShift);
        GLES20.glUniform2f(uUv0, uv[0], uv[1]);
        GLES20.glUniform2f(uUv1, uv[2], uv[3]);
        GLES20.glUniform2f(uUv2, uv[4], uv[5]);
        GLES20.glUniform2f(uUv3, uv[6], uv[7]);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
    }

    void resetSessionTexture() { textureBoundToSession = false; }

    void cycleOptics() {
        opticsPreset = (opticsPreset + 1) % 5;
        switch (opticsPreset) {
            case 0:
                lensK1 = 0f; lensK2 = 0f; cameraZoom = 1.00f; fusionShift = 0f;
                break;
            case 1:
                lensK1 = .10f; lensK2 = .025f; cameraZoom = 1.06f; fusionShift = 0f;
                break;
            case 2:
                lensK1 = .18f; lensK2 = .045f; cameraZoom = 1.10f; fusionShift = 0f;
                break;
            case 3:
                lensK1 = .24f; lensK2 = .065f; cameraZoom = 1.14f; fusionShift = .010f;
                break;
            default:
                lensK1 = .30f; lensK2 = .085f; cameraZoom = 1.18f; fusionShift = .020f;
                break;
        }
    }

    String opticsStatus() {
        return String.format(Locale.US, "Lentes P%d • dist %.2f • zoom %.2f • fusão %.3f",
                opticsPreset, lensK1, cameraZoom, fusionShift);
    }

    private static FloatBuffer direct(float[] data) {
        FloatBuffer b = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(data).position(0);
        return b;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException(GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static int link(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException(GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static final String VERT =
            "attribute vec2 aPos; varying vec2 vScreen; " +
            "void main(){vScreen=aPos*0.5+0.5;gl_Position=vec4(aPos,0.0,1.0);}";

    private static final String FRAG =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float; varying vec2 vScreen; uniform samplerExternalOES uTex;" +
            "uniform float uK1; uniform float uK2; uniform float uZoom; uniform float uEyeShift; uniform float uVerticalShift;" +
            "uniform vec2 uUv0; uniform vec2 uUv1; uniform vec2 uUv2; uniform vec2 uUv3;" +
            "void main(){" +
            " vec2 p=(vScreen-vec2(0.5))*2.0;" +
            " float r2=dot(p,p);" +
            " float factor=1.0+uK1*r2+uK2*r2*r2;" +
            " vec2 q=(p*factor)/max(uZoom,0.01);" +
            " q.x+=uEyeShift; q.y+=uVerticalShift;" +
            " vec2 t=q*0.5+vec2(0.5);" +
            " if(t.x<0.0||t.x>1.0||t.y<0.0||t.y>1.0){gl_FragColor=vec4(0.0,0.0,0.0,1.0);return;}" +
            " vec2 b=mix(uUv0,uUv1,t.x); vec2 top=mix(uUv2,uUv3,t.x); vec2 uv=mix(b,top,t.y);" +
            " gl_FragColor=texture2D(uTex,uv);" +
            "}";
}
