package top.nkbe.npatch.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.libxposed.service.IXposedService;

/**
 * Minimal NPatch Remote API client used by BetterHeybox.
 *
 * <p>Adapted from 7723mod/NPatch-Remote-API (Apache-2.0). Only the authenticated
 * API-102 RemotePreferences surface needed by this project is retained. This
 * variant avoids newer java.lang APIs so BetterHeybox's Android 8+ minSdk stays
 * valid.</p>
 */
public final class NPatchRemoteClient {
    public static final String DEFAULT_AUTHORITY = "top.nkbe.npatch.remote";

    private static final String METHOD_GET_REMOTE_SERVICE = "getRemoteService";
    private static final String KEY_MODULE_PACKAGE = "modulePackageName";
    private static final String KEY_BINDER = "binder";
    private static final long CONNECT_TIMEOUT_SECONDS = 3L;

    private static final ExecutorService CONNECTION_EXECUTOR =
            Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "NPatch-RemoteConnect");
                thread.setDaemon(true);
                return thread;
            });

    private final IXposedService service;
    private final Map<String, RemotePreferences> preferences = new ConcurrentHashMap<>();

    private NPatchRemoteClient(IXposedService service) {
        this.service = service;
    }

    public static boolean isAvailable(Context context) {
        try {
            return context.getPackageManager().resolveContentProvider(DEFAULT_AUTHORITY, 0) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static CompletableFuture<NPatchRemoteClient> connectAsync(Context context) {
        Context app = context.getApplicationContext();
        return CompletableFuture.supplyAsync(() -> connect(app), CONNECTION_EXECUTOR);
    }

    public static NPatchRemoteClient connect(Context context) {
        Objects.requireNonNull(context, "context");
        String modulePackageName = context.getPackageName();
        IBinder binder = requestBinder(context, modulePackageName, DEFAULT_AUTHORITY);
        IXposedService service = IXposedService.Stub.asInterface(binder);
        if (service == null) {
            throw new IllegalStateException("NPatch remote service returned an invalid binder");
        }
        return new NPatchRemoteClient(service);
    }

    public SharedPreferences getRemotePreferences(String group) {
        RemotePreferences cached = preferences.get(group);
        if (cached != null) {
            return cached;
        }
        RemotePreferences created = new RemotePreferences(service, group);
        RemotePreferences previous = preferences.putIfAbsent(group, created);
        return previous != null ? previous : created;
    }

    private static IBinder requestBinder(Context context, String modulePackageName, String authority) {
        if (authority == null || authority.trim().isEmpty()) {
            throw new IllegalArgumentException("authority is empty");
        }
        Bundle extras = new Bundle();
        extras.putString(KEY_MODULE_PACKAGE, modulePackageName);

        Future<Bundle> call = CONNECTION_EXECUTOR.submit(() ->
                context.getContentResolver().call(
                        Uri.parse("content://" + authority),
                        METHOD_GET_REMOTE_SERVICE,
                        null,
                        extras));

        Bundle result;
        try {
            result = call.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new IllegalStateException("NPatch remote service connection timed out", e);
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("NPatch remote service connection interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SecurityException) {
                throw (SecurityException) cause;
            }
            throw new IllegalStateException("NPatch remote service is unavailable", cause);
        }

        IBinder binder = result == null ? null : result.getBinder(KEY_BINDER);
        if (binder == null) {
            throw new SecurityException("NPatch rejected remote service request for " + modulePackageName);
        }
        return binder;
    }

    private static final class RemotePreferences implements SharedPreferences {
        private static final ExecutorService WRITE_EXECUTOR =
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "NPatch-RemotePrefs");
                    thread.setDaemon(true);
                    return thread;
                });

        private final IXposedService service;
        private final String group;
        private final Set<OnSharedPreferenceChangeListener> listeners =
                Collections.newSetFromMap(new ConcurrentHashMap<OnSharedPreferenceChangeListener, Boolean>());
        private volatile Map<String, Object> values;

        @SuppressWarnings("unchecked")
        RemotePreferences(IXposedService service, String group) {
            this.service = service;
            this.group = group;
            try {
                Bundle result = service.requestRemotePreferences(group);
                Serializable raw = result == null ? null : result.getSerializable("map");
                if (raw instanceof Map<?, ?>) {
                    values = immutableCopy((Map<String, Object>) raw);
                } else {
                    values = Collections.emptyMap();
                }
            } catch (RemoteException e) {
                throw new IllegalStateException("Cannot read NPatch remote preferences", e);
            }
        }

        @Override
        public Map<String, ?> getAll() {
            return new TreeMap<>(values);
        }

        @Override
        public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object value = values.get(key);
            return value instanceof Set<?> ? new HashSet<>((Set<String>) value) : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new RemoteEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
            if (listener != null) listeners.add(listener);
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
            if (listener != null) listeners.remove(listener);
        }

        private void applyDiff(Bundle diff) {
            Set<String> changed = new HashSet<>();
            synchronized (this) {
                Map<String, Object> updated = new HashMap<>(values);
                if (diff.getBoolean("clear", false)) {
                    changed.addAll(updated.keySet());
                    updated.clear();
                }
                Serializable deletes = diff.getSerializable("delete");
                if (deletes instanceof Set<?>) {
                    for (Object item : (Set<?>) deletes) {
                        if (item instanceof String && updated.remove(item) != null) {
                            changed.add((String) item);
                        }
                    }
                }
                Serializable puts = diff.getSerializable("put");
                if (puts instanceof Map<?, ?>) {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) puts).entrySet()) {
                        if (!(entry.getKey() instanceof String) || entry.getValue() == null) continue;
                        String key = (String) entry.getKey();
                        Object old = updated.put(key, entry.getValue());
                        if (!Objects.equals(old, entry.getValue())) changed.add(key);
                    }
                }
                values = immutableCopy(updated);
            }
            if (!changed.isEmpty()) {
                List<OnSharedPreferenceChangeListener> copy = new ArrayList<>(listeners);
                for (String key : changed) {
                    for (OnSharedPreferenceChangeListener listener : copy) {
                        listener.onSharedPreferenceChanged(this, key);
                    }
                }
            }
        }

        private static Map<String, Object> immutableCopy(Map<String, Object> source) {
            Map<String, Object> copy = new HashMap<>();
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Set<?>) {
                    copy.put(entry.getKey(), new HashSet<>((Set<?>) value));
                } else {
                    copy.put(entry.getKey(), value);
                }
            }
            return Collections.unmodifiableMap(copy);
        }

        private final class RemoteEditor implements Editor {
            private boolean clear;
            private final Set<String> deletes = new HashSet<>();
            private final Map<String, Object> puts = new HashMap<>();

            private Editor put(String key, Object value) {
                if (value == null) return remove(key);
                deletes.remove(key);
                puts.put(key, value);
                return this;
            }

            @Override public Editor putString(String key, String value) { return put(key, value); }
            @Override public Editor putStringSet(String key, Set<String> value) {
                return put(key, value == null ? null : new HashSet<>(value));
            }
            @Override public Editor putInt(String key, int value) { return put(key, value); }
            @Override public Editor putLong(String key, long value) { return put(key, value); }
            @Override public Editor putFloat(String key, float value) { return put(key, value); }
            @Override public Editor putBoolean(String key, boolean value) { return put(key, value); }

            @Override
            public Editor remove(String key) {
                puts.remove(key);
                deletes.add(key);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                puts.clear();
                deletes.clear();
                return this;
            }

            @Override
            public boolean commit() {
                Bundle diff = buildDiff();
                if (diff == null) return true;
                try {
                    service.updateRemotePreferences(group, diff);
                    applyDiff(diff);
                    return true;
                } catch (RemoteException e) {
                    return false;
                }
            }

            @Override
            public void apply() {
                Bundle diff = buildDiff();
                if (diff == null) return;
                applyDiff(diff);
                WRITE_EXECUTOR.execute(() -> {
                    try {
                        service.updateRemotePreferences(group, diff);
                    } catch (RemoteException ignored) {
                    }
                });
            }

            private Bundle buildDiff() {
                if (!clear && deletes.isEmpty() && puts.isEmpty()) return null;
                Bundle diff = new Bundle();
                diff.putBoolean("clear", clear);
                diff.putSerializable("delete", new HashSet<>(deletes));
                diff.putSerializable("put", new HashMap<>(puts));
                return diff;
            }
        }
    }
}
