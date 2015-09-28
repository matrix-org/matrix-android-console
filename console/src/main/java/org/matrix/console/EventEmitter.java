package org.matrix.console;


import android.util.Log;

import java.util.HashSet;
import java.util.Set;

public class EventEmitter<T> {
    private static final String LOG_TAG = "EventEmitter";

    private Set<Listener<T>> mCallbacks;

    public EventEmitter() {
        mCallbacks = new HashSet<>();
    }

    public void register(Listener<T> cb) {
        mCallbacks.add(cb);
    }

    public void unregister(Listener<T> cb) {
        mCallbacks.remove(cb);
    }

    public void fire(T t) {
        Set<Listener<T>> callbacks = new HashSet<>(mCallbacks);
        for (Listener<T> cb : callbacks) {
            try {
                cb.onEventFired(this, t);
            } catch(Exception e) {
                Log.e(LOG_TAG, "Callback threw: " + e.getMessage(), e);
            }
        }
    }

    public interface Listener<T> {
        void onEventFired(EventEmitter<T> emitter, T t);
    }
}
