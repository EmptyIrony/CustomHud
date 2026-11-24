package dev.customhud.adapters;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one ECARX {@code Car} connection of this process. The gear subscription, the vehicle data
 * poller and the probe page all attach here instead of calling {@code Car.create} themselves:
 * the SDK may hand out a single shared {@code Car} per process whose connect watcher is one slot,
 * so independent connections could steal each other's watcher and disconnect each other.
 *
 * <p>Connection steps are the field-verified ones from CarLight's gear source: {@code Car.create}
 * on the main looper, {@code registerConnectWatcher} + {@code connect()} on a worker, ready on
 * {@code onConnected}; 12 s timeout, 1–60 s reconnect backoff. Connected while at least one client
 * holds a handle; the last release unregisters the watcher and disconnects. Read-only: only the
 * factory, the connect watcher and connect / disconnect are called here.
 *
 * <p>Main thread API; client callbacks arrive on the main thread.
 */
public final class EcarxCarHub {
    public interface Client {
        /** {@code car} is a connected {@code ICar}; use it until {@link #onDisconnected}. */
        void onConnected(Object car);

        /** The car passed to {@link #onConnected} must no longer be used. */
        void onDisconnected(String reason);
    }

    public interface Log {
        void info(String message);

        void warn(String message);
    }

    /** A client's hold on the connection. */
    public final class Handle {
        private final Client client;
        private boolean released;

        private Handle(Client client) {
            this.client = client;
        }

        /** Main thread. Idempotent. */
        public void release() {
            requireMain();
            if (released) {
                return;
            }
            released = true;
            removeClient(client);
        }

        /**
         * A client found {@code car} unusable (dead binder, manager missing, subscription failed):
         * reconnect with backoff. Ignored when {@code car} is no longer the current connection.
         */
        public void reportFailure(Object car, String reason) {
            main.post(() -> {
                Session s = current;
                if (!released && s != null && s.car == car && connectedCar == car) {
                    failed(s, reason);
                }
            });
        }
    }

    private static final String CAR = "com.ecarx.xui.adaptapi.car.Car";
    private static final String WATCHER = "com.ecarx.xui.adaptapi.binder.IConnectable$IConnectWatcher";
    private static final long CONNECT_TIMEOUT_MS = 12_000;
    private static final long MAX_RETRY_MS = 60_000;

    private final Context context;
    private final Log log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "customhud-ecarx-car");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Client> clients = new ArrayList<>();
    private final Runnable retry = this::connect;
    private Session current;
    private Object connectedCar;
    private long retryMs = 1_000;

    private volatile String state = "未连接";
    private volatile String lastError;
    private volatile int connections;

    public EcarxCarHub(Context context, Log log) {
        this.context = context.getApplicationContext();
        this.log = log;
    }

    /** One-line state for diagnostics screens (any thread). */
    public String state() {
        return state;
    }

    /** Last connection failure, or null (any thread). */
    public String lastError() {
        return lastError;
    }

    /** Successful connections since process start (any thread). */
    public int connections() {
        return connections;
    }

    public boolean isConnected() {
        requireMain();
        return connectedCar != null;
    }

    /** Main thread. A connected car is delivered right away (posted), else once connected. */
    public Handle acquire(Client client) {
        requireMain();
        clients.add(client);
        Handle handle = new Handle(client);
        if (connectedCar != null) {
            Object car = connectedCar;
            main.post(() -> {
                if (!handle.released && clients.contains(client) && connectedCar == car) {
                    client.onConnected(car);
                }
            });
        } else if (current == null && !main.hasCallbacks(retry)) {
            connect();
        }
        return handle;
    }

    private void removeClient(Client client) {
        clients.remove(client);
        if (!clients.isEmpty()) {
            return;
        }
        main.removeCallbacks(retry);
        retryMs = 1_000;
        Session s = current;
        current = null;
        connectedCar = null;
        state = "未连接（没有使用者）";
        if (s != null) {
            s.cancelled = true;
            main.removeCallbacks(s.timeout);
            worker.execute(() -> cleanup(s));
        }
    }

    private void connect() {
        if (clients.isEmpty() || current != null) {
            return;
        }
        Session s = new Session();
        current = s;
        state = "正在连接 ECARX Car…";
        s.timeout = () -> failed(s, "连接超时（12 秒没有 onConnected）");
        main.postDelayed(s.timeout, CONNECT_TIMEOUT_MS);
        worker.execute(() -> {
            try {
                // minSdk 29, so the hidden-API exemption is always needed (as in CarLight).
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("");
                ClassLoader loader = EcarxCarHub.class.getClassLoader();
                Class<?> carClass = Class.forName(CAR, false, loader);
                Class<?> watcherClass = Class.forName(WATCHER, false, loader);
                if (!watcherClass.isInterface()) {
                    throw new IllegalStateException("ECARX connect watcher is not an interface");
                }
                Method factory = carClass.getMethod("create", Context.class);
                main.post(() -> create(s, factory, watcherClass));
            } catch (Exception | LinkageError e) {
                main.post(() -> failed(s, "ECARX SDK 不可用: " + describe(e)));
            }
        });
    }

    /** The SDK factory creates Handlers and must run on the main looper. */
    private void create(Session s, Method factory, Class<?> watcherClass) {
        if (!active(s)) {
            return;
        }
        try {
            s.car = factory.invoke(null, context);
            if (s.car == null) {
                throw new IllegalStateException("Car.create returned null");
            }
            s.watcherClass = watcherClass;
            s.watcher = Proxy.newProxyInstance(watcherClass.getClassLoader(), new Class<?>[]{watcherClass},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "onConnected":
                                main.post(() -> connected(s));
                                return null;
                            case "onDisConnected":
                                main.post(() -> failed(s, "ECARX 已断开（onDisConnected）"));
                                return null;
                            case "equals":
                                return args != null && args.length == 1 && proxy == args[0];
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "toString":
                                return "CustomHudEcarxWatcher";
                            default:
                                return null;
                        }
                    });
            worker.execute(() -> {
                if (s.cancelled) {
                    return;
                }
                try {
                    s.car.getClass().getMethod("registerConnectWatcher", watcherClass).invoke(s.car, s.watcher);
                    s.car.getClass().getMethod("connect").invoke(s.car);
                } catch (Exception | LinkageError e) {
                    main.post(() -> failed(s, "connect 失败: " + describe(e)));
                }
            });
        } catch (Exception | LinkageError e) {
            failed(s, "Car.create 失败: " + describe(e));
        }
    }

    private void connected(Session s) {
        if (!active(s) || connectedCar == s.car) {
            return;
        }
        main.removeCallbacks(s.timeout);
        retryMs = 1_000;
        connectedCar = s.car;
        connections++;
        state = "已连接（第 " + connections + " 次）";
        log.info("ECARX Car connected #" + connections + "; clients=" + clients.size());
        for (Client client : new ArrayList<>(clients)) {
            if (connectedCar == s.car && clients.contains(client)) {
                client.onConnected(s.car);
            }
        }
    }

    private void failed(Session s, String reason) {
        if (!active(s)) {
            return;
        }
        current = null;
        s.cancelled = true;
        main.removeCallbacks(s.timeout);
        boolean wasConnected = connectedCar != null;
        connectedCar = null;
        lastError = reason;
        state = "断开：" + reason + "；" + retryMs / 1000 + " 秒后重连";
        log.warn("ECARX Car " + reason + "; retry in " + retryMs + " ms; clients=" + clients.size());
        if (wasConnected) {
            for (Client client : new ArrayList<>(clients)) {
                client.onDisconnected(reason);
            }
        }
        worker.execute(() -> cleanup(s));
        main.removeCallbacks(retry);
        if (!clients.isEmpty()) {
            main.postDelayed(retry, retryMs);
            retryMs = Math.min(MAX_RETRY_MS, retryMs * 2);
        }
    }

    private boolean active(Session s) {
        return current == s && !s.cancelled && !clients.isEmpty();
    }

    /** Worker. Some SDK versions expose unregisterConnectWatcher, others only disconnect(). */
    private void cleanup(Session s) {
        if (s.car == null) {
            return;
        }
        if (s.watcher != null) {
            try {
                for (Method m : s.car.getClass().getMethods()) {
                    if ("unregisterConnectWatcher".equals(m.getName()) && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0].isInstance(s.watcher)) {
                        m.invoke(s.car, s.watcher);
                        break;
                    }
                }
            } catch (Exception | LinkageError e) {
                log.warn("ECARX unregisterConnectWatcher failed: " + describe(e));
            }
        }
        try {
            s.car.getClass().getMethod("disconnect").invoke(s.car);
        } catch (Exception | LinkageError e) {
            log.warn("ECARX disconnect failed: " + describe(e));
        }
    }

    static String describe(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) {
            error = error.getCause();
        }
        return error.toString();
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("EcarxCarHub must be used on the main thread");
        }
    }

    private static final class Session {
        volatile boolean cancelled;
        Runnable timeout;
        Object car;
        Object watcher;
        Class<?> watcherClass;
    }
}
