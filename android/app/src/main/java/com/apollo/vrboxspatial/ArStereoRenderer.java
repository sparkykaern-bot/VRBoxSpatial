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
    private int aPos, aUv, uTex;
    private int surfaceW = 1, surfaceH = 1;
    private boolean textureBoundToSession;
    private final float[] ndc = {-1f,-1f, 1f,-1f, -1f,1f, 1f,1f};
    private final float[] uv = new float[8];
    private final FloatBuffer posBuf = direct(ndc);
    private final FloatBuffer uvBuf = direct(new float[8]);

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
        aUv = GLES20.glGetAttribLocation(program, "aUv");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        textureBoundToSession = false;
    }

    @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceW = Math.max(1, width);
        surfaceH = Math.max(1, height);
    }

    @Override public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        Session session = listener.session();
        if (session == null || textureId <= 0) return;
        try {
            session.setDisplayGeometry(listener.displayRotation(), surfaceW, surfaceH);
            if (!textureBoundToSession) {
                session.setCameraTextureName(textureId);
                textureBoundToSession = true;
            }
            Frame frame = session.update();
            Camera camera = frame.getCamera();
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndc,
                    Coordinates2d.TEXTURE_NORMALIZED, uv);
            uvBuf.position(0); uvBuf.put(uv); uvBuf.position(0);
            drawEye(0, 0, surfaceW / 2, surfaceH);
            drawEye(surfaceW / 2, 0, surfaceW - surfaceW / 2, surfaceH);
            listener.onArFrame(frame, camera, surfaceW, surfaceH);
            if (camera.getTrackingState() == TrackingState.TRACKING) listener.onCameraImage(frame);
        } catch (CameraNotAvailableException e) {
            listener.onRendererError("Câmera indisponível para ARCore");
        } catch (Throwable t) {
            Log.w(TAG, "AR frame error", t);
        }
    }

    private void drawEye(int x, int y, int w, int h) {
        GLES20.glViewport(x, y, w, h);
        GLES20.glUseProgram(program);
        posBuf.position(0); uvBuf.position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf);
        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uvBuf);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aUv);
    }

    void resetSessionTexture() { textureBoundToSession = false; }

    private static FloatBuffer direct(float[] data) {
        FloatBuffer b = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(data).position(0); return b;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1]; GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException(GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static int link(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1]; GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException(GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static final String VERT =
            "attribute vec2 aPos; attribute vec2 aUv; varying vec2 vUv; void main(){vUv=aUv;gl_Position=vec4(aPos,0.0,1.0);}";
    private static final String FRAG =
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vUv; uniform samplerExternalOES uTex; void main(){gl_FragColor=texture2D(uTex,vUv);}";
}
