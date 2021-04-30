package com.lifemcserver.bytecodeversionanalyzer.extensions;

/**
 * A {@link RuntimeException} that is only thrown for the sole purpose of stopping the code execution.
 * <p>
 * It has a null message, null cause, suppression and stack trace disabled. Constructor is private to
 * promote usage of the singleton instance. Since it has no stack, creating new instances are unnecessary.
 */
@SuppressWarnings("SerializableHasSerializationMethods")
public final class StopCodeExecution extends RuntimeException {
    /**
     * A singleton to use instead of creating new objects every time.
     */
    public static final transient StopCodeExecution INSTANCE = new StopCodeExecution();
    /**
     * The serial version UUID for this exception, for supporting serialization.
     */
    private static final transient long serialVersionUID = 1L;

    /**
     * A private constructor to promote usage of the singleton {@link StopCodeExecution#INSTANCE}.
     */
    private StopCodeExecution() {
        super(null, null, false, false);
    }
}
