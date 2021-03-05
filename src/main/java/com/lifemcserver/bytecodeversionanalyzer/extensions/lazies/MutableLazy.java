package com.lifemcserver.bytecodeversionanalyzer.extensions.lazies;

import java.util.function.Supplier;

/**
 * Represents a lazy initialized value, but mutable unlike {@link Lazy}.
 * <p>
 * In fact, {@link Lazy} is already mutable, because it has to late init the variable,
 * so it can't declare it as final.
 * <p>
 * So it is mutable, but it does not provide any method to change the value, so it is effectively immutable.
 * This class has a {@link MutableLazy#set(Object)} method, so you can change the value afterwards.
 *
 * @param <T> The type of the lazy value.
 */
public final class MutableLazy<T> extends Lazy<T> {
    /**
     * Creates a new mutable lazily initialized value.
     *
     * @param valueSupplier The value supplier to invoke when get method of this mutable lazy
     *                      is called. This supplier will be invoked one time at most.
     */
    public MutableLazy(final Supplier<T> valueSupplier) {
        super(valueSupplier);
    }

    /**
     * Updates the value of this {@link MutableLazy}.
     * <p>
     * Note: This method will throw a {@link IllegalStateException} if this method is called
     * before the value is initialized (i.e. calling the get method for first time).
     * <p>
     * This because setting the value before initialization is pointless for {@link Lazy lazy objects}.
     *
     * @param value The new value to use.
     */
    public final void set(final T value) {
        if (!supplierInvoked) {
            throw new IllegalStateException("can't change value before initialization");
        }
        this.value = value;
    }
}
