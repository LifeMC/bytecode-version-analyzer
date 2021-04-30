package com.lifemcserver.bytecodeversionanalyzer.extensions.threading;

import com.lifemcserver.bytecodeversionanalyzer.extensions.lazies.Lazy;

import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

public final class SupplierManagedBlock<T> implements ForkJoinPool.ManagedBlocker {

    private final Lazy<T> lazy;

    public SupplierManagedBlock(final Supplier<T> supplier) {
        lazy = new Lazy<>(supplier);
    }

    public static final <T> T callInManagedBlock(final Supplier<T> supplier) {
        final SupplierManagedBlock<T> managedBlock = new SupplierManagedBlock<>(supplier);

        try {
            ForkJoinPool.managedBlock(managedBlock);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return managedBlock.getResult();
    }

    public static final void callInManagedBlock(final Runnable action) {
        callInManagedBlock(() -> {
            action.run();
            return null;
        });
    }

    @Override
    public final boolean block() {
        if (lazy.isSupplierInvoked()) {
            throw new IllegalStateException("already invoked");
        }

        lazy.get();

        if (!lazy.isSupplierInvoked()) {
            throw new IllegalStateException("invocation failure");
        }

        return true;
    }

    @Override
    public final boolean isReleasable() {
        return lazy.isSupplierInvoked();
    }

    public final T getResult() {
        if (!lazy.isSupplierInvoked()) {
            throw new IllegalStateException("result not available");
        }
        return lazy.get();
    }

    @Override
    public final String toString() {
        //noinspection MagicCharacter
        return "SupplierManagedBlock{" +
            "lazy=" + lazy +
            '}';
    }
}
