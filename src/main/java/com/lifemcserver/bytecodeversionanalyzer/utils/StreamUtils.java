package com.lifemcserver.bytecodeversionanalyzer.utils;

import com.lifemcserver.bytecodeversionanalyzer.BytecodeVersionAnalyzer;
import com.lifemcserver.bytecodeversionanalyzer.Constants;
import com.lifemcserver.bytecodeversionanalyzer.extensions.collections.EnumerationSpliterator;
import com.lifemcserver.bytecodeversionanalyzer.extensions.threading.NamedThreadFactory;
import com.lifemcserver.bytecodeversionanalyzer.extensions.threading.SupplierManagedBlock;
import com.lifemcserver.bytecodeversionanalyzer.logging.Logging;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class StreamUtils {
    /**
     * Static only class.
     */
    private StreamUtils() {
        throw new UnsupportedOperationException(Constants.STATIC_ONLY_CLASS);
    }

    /**
     * Runs an operation in parallel on the given stream, using given number of threads and async status & thread names.
     * <p>
     * This will make the stream parallel if it is not parallel and {@link BytecodeVersionAnalyzer#isParallel()} is true.
     * If {@link BytecodeVersionAnalyzer#isParallel()} is false, the stream is untouched and operation will run normally.
     * <p>
     * Otherwise, the stream will be made parallel (if it is not already), and then the operation will be executed on a new
     * {@link ForkJoinPool} with given parameters as settings.
     * <p>
     * This method will return when the operation is completed. Async parameter or stream being parallel does not affect
     * this behaviour, being parallel only means work is done on parallel.
     * <p>
     * The {@link ForkJoinPool} will be shutdown after the work is done automatically by this method.
     *
     * @param stream    The stream to run the given operation on.
     * @param operation The operation to run on the given stream. The stream will be passed as an argument to the operation.
     * @param threads   The thread count to use when doing parallel processing.
     * @param async     The async parameter to pass into {@link ForkJoinPool} constructor.
     * @param name      The name of the threads for the parallel processing.
     * @param <T>       The type of the stream.
     */
    public static final <T> void parallel(final Stream<T> stream, final Consumer<Stream<T>> operation, final int threads, final boolean async, final String name) {
        final Runnable action = () -> operation.accept(BytecodeVersionAnalyzer.isParallel() ? stream.parallel() : stream);

        if (BytecodeVersionAnalyzer.isParallel()) {
            final int defaultThreadCount = Runtime.getRuntime().availableProcessors();
            final int threadsCount = BytecodeVersionAnalyzer.limitRange("thread count", true, threads, 1, Constants.MAX_FORKJOINPOOL_THREADS, defaultThreadCount); // implementation limits

            final ForkJoinPool forkJoinPool = new ForkJoinPool(threadsCount, new NamedThreadFactory(name), BytecodeVersionAnalyzer.uncaughtExceptionHandler.get(), async);
            final ForkJoinTask<?> task = forkJoinPool.submit(() -> SupplierManagedBlock.callInManagedBlock(action));

            // Reduce priority of current thread, which will wait until the task in the pool is finished.
            final Thread currentThread = Thread.currentThread();

            final int oldPriority = currentThread.getPriority();
            currentThread.setPriority(oldPriority / 2);

            try {
                task.get();
            } catch (final InterruptedException e) {
                // Re-interrupt the thread
                currentThread.interrupt();
            } catch (final ExecutionException e) {
                throw BytecodeVersionAnalyzer.handleError(e);
            } finally {
                // Restore priority and shutdown the pool
                currentThread.setPriority(oldPriority);
                forkJoinPool.shutdown();
            }
        } else {
            action.run();
        }
    }

    /**
     * Makes an {@link InputStream} buffered if it is not already.
     *
     * @param is The {@link InputStream} to make buffered.
     * @return The passed {@link InputStream} but buffered.
     */
    public static final InputStream buffered(final InputStream is) {
        if (is instanceof BufferedInputStream) {
            Logging.debug(() -> "note: " + is + " is already buffered");
            return is;
        }
        if (!BytecodeVersionAnalyzer.isBuffered()) {
            Logging.debug(() -> "skipping buffering of " + is + " as requested by buffered parameter");
            return is;
        }
        return new BufferedInputStream(is);
    }

    /**
     * Converts an {@link Enumeration} to a {@link Stream}.
     *
     * @param enumeration The enumeration to convert into {@link Stream}.
     * @param <T>         The type of the enumeration.
     * @return The {@link Stream} originated from the given {@link Enumeration}.
     */
    public static final <T> Stream<T> enumerationAsStream(final Enumeration<? extends T> enumeration) {
        return StreamSupport.stream(new EnumerationSpliterator<>(enumeration), BytecodeVersionAnalyzer.isParallel());
    }
}
