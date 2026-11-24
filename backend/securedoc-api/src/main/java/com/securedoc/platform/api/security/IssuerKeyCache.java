package com.securedoc.platform.api.security;

import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.LongSupplier;

/**
 * Task 0056: the login system's signing keys, kept as long as before Spring Security 7: fetched again after 5 minutes, and
 * used for up to 15 minutes when the login system can't be reached. (Spring Security 7 drops them after 5 minutes, so a
 * short outage of the login system would refuse every signed-in person.) An unknown key id always fetches, and never
 * drops the keys that are there. One value: the decoder reads one key address.
 */
final class IssuerKeyCache implements Cache {

    static final Duration REFRESH_AFTER = Duration.ofMinutes(5);
    static final Duration KEEP = Duration.ofMinutes(15);

    private final LongSupplier nanos;
    private Object value;
    private long fetchedAt;
    private boolean refresh;

    IssuerKeyCache() {
        this(System::nanoTime);
    }

    IssuerKeyCache(LongSupplier nanos) {
        this.nanos = nanos;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> T get(Object key, Callable<T> loader) {
        long age = value == null ? Long.MAX_VALUE : nanos.getAsLong() - fetchedAt;
        if (!refresh && age < REFRESH_AFTER.toNanos()) return (T) value;
        boolean forced = refresh;
        refresh = false;
        try {
            T fetched = loader.call();
            put(key, fetched);
            return fetched;
        } catch (Exception unreachable) {
            if (!forced && age < KEEP.toNanos()) return (T) value;
            throw new ValueRetrievalException(key, loader, unreachable);
        }
    }

    /** Asked for when a token names a key that is not there: the next read fetches. */
    @Override
    public synchronized boolean invalidate() {
        refresh = true;
        return value != null;
    }

    @Override
    public synchronized void put(Object key, Object fetched) {
        value = fetched;
        fetchedAt = nanos.getAsLong();
    }

    @Override
    public synchronized ValueWrapper get(Object key) {
        return value == null ? null : new SimpleValueWrapper(value);
    }

    @Override
    public synchronized <T> T get(Object key, Class<T> type) {
        return type.cast(value);
    }

    @Override
    public void evict(Object key) {
        invalidate();
    }

    @Override
    public void clear() {
        invalidate();
    }

    @Override
    public String getName() {
        return "issuer-keys";
    }

    @Override
    public Object getNativeCache() {
        return this;
    }
}
