package com.lifemcserver.bytecodeversionanalyzer;

import java.util.concurrent.ForkJoinPool;

public final class Constants {
    /**
     * A preview class files minor version is always this value.
     */
    public static final int PREVIEW_CLASS_FILE_MINOR_VERSION = 65535;
    /**
     * Result of this value can be used to get a class file version from bits.
     * (example: {@code HEXADECIMAL_ALL_BITS_ONE & 0x34} will give 52, which is Java 8)
     * <p>
     * In fact, this the same number as {@link Constants#PREVIEW_CLASS_FILE_MINOR_VERSION}, but we represent it as a
     * hexadecimal value for clarification.
     */
    public static final int HEXADECIMAL_ALL_BITS_ONE = 0xFFFF;
    /**
     * Identifier for Java class files. Classes do not contain this value are invalid.
     */
    public static final int JAVA_CLASS_FILE_IDENTIFIER = 0xCAFEBABE;
    /**
     * Java class file versions start from this value. Class file versions below this value
     * should not be expected and historically, they are probably test versions or from the Oak language. (Java's root)
     * <p>
     * For transforming class file versions into Java versions, class file version - this constant's value can
     * be assumed to be correct. For vice versa, java version + this constant value can be assumed to be correct.
     */
    public static final int JAVA_CLASS_FILE_VERSION_START = 44;
    /**
     * The magic number used for hash code calculations.
     */
    public static final int HASH_CODE_MAGIC_NUMBER = 31;
    /**
     * The one hundred number with precision as a double.
     */
    public static final double ONE_HUNDRED = 100.0D;
    /**
     * Defines maximum number of threads a {@link ForkJoinPool} can have.
     * <p>
     * This same as ForkJoinPool#MAX_CAP, equals to 0x7fff, which is 32767.
     * We use our own constant because that field of {@link ForkJoinPool} is package-private.
     */
    public static final int MAX_FORKJOINPOOL_THREADS = 32767;
    /**
     * The prefix added to warning messages.
     */
    public static final String WARNING_PREFIX = "warning: ";
    /**
     * The prefix added to error messages.
     */
    public static final String ERROR_PREFIX = "error: ";
    /**
     * Exception message to pass as an argument to {@link UnsupportedOperationException},
     * to throw in utility class constructors.
     */
    public static final String STATIC_ONLY_CLASS = "Static only class";

    /**
     * Static only class.
     */
    private Constants() {
        throw new UnsupportedOperationException(STATIC_ONLY_CLASS);
    }
}
