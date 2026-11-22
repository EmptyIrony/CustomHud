package dev.customhud.adapters;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;


import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only ECARX gear subscription (sensor 0x200200), copied from CarLight's gear-sound source
 * ({@code dev.carlight.player.gear.EcarxGearSource}); only the package and the logger type changed.
 * Car.create on the main looper, connect + read baseline + subscribe on a worker, 1–60 s reconnect backoff.
 */
public final class EcarxGearSource implements AutoCloseable {
    /** Minimal logging sink (CarLight passes its own event log). */
    public interface Log {
        void info(String category, String message);

        void warn(String category, String message);
    }

    public interface Listener {
        /** Initial value after every connection; never represents a shift. Null means unavailable. */
        void onBaseline(Integer rawGear);

        /** A vendor gear event, or null when its support becomes unavailable. */
        void onGear(Integer rawGear);

        void onDisconnected();
    }

    private static final String CAR = "com.ecarx.xui.adaptapi.car.Car";
    private static final String WATCHER = "com.ecarx.xui.adaptapi.binder.IConnectable$IConnectWatcher";
    private static final String SENSOR_LISTENER =
            "com.ecarx.xui.adaptapi.car.sensor.ISensor$ISensorListener";
    private static final long CONNECT_TIMEOUT_MS = 12_000;
    private static final long MAX_RETRY_MS = 60_000;

    private final Context context;
    private final Log log;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "gear-ecarx");
        thread.setDaemon(true);
        return thread;
    });
    private final Runnable retry = this::connect;
    private boolean running;
    private boolean closed;
    private long retryMs = 1_000;
    private Session current;

    public EcarxGearSource(Context context, Log log, Listener listener) {
        this.context = context.getApplicationContext();
        this.log = log;
        this.listener = listener;
    }

    /** Main thread only. Idempotent; a closed instance cannot be restarted. */
    public void start() {
        requireMain();
        if (closed || running) {
            return;
        }
        running = true;
        connect();
    }

    private void connect() {
        if (!running || current != null) {
            return;
        }
        Session session = new Session();
        current = session;
        session.timeout = () -> failed(session, "连接或订阅超时");
        main.postDelayed(session.timeout, CONNECT_TIMEOUT_MS);
        worker.execute(() -> {
            try {
                if (Build.VERSION.SDK_INT >= 28) {
                    org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("");
                }
                ClassLoader loader = EcarxGearSource.class.getClassLoader();
                Class<?> carClass = Class.forName(CAR, false, loader);
                Class<?> watcherClass = Class.forName(WATCHER, false, loader);
                Class<?> sensorListenerClass = Class.forName(SENSOR_LISTENER, false, loader);
                Method factory = carClass.getMethod("create", Context.class);
                if (!watcherClass.isInterface() || !sensorListenerClass.isInterface()) {
                    throw new IllegalStateException("ECARX callback types are not interfaces");
                }
                main.post(() -> create(session, factory, watcherClass, sensorListenerClass));
            } catch (Exception | LinkageError e) {
                main.post(() -> failed(session, describe(e)));
            }
        });
    }

    /** The verified SDK factory creates Handlers and must run on the main looper. */
    private void create(Session session, Method factory, Class<?> watcherClass, Class<?> sensorListenerClass) {
        if (!active(session)) {
            return;
        }
        try {
            session.car = factory.invoke(null, context);
            if (session.car == null) {
                throw new IllegalStateException("Car.create returned null");
            }
            session.watcher = Proxy.newProxyInstance(watcherClass.getClassLoader(),
                    new Class<?>[]{watcherClass}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "onConnected":
                                main.post(() -> subscribe(session, sensorListenerClass));
                                break;
                            case "onDisConnected":
                                main.post(() -> failed(session, "ECARX 已断开"));
                                break;
                            default:
                                return objectMethod(proxy, method, args);
                        }
                        return null;
                    });
            worker.execute(() -> {
                if (session.cancelled) {
                    return;
                }
                try {
                    session.car.getClass().getMethod("registerConnectWatcher", watcherClass)
                            .invoke(session.car, session.watcher);
                    session.car.getClass().getMethod("connect").invoke(session.car);
                } catch (Exception | LinkageError e) {
                    main.post(() -> failed(session, describe(e)));
                }
            });
        } catch (Exception | LinkageError e) {
            failed(session, describe(e));
        }
    }

    private void subscribe(Session session, Class<?> listenerClass) {
        if (!active(session) || session.subscribing) {
            return;
        }
        session.subscribing = true;
        worker.execute(() -> {
            if (session.cancelled) {
                return;
            }
            try {
                session.sensor = session.car.getClass().getMethod("getSensorManager").invoke(session.car);
                if (session.sensor == null) {
                    throw new IllegalStateException("getSensorManager returned null");
                }
                Class<?> sensorClass = session.sensor.getClass();
                Method register = sensorClass.getMethod("registerListener", listenerClass, int.class);
                // Verify cleanup before creating a subscription.
                session.unregisterSensor = sensorClass.getMethod("unregisterListener", listenerClass);
                session.sensorListener = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                        new Class<?>[]{listenerClass}, (proxy, method, args) -> {
                            if ("onSensorEventChanged".equals(method.getName())
                                    && args != null && args.length >= 2
                                    && args[0] instanceof Integer && args[1] instanceof Integer
                                    && (Integer) args[0] == EcarxGear.SENSOR_GEAR) {
                                Integer raw = (Integer) args[1];
                                main.post(() -> event(session, raw));
                            } else if ("onSensorSupportChanged".equals(method.getName())
                                    && args != null && args.length >= 2
                                    && args[0] instanceof Integer
                                    && (Integer) args[0] == EcarxGear.SENSOR_GEAR) {
                                boolean supported = "active".equalsIgnoreCase(String.valueOf(args[1]));
                                main.post(() -> supportChanged(session, supported));
                            }
                            return objectMethod(proxy, method, args);
                        });
                Integer initial;
                try {
                    Object value = sensorClass.getMethod("getSensorEvent", int.class)
                            .invoke(session.sensor, EcarxGear.SENSOR_GEAR);
                    initial = value instanceof Integer ? (Integer) value : null;
                } catch (Exception | LinkageError e) {
                    log.warn("gear", "初始挡位读取失败，等待事件建立基准: " + describe(e));
                    initial = null;
                }
                Integer baseline = initial;
                // Enqueue the sampled value BEFORE registering: even an immediate callback from
                // registerListener is ordered after this baseline on the same main Handler. A real
                // shift after the read is therefore delivered, never discarded during setup or
                // overwritten by an older snapshot posted after its callback.
                main.post(() -> {
                    if (!active(session)) {
                        return;
                    }
                    session.ready = true;
                    listener.onBaseline(baseline);
                });
                if (session.cancelled) {
                    return;
                }
                Object accepted = register.invoke(session.sensor, session.sensorListener, EcarxGear.SENSOR_GEAR);
                if (Boolean.FALSE.equals(accepted)) {
                    throw new IllegalStateException("registerListener returned false");
                }
                main.post(() -> {
                    if (!active(session)) {
                        return;
                    }
                    main.removeCallbacks(session.timeout);
                    retryMs = 1_000;
                    log.info("gear", "ECARX 挡位已连接并订阅 0x200200; baseline=" + baseline);
                });
            } catch (Exception | LinkageError e) {
                main.post(() -> failed(session, describe(e)));
            }
        });
    }

    private void event(Session session, Integer raw) {
        if (active(session) && session.ready && !session.supportUnavailable) {
            listener.onGear(raw);
        }
    }

    private void supportChanged(Session session, boolean supported) {
        if (!active(session)) {
            return;
        }
        if (!supported) {
            session.supportUnavailable = true;
            listener.onGear(null);
        } else if (session.supportUnavailable) {
            // Reconnect through the same read-before-subscribe path to obtain a silent, fresh
            // baseline, rather than treating a value retained during unavailability as a shift.
            failed(session, "挡位支持已恢复，重新读取基准");
        }
    }

    private boolean active(Session session) {
        return running && current == session && !session.cancelled;
    }

    private void failed(Session session, String reason) {
        if (!active(session)) {
            return;
        }
        current = null;
        session.cancelled = true;
        main.removeCallbacks(session.timeout);
        listener.onDisconnected();
        log.warn("gear", reason + "; 将在 " + retryMs + "ms 后重连");
        worker.execute(() -> cleanup(session));
        main.removeCallbacks(retry);
        main.postDelayed(retry, retryMs);
        retryMs = Math.min(MAX_RETRY_MS, retryMs * 2);
    }

    /** Main thread only; listener callbacks already queued for old sessions are discarded. */
    @Override
    public void close() {
        requireMain();
        if (closed) {
            return;
        }
        closed = true;
        running = false;
        main.removeCallbacks(retry);
        Session session = current;
        current = null;
        if (session != null) {
            session.cancelled = true;
            main.removeCallbacks(session.timeout);
            worker.execute(() -> cleanup(session));
        }
        worker.shutdown();
    }

    private void cleanup(Session session) {
        if (session.sensor != null && session.sensorListener != null && session.unregisterSensor != null) {
            try {
                session.unregisterSensor.invoke(session.sensor, session.sensorListener);
            } catch (Exception | LinkageError e) {
                log.warn("gear", "注销挡位监听失败: " + describe(e));
            }
        }
        if (session.car != null) {
            // Some SDK versions expose an unregister method, others only disconnect().
            if (session.watcher != null) {
                try {
                    for (Method method : session.car.getClass().getMethods()) {
                        if ("unregisterConnectWatcher".equals(method.getName())
                                && method.getParameterTypes().length == 1
                                && method.getParameterTypes()[0].isInstance(session.watcher)) {
                            method.invoke(session.car, session.watcher);
                            break;
                        }
                    }
                } catch (Exception | LinkageError e) {
                    log.warn("gear", "注销连接监听失败: " + describe(e));
                }
            }
            try {
                session.car.getClass().getMethod("disconnect").invoke(session.car);
            } catch (Exception | LinkageError e) {
                log.warn("gear", "释放 ECARX 连接失败: " + describe(e));
            }
        }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals": return args != null && args.length == 1 && proxy == args[0];
            case "hashCode": return System.identityHashCode(proxy);
            case "toString": return "GearEcarxListener";
            default: return null;
        }
    }

    private static String describe(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) {
            error = error.getCause();
        }
        return error.toString();
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("EcarxGearSource must be used on the main thread");
        }
    }

    private static final class Session {
        volatile boolean cancelled;
        boolean subscribing;
        boolean ready;
        boolean supportUnavailable;
        Runnable timeout;
        Object car;
        Object watcher;
        Object sensor;
        Object sensorListener;
        Method unregisterSensor;
    }
}
