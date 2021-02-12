package com.lifemcserver.bytecodeversionanalyzer;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.math.RoundingMode;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.time.Year;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.ZipFile;

/**
 * A Java Bytecode Version Analyzer CLI program.
 * <p>
 * Can show the Java class file version target of the given class,
 * classes from a JAR or ZIP archive.
 * <p>
 * It can display a metric like % classes use Java 8 or such,
 * or warn legacy/bleeding edge ones, too.
 */
final class BytecodeVersionAnalyzer {
    /**
     * The prefix added to warning messages.
     */
    private static final String warningPrefix = "warning: ";
    /**
     * The prefix added to error messages.
     */
    private static final String errorPrefix = "error: ";

    // Required for getting version at runtime
    /**
     * The Maven groupId of the project.
     */
    private static final String groupId = "com.lifemcserver";
    /**
     * The Maven artifactId of the project.
     */
    private static final String artifactId = "bytecode-version-analyzer";

    /**
     * The decimal format for two numbers at most after the first dot in non-integer/long numbers.
     */
    private static final ThreadLocal<DecimalFormat> twoNumbersAfterDotFormat = ThreadLocal.withInitial(TwoNumbersAfterDotDecimalFormat::new);
    /**
     * The parsed model object for pom file, for getting the version and other information.
     */
    private static Model model = null;

    /**
     * Called by the JVM when the program is double clicked or used from the command line.
     * This a CLI program, so it should instantly close if double clicked.
     *
     * @param args The arguments array passed by the JVM to indicate command line arguments.
     */
    public static final void main(final String[] args) {
        loadPom();

        if (model == null) {
            warning();
            warning("couldn't load POM file; some things will not display");
            warning();
        }

        if (args == null || args.length < 1 || args[0].length() < 1) {
            // Display the help and quit, called with no/invalid arguments; maybe just double clicking
            displayHelp();
            return;
        }

        ClassFileVersion printIfBelow = null;
        ClassFileVersion printIfAbove = null;

        final StringBuilder archivePath = new StringBuilder();

        boolean printedAtLeastOneVersion = false;
        String startOfArgumentValue = null;

        final int argsLength = args.length;

        String filter = null;

        for (int i = 0; i < argsLength; i++) {
            final String arg = args[i];

            if (arg.endsWith(".class")) {
                // Display version of a single class file
                final File file = new File(arg);

                if (!file.exists()) {
                    error("file does not exist: " + arg);
                    continue;
                }

                final ClassFileVersion version;

                try {
                    version = getClassFileVersion(file);
                } catch (final FileNotFoundException e) {
                    throw handleError(e); // We checked that the file exists.. How?
                } catch (final IOException e) {
                    error("error when processing class: " + e.getMessage());
                    continue;
                }

                info(version.toStringAddJavaVersionToo());
                printedAtLeastOneVersion = true;

                continue;
            }

            if (arg.startsWith("--print-if-below")) {
                startOfArgumentValue = "printIfBelow";
                continue;
            }

            if ("printIfBelow".equals(startOfArgumentValue)) {
                startOfArgumentValue = null;
                try {
                    printIfBelow = ClassFileVersion.fromString(arg);
                } catch (final IllegalArgumentException e) {
                    error("invalid class file version: " + e.getMessage());
                }
                continue;
            }

            if (arg.startsWith("--print-if-above")) {
                startOfArgumentValue = "printIfAbove";
                continue;
            }

            if ("printIfAbove".equals(startOfArgumentValue)) {
                startOfArgumentValue = null;
                try {
                    printIfAbove = ClassFileVersion.fromString(arg);
                } catch (final IllegalArgumentException e) {
                    error("invalid class file version: " + e.getMessage());
                }
                continue;
            }

            if (arg.startsWith("--filter")) {
                startOfArgumentValue = "filter";
                continue;
            }

            if ("filter".equals(startOfArgumentValue)) {
                startOfArgumentValue = null;
                filter = arg;

                continue;
            }

            archivePath.append(arg);

            if (i < argsLength - 1)
                archivePath.append(" ");
        }

        // OK, we are processing a jar
        final JarFile jar;
        final String path = archivePath.toString().trim();

        try {
            if (!new File(path).exists()) {
                if (!printedAtLeastOneVersion) {
                    error("archive file does not exist: " + path + " (tip: use quotes if the path contains space)");
                }
                return;
            }

            jar = newJarFile(path);
        } catch (final IOException e) {
            throw handleError(e);
        }

        // Process the jar
        final Map<String, ClassFileVersion> classes = getClassFileVersionsInJar(jar);
        final Map<ClassFileVersion, Integer> counter = new HashMap<>();

        for (final Map.Entry<String, ClassFileVersion> entry : classes.entrySet()) {
            final String clazz = entry.getKey();
            final ClassFileVersion version = entry.getValue();

            final int amount = counter.getOrDefault(version, 0);

            counter.remove(version);
            counter.put(version, amount + 1);

            if (printIfBelow != null) {
                if (printIfBelow.isHigherThan(version) && (filter == null || clazz.contains(filter))) {
                    warning("class " + clazz + " uses version " + version.toStringAddJavaVersionToo() + " which is below specified (" + printIfBelow + ", Java " + printIfBelow.toJavaVersion() + ")");
                }
            }

            if (printIfAbove != null) {
                if (version.isHigherThan(printIfAbove) && (filter == null || clazz.contains(filter))) {
                    warning("class " + clazz + " uses version " + version.toStringAddJavaVersionToo() + " which is above specified (" + printIfAbove + ", Java " + printIfAbove.toJavaVersion() + ")");
                }
            }
        }

        final int total = classes.size();

        for (final Map.Entry<ClassFileVersion, Integer> entry : counter.entrySet()) {
            final ClassFileVersion version = entry.getKey();
            final int usages = entry.getValue();

            final double percent = percentOf(usages, total);

            info(usages + " out of total " + total + " classes (%" + formatDouble(percent) + ") use " + version.toStringAddJavaVersionToo() + " class file version");
        }
    }

    private static final Object runtimeVersion;
    private static final MethodHandle jarFileMultiReleaseConstructor;

    private static final MethodHandle versionedStream = findVersionedStream();

    static {
        // In-block to guarantee order of execution. Tools such as IntelliJ can re-arrange field order.
        runtimeVersion = findRuntimeVersion();
        jarFileMultiReleaseConstructor = findConstructor();
    }

    private static final MethodHandle findVersionedStream() {
        try {
            // does not exist on JDK 8 obviously, we must suppress it.
            //noinspection JavaLangInvokeHandleSignature
            return MethodHandles.publicLookup().findVirtual(JarFile.class, "versionedStream", MethodType.methodType(Stream.class));
        } catch (final NoSuchMethodException e) {
            return null;
        } catch (final IllegalAccessException e) {
            throw handleError(e);
        }
    }

    /**
     * Finds the result of execution for JarFile#runtimeVersion method when on Java 9 or above.
     * <p>
     * Returns null if not available (i.e. Java 8 or below)
     *
     * @return The result of execution for JarFile#runtimeVersion method.
     * It will be null if on Java 8 or below or Runtime#Version when on Java 9 or above.
     */
    private static final Object findRuntimeVersion() {
        try {
            // does not exist on JDK 8 obviously, we must suppress it.
            //noinspection JavaLangInvokeHandleSignature
            final MethodHandle runtimeVersionMethod = MethodHandles.publicLookup().findStatic(JarFile.class, "runtimeVersion", MethodType.methodType(Class.forName("java.lang.Runtime$Version")));
            return runtimeVersionMethod.invoke();
        } catch (final ClassNotFoundException e) {
            return null;
        } catch (final Throwable e) {
            throw handleError(e);
        }
    }

    private static final MethodHandle findConstructor() {
        if (runtimeVersion == null)
            return null;
        try {
            // does not exist on JDK 8 obviously, we must suppress it.
            //noinspection JavaLangInvokeHandleSignature
            return MethodHandles.publicLookup().findConstructor(JarFile.class, MethodType.methodType(void.class, File.class, boolean.class, int.class, runtimeVersion.getClass()));
        } catch (final NoSuchMethodException | IllegalAccessException e) {
            throw handleError(e);
        }
    }

    /**
     * Creates a new {@link JarFile} object with Multi-Release JAR support if available from the given path.
     * Returns a normal {@link JarFile} object without Multi-Release JAR support if it is not available.
     * <p>
     * It should return Multi-Release supported instance on Java 9 and above. However, you still need to do few
     * things if you want to process each entry in a JAR with correct Multi-Release support:
     * <p>
     * - Creating the JAR file with this method,
     * <p>
     * - Using the new versionedStream method instead of entries to loop/process for each entry (Java 10+ unfortunately),
     * - Ignore entries on META-INF/versions,
     * <p>
     * - Then do {@code entry = jar.getJarEntry(entry.getName());}. This will get the entry with correct release
     * depending on the JVM that is running the code. If on a non Multi-Release constructed JarFile instance, it will
     * return the same entry.
     *
     * @param path The path of the JAR file.
     * @return A new JAR file object with Multi-Release JAR support.
     */
    private static final JarFile newJarFile(final String path) throws IOException {
        if (jarFileMultiReleaseConstructor != null) {
            try {
                return (JarFile) jarFileMultiReleaseConstructor.invoke(new File(path), true, ZipFile.OPEN_READ, runtimeVersion);
            } catch (final Throwable tw) {
                throw handleError(tw);
            }
        }
        return new JarFile(path);
    }

    /**
     * Formats a double to remove extra verbose numbers and only show 2 numbers after the dot.
     *
     * @param number The double number.
     * @return The non-verbose, human-friendly read-able double.
     */
    private static final String formatDouble(final double number) {
        return twoNumbersAfterDotFormat.get().format(number);
    }

    private static final class TwoNumbersAfterDotDecimalFormat extends DecimalFormat {
        public TwoNumbersAfterDotDecimalFormat() {
            super("##.##");
            setRoundingMode(RoundingMode.DOWN);
        }
    }

    /**
     * Returns the percentage of the current to the total value.
     * <p>
     * For example, if you enter 6 as current and 10 as total, it will return 60.
     * <p>
     * Format the returning double with {@link BytecodeVersionAnalyzer#twoNumbersAfterDotFormat} if
     * you want a non-exact precise but human-readable value.
     *
     * @param current The current value.
     * @param total   The total value.
     * @return The percentage of the current to the total value.
     */
    private static final double percentOf(final double current, final double total) {
        return (current / total) * 100.00D;
    }

    private static final <T> Stream<T> enumerationAsStream(final Enumeration<T> e) {
        return StreamSupport.stream(
            new Spliterators.AbstractSpliterator<T>(Long.MAX_VALUE, Spliterator.ORDERED) {
                @Override
                public final boolean tryAdvance(final Consumer<? super T> action) {
                    if (e.hasMoreElements()) {
                        action.accept(e.nextElement());
                        return true;
                    }
                    return false;
                }

                @Override
                public final void forEachRemaining(final Consumer<? super T> action) {
                    while (e.hasMoreElements()) {
                        action.accept(e.nextElement());
                    }
                }
            }, false);
    }

    private static final Map<String, ClassFileVersion> getClassFileVersionsInJar(final JarFile jar) {
        final Map<String, ClassFileVersion> classes = new HashMap<>();

        final Stream<JarEntry> stream;
        try {
            // must be done
            //noinspection unchecked
            stream = versionedStream != null ? ((Stream<JarEntry>) versionedStream.invoke(jar)) : enumerationAsStream(jar.entries());
        } catch (final Throwable tw) {
            throw handleError(tw);
        }

        for (JarEntry entry : stream.toArray(JarEntry[]::new)) {
            if (!entry.isDirectory()) {
                if (entry.getName().endsWith(".class") && !entry.getName().contains("META-INF/versions")) {
                    entry = jar.getJarEntry(entry.getName());

                    try (final InputStream in = jar.getInputStream(entry)) {
                        final ClassFileVersion version;

                        try {
                            version = getClassFileVersion(in);
                        } catch (final IOException e) {
                            error("error when processing class: " + e.getMessage());
                            continue;
                        }

                        if (!classes.containsKey(entry.getName())) {
                            classes.put(entry.getName(), version);
                        } else {
                            warning("duplicate class: " + entry.getName());
                        }
                    } catch (final IOException e) {
                        throw handleError(e);
                    }
                }
            }
        }

        return classes;
    }

    /**
     * Gets the class file version of a single class {@link File}.
     *
     * @param file The class file to get class file version of it.
     * @return The class file version of the given class file.
     * @throws IOException If the file is not a valid Java class or contain
     *                     illegal major / minor version specifications.
     */
    @SuppressWarnings("DuplicateThrows")
    private static final ClassFileVersion getClassFileVersion(final File file) throws FileNotFoundException, IOException {
        try (final InputStream in = new FileInputStream(file)) {
            return getClassFileVersion(in);
        }
    }

    /**
     * Gets the class file version of a Java class from an {@link InputStream}.
     * Useful with {@link JarFile#getInputStream(ZipEntry)} and such methods, since it does not require a file.
     *
     * @param in The {@link InputStream} to read from.
     * @return The class file version read from the {@link InputStream}.
     * @throws IOException If the file is not a valid Java class or contain
     *                     illegal major / minor version specifications.
     */
    private static final ClassFileVersion getClassFileVersion(final InputStream in) throws IOException {
        final DataInputStream data = new DataInputStream(in);

        final int magic = data.readInt();

        // Identifier for Java class files
        if (magic != 0xCAFEBABE) {
            throw new IOException("invalid Java class");
        }

        // Note: Do not reverse the order, minor comes first.
        final int minor = 0xFFFF & data.readShort();
        final int major = 0xFFFF & data.readShort();

        data.close();

        return new ClassFileVersion(major, minor);
    }

    /**
     * Gets the version from the attached maven pom file to the JAR.
     * Returns "Unknown-Version" if it can't get the version.
     *
     * @return The version from the attached maven pom file to the JAR.
     */
    private static final String getVersion() {
        if (model == null)
            return "Unknown-Version";
        return model.getVersion();
    }

    /**
     * Gets the source url.
     *
     * @return The source url or "Error-Loading-Pom" string if it can't get it.
     */
    private static final String getSourceUrl() {
        if (model == null)
            return "Error-Loading-Pom";
        return model.getScm().getUrl();
    }

    /**
     * Gets the issues url.
     *
     * @return The issues url or "Error-Loading-Pom" string if it can't get it.
     */
    private static final String getIssuesUrl() {
        if (model == null)
            return "Error-Loading-Pom";
        return model.getIssueManagement().getUrl();
    }

    /**
     * Loads the pom.xml file either from the content root (works when running/building from IDE)
     * or the META-INF/maven directory, using the {@link BytecodeVersionAnalyzer#groupId} and {@link BytecodeVersionAnalyzer#artifactId}.
     */
    private static final void loadPom() {
        InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream("META-INF/maven/" + groupId + "/" + artifactId + "/pom.xml");

        try {
            if (stream == null) {
                try {
                    final Path path = Paths.get(Objects.requireNonNull(Thread.currentThread().getContextClassLoader().getResource(".")).toURI());
                    final File file = new File(path.getParent().getParent().toString(), "pom.xml");

                    if (file.exists()) {
                        stream = new FileInputStream(file);
                    }
                } catch (final FileNotFoundException | URISyntaxException e) {
                    return;
                }

                if (stream == null)
                    return;
            }

            final MavenXpp3Reader reader = new MavenXpp3Reader();

            try (final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                model = reader.read(bufferedReader);
            } catch (final IOException | XmlPullParserException e) {
                throw handleError(e);
            }
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (final IOException e) {
                    handleError(e);
                }
            }
        }
    }

    /**
     * Displays the CLI help message.
     */
    private static final void displayHelp() {
        info();
        info("Bytecode Version Analyzer v" + getVersion());
        info("Created by Mustafa Öncel @ LifeMC. © " + Year.now().getValue() + " GNU General Public License v3.0");
        info();
        info("Source code can be found at: " + getSourceUrl());
        info();
        info("Usage:");
        info();
        info("Analyze bytecode version of class files from the provided JAR file:");
        info("[--print-if-below <major.minor or Java version>] [--print-if-above <major.minor or Java version>] <paths-to-jars>");
        info();
        info("Show bytecode version of a class file:");
        info("[--print-if-below <major.minor or Java version>] [--print-if-above <major.minor or Java version>] <paths-to-class-files>");
        info();
        info("Additional arguments");
        info();
        info("--filter <filter text>: Filters the --print-if-above and --print-if-below messages to those that contain the specified text only.");
        info("i.e great for filtering to your package only, or a specific library's package only.");
        info();
    }

    /**
     * Handles an abnormal error case, pointing the user to report it, printing the error
     * and returning an empty exception that can be thrown to stop the code execution.
     * <p>
     * The type of the exception returned can be changed, as such, the return type is
     * just {@link RuntimeException}.
     *
     * @param error The error to handle and print.
     * @return An empty exception that can be thrown to stop the code execution.
     */
    @SuppressWarnings("SameReturnValue")
    private static final RuntimeException handleError(final Throwable error) {
        error();
        error("An error occurred when running Bytecode Version Analyzer.");
        error("Please report the error below by creating a new issue on " + getIssuesUrl());
        error();

        error.printStackTrace();

        // Can be thrown to stop the code execution.
        // Allows for using i.e non null final variables initialized inside catch blocks.
        return StopCodeExecution.INSTANCE;
    }

    /**
     * Prints an empty information message.
     */
    private static final void info() {
        info("");
    }

    /**
     * Prints an information message.
     *
     * @param message The message to be printed.
     */
    private static final void info(final String message) {
        System.out.println(message);
    }

    /**
     * Prints an empty warning message.
     */
    private static final void warning() {
        warning("");
    }

    /**
     * Prints a warning message.
     *
     * @param message The message to be printed.
     */
    private static final void warning(final String message) {
        System.err.println(warningPrefix + message);
    }

    /**
     * Prints an empty error message.
     */
    private static final void error() {
        error("");
    }

    /**
     * Prints a error message.
     *
     * @param message The message to be printed.
     */
    private static final void error(final String message) {
        System.err.println(errorPrefix + message);
    }

    /**
     * Represents a class file version, with a major and minor version.
     */
    private static final class ClassFileVersion {
        /**
         * The dot pattern to split inputs from it.
         */
        private static final Pattern dotPattern = Pattern.compile(".", Pattern.LITERAL);

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
        private ClassFileVersion(final int major, final int minor) {
            this.major = major;
            this.minor = minor;
        }

        /**
         * Creates a new {@link ClassFileVersion} from the given Java version.
         *
         * @param javaVersion The Java version to convert into {@link ClassFileVersion}.
         * @return The {@link ClassFileVersion} representing the given Java version.
         */
        private static final ClassFileVersion fromJavaVersion(final String javaVersion) {
            // NumberFormatException is callers problem
            return fromBytecodeVersionString((Integer.parseInt(javaVersion) + JAVA_CLASS_FILE_VERSION_START) + ".0"); // lgtm [java/uncaught-number-format-exception]
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
        private static final ClassFileVersion fromString(final String bytecodeOrJavaVersionString) {
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
        private static final ClassFileVersion fromBytecodeVersionString(final String bytecodeVersionString) {
            final String[] splitByDot = dotPattern.split(bytecodeVersionString);

            if (splitByDot.length != 2) {
                throw new IllegalArgumentException("not in major.minor format: " + bytecodeVersionString);
            }

            final int major = Integer.parseInt(splitByDot[0]);
            final int minor = Integer.parseInt(splitByDot[1]);

            return new ClassFileVersion(major, minor);
        }

        /**
         * Checks if this class file version is higher than the given one.
         *
         * @param other The other class file version.
         * @return True if this class file version is higher either in major or minor than
         * the given one, false otherwise. (same or lower)
         */
        private final boolean isHigherThan(final ClassFileVersion other) {
            if (major > other.major)
                return true;
            return major == other.major && minor > other.minor;
        }

        /**
         * Returns the Java version equivalent of this {@link ClassFileVersion}.
         *
         * @return The Java version equivalent of this {@link ClassFileVersion}.
         */
        private final int toJavaVersion() {
            return major - 44;
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
        private final String toStringAddJavaVersionToo() {
            return this + " (Java " + toJavaVersion() + ")";
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
            result = HASH_CODE_MAGIC_NUMBER * result + minor;

            return result;
        }
    }

    /**
     * A {@link RuntimeException} that is only thrown for the sole purpose of stopping the code execution.
     * <p>
     * It has a null message, null cause, suppression and stack trace disabled. Constructor is private to
     * promote usage of the singleton instance. Since it has no stack, creating new instances are unnecessary.
     */
    private static final class StopCodeExecution extends RuntimeException {
        /**
         * A singleton to use instead of creating new objects every time.
         */
        private static final StopCodeExecution INSTANCE = new StopCodeExecution();
        /**
         * The serial version UUID for this exception, for supporting serialization.
         */
        private static final long serialVersionUID = -6852778657371379400L;

        /**
         * A private constructor to promote usage of the singleton {@link StopCodeExecution#INSTANCE}.
         */
        private StopCodeExecution() {
            super(null, null, false, false);
        }
    }
}
