package dev.customhud.camera;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives the Zeekr camera SDK ({@code com.zeekr.camera.ZeekrCameraManager}, v4.2.5 inside
 * {@code com.zeekr.stream}) from this process: loads the installed package's code, connects through
 * {@code com.zeekr.camera.service} and renders one {@code zeekr:<id>} channel into a caller Surface.
 *
 * <p>Checked against the decompiled SDK:
 * <ul>
 *   <li>The SDK keeps {@code context.getApplicationContext()}; for a {@code createPackageContext} context
 *       that is null and its work thread then crashes the process in {@code native_create(getAssets())}.
 *       It therefore gets {@link SdkContext}: our application (bindService / package name / uid) with the
 *       target package's assets, resources and class loader.</li>
 *   <li>The params field is {@code mirror}; {@code f524mirror} is only jadx's rename.</li>
 *   <li>Every SDK call just posts to its static "CamManWorkTh"; loading the package (dex + native libs) is the
 *       slow part and runs on our own control thread, never on the caller's. The SDK's service connect blocks
 *       its work thread until {@code onServiceConnected} arrives on the main thread, so nothing here ever
 *       blocks the main thread waiting for the SDK.</li>
 *   <li>{@code close()} is asynchronous; {@link #close(Runnable)} reports completion by fencing behind the
 *       SDK's work queue, so the owner can release its Surface afterwards instead of under the renderer.</li>
 *   <li>Raw frames ({@code onFrameCallback}) only exist when {@code callbackFormat != 0} (native conversion
 *       cost); off by default. {@code onDrawFrame} runs on the render thread under the SDK's render mutex, so
 *       per-frame handlers only bump counters; statistics reach the main thread at 2 Hz.</li>
 * </ul>
 * All listener methods run on the main thread except {@link Listener#onRawFrame}.
 */
public final class ZeekrStreamCamera {
    public static final String TARGET_PACKAGE = "com.zeekr.stream";
    public static final String SERVICE_PACKAGE = "com.zeekr.camera.service";

    private static final String MANAGER_CLASS = "com.zeekr.camera.ZeekrCameraManager";
    private static final String PARAMS_CLASS = "com.zeekr.camera.CameraParams";
    private static final long STATS_MS = 500;
    private static final long CLOSE_FALLBACK_MS = 400;

    /** {@code CameraDefine.IMAGE_FORMAT_*} values usable as raw-frame callback formats. */
    public static final int CALLBACK_OFF = 0;
    public static final int CALLBACK_NV21 = 3;
    public static final int CALLBACK_RGBA = 7;

    public enum State { IDLE, LOADING, OPENING, STARTED, STOPPED, DISCONNECTED, RECONNECTED, ERROR, RELEASED }

    /** One open request. Channels as configured by com.zeekr.stream: 201 front, 202 rear, 203 left, 204 right. */
    public static final class Config {
        public final String url;
        public final int width;
        public final int height;
        public final int fps;
        public final boolean mirror;
        public final boolean useGraphicBuffer;
        public final int callbackFormat;

        public Config(String url, int width, int height, int fps, boolean mirror, boolean useGraphicBuffer,
                      int callbackFormat) {
            this.url = url;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.mirror = mirror;
            this.useGraphicBuffer = useGraphicBuffer;
            this.callbackFormat = callbackFormat;
        }
    }

    public static final class Stats {
        public final long renderedFrames;
        public final float renderFps;
        public final long rawFrames;
        public final float rawFps;
        public final int rawFormat;
        public final int rawWidth;
        public final int rawHeight;
        public final int rawBytes;
        public final long firstFrameMs;

        Stats(long renderedFrames, float renderFps, long rawFrames, float rawFps, int rawFormat, int rawWidth,
              int rawHeight, int rawBytes, long firstFrameMs) {
            this.renderedFrames = renderedFrames;
            this.renderFps = renderFps;
            this.rawFrames = rawFrames;
            this.rawFps = rawFps;
            this.rawFormat = rawFormat;
            this.rawWidth = rawWidth;
            this.rawHeight = rawHeight;
            this.rawBytes = rawBytes;
            this.firstFrameMs = firstFrameMs;
        }
    }

    public interface Listener {
        default void onState(State state, String detail) {
        }

        default void onEvent(String line) {
        }

        default void onFirstFrame(int cameraIndex, long sinceOpenMs) {
        }

        default void onCameraError(int cameraIndex, int code, String name) {
        }

        default void onStats(Stats stats) {
        }

        /**
         * SDK camera thread, synchronously, every frame while a callback format is set. {@code data} is a
         * direct buffer valid only during the call; keep this cheap and never touch UI here.
         */
        default void onRawFrame(int cameraIndex, int format, int width, int height, ByteBuffer data) {
        }
    }

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService control = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zeekr-cam-ctl");
        t.setDaemon(true);
        return t;
    });

    // Control-thread state.
    private Object manager;
    private Object params;
    private Handler sdkWorkHandler;
    private Method mSetPreviewSurface;
    private Method mSetParams;
    private Method mOpen;
    private Method mClose;
    private Method mRelease;
    private Field fUrl;
    private Field fFps;
    private Field fUseGraphicBuffer;
    private Field fCallbackFormat;
    private Object sizeVec;
    private Object mirrorVec;
    private Method mVecUpdate;
    private boolean opened;
    private boolean loadFailed;

    private volatile boolean released;
    private volatile State state = State.IDLE;
    private volatile int generation;
    private volatile long openedAt;
    private volatile long firstFrameMs = -1;

    // Per-frame counters (render / camera threads); read by the main-thread ticker.
    private final AtomicLong rendered = new AtomicLong();
    private final AtomicLong raw = new AtomicLong();
    private volatile int rawFormat;
    private volatile int rawWidth;
    private volatile int rawHeight;
    private volatile int rawBytes;
    private long tickAt;
    private long tickRendered;
    private long tickRaw;
    private boolean ticking;

    public ZeekrStreamCamera(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
    }

    public State state() {
        return state;
    }

    /** Opens {@code config.url} into {@code surface}; any open channel is closed first. Returns immediately. */
    public void open(Surface surface, int surfaceWidth, int surfaceHeight, Config config) {
        if (surface == null || !surface.isValid()) {
            event("open 跳过：Surface 无效");
            return;
        }
        run(() -> {
            if (!ensureLoaded()) {
                return;
            }
            if (opened) {
                opened = false;
                try {
                    invoke(mClose);
                    event("切换前 close");
                } catch (Throwable t) {
                    fail("切换前 close", t);
                }
            }
            int gen = ++generation;
            resetCounters();
            try {
                applyConfig(config);
                invoke(mSetPreviewSurface, surface, surfaceWidth, surfaceHeight);
                invoke(mSetParams, params);
                openedAt = SystemClock.elapsedRealtime();
                invoke(mOpen);
                opened = true;
                event(String.format(java.util.Locale.ROOT,
                        "open %s %dx%d@%d 镜像=%s GraphicBuffer=%s 回调格式=%d Surface=%dx%d",
                        config.url, config.width, config.height, config.fps, config.mirror,
                        config.useGraphicBuffer, config.callbackFormat, surfaceWidth, surfaceHeight));
                setState(State.OPENING, config.url);
                main.post(this::startTicker);
                main.postDelayed(() -> {
                    if (generation == gen && firstFrameMs < 0 && state == State.OPENING) {
                        setState(State.OPENING, "8 秒仍未出图（服务未连上 / 取流卡住？）");
                    }
                }, 8_000);
            } catch (Throwable t) {
                opened = false;
                fail("open " + config.url, t);
            }
        });
    }

    /** Re-targets the render surface size (TextureView resize) without reopening. */
    public void updateSurface(Surface surface, int width, int height) {
        run(() -> {
            if (opened && surface != null && surface.isValid()) {
                try {
                    invoke(mSetPreviewSurface, surface, width, height);
                } catch (Throwable t) {
                    fail("setPreviewSurface", t);
                }
            }
        });
    }

    /** Changes the raw-frame callback format of the running channel (0 = off). */
    public void setCallbackFormat(int format) {
        run(() -> {
            if (params == null) {
                return;
            }
            try {
                fCallbackFormat.setInt(params, format);
                if (opened) {
                    invoke(mSetParams, params);
                    event("回调格式 → " + format);
                }
            } catch (Throwable t) {
                fail("callbackFormat", t);
            }
        });
    }

    /**
     * Stops the channel. {@code onClosed} runs on the main thread after the SDK's work queue has processed
     * the close (or after a short fallback delay when that queue is not reachable).
     */
    public void close(Runnable onClosed) {
        try {
            control.execute(() -> {
                boolean fenced = false;
                if (manager != null && opened) {
                    try {
                        invoke(mClose);
                        event("close");
                    } catch (Throwable t) {
                        fail("close", t);
                    }
                    opened = false;
                    generation++;
                    setState(State.STOPPED, "");
                }
                if (onClosed != null && sdkWorkHandler != null) {
                    fenced = sdkWorkHandler.post(() -> main.post(onClosed));
                }
                if (onClosed != null && !fenced) {
                    main.postDelayed(onClosed, manager != null ? CLOSE_FALLBACK_MS : 0);
                }
            });
        } catch (RejectedExecutionException e) {
            if (onClosed != null) {
                main.post(onClosed);
            }
        }
    }

    /** Final teardown: close, SDK release (native destroy, service unbind on the last instance). */
    public void release() {
        if (released) {
            return;
        }
        released = true;
        try {
            control.execute(() -> {
                if (manager != null) {
                    try {
                        if (opened) {
                            invoke(mClose);
                        }
                        invoke(mRelease);
                    } catch (Throwable t) {
                        fail("release", t);
                    }
                }
                opened = false;
                manager = null;
                params = null;
                sdkWorkHandler = null;
                state = State.RELEASED;
            });
        } catch (RejectedExecutionException ignored) {
            // already shut down
        }
        control.shutdown();
        main.removeCallbacksAndMessages(null);
        ticking = false;
    }

    // ---------------------------------------------------------------- loading

    private boolean ensureLoaded() {
        if (manager != null) {
            return true;
        }
        if (loadFailed) {
            setState(State.ERROR, "SDK 加载已失败，重新进入页面再试");
            return false;
        }
        setState(State.LOADING, TARGET_PACKAGE);
        long t0 = SystemClock.elapsedRealtime();
        try {
            Context target = app.createPackageContext(TARGET_PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader loader = target.getClassLoader();
            // Static init loads libZeekrCameraJni (+ its Nano* / ffmpeg dependencies).
            Class<?> managerClass = Class.forName(MANAGER_CLASS, true, loader);
            Class<?> paramsClass = Class.forName(PARAMS_CLASS, true, loader);
            Class<?> cameraCb = Class.forName(MANAGER_CLASS + "$CameraCallback", false, loader);
            Class<?> renderCb = Class.forName(MANAGER_CLASS + "$RenderCallback", false, loader);
            event("类与原生库已加载 " + (SystemClock.elapsedRealtime() - t0) + " ms");

            mSetPreviewSurface = managerClass.getMethod("setPreviewSurface", Surface.class, int.class, int.class);
            mSetParams = managerClass.getMethod("setParams", paramsClass);
            mOpen = managerClass.getMethod("open");
            mClose = managerClass.getMethod("close");
            mRelease = managerClass.getMethod("release");
            fUrl = paramsClass.getField("url");
            fFps = paramsClass.getField("fps");
            fUseGraphicBuffer = paramsClass.getField("useGraphicBuffer");
            fCallbackFormat = paramsClass.getField("callbackFormat");

            Object p = paramsClass.getConstructor().newInstance();
            sizeVec = paramsClass.getField("size").get(p);
            mirrorVec = paramsClass.getField("mirror").get(p);
            mVecUpdate = sizeVec.getClass().getMethod("update", float.class, float.class);

            Object m = managerClass.getConstructor(Context.class).newInstance(new SdkContext(app, target));
            managerClass.getMethod("setCameraCallback", cameraCb).invoke(m, proxy(loader, cameraCb, this::onCamera));
            managerClass.getMethod("setRenderCallback", renderCb).invoke(m, proxy(loader, renderCb, this::onRender));
            try {
                Field f = managerClass.getDeclaredField("mWorkHandler");
                f.setAccessible(true);
                Object h = f.get(m);
                sdkWorkHandler = h instanceof Handler ? (Handler) h : null;
            } catch (ReflectiveOperationException | RuntimeException e) {
                sdkWorkHandler = null;
                event("取不到 SDK 工作队列，close 完成改用 " + CLOSE_FALLBACK_MS + " ms 延时");
            }
            params = p;
            manager = m;
            event("ZeekrCameraManager 已创建，共 " + (SystemClock.elapsedRealtime() - t0) + " ms");
            return true;
        } catch (Throwable t) {
            loadFailed = true;
            fail("加载 " + TARGET_PACKAGE, t);
            return false;
        }
    }

    private void applyConfig(Config c) throws ReflectiveOperationException {
        fUrl.set(params, c.url);
        fFps.setInt(params, c.fps);
        fUseGraphicBuffer.setBoolean(params, c.useGraphicBuffer);
        fCallbackFormat.setInt(params, c.callbackFormat);
        mVecUpdate.invoke(sizeVec, (float) c.width, (float) c.height);
        mVecUpdate.invoke(mirrorVec, c.mirror ? 1f : 0f, 0f);
    }

    /**
     * What the SDK sees as its context: our application for binding / package name / uid, the target
     * package for assets (shaders), resources and classes. Its application context is itself, never null.
     */
    private static final class SdkContext extends ContextWrapper {
        private final Context target;

        SdkContext(Context app, Context target) {
            super(app);
            this.target = target;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public AssetManager getAssets() {
            return target.getAssets();
        }

        @Override
        public Resources getResources() {
            return target.getResources();
        }

        @Override
        public ClassLoader getClassLoader() {
            return target.getClassLoader();
        }
    }

    // ---------------------------------------------------------------- SDK callbacks

    private interface CallbackHandler {
        void handle(String name, Object[] args);
    }

    private static Object proxy(ClassLoader loader, Class<?> iface, CallbackHandler h) {
        return Proxy.newProxyInstance(loader, new Class<?>[]{iface}, (p, m, args) -> {
            switch (m.getName()) {
                case "equals":
                    return args != null && args.length == 1 && p == args[0];
                case "hashCode":
                    return System.identityHashCode(p);
                case "toString":
                    return "CarLight" + iface.getSimpleName();
                default:
                    h.handle(m.getName(), args);
                    return defaultValue(m.getReturnType());
            }
        });
    }

    /** Camera callbacks: SDK callback thread, except onFrameCallback (camera/JNI thread, every frame). */
    private void onCamera(String name, Object[] a) {
        if ("onFrameCallback".equals(name)) {
            raw.incrementAndGet();
            int format = intArg(a, 1);
            int w = intArg(a, 2);
            int h = intArg(a, 3);
            ByteBuffer data = a != null && a.length > 4 && a[4] instanceof ByteBuffer ? (ByteBuffer) a[4] : null;
            rawFormat = format;
            rawWidth = w;
            rawHeight = h;
            rawBytes = data != null ? data.capacity() : -1;
            if (data != null) {
                try {
                    listener.onRawFrame(intArg(a, 0), format, w, h, data);
                } catch (RuntimeException ignored) {
                    // never throw into the SDK's camera thread
                }
            }
            return;
        }
        int index = intArg(a, 0);
        switch (name) {
            case "onCameraStarted":
                event("onCameraStarted index=" + index);
                setState(State.STARTED, "index " + index);
                break;
            case "onCameraStopped":
                event("onCameraStopped index=" + index);
                setState(State.STOPPED, "index " + index);
                break;
            case "onDisconnected":
                event("onDisconnected index=" + index);
                setState(State.DISCONNECTED, "index " + index);
                break;
            case "onReconnected":
                event("onReconnected index=" + index);
                setState(State.RECONNECTED, "index " + index);
                break;
            case "onError": {
                int code = intArg(a, 1);
                String label = errorName(code);
                event("onError index=" + index + " code=" + code + " " + label);
                setState(State.ERROR, "错误 " + code + " " + label);
                main.post(() -> listener.onCameraError(index, code, label));
                break;
            }
            default:
                event("相机回调 " + name);
        }
    }

    /** Render callbacks: onDrawFrame runs on the render thread under the SDK's render mutex, every frame. */
    private void onRender(String name, Object[] a) {
        if ("onDrawFrame".equals(name)) {
            rendered.incrementAndGet();
            return;
        }
        if ("onFirstFrameRendered".equals(name)) {
            int index = intArg(a, 0);
            long ms = SystemClock.elapsedRealtime() - openedAt;
            firstFrameMs = ms;
            event("onFirstFrameRendered index=" + index + "，open 后 " + ms + " ms");
            main.post(() -> listener.onFirstFrame(index, ms));
            return;
        }
        event("渲染回调 " + name);
    }

    static String errorName(int code) {
        switch (code) {
            case 1: return "CAMERA_IN_USE";
            case 2: return "MAX_CAMERAS_IN_USE";
            case 3: return "CAMERA_DISABLED";
            case 4: return "CAMERA_DEVICE";
            case 5: return "CAMERA_SERVICE";
            case 6: return "CAMERA_STREAM（5 秒无纹理，取流卡住）";
            case 1000: return "CODEC_CREATE_ENCODER_VIDEO";
            case 1001: return "CODEC_CREATE_ENCODER_AUDIO";
            case 1010: return "CODEC_CREATE_DECODER_VIDEO";
            case 1011: return "CODEC_CREATE_DECODER_AUDIO";
            default: return "";
        }
    }

    // ---------------------------------------------------------------- statistics (main thread)

    private void resetCounters() {
        rendered.set(0);
        raw.set(0);
        rawFormat = 0;
        rawWidth = 0;
        rawHeight = 0;
        rawBytes = 0;
        firstFrameMs = -1;
        main.post(() -> {
            tickAt = SystemClock.elapsedRealtime();
            tickRendered = 0;
            tickRaw = 0;
        });
    }

    private void startTicker() {
        if (ticking || released) {
            return;
        }
        ticking = true;
        tickAt = SystemClock.elapsedRealtime();
        main.postDelayed(this::tick, STATS_MS);
    }

    private void tick() {
        if (released) {
            ticking = false;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long r = rendered.get();
        long f = raw.get();
        float dt = Math.max(1, now - tickAt) / 1000f;
        Stats s = new Stats(r, (r - tickRendered) / dt, f, (f - tickRaw) / dt, rawFormat, rawWidth, rawHeight,
                rawBytes, firstFrameMs);
        tickAt = now;
        tickRendered = r;
        tickRaw = f;
        listener.onStats(s);
        main.postDelayed(this::tick, STATS_MS);
    }

    // ---------------------------------------------------------------- helpers

    private void invoke(Method m, Object... args) throws Throwable {
        try {
            m.invoke(manager, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    private void run(Runnable r) {
        if (released) {
            return;
        }
        try {
            control.execute(r);
        } catch (RejectedExecutionException ignored) {
            // released
        }
    }

    private void setState(State s, String detail) {
        if (released && s != State.RELEASED) {
            return;
        }
        state = s;
        main.post(() -> listener.onState(s, detail));
    }

    private void event(String line) {
        String stamped = String.format(java.util.Locale.ROOT, "%tT  %s", System.currentTimeMillis(), line);
        main.post(() -> listener.onEvent(stamped));
    }

    private void fail(String action, Throwable t) {
        String detail = action + "：" + describe(t);
        event(detail);
        setState(State.ERROR, detail);
    }

    private static int intArg(Object[] a, int i) {
        return a != null && a.length > i && a[i] instanceof Integer ? (Integer) a[i] : -1;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return 0;
    }

    static String describe(Throwable t) {
        while (t instanceof InvocationTargetException && t.getCause() != null) {
            t = t.getCause();
        }
        StringBuilder b = new StringBuilder(t.getClass().getName());
        if (t.getMessage() != null) {
            b.append(": ").append(t.getMessage());
        }
        Throwable c = t.getCause();
        for (int i = 0; c != null && i < 3; i++, c = c.getCause()) {
            b.append(" ← ").append(c.getClass().getSimpleName());
            if (c.getMessage() != null) {
                b.append(": ").append(c.getMessage());
            }
        }
        return b.toString();
    }
}
