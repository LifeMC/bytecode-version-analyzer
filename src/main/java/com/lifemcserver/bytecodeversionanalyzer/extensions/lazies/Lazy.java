package com.lifemcserver.bytecodeversionanalyzer.extensions.lazies;

import java.util.function.Supplier;

/**
 * Represents a lazy initialized value.
 * The value will be initialized when the get method is first invoked.
 * <p>
 * It also supports null values.
 *
 * @param <T> The type of the value.
 */
public class Lazy<T> {
    /**
     * The supplier of the value. Will be invoked one time at most.
     */
    private final Supplier<T> valueSupplier;

    /**
     * The value. Will never change once initialized first time.
     */
    protected T value;
    /**
     * The variable that holds if supplier is invoked or not state.
     */
    protected boolean supplierInvoked;

    /**
     * Creates a new lazily initialized value.
     *
     * @param valueSupplier The value supplier to invoke when get method of this lazy
     *                      is called. This supplier will be invoked one time at most.
     */
    public Lazy(final Supplier<T> valueSupplier) {
        this.valueSupplier = valueSupplier;
    }

    /**
     * Gets the value of this lazy, causing it to initialize if not already.
     * It will return the same object every call, but only the first call initializes it.
     * <p>
     * Avoid calling this method until you need the return object to maximize performance gains
     * of {@link Lazy} values.
     * <p>
     * Note: This method is not thread-safe. If you call this method from multi-threaded code,
     * the supplier will still not be invoked multiple times most probably, but it can.
     * <p>
     * Otherwise, it will just return null if supplier's get method is executing while another
     * thread calls this method.
     * <p>
     * Consider using {@link Lazy#getSync()} or {@link Lazy#getSyncIfNull()}. {@link Lazy#getSyncIfNull()}
     * has better performance but you must be sure that your supplier will not return null.
     *
     * @return The value of this lazy.
     */
    public final T get() {
        if (supplierInvoked) { // not value != null to support supplier returning null scenarios
            return value;
        }

        supplierInvoked = true; // set invoked status to true before invoking to not call get multiple times if exceptions occur
        value = valueSupplier.get();

        return value; // return the computed and now initialized value - further calls will return the value directly.
    }

    /**
     * Gets the value of this lazy, causing it to initialize if not already.
     * It will return the same object every call, but only the first call initializes it.
     * <p>
     * Avoid calling this method until you need the return object to maximize performance gains
     * of {@link Lazy} values.
     * <p>
     * This method is same as {@link Lazy#get()}, except it is thread-safe.
     *
     * @return The value of this lazy.
     */
    public final synchronized T getSync() {
        return get();
    }

    /**
     * Gets the value of this lazy, causing it to initialize if not already.
     * It will return the same object every call, but only the first call initializes it.
     * <p>
     * Avoid calling this method until you need the return object to maximize performance gains
     * of {@link Lazy} values.
     * <p>
     * This method is same as {@link Lazy#getSync()}, except it avoids extra synchronization when
     * value is already initialized. This a safe operation since the value will never change after initialization.
     * <p>
     * However, you must be sure that your supplier will never ever return null. This the case for most
     * scenarios, since it will either return a non-null value or throw an exception.
     *
     * @return The value of this lazy.
     */
    public final T getSyncIfNull() {
        if (value != null) {
            return value;
        }
        return getSync();
    }

    /**
     * Returns the debug string representation of this {@link Lazy}.
     *
     * @return The debug string representation of this {@link Lazy}.
     */
    @Override
    public final String toString() {
        //noinspection MagicCharacter
        return "Lazy{" +
            "valueSupplier=" + valueSupplier +
            ", value=" + value +
            ", supplierInvoked=" + supplierInvoked +
            '}';
    }
}
