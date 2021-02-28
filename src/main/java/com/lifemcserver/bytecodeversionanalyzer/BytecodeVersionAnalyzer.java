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
import java.text.DecimalFormatSymbols;
import java.time.Year;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
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
    private static final String WARNING_PREFIX = "warning: ";
    /**
     * The prefix added to error messages.
     */
    private static final String ERROR_PREFIX = "error: ";

    // Required for getting version at runtime
    /**
     * The Maven groupId of the project.
     */
    private static final String GROUP_ID = "com.lifemcserver";
    /**
     * The Maven artifactId of the project.
     */
    private static final String ARTIFACT_ID = "bytecode-version-analyzer";

    /**
     * The decimal format for two numbers at most after the first dot in non-integer/long numbers.
     */
    private static final DecimalFormat twoNumbersAfterDotFormat = getTwoNumbersAfterDotFormat();
    /**
     * The result of JarFile#runtimeVersion method when on Java 9 or above. Null otherwise.
     */
    private static final Object runtimeVersion;
    /**
     * The constructor of JarFile to enable Multi-Release JAR support when on Java 9 or above. Null otherwise.
     */
    private static final MethodHandle jarFileMultiReleaseConstructor;
    /**
     * A preview class files minor version is always this value.
     */
    private static final int PREVIEW_CLASS_FILE_MINOR_VERSION = 65535;
    /**
     * Result of this value can be used to get a class file version from bits.
     * (example: {@code HEXADECIMAL_ALL_BITS_ONE & 0x34} will give 52, which is Java 8)
     * <p>
     * In fact, this the same number as {@link BytecodeVersionAnalyzer#PREVIEW_CLASS_FILE_MINOR_VERSION}, but we represent it as a
     * hexadecimal value for clarification.
     */
    private static final int HEXADECIMAL_ALL_BITS_ONE = 0xFFFF;
    /**
     * Identifier for Java class files. Classes do not contain this value are invalid.
     */
    private static final int JAVA_CLASS_FILE_IDENTIFIER = 0xCAFEBABE;
    /**
     * Java class file versions start from this value. Class file versions below this value
     * should not be expected and historically, they are probably test versions or from the Oak language. (Java's root)
     * <p>
     * For transforming class file versions into Java versions, class file version - this constant's value can
     * be assumed to be correct. For vice versa, java version + this constant value can be assumed to be correct.
     */
    private static final int JAVA_CLASS_FILE_VERSION_START = 44;
    /**
     * The magic number used for hash code calculations.
     */
    private static final int HASH_CODE_MAGIC_NUMBER = 31;
    /**
     * The one hundred number with precision as a double.
     */
    private static final double ONE_HUNDRED = 100.0D;
    /**
     * Uncaught exception handler for the program.
     */
    private static final Thread.UncaughtExceptionHandler uncaughtExceptionHandler = new BytecodeVersionAnalyzerUncaughtExceptionHandler();
    /**
     * Matches the literal pattern of text ".class"
     */
    private static final Matcher dotClassPatternMatcher = Pattern.compile(".class", Pattern.LITERAL).matcher("");
    /**
     * Map that holds and caches arguments for direct key value access.
     */
    private static final Map<String, ArgumentAction> argumentMap = new HashMap<>();
    /**
     * The parsed model object for pom file, for getting the version and other information.
     */
    private static Model model;
    /**
     * Indicates that POM is loaded or not. Does not indicate a successful load, though.
     */
    private static boolean loadedPom;
    /**
     * Determines if debug messages should be printed.
     */
    private static boolean debug;
    /**
     * The versionedStream method of JarFile when on Java 10 or above. Null otherwise.
     */
    private static final MethodHandle versionedStream = findVersionedStream();
    /**
     * Keeps track of the total spawned thread count. (not currently live threads)
     */
    private static long threadCount;

    static {
        // In-block to guarantee order of execution. Tools such as IntelliJ can re-arrange field order.
        runtimeVersion = findRuntimeVersion();
        jarFileMultiReleaseConstructor = findJarFileMultiReleaseConstructor();
    }

    /**
     * Private constructor to avoid creation of new instances.
     */
    private BytecodeVersionAnalyzer() {
        throw new UnsupportedOperationException("Static only class");
    }

    /**
     * Gets a unique thread suffix in the format " #number", where number is a long number.
     * The number increases everytime this method is called. If it was 0, then an empty suffix is return.
     *
     * @return An unique thread suffix in the format " #number".
     */
    private static final String getThreadSuffix() {
        return getThreadSuffix(() -> ++threadCount, () -> threadCount);
    }

    /**
     * Gets a unique thread suffix in the format " #number", where number is a long number.
     * The number increases everytime this method is called. If it was 0, then an empty suffix is return.
     *
     * @param incrementAndGet The operation to increment and get a new long value.
     * @param get             The operation to just get without incrementing the long value.
     * @return An unique thread suffix in the format " #number".
     */
    private static final String getThreadSuffix(final LongSupplier incrementAndGet,
                                                final LongSupplier get) {
        return incrementAndGet.getAsLong() > 1L ? " #" + get.getAsLong() : "";
    }

    /**
     * Gets thread name for spawning/setting up on a thread.
     *
     * @return The programs default thread name.
     */
    private static final String getThreadName() {
        return "Bytecode version analyzer thread" + getThreadSuffix();
    }

    /**
     * Sets up the given thread with the program's default thread name and uncaught exception handler.
     *
     * @param thread The thread to set up.
     */
    private static final void setupThread(final Thread thread) {
        thread.setName(getThreadName());
        thread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
    }

    /**
     * Sets up the current thread (name and uncaught exception handler), and sets the uncaught exception handlers
     * for other threads. This must be called before any thread creation except the current thread to be effective.
     */
    private static final void setupThreads() {
        setupThread(Thread.currentThread());
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
    }

    /**
     * Called by the JVM when the program is double clicked or used from the command line.
     * This a CLI program, so it should instantly close if double clicked.
     *
     * @param args The arguments array passed by the JVM to indicate command line arguments.
     */
    public static final void main(final String[] args) {
        final Timing timing = new Timing();
        timing.start();

        setupThreads();
        runCli(args);

        timing.stop();
        info("Took " + timing);
    }

    /**
     * Checks arguments and processes them.
     *
     * @param args The arguments to check and process.
     */
    private static final void runCli(final String[] args) {
        if (args == null || args.length < 1 || args[0] == null || args[0].length() < 1) {
            // Display the help and quit, called with no/invalid arguments; maybe just double clicking
            displayHelp();
            return;
        }

        process(args);
    }

    /**
     * Gets the POM model object, loading it if not already loaded.
     *
     * @return The POM model object, null if failed to load.
     */
    private static final Model getModel() {
        if (!loadedPom) {
            loadedPom = true;
            loadPom();

            if (model == null) {
                warning();
                warning("couldn't load POM file; some things will not display");
                warning();
            }
        }

        return model;
    }

    /**
     * Processes the arguments.
     *
     * @param args The arguments.
     */
    private static final void process(final String[] args) {
        // Load arguments
        loadArguments();

        // Parse arguments
        final ArgumentParseResult result = parseArguments(args, args.length, new StringBuilder());

        // Initialize parse result variables
        final String path = result.archivePath;
        final boolean printedAtLeastOneVersion = result.printedAtLeastOneVersion;

        // OK, we are processing a jar
        final JarFile jar;

        try {
            final File archiveFile = new File(path);

            if (!archiveFile.exists()) {
                if (!printedAtLeastOneVersion) {
                    error("archive file does not exist: " + path + " (tip: use quotes if the path contains space)");
                }
                return;
            }

            if (!archiveFile.isFile()) {
                error("can't process a directory: " + path);
                return;
            }

            if (!archiveFile.canRead()) {
                error("can't read the file: " + path);
            }

            jar = newJarFile(path);
        } catch (final IOException e) {
            throw handleError(e);
        }

        // Process the jar
        printJarManifestInformation(jar);

        final Map<String, ClassFileVersion> classes = getClassFileVersionsInJar(jar);
        try {
            jar.close();
        } catch (final IOException e) {
            throw handleError(e);
        }

        analyze(classes, new HashMap<>(), result.printIfBelow, result.printIfAbove, result.filter);
    }

    /**
     * Prints the JAR manifest information for the given {@link JarFile}.
     *
     * @param jar The {@link JarFile}.
     */
    private static final void printJarManifestInformation(final JarFile jar) {
        final Manifest manifest;
        try {
            manifest = jar.getManifest();
        } catch (final IOException e) {
            throw handleError(e);
        }

        if (manifest == null) {
            warning("jar has no manifest");
        } else {
            if (isMultiRelease(manifest)) {
                info("jar is a multi release jar");
            } else {
                info("jar is not a multi release jar");
            }

            if (isSealed(manifest)) {
                info("the jar is sealed globally");
            } else {
                info("the jar is not sealed globally");
            }

            if (isSigned(manifest)) {
                info("the jar is signed from manifest");
            } else {
                info("the jar is not signed from manifest");
            }
        }
    }

    /**
     * Checks if the given {@link Manifest} contains a Multi-Release: true definition.
     * This method does not guarantee the JAR being treated as such from JVM or {@link JarFile} APIs in any way.
     *
     * @param manifest The {@link Manifest} to check for Multi-Release: true definition.
     * @return True if the given {@link Manifest} contains a Multi-Release: true definition.
     */
    private static final boolean isMultiRelease(final Manifest manifest) {
        return "true".equals(manifest.getMainAttributes().getValue("Multi-Release"));
    }

    /**
     * Checks if the given {@link Manifest} contains a Sealed: true definition.
     * This method does not guarantee the JAR being treated as such from JVM or {@link JarFile} APIs in any way.
     *
     * @param manifest The {@link Manifest} to check for Sealed: true definition.
     * @return True if the given {@link Manifest} contains a Sealed: true definition.
     */
    private static final boolean isSealed(final Manifest manifest) {
        return "true".equals(manifest.getMainAttributes().getValue("Sealed"));
    }

    /**
     * Checks if the given {@link Manifest} contains signature definitions.
     * This method does not guarantee the JAR being treated as such from JVM or {@link JarFile} APIs in any way.
     * <p>
     * Note: This method does not check signing files, only manifest entries.
     *
     * @param manifest The {@link Manifest} to check for signature definitions.
     * @return True if the given {@link Manifest} contains signature definitions.
     */
    private static final boolean isSigned(final Manifest manifest) {
        for (final String key : manifest.getEntries().keySet()) {
            if (key.endsWith("-Digest-Manifest-Main-Attributes") || key.endsWith("-Digest-Manifest") || key.endsWith("-Digest")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Analyzes the classes.
     *
     * @param classes      The classes.
     * @param counter      The counter.
     * @param printIfBelow Prints a warning if class file version is lower than this value.
     * @param printIfAbove Prints a warning if class file version is higher than this value.
     * @param filter       Makes warnings printed by printIfAbove and printIfBelow filtered by this text.
     *                     The warnings will not be printed unless they contain the filter text, or the filter text is null.
     */
    private static final void analyze(final Map<String, ClassFileVersion> classes, final Map<ClassFileVersion, Integer> counter,
                                      final ClassFileVersion printIfBelow, final ClassFileVersion printIfAbove, final CharSequence filter) {
        for (final Map.Entry<String, ClassFileVersion> entry : classes.entrySet()) {
            final String clazz = entry.getKey();
            final ClassFileVersion version = entry.getValue();

            final int amount = counter.getOrDefault(version, 0);

            counter.remove(version);
            counter.put(version, amount + 1);

            if (version.isPreview()) {
                warning("class " + clazz + " uses preview language features (" + version + ", Java " + version.toJavaVersion() + " with preview language features)");
            }

            if (printIfBelow != null && printIfBelow.isHigherThan(version) && (filter == null || clazz.contains(filter))) {
                warning("class " + clazz + " uses version " + version.toStringAddJavaVersionToo() + " which is below specified (" + printIfBelow + ", Java " + printIfBelow.toJavaVersion() + ")");
            }

            if (printIfAbove != null && version.isHigherThan(printIfAbove) && (filter == null || clazz.contains(filter))) {
                warning("class " + clazz + " uses version " + version.toStringAddJavaVersionToo() + " which is above specified (" + printIfAbove + ", Java " + printIfAbove.toJavaVersion() + ")");
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

    /**
     * Loads the CLI arguments.
     */
    private static final void loadArguments() {
        addArgument("print-if-below", true, (arg, result) -> result.printIfBelow = parseClassFileVersionFromUserInput(arg));
        addArgument("print-if-above", true, (arg, result) -> result.printIfAbove = parseClassFileVersionFromUserInput(arg));

        addArgument("filter", true, (arg, result) -> result.filter = arg);

        addArgument("loadPom", BytecodeVersionAnalyzer::getModel);

        // Reserved argument
        addArgument("threads", true, () -> warning("reserved argument usage detected; this option currently does not have any effect but it will in future."));

        addArgument("debug", () -> {
            debug = true;
            info("note: debug mode is enabled");
        });
    }

    /**
     * Adds an argument.
     *
     * @param name   The argument name. It will be usable prefixed with -- in CLI.
     * @param action The action to run when the parameter is used.
     */
    private static final void addArgument(final String name, final Runnable action) {
        addArgument(name, false, action);
    }

    /**
     * Adds an argument.
     *
     * @param name     The argument name. It will be usable prefixed with -- in CLI.
     * @param hasValue Enter true to delay execution of the action to next iteration (when the value for argument is encountered),
     *                 instead of the first iteration where only the argument is encountered and no value is known.
     * @param action   The action to run when the parameter, and, if hasValue is true, the value is used.
     */
    private static final void addArgument(final String name, final boolean hasValue, final Runnable action) {
        addArgument(name, hasValue, (arg, result) -> action.run());
    }

    /**
     * Adds an argument.
     *
     * @param name   The argument name. It will be usable prefixed with -- in CLI.
     * @param action The action to run when the parameter is used.
     */
    private static final void addArgument(final String name, final BiConsumer<String, ArgumentParseResult> action) {
        addArgument(name, false, action);
    }

    /**
     * Adds an argument.
     *
     * @param name     The argument name. It will be usable prefixed with -- in CLI.
     * @param hasValue Enter true to delay execution of the action to next iteration (when the value for argument is encountered),
     *                 instead of the first iteration where only the argument is encountered and no value is known.
     * @param action   The action to run when the parameter, and, if hasValue is true, the value is used.
     */
    private static final void addArgument(final String name, final boolean hasValue, final BiConsumer<String, ArgumentParseResult> action) {
        argumentMap.put("--" + name, hasValue ? new ArgumentAction(new ArgumentAction(action)) : new ArgumentAction(action));
    }

    /**
     * Parses the arguments and returns the parse result.
     *
     * @param args        The arguments.
     * @param argsLength  The length of arguments.
     * @param archivePath A string builder to append archive path to.
     * @return The parse result of the given arguments.
     */
    private static final ArgumentParseResult parseArguments(final String[] args, final int argsLength, final StringBuilder archivePath) {
        boolean printedAtLeastOneVersion = false;
        String startOfArgumentValue = null;

        final ArgumentParseResult result = new ArgumentParseResult();

        for (int i = 0; i < argsLength; i++) {
            final String arg = args[i];

            if (arg.endsWith(".class")) {
                printedAtLeastOneVersion = true;

                // Display version of a single class file
                printSingleClassFile(arg);

                continue;
            }

            final ArgumentAction argumentAction = argumentMap.get(arg);

            if (argumentAction != null) {
                if (argumentAction.hasNext()) {
                    // Run the action after the value is encountered
                    startOfArgumentValue = arg;
                } else {
                    // Run the action directly, has no value
                    argumentAction.run(arg, result);
                }
                continue;
            } else {
                if (startOfArgumentValue == null) {
                    if (arg.startsWith("--") && !arg.contains(".")) {
                        error("unrecognized argument: " + arg + ", skipping...");
                        continue;
                    }
                } else if (debug) {
                    info("will parse value of " + startOfArgumentValue + " argument in next iteration");
                }
            }

            if (startOfArgumentValue != null) {
                // argumentAction will be null here, can't use that.
                final ArgumentAction previous = argumentMap.get(startOfArgumentValue);

                startOfArgumentValue = null;
                previous./*getNext().*/run(arg, result);

                continue;
            }

            archivePath.append(arg);

            if (i < argsLength - 1)
                archivePath.append(" ");
        }

        result.archivePath = archivePath.toString().trim();
        result.printedAtLeastOneVersion = printedAtLeastOneVersion;

        return result;
    }

    /**
     * Parses user input as {@link ClassFileVersion}, printing errors if necessary.
     *
     * @param input The user input to parse.
     * @return The {@link ClassFileVersion}.
     */
    private static final ClassFileVersion parseClassFileVersionFromUserInput(final String input) {
        try {
            return ClassFileVersion.fromString(input);
        } catch (final IllegalArgumentException e) {
            error("invalid class file version: " + e.getMessage());
        }
        return null;
    }

    /**
     * Prints version of a single class file.
     *
     * @param arg The path argument.
     */
    private static final void printSingleClassFile(final String arg) {
        final File file = new File(arg);

        if (!file.exists()) {
            error("file does not exist: " + arg);
            return;
        }

        if (!file.isFile()) {
            error("can't process a directory: " + arg);
            return;
        }

        if (!file.canRead()) {
            error("can't read the file: " + arg);
            return;
        }

        final ClassFileVersion version;

        try {
            version = getClassFileVersion(file);
        } catch (final FileNotFoundException e) {
            throw handleError(e); // We checked that the file exists.. How?
        } catch (final IOException e) {
            error("error when processing class: " + e.getMessage());
            return;
        }

        info(version.toStringAddJavaVersionToo());
    }

    /**
     * Finds the JarFile#versionedStream method when on Java 10 or above.
     * <p>
     * Returns null if not available (i.e. Java 8 or below)
     *
     * @return The versionedStream {@link MethodHandle}.
     */
    private static final MethodHandle findVersionedStream() {
        try {
            // does not exist on JDK 8 obviously, we must suppress it.
            //noinspection JavaLangInvokeHandleSignature
            return MethodHandles.publicLookup().findVirtual(JarFile.class, "versionedStream", MethodType.methodType(Stream.class));
        } catch (final NoSuchMethodException e) {
            if (debug) {
                warning("JarFile#versionedStream is not available (Java 10+)");
            }
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
        //noinspection OverlyBroadCatchBlock
        try {
            // does not exist on JDK 8 obviously, we must suppress it.
            //noinspection JavaLangInvokeHandleSignature
            final MethodHandle runtimeVersionMethod = MethodHandles.publicLookup().findStatic(JarFile.class, "runtimeVersion", MethodType.methodType(Class.forName("java.lang.Runtime$Version")));
            return runtimeVersionMethod.invoke();
        } catch (final ClassNotFoundException e) {
            if (debug) {
                warning("JarFile#runtimeVersion is not available (Java 9+)");
            }
            return null;
        } catch (final Throwable e) {
            throw handleError(e);
        }
    }

    /**
     * Finds the constructor of JarFile with Multi-Release support when on Java 9 or above.
     * <p>
     * Returns null if not available (i.e. Java 8 or below)
     * <p>
     * Note: In order to make this work, runtimeVersion variable should not be null.
     *
     * @return The JarFile constructor with Multi-Release support.
     */
    private static final MethodHandle findJarFileMultiReleaseConstructor() {
        if (runtimeVersion == null) {
            if (debug) {
                warning("JarFile constructor with Multi-Release support is not available because a dependency of it is not available (Java 9+, depends on JarFile#runtimeVersion)");
            }
            return null;
        }
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
     * <p>
     * - Then call {@link JarEntryVersionConsumer#shouldSkip(JarEntry, JarEntry, JarFile)} to determine if an entry should
     * be skipped. This for skipping the compiler generated synthetic classes.
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
        if (debug) {
            warning("using non-versioned JarFile constructor");
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
        return twoNumbersAfterDotFormat.format(number);
    }

    /**
     * Returns a new {@link DecimalFormat} that removes verbose precision from floating point numbers.
     * <p>
     * It only allows maximum of two numbers after the dot.
     *
     * @return A new {@link DecimalFormat} that removes verbose precision from floating point numbers.
     */
    private static final DecimalFormat getTwoNumbersAfterDotFormat() {
        final DecimalFormat format = new DecimalFormat("##.##", new DecimalFormatSymbols(Locale.ROOT));
        format.setRoundingMode(RoundingMode.UP);

        return format;
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
        return (current / total) * ONE_HUNDRED;
    }

    /**
     * Converts an {@link Enumeration} to a {@link Stream}.
     *
     * @param enumeration The enumeration to convert into {@link Stream}.
     * @param <T>         The type of the enumeration.
     * @return The {@link Stream} originated from the given {@link Enumeration}.
     */
    private static final <T> Stream<T> enumerationAsStream(final Enumeration<? extends T> enumeration) {
        return StreamSupport.stream(new EnumerationSpliterator<>(enumeration), false);
    }

    /**
     * Returns class file versions in a {@link JarFile} archive.
     * <p>
     * Construct the {@link JarFile} instance with {@link BytecodeVersionAnalyzer#newJarFile(String)} method
     * for Multi-Release JAR support.
     * <p>
     * This method uses versionedStream method if available (Java 10+), skips META-INF/versions, refreshes
     * entry (to get versioned one on Java 9+) and uses {@link JarEntryVersionConsumer#shouldSkip(JarEntry, JarEntry, JarFile)}.
     * <p>
     * This means it has full Multi-Release support requirements described in {@link BytecodeVersionAnalyzer#newJarFile(String)},
     * if you construct the given {@link JarFile} with that method, of course.
     *
     * @param jar The {@link JarFile} to get class files versions from.
     * @return A map containing a {@link JarFile} entry name and {@link ClassFileVersion} of it.
     * The classes are checked for .class extension, Java class identifier, and, major & minor versions only. It is not
     * guaranteed to be a valid class.
     */
    private static final Map<String, ClassFileVersion> getClassFileVersionsInJar(final JarFile jar) {
        final Stream<JarEntry> stream;
        try {
            if (debug && versionedStream == null) {
                warning("using non-versioned enumeration stream");
            }
            // must be done
            //noinspection unchecked
            stream = versionedStream != null ? ((Stream<JarEntry>) versionedStream.invoke(jar)) : enumerationAsStream(jar.entries());
        } catch (final Throwable tw) {
            throw handleError(tw);
        }

        // create processor
        final JarEntryVersionConsumer jarEntryVersionConsumer = new JarEntryVersionConsumer(jar);

        // create a process tracker to track progress of entries processed
        final ProcessTracker tracker = new ProcessTrackerBuilder()
            .interval(500L, TimeUnit.MILLISECONDS) // every 500ms
            .current(jarEntryVersionConsumer.entries::size) // current supplier
            .notify((current, total) -> info("Processing entries... (" + current + ")"))
            .build()
            .start();

        final Timing timer = new Timing();
        timer.start();

        // start processing - process will be tracked with above code.
        stream.forEach(jarEntryVersionConsumer);

        timer.stop();
        tracker.stop(); // process is completed, stop tracking it.

        info("Processed " + jarEntryVersionConsumer.entries.size() + " entries in " + timer);

        return jarEntryVersionConsumer.classes;
    }

    /**
     * Gets the class file version of a single class {@link File}.
     *
     * @param file The class file to get class file version of it.
     * @return The class file version of the given class file.
     * @throws IOException If the file is not a valid Java class or contain
     *                     illegal major / minor version specifications.
     */
    private static final ClassFileVersion getClassFileVersion(final File file) throws IOException {
        try (final InputStream in = new BufferedInputStream(new FileInputStream(file))) {
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
        if (magic != JAVA_CLASS_FILE_IDENTIFIER) {
            throw new IOException("invalid Java class");
        }

        // Note: Do not reverse the order, minor comes first.
        final int minor = HEXADECIMAL_ALL_BITS_ONE & data.readShort();
        final int major = HEXADECIMAL_ALL_BITS_ONE & data.readShort();

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
        if (getModel() == null)
            return "Unknown-Version";
        return model.getVersion();
    }

    /**
     * Gets the source url.
     *
     * @return The source url or "Error-Loading-Pom" string if it can't get it.
     */
    private static final String getSourceUrl() {
        if (getModel() == null)
            return "Error-Loading-Pom";
        return model.getScm().getUrl();
    }

    /**
     * Gets the issues url.
     *
     * @return The issues url or "Error-Loading-Pom" string if it can't get it.
     */
    private static final String getIssuesUrl() {
        if (getModel() == null)
            return "Error-Loading-Pom";
        return model.getIssueManagement().getUrl();
    }

    /**
     * Loads the pom.xml file either from the content root (works when running/building from IDE)
     * or the META-INF/maven directory, using the {@link BytecodeVersionAnalyzer#GROUP_ID} and {@link BytecodeVersionAnalyzer#ARTIFACT_ID}.
     */
    private static final void loadPom() {
        InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream("META-INF/maven/" + GROUP_ID + "/" + ARTIFACT_ID + "/pom.xml");

        try {
            if (stream == null) {
                try {
                    final Path path = Paths.get(Objects.requireNonNull(Thread.currentThread().getContextClassLoader().getResource(".")).toURI());
                    final File file = new File(path.getParent().getParent().toString(), "pom.xml");

                    if (file.exists()) {
                        stream = new BufferedInputStream(new FileInputStream(file));
                    }
                } catch (final FileNotFoundException | URISyntaxException e) {
                    return;
                }

                if (stream == null) {
                    return;
                }
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
        System.err.println(WARNING_PREFIX + message);
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
        System.err.println(ERROR_PREFIX + message);
    }

    /**
     * A combination of {@link BiConsumer} and {@link IntConsumer}.
     * Unfortunately there is no {@link BiIntConsumer} in the JDK, currently. So we use our own interface.
     * <p>
     * This not compatible with any of the mentioned classes and will not necessarily provide their new methods like
     * {@link BiConsumer#andThen(BiConsumer)}.
     *
     * @see BiConsumer
     * @see IntConsumer
     */
    @FunctionalInterface
    private interface BiIntConsumer {
        /**
         * Runs this {@link BiIntConsumer} with the given parameters.
         *
         * @param value1 The first value.
         * @param value2 The second value.
         */
        void accept(final int value1, final int value2);
    }

    /**
     * Represents an execution of an argument.
     */
    private static final class ArgumentAction {
        /**
         * The action to execute.
         */
        private final BiConsumer<String, ArgumentParseResult> action;
        /**
         * The next {@link ArgumentAction} if this action has a value.
         */
        private final ArgumentAction next;

        /**
         * Creates a new {@link ArgumentAction}.
         *
         * @param action The action to execute.
         */
        private ArgumentAction(final BiConsumer<String, ArgumentParseResult> action) {
            this.action = action;
            next = null;
        }

        /**
         * Creates a new {@link ArgumentAction}.
         *
         * @param next The next {@link ArgumentAction}.
         */
        private ArgumentAction(final ArgumentAction next) {
            this.next = next;
            action = null;
        }

        /**
         * Runs the action.
         *
         * @param arg    The argument to be passed into action.
         * @param result The mutable result to be processed by action.
         */
        private final void run(final String arg, final ArgumentParseResult result) {
            final BiConsumer<String, ArgumentParseResult> act;

            if (action != null) {
                act = action;
            } else {
                // To avoid recursion
                ArgumentAction lastAction = next;

                while (lastAction.next != null) {
                    lastAction = lastAction.next;
                }

                act = lastAction.action;
            }

            act.accept(arg, result);
        }

        /**
         * Checks if this action has a next {@link ArgumentAction}.
         *
         * @return True if this action has a next {@link ArgumentAction}.
         */
        private final boolean hasNext() {
            return next != null;
        }

        /**
         * Gets the next {@link ArgumentAction}.
         *
         * @return The next {@link ArgumentAction}.
         */
        private final ArgumentAction getNext() {
            return next;
        }

        /**
         * Returns the debug string representation of this {@link ArgumentAction}.
         *
         * @return The debug string representation of this {@link ArgumentAction}.
         */
        @Override
        public final String toString() {
            //noinspection MagicCharacter
            return "ArgumentAction{" +
                "action=" + action +
                ", next=" + next +
                '}';
        }
    }

    /**
     * A builder for the class {@link ProcessTracker}.
     */
    private static final class ProcessTrackerBuilder {
        /**
         * The interval of {@link ProcessTracker}.
         */
        private long interval;
        /**
         * The current of {@link ProcessTracker}.
         */
        private IntSupplier current;
        /**
         * The total of {@link ProcessTracker}.
         */
        private IntSupplier total;
        /**
         * The notify of {@link ProcessTracker}.
         */
        private BiIntConsumer notify;

        /**
         * Sets interval of {@link ProcessTracker}.
         *
         * @param interval The interval.
         * @return This {@link ProcessTrackerBuilder} instance for chaining.
         */
        private final ProcessTrackerBuilder interval(final long interval) {
            this.interval = interval;
            return this;
        }

        /**
         * Sets interval of {@link ProcessTracker}.
         *
         * @param interval The interval.
         * @param unit     The unit.
         * @return This {@link ProcessTrackerBuilder} instance for chaining.
         */
        private final ProcessTrackerBuilder interval(final long interval, final TimeUnit unit) {
            return interval(unit.toNanos(interval));
        }

        /**
         * Sets current of {@link ProcessTracker}.
         *
         * @param current The current.
         * @return This {@link ProcessTrackerBuilder} instance for chaining.
         */
        private final ProcessTrackerBuilder current(final IntSupplier current) {
            this.current = current;
            return this;
        }

        /**
         * Sets total of {@link ProcessTracker}.
         *
         * @param total The total.
         * @return This {@link ProcessTrackerBuilder} instance for chaining.
         */
        private final ProcessTrackerBuilder total(final IntSupplier total) {
            this.total = total;
            return this;
        }

        /**
         * Sets notify of {@link ProcessTracker}.
         *
         * @param notify The notify.
         * @return This {@link ProcessTrackerBuilder} instance for chaining.
         */
        private final ProcessTrackerBuilder notify(final BiIntConsumer notify) {
            this.notify = notify;
            return this;
        }

        /**
         * Builds this {@link ProcessTracker}.
         *
         * @return The {@link ProcessTracker}.
         */
        private final ProcessTracker build() {
            return new ProcessTracker(interval, current, total, notify);
        }

        /**
         * Returns the debug string representation of this {@link ProcessTrackerBuilder}.
         *
         * @return The debug string representation of this {@link ProcessTrackerBuilder}.
         */
        @Override
        public final String toString() {
            //noinspection MagicCharacter
            return "ProcessTrackerBuilder{" +
                "interval=" + interval +
                ", current=" + current +
                ", total=" + total +
                ", notify=" + notify +
                '}';
        }
    }

    /**
     * A class for tracking process of something.
     */
    private static final class ProcessTracker {
        /**
         * The interval to run this tracker on.
         */
        private final long interval;
        /**
         * Currently processed count.
         */
        private final IntSupplier current;
        /**
         * Total count, might not be available, i.e on {@link Stream Streams}.
         */
        private final IntSupplier total;
        /**
         * The notify hook that accepts current and total count.
         */
        private final BiIntConsumer notify;
        /**
         * The executor to schedule our task at given interval.
         */
        private ScheduledExecutorService executor;
        /**
         * Our last scheduled task, stored to be able to cancel it.
         */
        private ScheduledFuture<?> task;

        /**
         * Creates a new {@link ProcessTracker}.
         * Consider using {@link ProcessTrackerBuilder} instead.
         *
         * @param interval The interval to check for progress.
         * @param current  The supplier to the current value.
         * @param total    The supplier to the total value.
         * @param notify   The notify hook that will run every given interval.
         */
        private ProcessTracker(final long interval, final IntSupplier current, final IntSupplier total, final BiIntConsumer notify) {
            this.interval = interval;

            this.current = current;
            this.total = total;

            this.notify = notify;
        }

        /**
         * Starts this {@link ProcessTracker}.
         * <p>
         * The process tracker thread will be a daemon thread meaning it will stop when all other threads stop and exit the JVM.
         * However this often not desired and you want to stop tracking as soon as the task is complete.
         * <p>
         * Then you must call {@link ProcessTracker#stop()} after the task is complete.
         *
         * @return This {@link ProcessTracker} instance for ease of use.
         * @see ProcessTracker#stop()
         */
        private final ProcessTracker start() {
            if (executor == null) {
                executor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("Process Tracker", t -> t.setDaemon(true)));
            }

            if (interval > 0L) {
                task = executor
                    .scheduleAtFixedRate(this::onInterval, interval, interval, TimeUnit.NANOSECONDS);
            }

            return this;
        }

        /**
         * Stops this {@link ProcessTracker} completely.
         * Cancels the tasks and shutdowns the executor, too.
         * <p>
         * It can be started again, however. With a new executor and a new task of course.
         *
         * @return This {@link ProcessTracker} instance for ease of use.
         * @see ProcessTracker#start()
         */
        private final ProcessTracker stop() {
            // Stop accepting new tasks
            if (executor != null) {
                executor.shutdown();
            }

            // Cancel & nullify our task
            if (task != null) {
                task.cancel(false);
                task = null;
            }

            // Shutdown the executor
            if (executor != null) {
                executor.shutdownNow();
            }

            return this;
        }

        /**
         * Runs the notify hook.
         */
        private final void onInterval() {
            try {
                notify.accept(current != null ? current.getAsInt() : -1, total != null ? total.getAsInt() : -1);
            } catch (final Throwable tw) {
                throw handleError(tw);
            }
        }

        /**
         * Returns the debug string representation of this {@link ProcessTracker}.
         *
         * @return The debug string representation of this {@link ProcessTracker}.
         */
        @Override
        public final String toString() {
            //noinspection MagicCharacter
            return "ProcessTracker{" +
                "interval=" + interval +
                ", current=" + current +
                ", total=" + total +
                ", notify=" + notify +
                ", executor=" + executor +
                ", task=" + task +
                '}';
        }
    }

    /**
     * A {@link ThreadFactory} implementation for ease of use.
     */
    private static final class NamedThreadFactory implements ThreadFactory {
        /**
         * A global name tracker that keeps track of thread names and their duplicate name prevention IDs.
         */
        private static final Map<String, Long> globalNameTracker = new HashMap<>();

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
        private NamedThreadFactory(final Supplier<String> nameSupplier) {
            this(nameSupplier, t -> {
            });
        }

        /**
         * Creates a new {@link NamedThreadFactory}.
         *
         * @param name The name of the threads that will be created using this {@link ThreadFactory}.
         *             Duplicates names are prevented and a suffix will be added to duplicate names with a unique ID.
         */
        private NamedThreadFactory(final String name) {
            this(name, t -> {
            });
        }

        /**
         * Creates a new {@link NamedThreadFactory}.
         *
         * @param nameSupplier The name supplier that gives a {@link Thread} name.
         * @param hook         The hook to run after a new {@link Thread} is created.
         */
        private NamedThreadFactory(final Supplier<String> nameSupplier, final Consumer<Thread> hook) {
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
        private NamedThreadFactory(final String name, final Consumer<Thread> hook) {
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
        private static final String preventDuplicates(final String name) {
            final long start = 1L;
            final long current = globalNameTracker.computeIfAbsent(name, key -> start);

            if (current == start) {
                return name;
            }

            final long next = current + 1;
            globalNameTracker.put(name, next);

            return getThreadSuffix(() -> next, () -> next);
        }

        /**
         * Creates a new thread.
         *
         * @param r The runnable to pass to thread.
         * @return The new thread.
         */
        @Override
        public final Thread newThread(final Runnable r) {
            final Thread thread = new Thread(r, nameSupplier.get());
            thread.setUncaughtExceptionHandler(uncaughtExceptionHandler);

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

    /**
     * A custom uncaught exception handler that is different from Java's default.
     * Java's default implementation only ignore {@link ThreadDeath} exceptions.
     * <p>
     * We added our {@link StopCodeExecution} too, to do ignored exceptions list.
     */
    private static final class BytecodeVersionAnalyzerUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {
        /**
         * Handles an uncaught exception exactly like Java's default {@link Thread.UncaughtExceptionHandler},
         * but also ignoring {@link StopCodeExecution} together with {@link ThreadDeath}.
         *
         * @param t The thread that generated the exception.
         * @param e The exception that occurred.
         */
        @Override
        public final void uncaughtException(final Thread t, final Throwable e) {
            if (!(e instanceof ThreadDeath) && !(e instanceof StopCodeExecution)) {
                System.err.print("Exception in thread \"" + t.getName() + "\" ");
                e.printStackTrace(System.err);
            }
        }
    }

    /**
     * The consumer, that processes {@link JarEntry} objects in a {@link JarFile} and stores the class file information.
     * You should first create a new instance of this class as {@link JarFile} as an argument, then supply this to {@link Stream#forEach(Consumer)} method.
     * <p>
     * And then you can get the class file information by accessing {@link JarEntryVersionConsumer#classes}.
     */
    private static final class JarEntryVersionConsumer implements Consumer<JarEntry> {
        /**
         * The map storing entry (class) names and their versions.
         */
        private final Map<String, ClassFileVersion> classes = new HashMap<>();
        /**
         * A set which stores all entry names, to check for any duplicate.
         */
        private final Set<String> entries = new HashSet<>();
        /**
         * The {@link JarFile} of the {@link JarEntry} to consume.
         */
        private final JarFile jar;

        /**
         * Creates a new {@link JarEntryVersionConsumer}.
         *
         * @param jar The {@link JarFile} that will own {@link JarEntry JarEntries}.
         */
        private JarEntryVersionConsumer(final JarFile jar) {
            this.jar = jar;
        }

        /**
         * Checks for duplicates and prints a warning when a duplicate is found.
         * If a duplicate entry exists on a JAR, it may cause issues.
         *
         * @param entries The entries collection to add and check for duplicates.
         * @param name    The name of the entry to check for duplicate and add if not.
         */
        private static final void duplicateEntryCheck(final Collection<String> entries, final String name) {
            if (!entries.add(name)) {
                warning("duplicate entry: " + name);
            }
        }

        /**
         * Checks for signing extensions on the given file name and prints an information message
         * if the file extension looks like a signing extension.
         *
         * @param fileName The file name to check for signing extensions.
         */
        private static final void detectSigningFile(final String fileName) {
            if (fileName.endsWith(".RSA") || fileName.endsWith(".DSA") || fileName.endsWith(".SF") || fileName.endsWith(".EC") || fileName.startsWith("SIG-")) {
                info("found signing file: " + fileName);
            }
        }

        /**
         * Processes a class.
         *
         * @param classes   The classes map to put class file version information into.
         * @param jar       The {@link JarFile} to get a {@link InputStream} via {@link JarFile#getInputStream(ZipEntry)}.
         * @param entry     The {@link JarEntry} that represents a class file to process.
         * @param entryName The name of the given {@link JarEntry}.
         */
        private static final void processClass(final Map<String, ClassFileVersion> classes, final JarFile jar, final JarEntry entry, final String entryName) {
            try (final InputStream in = new BufferedInputStream(jar.getInputStream(entry))) {
                classes.put(entryName, getClassFileVersion(in));
            } catch (final IOException e) {
                error("error when processing class: " + e.getMessage());
            }
        }

        /**
         * Checks if the given {@link CharSequence} is a digit.
         * Uses {@link Character#isDigit(char)}, but checks for all characters.
         *
         * @param cs The {@link CharSequence} to check.
         * @return True if the {@link CharSequence} only consists of digits.
         */
        private static final boolean isDigit(final CharSequence cs) {
            final int csLength = cs.length();

            for (int i = 0; i < csLength; i++) {
                if (!Character.isDigit(cs.charAt(i))) {
                    return false;
                }
            }

            return true;
        }

        /**
         * Determines if the given {@link JarEntry} should be skipped.
         * <p>
         * {@link JarEntry JarEntries} will be skipped when they are non-versioned compiler generated classes, i.e synthetic classes.
         *
         * @return Whatever the given {@link JarEntry} should be skipped or not.
         */
        private static final boolean shouldSkip(final JarEntry entry, final JarEntry oldEntry, final JarFile jar) {
            // Skip the non-versioned compiler generated classes

            // JarEntry or ZipEntry does not implement a equals method, but they implement a hashCode method.
            // So we use it to check equality.
            if (entry.getName().contains("$") && entry.hashCode() == oldEntry.hashCode()) { // Compiler generated class (not necessarily a fully generated class, maybe just a nested class) and is not versioned
                final String[] nestedClassSplit = entry.getName().split("\\$"); // Note: This will not impact performance, String#split has a fast path for single character arguments.

                // If it is a fully generated class, compiler formats it like ClassName$<id>.class, where <id> is a number, i.e. 1
                if (isDigit(dotClassPatternMatcher.reset(nestedClassSplit[1]).replaceAll(""))) { // A synthetic accessor class, or an anonymous/lambda class.
                    final String baseClassName = nestedClassSplit[0] + ".class";
                    final JarEntry baseClassJarEntry = jar.getJarEntry(baseClassName);

                    final ZipEntry baseClassEntry;

                    try (final ZipFile zip = new ZipFile(jar.getName())) {
                        baseClassEntry = zip.getEntry(baseClassName);
                    } catch (final IOException e) {
                        throw handleError(e);
                    }

                    if (baseClassJarEntry != null && baseClassJarEntry.hashCode() != baseClassEntry.hashCode()) { // Base class is found and versioned
                        if (debug) {
                            info("skipping " + entry.getName() + " (non-versioned compiler generated class whose base class is found and versioned)");
                        }

                        return true;
                    }
                }
            }

            return false;
        }

        /**
         * Process an entry.
         *
         * @param classes The classes map to hold information about classes and their versions.
         * @param entries The entries map to keep track of duplicate entries.
         * @param jar     The {@link JarFile} for refreshing the entry for versioning and getting {@link InputStream}.
         * @param entry   The {@link JarEntry} to start with before refreshing.
         */
        private static final void accept0(final Map<String, ClassFileVersion> classes, final Collection<String> entries,
                                          final JarFile jar, final JarEntry entry) {
            final String name = entry.getName();
            duplicateEntryCheck(entries, name);

            if (!entry.isDirectory()) {
                detectSigningFile(name);

                if (name.endsWith(".class") && !name.contains("META-INF/versions")) {
                    final JarEntry newEntry = jar.getJarEntry(name);

                    if (shouldSkip(newEntry, entry, jar)) {
                        return;
                    }

                    processClass(classes, jar, newEntry, name);
                }
            }
        }

        /**
         * Processes the given entry.
         *
         * @param entry The {@link JarEntry} to process.
         */
        @Override
        public final void accept(final JarEntry entry) {
            accept0(classes, entries, jar, entry);
        }

        /**
         * Returns a debug string for this {@link JarEntryVersionConsumer} instance.
         *
         * @return Debug string for this {@link JarEntryVersionConsumer} instance.
         */
        @Override
        public final String toString() {
            //noinspection MagicCharacter
            return "JarEntryVersionConsumer{" +
                "classes=" + classes +
                ", entries=" + entries +
                ", jar=" + jar +
                '}';
        }
    }

    /**
     * Represents the parse result of arguments.
     */
    private static final class ArgumentParseResult {
        /**
         * The printIfBelow argument.
         */
        private ClassFileVersion printIfBelow;
        /**
         * The printIfAbove argument.
         */
        private ClassFileVersion printIfAbove;

        /**
         * The archivePath {@link String}.
         */
        private String archivePath;
        /**
         * Tracks if we printed class file version of a single class already.
         * If false, we will try to interpret argument as JAR instead.
         */
        private boolean printedAtLeastOneVersion;
        /**
         * The filter argument.
         * Used for filtering warnings printed by printIfAbove and printIfBelow.
         */
        private String filter;

        /**
         * Constructs an empty {@link ArgumentParseResult}.
         * The fields should be initialized later if this constructor is used.
         */
        private ArgumentParseResult() {
            /* implicit super-call */
        }

        /**
         * Constructs a argument parse result. The fields can be changed later.
         * To create an empty one with default field values (null, false, etc.), use the no-arg constructor.
         *
         * @param printIfBelow             The printIfBelow argument.
         * @param printIfAbove             The printIfAbove argument.
         * @param archivePath              The {@link String} of archive path.
         * @param printedAtLeastOneVersion True if printed a version of single class file.
         * @param filter                   The filter used to filter warning messages printed by printIfBelow and printIfAbove.
         */
        private ArgumentParseResult(final ClassFileVersion printIfBelow, final ClassFileVersion printIfAbove, final String archivePath,
                                    final boolean printedAtLeastOneVersion, final String filter) {
            this.printIfBelow = printIfBelow;
            this.printIfAbove = printIfAbove;

            this.archivePath = archivePath;
            this.printedAtLeastOneVersion = printedAtLeastOneVersion;

            this.filter = filter;
        }

        /**
         * Returns the debug string representation of this {@link ArgumentParseResult}.
         *
         * @return The debug string representation of this {@link ArgumentParseResult}.
         */
        @Override
        public final String toString() {
            //noinspection MagicCharacter
            return "ArgumentParseResult{" +
                "printIfBelow=" + printIfBelow +
                ", printIfAbove=" + printIfAbove +
                ", archivePath=" + archivePath +
                ", printedAtLeastOneVersion=" + printedAtLeastOneVersion +
                ", filter='" + filter + '\'' +
                '}';
        }
    }

    /**
     * Used for timing something, i.e a long operation.
     * Or a short one, for analytic purposes.
     */
    private static final class Timing {
        /**
         * The start time.
         */
        @SuppressWarnings("FieldNotUsedInToString")
        private long startTime;
        /**
         * The end time.
         */
        @SuppressWarnings("FieldNotUsedInToString")
        private long finishTime;

        /**
         * Starts the timing.
         */
        private final void start() {
            startTime = System.nanoTime();
        }

        /**
         * Resets the timing to current time,
         * calls both {@link Timing#start()} and {@link Timing#stop()}.
         */
        private final void reset() {
            start();
            stop();
        }

        /**
         * Stops the timing.
         */
        private final void stop() {
            finishTime = System.nanoTime();
        }

        /**
         * Gets the elapsed time in the given unit.
         *
         * @param unit The unit of the return value.
         * @return The elapsed time in the requested unit.
         */
        private final long getElapsedTime(final TimeUnit unit) {
            return unit.convert(finishTime - startTime, TimeUnit.NANOSECONDS);
        }

        /**
         * Returns the string representation of this timing.
         */
        @Override
        public final String toString() {
            final long elapsedTime = getElapsedTime(TimeUnit.MILLISECONDS);

            //noinspection StringConcatenationMissingWhitespace
            return elapsedTime + "ms";
        }
    }

    /**
     * Represents a class file version, with a major and minor version.
     */
    private static final class ClassFileVersion {
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
            final String[] splitByDot = bytecodeVersionString.split("\\."); // Note: This will not impact performance, String#split has a fast path for single character arguments.

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
         * Checks if this class file version is a preview class file version.
         * (meaning it is compiled by using --enable-preview argument and can use preview features.)
         *
         * @return True if this class file version is a preview class file version, false otherwise.
         */
        private final boolean isPreview() {
            return minor == PREVIEW_CLASS_FILE_MINOR_VERSION;
        }

        /**
         * Returns the Java version equivalent of this {@link ClassFileVersion}.
         *
         * @return The Java version equivalent of this {@link ClassFileVersion}.
         */
        private final int toJavaVersion() {
            return major - JAVA_CLASS_FILE_VERSION_START;
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
    @SuppressWarnings("SerializableHasSerializationMethods")
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

    /**
     * A class for using {@link Enumeration}s as {@link Spliterators}.
     *
     * @param <T> The type of the {@link Enumeration}.
     */
    private static final class EnumerationSpliterator<T> extends Spliterators.AbstractSpliterator<T> {
        /**
         * The {@link Enumeration}.
         */
        private final Enumeration<? extends T> enumeration;

        /**
         * Constructs a new {@link EnumerationSpliterator}.
         *
         * @param enumeration The {@link Enumeration}.
         */
        private EnumerationSpliterator(final Enumeration<? extends T> enumeration) {
            super(Long.MAX_VALUE, Spliterator.ORDERED & Spliterator.IMMUTABLE);

            this.enumeration = enumeration;
        }

        /**
         * Runs the specified consumer if there is more elements.
         *
         * @param action The consumer to invoke.
         * @return True if the consumer is invoked, false otherwise.
         */
        @Override
        public final boolean tryAdvance(final Consumer<? super T> action) {
            if (enumeration.hasMoreElements()) {
                action.accept(enumeration.nextElement());
                return true;
            }
            return false;
        }

        /**
         * Invokes the given consumer for each element remaining.
         *
         * @param action The consumer to invoke.
         */
        @Override
        public final void forEachRemaining(final Consumer<? super T> action) {
            while (enumeration.hasMoreElements()) {
                action.accept(enumeration.nextElement());
            }
        }

        /**
         * Returns debug string of this {@link EnumerationSpliterator}.
         *
         * @return The debug string of this {@link EnumerationSpliterator}.
         */
        @Override
        public final String toString() {
            //noinspection MagicCharacter
            return "EnumerationSpliterator{" +
                "enumeration=" + enumeration +
                '}';
        }
    }
}
