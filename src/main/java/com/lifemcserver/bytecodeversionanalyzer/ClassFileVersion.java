package com.lifemcserver.bytecodeversionanalyzer;

import java.util.Map;

/**
 * Represents a class file version, with a major and minor version.
 */
public final class ClassFileVersion {
    /**
     * Class file version cache.
     */
    private static final Map<Integer, ClassFileVersion> CLASS_FILE_VERSION_CACHE = BytecodeVersionAnalyzer.newHashMap();
    /**
     * The major version of the class file.
     */
    private final int major;
    /**
     * The minor version of the class file.
     */
    private final int minor;

    /**
     * Creates a new {@link ClassFileVersion} object with the given major and minor version values.
     *
     * @param major The major version of the new {@link ClassFileVersion} object.
     * @param minor The minor version of the new {@link ClassFileVersion} object.
     */
    public ClassFileVersion(final int major, final int minor) {
        this.major = major;
        this.minor = minor;
    }

    /**
     * Creates a new {@link ClassFileVersion} from the given Java version.
     *
     * @param javaVersion The Java version to convert into {@link ClassFileVersion}.
     * @return The {@link ClassFileVersion} representing the given Java version.
     */
    public static final ClassFileVersion fromJavaVersion(final String javaVersion) {
        // NumberFormatException is callers problem
        return fromBytecodeVersionString((Integer.parseInt(javaVersion) + Constants.JAVA_CLASS_FILE_VERSION_START) + ".0"); // lgtm [java/uncaught-number-format-exception]
    }

    /**
     * Creates a new {@link ClassFileVersion} parsing the given string.
     * <p>
     * This method first tries to parse it as bytecode version with the major.minor format,
     * and falls back to using {@link ClassFileVersion#fromJavaVersion(String)} if it fails.
     *
     * @param bytecodeOrJavaVersionString The string to parse and transform into {@link ClassFileVersion}.
     * @return The {@link ClassFileVersion} representing the given Java version.
     */
    public static final ClassFileVersion fromString(final String bytecodeOrJavaVersionString) {
        try {
            return fromBytecodeVersionString(bytecodeOrJavaVersionString);
        } catch (final IllegalArgumentException e) {
            return fromJavaVersion(bytecodeOrJavaVersionString);
        }
    }

    /**
     * Converts the given bytecode version string into a {@link ClassFileVersion} object, creating a new instance on each call.
     * <p>
     * The given bytecode version string should be on the major.minor format (i.e. 52.0).
     *
     * @param bytecodeVersionString The bytecode version string to parse and crete a new {@link ClassFileVersion} for it.
     * @return The {@link ClassFileVersion} representing the given bytecode version string.
     */
    public static final ClassFileVersion fromBytecodeVersionString(final String bytecodeVersionString) {
        final String[] splitByDot = bytecodeVersionString.split("\\."); // Note: This will not impact performance, String#split has a fast path for single character arguments.

        if (splitByDot.length != 2) {
            throw new IllegalArgumentException("not in major.minor format: " + bytecodeVersionString);
        }

        final int major = Integer.parseInt(splitByDot[0]);
        final int minor = Integer.parseInt(splitByDot[1]);

        return get(major, minor);
    }

    /**
     * Tries to get a cached {@link ClassFileVersion} with the given major and minor version.
     *
     * @param major The major class file version.
     * @param minor The minor class file version.
     * @return A {@link ClassFileVersion} with the given major and minor version, might be cached
     * or not, the behaviour is not guaranteed.
     */
    public static final ClassFileVersion get(final int major, final int minor) {
        if (minor != 0) {
            return new ClassFileVersion(major, minor);
        }
        return CLASS_FILE_VERSION_CACHE.computeIfAbsent(major, m -> new ClassFileVersion(m, 0));
    }

    /**
     * Checks if this class file version is higher than the given one.
     *
     * @param other The other class file version.
     * @return True if this class file version is higher either in major or minor than
     * the given one, false otherwise. (same or lower)
     */
    public final boolean isHigherThan(final ClassFileVersion other) {
        if (major > other.major)
            return true;
        return major == other.major && minor > other.minor;
    }

    /**
     * Checks if this class file version is a preview class file version.
     * (meaning it is compiled by using --enable-preview argument and can use preview features.)
     *
     * @return True if this class file version is a preview class file version, false otherwise.
     */
    public final boolean isPreview() {
        return minor == Constants.PREVIEW_CLASS_FILE_MINOR_VERSION;
    }

    /**
     * Returns the Java version equivalent of this {@link ClassFileVersion}.
     *
     * @return The Java version equivalent of this {@link ClassFileVersion}.
     */
    public final int toJavaVersion() {
        return major - Constants.JAVA_CLASS_FILE_VERSION_START;
    }

    /**
     * Converts this {@link ClassFileVersion} to a string.
     * <p>
     * Uses major.minor format. The returned value can be converted back using {@link ClassFileVersion#fromBytecodeVersionString(String)}.
     * <p>
     * Use {@link ClassFileVersion#toJavaVersion()} instead if you need the Java version instead of bytecode version.
     *
     * @return The bytecode version string representing this {@link ClassFileVersion}.
     */
    @Override
    public final String toString() {
        return major + "." + minor;
    }

    /**
     * Converts this {@link ClassFileVersion} to a string, adding both bytecode and Java version.
     * <p>
     * Uses {@link ClassFileVersion#toString()} and {@link ClassFileVersion#toJavaVersion()}.
     * <p>
     * The Java version will be added to the result of {@link ClassFileVersion#toString()} with a space
     * and parentheses prefixed with "Java".
     *
     * @return The string representation of this {@link ClassFileVersion} including both bytecode version and the Java version.
     * The return value can not be reverted back to {@link ClassFileVersion} using standard methods.
     */
    public final String toStringAddJavaVersionToo() {
        return this + " (Java " + toJavaVersion() + (isPreview() ? ", with preview features enabled)" : ")");
    }

    /**
     * Checks for equality with another {@link ClassFileVersion}.
     *
     * @param obj The other {@link ClassFileVersion} to check equality.
     * @return True if both are equal in major & minor, false otherwise.
     */
    @Override
    public final boolean equals(final Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ClassFileVersion)) return false;

        final ClassFileVersion version = (ClassFileVersion) obj;
        return major == version.major && minor == version.minor;
    }

    /**
     * Returns the unique hashcode of this {@link ClassFileVersion}.
     *
     * @return The unique hashcode of this {@link ClassFileVersion}.
     */
    @Override
    public final int hashCode() {
        // Note: No Objects#hashCode to avoid autoboxing
        int result = major;
        result = Constants.HASH_CODE_MAGIC_NUMBER * result + minor;

        return result;
    }
}
