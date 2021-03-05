package com.lifemcserver.bytecodeversionanalyzer.extensions.threading;

import com.lifemcserver.bytecodeversionanalyzer.BytecodeVersionAnalyzer;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A {@link ThreadFactory} implementation for ease of use.
 */
public final class NamedThreadFactory implements ThreadFactory, ForkJoinPool.ForkJoinWorkerThreadFactory {
    /**
     * A global name tracker that keeps track of thread names and their duplicate name prevention IDs.
     */
    private static final Map<String, Long> globalNameTracker = new ConcurrentHashMap<>();

    /**
     * A supplier that gives a {@link Thread} name.
     */
    private final Supplier<String> nameSupplier;
    /**
     * A hook that set-ups the {@link Thread Threads}.
     */
    private final Consumer<Thread> hook;

    /**
     * Creates a new {@link NamedThreadFactory}.
     *
     * @param nameSupplier The name supplier that gives a {@link Thread} name.
     */
    public NamedThreadFactory(final Supplier<String> nameSupplier) {
        this(nameSupplier, t -> {
        });
    }

    /**
     * Creates a new {@link NamedThreadFactory}.
     *
     * @param name The name of the threads that will be created using this {@link ThreadFactory}.
     *             Duplicates names are prevented and a suffix will be added to duplicate names with a unique ID.
     */
    public NamedThreadFactory(final String name) {
        this(name, t -> {
        });
    }

    /**
     * Creates a new {@link NamedThreadFactory}.
     *
     * @param nameSupplier The name supplier that gives a {@link Thread} name.
     * @param hook         The hook to run after a new {@link Thread} is created.
     */
    public NamedThreadFactory(final Supplier<String> nameSupplier, final Consumer<Thread> hook) {
        this.nameSupplier = nameSupplier;
        this.hook = hook;
    }

    /**
     * Creates a new {@link NamedThreadFactory}.
     *
     * @param name The name of the threads that will be created using this {@link ThreadFactory}.
     *             Duplicates names are prevented and a suffix will be added to duplicate names with a unique ID.
     * @param hook The hook to run after a new {@link Thread} is created.
     */
    public NamedThreadFactory(final String name, final Consumer<Thread> hook) {
        this(() -> preventDuplicates(name), hook);
    }

    /**
     * Adds a suffix to given name if it is already used by a {@link Thread}.
     * Otherwise, marks the name as used and returns the name without the suffix.
     *
     * @param name The name to prevent duplicates of it.
     * @return An unique name in the format "name #id" if duplicated, otherwise just the
     * given name.
     */
    public static final String preventDuplicates(final String name) {
        final long start = 0L;
        final long current = globalNameTracker.computeIfAbsent(name, key -> start);

        final long next = current + 1;
        globalNameTracker.put(name, next);

        return name + BytecodeVersionAnalyzer.getThreadSuffix(() -> next, () -> next);
    }

    /**
     * Gets a name for a new thread.
     *
     * @return The name.
     */
    public final String getName() {
        return nameSupplier.get();
    }

    /**
     * Creates a new thread.
     *
     * @param r The runnable to pass to thread.
     * @return The new thread.
     */
    @Override
    public final Thread newThread(final @NotNull Runnable r) {
        return newThread0(new Thread(r, getName()));
    }

    /**
     * Creates a new thread.
     *
     * @param pool The pool to create thread on.
     * @return The new thread.
     */
    @Override
    public final ForkJoinWorkerThread newThread(final ForkJoinPool pool) {
        final ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
        thread.setName(getName());

        return newThread0(thread);
    }

    /**
     * Sets uncaught exception handler of a {@link Thread}, runs hooks on it,
     * and then returns it.
     *
     * @param thread The thread.
     * @param <T>    The type of thread.
     * @return The given thread, with uncaught exception handler set and hooks run.
     */
    @SuppressWarnings("ClassExplicitlyExtendsThread") // False positive
    private final <T extends Thread> T newThread0(final T thread) {
        thread.setUncaughtExceptionHandler(BytecodeVersionAnalyzer.uncaughtExceptionHandler.get());
        hook.accept(thread);

        return thread;
    }

    /**
     * Returns the debug string representation of this {@link NamedThreadFactory}.
     *
     * @return The debug string representation of this {@link NamedThreadFactory}.
     */
    @Override
    public final String toString() {
        //noinspection MagicCharacter
        return "NamedThreadFactory{" +
            "nameSupplier=" + nameSupplier +
            ", hook=" + hook +
            '}';
    }
}
