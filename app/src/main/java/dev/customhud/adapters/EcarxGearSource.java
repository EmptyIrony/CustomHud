package dev.customhud.adapters;

import android.os.DeadObjectException;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only ECARX gear subscription (sensor 0x200200), from CarLight's gear-sound source
 * ({@code dev.carlight.player.gear.EcarxGearSource}). The subscription steps are unchanged (read the
 * baseline, post it, then {@code registerListener}); the {@code Car} connection itself now comes from
 * the process-wide {@link EcarxCarHub}, shared with the vehicle data poller. A failed subscription is
 * retried on the same car with its own 1–60 s backoff, so a gear-only problem does not reconnect the
 * shared car under the vehicle poller; only a dead binder asks the hub to reconnect.
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

    private static final String SENSOR_LISTENER =
            "com.ecarx.xui.adaptapi.car.sensor.ISensor$ISensorListener";
    private static final long SUBSCRIBE_TIMEOUT_MS = 12_000;
    private static final long RESUBSCRIBE_DELAY_MS = 1_000;
    private static final long MAX_RETRY_MS = 60_000;

    private final EcarxCarHub hub;
    private final Log log;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "gear-ecarx");
        thread.setDaemon(true);
        return thread;
    });
    private boolean running;
    private boolean closed;
    private EcarxCarHub.Handle handle;
    private Object car;
    private Session current;
    private long retryMs = 1_000;
    private Runnable pendingRetry;

    public EcarxGearSource(EcarxCarHub hub, Log log, Listener listener) {
        this.hub = hub;
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
        handle = hub.acquire(new EcarxCarHub.Client() {
            @Override
            public void onConnected(Object connected) {
                if (!running) {
                    return;
                }
                car = connected;
                cancelRetry();
                retryMs = 1_000;
                subscribe(connected);
            }

            @Override
            public void onDisconnected(String reason) {
                car = null;
                cancelRetry();
                Session s = current;
                if (s != null) {
                    drop(s, reason);
                }
            }
        });
    }

    private void subscribe(Object connectedCar) {
        if (!running || current != null) {
            return;
        }
        Session session = new Session(connectedCar);
        current = session;
        session.timeout = () -> subscriptionFailed(session, "订阅超时", null);
        main.postDelayed(session.timeout, SUBSCRIBE_TIMEOUT_MS);
        worker.execute(() -> {
            if (session.cancelled) {
                return;
            }
            try {
                Class<?> listenerClass = Class.forName(SENSOR_LISTENER, false, EcarxGearSource.class.getClassLoader());
                if (!listenerClass.isInterface()) {
                    throw new IllegalStateException("ECARX sensor listener is not an interface");
                }
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
                    log.warn("gear", "初始挡位读取失败，等待事件建立基准: " + EcarxCarHub.describe(e));
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
                session.registered = true;
                if (Boolean.FALSE.equals(accepted)) {
                    throw new IllegalStateException("registerListener returned false");
                }
                main.post(() -> {
                    if (!active(session)) {
                        return;
                    }
                    main.removeCallbacks(session.timeout);
                    retryMs = 1_000;
                    log.info("gear", "ECARX 挡位已订阅 0x200200（共享连接）; baseline=" + baseline);
                });
            } catch (Exception | LinkageError e) {
                main.post(() -> subscriptionFailed(session, EcarxCarHub.describe(e), e));
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
            // Re-subscribe through the same read-before-subscribe path to obtain a silent, fresh
            // baseline, rather than treating a value retained during unavailability as a shift.
            Object sameCar = session.car;
            drop(session, "挡位支持已恢复，重新读取基准");
            main.postDelayed(() -> {
                if (running && car == sameCar) {
                    subscribe(sameCar);
                }
            }, RESUBSCRIBE_DELAY_MS);
        }
    }

    /**
     * The subscription failed on a connected car. A dead binder is a connection problem: the hub
     * reconnects everyone. Anything else is retried here on the same car with backoff.
     */
    private void subscriptionFailed(Session session, String reason, Throwable error) {
        if (!active(session)) {
            return;
        }
        drop(session, reason + "; " + retryMs + "ms 后重新订阅");
        if (isDeadObject(error)) {
            if (handle != null) {
                handle.reportFailure(session.car, "挡位订阅失败: " + reason);
            }
            return;
        }
        Object sameCar = session.car;
        cancelRetry();
        pendingRetry = () -> {
            pendingRetry = null;
            if (running && car == sameCar) {
                subscribe(sameCar);
            }
        };
        main.postDelayed(pendingRetry, retryMs);
        retryMs = Math.min(MAX_RETRY_MS, retryMs * 2);
    }

    private void cancelRetry() {
        if (pendingRetry != null) {
            main.removeCallbacks(pendingRetry);
            pendingRetry = null;
        }
    }

    private static boolean isDeadObject(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) {
            error = error.getCause();
        }
        return error instanceof DeadObjectException;
    }

    private boolean active(Session session) {
        return running && current == session && !session.cancelled;
    }

    private void drop(Session session, String reason) {
        if (current != session) {
            return;
        }
        current = null;
        session.cancelled = true;
        main.removeCallbacks(session.timeout);
        listener.onDisconnected();
        log.warn("gear", reason);
        worker.execute(() -> cleanup(session));
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
        cancelRetry();
        Session session = current;
        current = null;
        if (session != null) {
            session.cancelled = true;
            main.removeCallbacks(session.timeout);
            worker.execute(() -> cleanup(session));
        }
        worker.shutdown();
        if (handle != null) {
            handle.release();
            handle = null;
        }
        car = null;
    }

    /** Worker. Only the sensor listener is ours; the Car connection belongs to the hub. */
    private void cleanup(Session session) {
        if (session.registered && session.sensor != null && session.sensorListener != null && session.unregisterSensor != null) {
            try {
                session.unregisterSensor.invoke(session.sensor, session.sensorListener);
            } catch (Exception | LinkageError e) {
                log.warn("gear", "注销挡位监听失败: " + EcarxCarHub.describe(e));
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

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("EcarxGearSource must be used on the main thread");
        }
    }

    private static final class Session {
        final Object car;
        volatile boolean cancelled;
        volatile boolean registered;
        boolean ready;
        boolean supportUnavailable;
        Runnable timeout;
        Object sensor;
        Object sensorListener;
        Method unregisterSensor;

        Session(Object car) {
            this.car = car;
        }
    }
}
