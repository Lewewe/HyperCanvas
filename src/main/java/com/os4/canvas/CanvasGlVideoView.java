package com.os4.canvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.function.Consumer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Canvas-only GLES renderer, adapted from puhboo's HyperCanvas commit 4c9fa0a.
 * Blur uses two small offscreen passes; the paused AOD snapshot is read only on demand.
 */
final class CanvasGlVideoView extends GLSurfaceView implements GLSurfaceView.Renderer {
    interface Callback {
        void ready(CanvasGlVideoView view);
        void presented(CanvasGlVideoView view);
        void failed(CanvasGlVideoView view, String reason);
    }
    private static final String VERTEX = "attribute vec2 a; attribute vec2 u; varying vec2 v; void main(){gl_Position=vec4(a,0.,1.);v=u;}";
    private static final String EXTERNAL = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 v; uniform samplerExternalOES t; uniform mat4 m; uniform vec2 crop; void main(){vec2 p=(v-.5)*crop+.5; gl_FragColor=vec4(texture2D(t,(m*vec4(p,0.,1.)).xy).rgb,1.);}";
    private static final String BLUR = "precision mediump float; varying vec2 v; uniform sampler2D t; uniform vec2 delta; uniform float weights[49]; void main(){vec4 c=vec4(0.); for(int i=0;i<49;i++){c+=texture2D(t,v+delta*float(i-24))*weights[i];} gl_FragColor=vec4(c.rgb,1.);}";
    private static final String COPY = "precision mediump float; varying vec2 v; uniform sampler2D t; void main(){gl_FragColor=texture2D(t,v);}";
    private final Callback callback;
    private final FloatBuffer quad = buffer(new float[]{-1,-1,1,-1,-1,1,1,1});
    private final FloatBuffer uv = buffer(new float[]{0,0,1,0,0,1,1,1});
    private final float[] transform = new float[16];
    private final AtomicBoolean frame = new AtomicBoolean();
    private volatile Surface surface;
    private SurfaceTexture texture;
    private Program external, blurProgram, copyProgram;
    private final float[] kernel = new float[49];
    private int oes, width, height, smallW, smallH;
    private final int[] targets = new int[3], buffers = new int[3];
    private volatile int videoW, videoH;
    private volatile float blurRadius;
    private volatile boolean closed, failed;
    private boolean hasFrame, notified;
    private Consumer<Bitmap> snapshot;
    private final ArrayList<Surface> retiredSurfaces = new ArrayList<>();
    private final ArrayList<SurfaceTexture> retiredTextures = new ArrayList<>();
    void releaseRetiredSurfaces() { queueEvent(this::releaseRetired); }
    private void releaseRetired() {
        for (Surface value : retiredSurfaces) value.release();
        for (SurfaceTexture value : retiredTextures) value.release();
        retiredSurfaces.clear(); retiredTextures.clear();
    }

    CanvasGlVideoView(Context context, Callback callback) {
        super(context);
        this.callback = callback;
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        setZOrderMediaOverlay(false);
    }
    Surface videoSurface() { return surface; }
    void setVideoSize(int w, int h) { videoW = w; videoH = h; requestRender(); }
    void setBlur(float radius) {
        if (blurRadius == radius) return;
        blurRadius = radius;
        requestRender();
    }
    void requestPresentation() { queueEvent(() -> notified = false); requestRender(); }
    void snapshot(Consumer<Bitmap> result) {
        if (closed || failed) { result.accept(null); return; }
        queueEvent(() -> {
            if (snapshot != null) deliver(snapshot, null);
            snapshot = result;
        });
        requestRender();
    }
    void close() {
        if (closed) return;
        closed = true;
        queueEvent(this::releaseGl);
    }
    @Override protected void onDetachedFromWindow() {
        boolean unexpected = !closed;
        close();
        super.onDetachedFromWindow();
        if (unexpected) callback.failed(this, "Video view detached");
    }
    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        if (closed) return;
        try {
            // Java decoder surfaces must also be replaced after an EGL context loss.
            if (surface != null) retiredSurfaces.add(surface);
            if (texture != null) {
                texture.setOnFrameAvailableListener(null);
                retiredTextures.add(texture);
            }
            surface = null; texture = null; hasFrame = notified = false; frame.set(false);
            smallW = smallH = 0;
            java.util.Arrays.fill(targets, 0); java.util.Arrays.fill(buffers, 0);
            external = new Program(EXTERNAL, true);
            blurProgram = new Program(BLUR, false);
            copyProgram = new Program(COPY, false);
            int[] id = new int[1]; GLES20.glGenTextures(1, id, 0); oes = id[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes);
            textureParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            texture = new SurfaceTexture(oes);
            texture.setOnFrameAvailableListener(value -> { if (!closed) { frame.set(true); requestRender(); } });
            surface = new Surface(texture);
            Surface published = surface;
            post(() -> { if (!closed && !failed && surface == published) callback.ready(this); });
        } catch (RuntimeException error) { fail(error); }
    }
    @Override public void onSurfaceChanged(GL10 gl, int w, int h) { width = w; height = h; }
    @Override public void onDrawFrame(GL10 gl) {
        if (closed || failed || width <= 0 || height <= 0) return;
        try {
            if (frame.getAndSet(false)) {
                texture.updateTexImage(); texture.getTransformMatrix(transform); hasFrame = true;
            }
            if (!hasFrame) return;
            if (snapshot != null) {
                Consumer<Bitmap> result = snapshot; snapshot = null;
                deliver(result, captureSnapshot());
            }
            float radius = blurRadius;
            if (radius >= .5f) {
                ensureTargets();
                drawExternal(buffers[0], smallW, smallH);
                // Dense Gaussian samples stay one texel apart. Large sample gaps repeat objects.
                drawBlur(targets[0], buffers[1], true, radius * smallW / width);
                drawBlur(targets[1], buffers[2], false, radius * smallH / height);
                bind(copyProgram, 0, width, height, GLES20.GL_TEXTURE_2D, targets[2]);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            } else drawExternal(0, width, height);
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) throw new IllegalStateException("GL error " + error);
            if (!notified) {
                notified = true;
                Surface published = surface;
                post(() -> { if (!closed && !failed && surface == published) callback.presented(this); });
            }
        } catch (RuntimeException error) { fail(error); }
    }
    private void drawExternal(int target, int w, int h) {
        bind(external, target, w, h, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes);
        float cropX = 1, cropY = 1;
        if (videoW > 0 && videoH > 0) {
            float videoAspect = videoW / (float) videoH, viewAspect = width / (float) height;
            if (videoAspect > viewAspect) cropX = viewAspect / videoAspect;
            else cropY = videoAspect / viewAspect;
        }
        GLES20.glUniformMatrix4fv(external.matrix, 1, false, transform, 0);
        GLES20.glUniform2f(external.crop, cropX, cropY);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }
    private void drawBlur(int input, int target, boolean horizontal, float sigma) {
        bind(blurProgram, target, smallW, smallH, GLES20.GL_TEXTURE_2D, input);
        sigma = Math.max(.2f, sigma);
        float total = 0f;
        for (int i = 0; i < kernel.length; i++) {
            float distance = i - 24;
            kernel[i] = (float) Math.exp(-distance * distance / (2f * sigma * sigma));
            total += kernel[i];
        }
        for (int i = 0; i < kernel.length; i++) kernel[i] /= total;
        GLES20.glUniform1fv(blurProgram.weights, kernel.length, kernel, 0);
        GLES20.glUniform2f(blurProgram.step, horizontal ? 1f / smallW : 0f, horizontal ? 0f : 1f / smallH);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }
    private void bind(Program p, int target, int w, int h, int kind, int input) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target);
        GLES20.glViewport(0, 0, w, h);
        GLES20.glUseProgram(p.id);
        quad.position(0); uv.position(0);
        GLES20.glEnableVertexAttribArray(p.position);
        GLES20.glVertexAttribPointer(p.position, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glEnableVertexAttribArray(p.uv);
        GLES20.glVertexAttribPointer(p.uv, 2, GLES20.GL_FLOAT, false, 0, uv);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(kind, input);
        GLES20.glUniform1i(p.sampler, 0);
    }
    private void ensureTargets() {
        int w = Math.min(128, width), h = Math.max(1, Math.round(height * (w / (float) width)));
        if (smallW == w && smallH == h) return;
        deleteTargets(); smallW = w; smallH = h;
        GLES20.glGenTextures(3, targets, 0); GLES20.glGenFramebuffers(3, buffers, 0);
        for (int i = 0; i < 3; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, targets[i]); textureParameters(GLES20.GL_TEXTURE_2D);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, buffers[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, targets[i], 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete blur target");
        }
    }
    private Bitmap captureSnapshot() {
        int w = Math.min(512, width), h = Math.max(1, Math.round(height * (w / (float) width)));
        int[] target = new int[1], framebuffer = new int[1];
        GLES20.glGenTextures(1, target, 0); GLES20.glGenFramebuffers(1, framebuffer, 0);
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, target[0]); textureParameters(GLES20.GL_TEXTURE_2D);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, target[0], 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) return null;
            drawExternal(framebuffer[0], w, h);
            return readSnapshot(w, h);
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glDeleteTextures(1, target, 0); GLES20.glDeleteFramebuffers(1, framebuffer, 0);
        }
    }
    private Bitmap readSnapshot(int w, int h) {
        // Read a small, unblurred frame off the UI thread. Android bitmaps use top-down rows.
        ByteBuffer bytes = ByteBuffer.allocateDirect(w * h * 4);
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes);
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int offset = (y * w + x) * 4;
            pixels[(h - 1 - y) * w + x] = 0xff000000
                    | (bytes.get(offset) & 255) << 16 | (bytes.get(offset + 1) & 255) << 8 | bytes.get(offset + 2) & 255;
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
    }
    private void deliver(Consumer<Bitmap> result, Bitmap bitmap) {
        post(() -> result.accept(bitmap));
    }
    private void fail(RuntimeException error) {
        if (failed || closed) return;
        failed = true;
        if (snapshot != null) { deliver(snapshot, null); snapshot = null; }
        post(() -> { if (!closed) callback.failed(this, error.toString()); });
    }
    private void releaseGl() {
        releaseRetired();
        if (snapshot != null) { deliver(snapshot, null); snapshot = null; }
        if (surface != null) { surface.release(); surface = null; }
        if (texture != null) { texture.setOnFrameAvailableListener(null); texture.release(); texture = null; }
        if (oes != 0) { GLES20.glDeleteTextures(1, new int[]{oes}, 0); oes = 0; }
        deleteTargets();
        if (external != null) { GLES20.glDeleteProgram(external.id); external = null; }
        if (blurProgram != null) { GLES20.glDeleteProgram(blurProgram.id); blurProgram = null; }
        if (copyProgram != null) { GLES20.glDeleteProgram(copyProgram.id); copyProgram = null; }
    }
    private void deleteTargets() {
        GLES20.glDeleteTextures(3, targets, 0); GLES20.glDeleteFramebuffers(3, buffers, 0);
        java.util.Arrays.fill(targets, 0); java.util.Arrays.fill(buffers, 0);
    }
    private static void textureParameters(int target) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }
    private static FloatBuffer buffer(float[] values) {
        FloatBuffer out = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        out.put(values); out.position(0); return out;
    }
    private static final class Program {
        final int id, position, uv, sampler, matrix, crop, step, weights;
        Program(String fragment, boolean external) {
            int vs = 0, fs = 0, linked = 0;
            try {
                vs = shader(GLES20.GL_VERTEX_SHADER, VERTEX); fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment);
                linked = GLES20.glCreateProgram(); GLES20.glAttachShader(linked, vs); GLES20.glAttachShader(linked, fs); GLES20.glLinkProgram(linked);
                int[] ok = new int[1]; GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, ok, 0);
                if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(linked));
                id = linked; linked = 0;
            } finally {
                if (vs != 0) GLES20.glDeleteShader(vs); if (fs != 0) GLES20.glDeleteShader(fs);
                if (linked != 0) GLES20.glDeleteProgram(linked);
            }
            position = GLES20.glGetAttribLocation(id, "a"); uv = GLES20.glGetAttribLocation(id, "u");
            sampler = GLES20.glGetUniformLocation(id, "t");
            matrix = external ? GLES20.glGetUniformLocation(id, "m") : -1;
            crop = external ? GLES20.glGetUniformLocation(id, "crop") : -1;
            step = external ? -1 : GLES20.glGetUniformLocation(id, "delta");
            weights = external ? -1 : GLES20.glGetUniformLocation(id, "weights[0]");
        }
        private static int shader(int kind, String source) {
            int shader = GLES20.glCreateShader(kind); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
            int[] ok = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) { String log = GLES20.glGetShaderInfoLog(shader); GLES20.glDeleteShader(shader); throw new IllegalStateException(log); }
            return shader;
        }
    }
}
