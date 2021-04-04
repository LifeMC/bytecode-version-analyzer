package com.lifemcserver.bytecodeversionanalyzer;

import com.lifemcserver.bytecodeversionanalyzer.arguments.ArgumentAction;
import com.lifemcserver.bytecodeversionanalyzer.arguments.ArgumentParseResult;
import com.lifemcserver.bytecodeversionanalyzer.crosscompile.MultiReleaseJarFile;
import com.lifemcserver.bytecodeversionanalyzer.crosscompile.VersionedJarFileStream;
import com.lifemcserver.bytecodeversionanalyzer.extensions.StopCodeExecution;
import com.lifemcserver.bytecodeversionanalyzer.extensions.StopCodeExecutionUncaughtExceptionHandler;
import com.lifemcserver.bytecodeversionanalyzer.extensions.lazies.Lazy;
import com.lifemcserver.bytecodeversionanalyzer.extensions.lazies.MutableLazy;
import com.lifemcserver.bytecodeversionanalyzer.extensions.timing.Timing;
import com.lifemcserver.bytecodeversionanalyzer.logging.Logging;
import com.lifemcserver.bytecodeversionanalyzer.logging.Verbosity;
import com.lifemcserver.bytecodeversionanalyzer.utils.ProgressTracker;
import com.lifemcserver.bytecodeversionanalyzer.utils.ProgressTrackerBuilder;
import com.lifemcserver.bytecodeversionanalyzer.utils.StreamUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.*;
import java.math.RoundingMode;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Year;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
public final class BytecodeVersionAnalyzer {
    /**
     * Uncaught exception handler for the program.
     */
    public static final Lazy<Thread.UncaughtExceptionHandler> uncaughtExceptionHandler = new Lazy<>(StopCodeExecutionUncaughtExceptionHandler::new);
    /**
     * The decimal format for two numbers at most after the first dot in non-integer/long numbers.
     */
    private static final Lazy<DecimalFormat> twoNumbersAfterDotFormat = new Lazy<>(BytecodeVersionAnalyzer::getTwoNumbersAfterDotFormat);
    /**
     * Map that holds and caches arguments for direct key value access.
     */
    private static final Lazy<Map<String, ArgumentAction>> argumentMap = new Lazy<>(HashMap::new);
    /**
     * Determines how many threads to use if {@link BytecodeVersionAnalyzer#parallel} is true.
     */
    private static final MutableLazy<Integer> threads = new MutableLazy<>(() -> Runtime.getRuntime().availableProcessors());
    /**
     * Determines if classes should be processed on parallel or not.
     */
    private static boolean parallel = true;
    /**
     * Determines if {@link InputStream InputStreams} should be buffered or not.
     */
    private static boolean buffered = true;
    /**
     * The properties file of the project.
     */
    private static Properties projectProperties;
    /**
     * The parsed model object for pom file, for getting the version and other information.
     */
    private static Model model;
    /**
     * The Maven groupId of the project.
     */
    private static final Lazy<String> groupId = new Lazy<>(() -> getProjectProperties().getProperty("groupId"));
    /**
     * The Maven artifactId of the project.
     */
    private static final Lazy<String> artifactId = new Lazy<>(() -> getProjectProperties().getProperty("artifactId"));
    /**
     * Indicates that POM is loaded or not. Does not indicate a successful load, though.
     */
    private static boolean loadedPom;
    /**
     * Determines if the execution time should be printed or not.
     * Note that execution time will still be timed, just not printed if false.
     * <p>
     * This because argument parsing is also affected by the timing.
     */
    private static boolean timed = true;
    /**
     * Determines if the entry processing should be tracked.
     * <p>
     * This will keep you updated with the processed entry count if you are processing
     * a big JAR.
     * <p>
     * It will print the processed entry count every 500ms, preventing unresponsive
     * look of program when doing processing.
     */
    private static boolean track = true;
    /**
     * Determines if {@link ForkJoinPool} constructor should be invoked with async parameter true.
     */
    private static boolean async = true;
    /**
     * Determines if we should check Java class files for {@link Constants#JAVA_CLASS_FILE_IDENTIFIER} or not.
     */
    private static boolean verify = true;
    /**
     * Keeps track of the total spawned thread count. (not currently live threads)
     */
    private static long threadCount;
    /**
     * The verbosity which fail the execution (returning a non-zero exit code) if messages on that verbosity
     * or higher are printed.
     */
    private static Verbosity failVerbosity = Verbosity.ERROR;
    /**
     * The boolean that holds the failed state. Will become true if messages on {@link BytecodeVersionAnalyzer#failVerbosity}
     * or higher are printed during execution.
     */
    private static boolean failed;

    /**
     * Private constructor to avoid creation of new instances.
     */
    private BytecodeVersionAnalyzer() {
        throw new UnsupportedOperationException(Constants.STATIC_ONLY_CLASS);
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
    public static final String getThreadSuffix(final LongSupplier incrementAndGet,
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
        thread.setUncaughtExceptionHandler(uncaughtExceptionHandler.get());
    }

    /**
     * Sets up the current thread (name and uncaught exception handler), and sets the uncaught exception handlers
     * for other threads. This must be called before any thread creation except the current thread to be effective.
     */
    private static final void setupThreads() {
        setupThread(Thread.currentThread());
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler.get());
    }

    /**
     * Called by the JVM when the program is double clicked or used from the command line.
     * This a CLI program, so it should instantly shutdown if double clicked.
     *
     * @param args The arguments array passed by the JVM to indicate command line arguments.
     */
    public static final void main(final String[] args) {
        final Timing timing = new Timing();
        timing.start();

        setupThreads();
        runCli(args);

        timing.stop();

        if (timed) {
            Logging.info("Took " + timing);
        }

        if (failed) {
            // This will stop the code execution and VM will automatically set
            // the exit code to a non-zero code. We are not using System#exit since that will exit entire VM.
            // (it will shutdown the JUnit engine when run from tests, for an example.)
            throw StopCodeExecution.INSTANCE;
        }
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
                Logging.warning();
                Logging.warning("couldn't load POM file; some things will not display");
                Logging.warning();
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
        final String path = result.getArchivePath();
        final boolean printedAtLeastOneVersion = result.hasPrintedAtLeastOneVersion();

        // Check file path
        final File archiveFile = new File(path);

        if (!archiveFile.exists()) { // Does not exist
            if (!printedAtLeastOneVersion) {
                Logging.error("archive file does not exist: " + path + " (tip: use quotes if the path contains space)");
            }
            return;
        }

        if (!archiveFile.isFile()) { // Is a directory
            Logging.error("can't process a directory: " + path);
            return;
        }

        if (!archiveFile.canRead()) { // Can't read
            Logging.error("can't read the file: " + path);
        }

        final Map<String, ClassFileVersion> classes;

        try (final JarFile jar = newJarFile(path)) {
            // Process the jar
            printJarManifestInformation(jar);
            classes = getClassFileVersionsInJar(jar);
        } catch (final IOException e) {
            throw handleError(e);
        }

        analyze(classes, new HashMap<>(), result.getPrintIfBelow(), result.getPrintIfAbove(), result.getFilter());
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
            Logging.warning("the jar has no manifest");
        } else {
            if (isMultiRelease(manifest)) {
                Logging.info("the jar is a multi release jar");
            } else {
                Logging.info("the jar is not a multi release jar");
                if (jar.getJarEntry("META-INF/versions") != null) {
                    Logging.warning("the jar is not a multi release jar; but it has META-INF/versions. consider adding Multi-Release: true to MANIFEST.MF for clarification and/or launch your program with -Djdk.util.jar.enableMultiRelease=force, otherwise they will have no effect on runtime! (note that the latter does not resolve this warning)");
                }
            }

            if (isSealed(manifest)) {
                Logging.info("the jar is sealed globally");
            } else {
                Logging.info("the jar is not sealed globally");
            }

            if (isSigned(manifest)) {
                Logging.info("the jar is signed from manifest");
            } else {
                Logging.info("the jar is not signed from manifest");
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
        return Boolean.parseBoolean(manifest.getMainAttributes().getValue("Multi-Release"));
    }

    /**
     * Checks if the given {@link Manifest} contains a Sealed: true definition.
     * This method does not guarantee the JAR being treated as such from JVM or {@link JarFile} APIs in any way.
     *
     * @param manifest The {@link Manifest} to check for Sealed: true definition.
     * @return True if the given {@link Manifest} contains a Sealed: true definition.
     */
    private static final boolean isSealed(final Manifest manifest) {
        return Boolean.parseBoolean(manifest.getMainAttributes().getValue("Sealed"));
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
                Logging.warning("class " + clazz + " uses preview language features (" + version + ", Java " + version.toJavaVersion() + " with preview language features)");
            }

            if (printIfBelow != null && printIfBelow.isHigherThan(version) && (filter == null || clazz.contains(filter))) {
                Logging.warning("class " + clazz + " uses version " + version.toStringAddJavaVersionToo() + " which is below specified (" + printIfBelow + ", Java " + printIfBelow.toJavaVersion() + ")");
            }

            if (printIfAbove != null && version.isHigherThan(printIfAbove) && (filter == null || clazz.contains(filter))) {
                Logging.warning("class " + clazz + " uses version " + version.toStringAddJavaVersionToo() + " which is above specified (" + printIfAbove + ", Java " + printIfAbove.toJavaVersion() + ")");
            }
        }

        final int total = classes.size();

        for (final Map.Entry<ClassFileVersion, Integer> entry : counter.entrySet()) {
            final ClassFileVersion version = entry.getKey();
            final int usages = entry.getValue();

            final double percent = percentOf(usages, total);

            Logging.info(usages + " out of total " + total + " classes (%" + formatDouble(percent) + ") use " + version.toStringAddJavaVersionToo() + " class file version");
        }
    }

    /**
     * Loads the CLI arguments.
     */
    private static final void loadArguments() {
        argumentMap.get().clear();

        addArgument("print-if-below", true, (arg, result) -> result.setPrintIfBelow(parseClassFileVersionFromUserInput(arg)));
        addArgument("print-if-above", true, (arg, result) -> result.setPrintIfAbove(parseClassFileVersionFromUserInput(arg)));

        addArgument("filter", true, (arg, result) -> result.setFilter(arg));

        addArgument("loadPom", BytecodeVersionAnalyzer::getModel);

        addArgument("parallel", true, arg -> parallel = parseBooleanFromUserInput(true, arg, parallel));

        addArgument("debug", () -> {
            Logging.setVerbosity(Verbosity.DEBUG);
        });

        addArgument("timing", true, arg -> timed = parseBooleanFromUserInput(true, arg, timed));
        addArgument("track", true, arg -> track = parseBooleanFromUserInput(true, arg, track));

        addArgument("async", true, arg -> async = parseBooleanFromUserInput(true, arg, async));
        //noinspection MagicCharacter
        addArgument("threads", true, arg -> threads.set(parseIntegerFromUserInput(arg.charAt(arg.length() - 1) != 'C', arg, () -> arg.charAt(arg.length() - 1) == 'C' ? (int) (parseDoubleFromUserInput(true, arg.substring(0, arg.length() - 1), () -> 1) * threads.get()) : threads.get())));

        addArgument("buffered", true, arg -> buffered = parseBooleanFromUserInput(true, arg, buffered));
        addArgument("verify", true, arg -> verify = parseBooleanFromUserInput(true, arg, verify));

        addArgument("verbosity", true, arg -> Logging.setVerbosity(Verbosity.fromString(true, arg, Logging.getVerbosity())));
        addArgument("fail-verbosity", true, arg -> {
            failVerbosity = Verbosity.fromString(true, arg, failVerbosity);

            Verbosity.clearAllHooks();
            failVerbosity.onPrintRecursive(message -> failed = true);
        });

        addArgument("help", (arg, result) -> {
            if (result.hasPrintedAtLeastOneVersion()) {
                return;
            }

            result.setHasPrintedAtLeastOneVersion(true);
            displayHelp(true);
        });
    }

    /**
     * Parses a boolean from user input, optionally printing errors and returning a default value.
     *
     * @param printErrors  Pass true to make method print an error before returning the default value if the input is invalid.
     * @param input        The input to try to parse as a boolean.
     * @param defaultValue The default value to return when the input is invalid.
     * @return The parsed boolean from input, or default value if the input is invalid.
     */
    private static final boolean parseBooleanFromUserInput(final boolean printErrors, final String input, final boolean defaultValue) {
        final boolean cond = Boolean.parseBoolean(input);
        final boolean valid = cond || "false".equals(input);

        if (valid) {
            return cond;
        }

        if (printErrors) {
            Logging.error("invalid boolean value, expected true or false, got \"" + input + "\"");
        }

        return defaultValue;
    }

    /**
     * Parses a integer from user input, optionally printing errors and returning a default value.
     *
     * @param printErrors  Pass true to make method print an error before returning the default value if the input is invalid.
     * @param input        The input to try to parse as a integer.
     * @param defaultValue The default value to return when the input is invalid.
     * @return The parsed integer from input, or default value if the input is invalid.
     */
    private static final int parseIntegerFromUserInput(final boolean printErrors, final String input, final IntSupplier defaultValue) {
        try {
            return Integer.parseInt(input);
        } catch (final NumberFormatException e) {
            if (printErrors) {
                Logging.error("invalid integer value (" + input + "): " + e.getMessage());
            }
            return defaultValue.getAsInt();
        }
    }

    /**
     * Parses a double from user input, optionally printing errors and returning a default value.
     *
     * @param printErrors  Pass true to make method print an error before returning the default value if the input is invalid.
     * @param input        The input to try to parse as a double.
     * @param defaultValue The default value to return when the input is invalid.
     * @return The parsed double from input, or default value if the input is invalid.
     */
    private static final double parseDoubleFromUserInput(final boolean printErrors, final String input, final DoubleSupplier defaultValue) {
        try {
            return Double.parseDouble(input);
        } catch (final NumberFormatException e) {
            if (printErrors) {
                Logging.error("invalid double value (" + input + "): " + e.getMessage());
            }
            return defaultValue.getAsDouble();
        }
    }


    /**
     * Ensures that the given number is in a specific range.
     * 
     * @param name The name to use on error messages if printErrors is true (i.e thread count).
     * @param printErrors Whether to print errors or not.
     * @param value The value to check for range.
     * @param minimum The minimum value to allow.
     * @param maximum The maximum value to allow.
     * 
     * @return The number, returning the minimum value if it is less than minimum and returning the maximum value
     * if above the maximum.
     */
    public static final int limitRange(final String name, final boolean printErrors, final int value, final int minimum, final int maximum) {
        if (value < minimum) {
            if (printErrors) {
                Logging.error(name + " not in required range, expected [" + minimum + ".." + maximum + "], got " + value + ", using minimum possible value " + minimum);
            }
            return minimum;
        }
        if (value > maximum) {
            if (printErrors) {
                Logging.error(name + " not in required range, expected [" + minimum + ".." + maximum + "], got " + value + ", using maximum possible value " + maximum);
            }
            return maximum;
        }
        return value;
    }


    /**
     * Ensures that the given number is in a specific range.
     * 
     * @param name The name to use on error messages if printErrors is true (i.e thread count).
     * @param printErrors Whether to print errors or not.
     * @param value The value to check for range.
     * @param minimum The minimum value to allow.
     * @param maximum The maximum value to allow.
     * @param defaultValue The default value to return if the number is not in range.
     * 
     * @return The number, or the default value if it is not in range (the number maybe same as default value though) 
     */
    public static final int limitRange(final String name, final boolean printErrors, final int value, final int minimum, final int maximum, final int defaultValue) {
        return limitRange(name, printErrors, value, minimum, maximum, () -> defaultValue);
    }

    /**
     * Ensures that the given number is in a specific range.
     * 
     * @param name The name to use on error messages if printErrors is true (i.e thread count).
     * @param printErrors Whether to print errors or not.
     * @param value The value to check for range.
     * @param minimum The minimum value to allow.
     * @param maximum The maximum value to allow.
     * @param defaultValue The default value to return if the number is not in range.
     * 
     * @return The number, or the default value if it is not in range (the number maybe same as default value though) 
     */
    public static final int limitRange(final String name, final boolean printErrors, final int value, final int minimum, final int maximum, final IntSupplier defaultValue) {
        if (value < minimum || value > maximum) {
            if (printErrors) {
                Logging.error(name + " not in required range, expected [" + minimum + ".." + maximum + "], got " + value + ", falling back to default of " + defaultValue);
            }
            return defaultValue.getAsInt();
        }
        return value;
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
    public static final void addArgument(final String name, final Consumer<String> action) {
        addArgument(name, (arg, result) -> action.accept(arg));
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
    private static final void addArgument(final String name, final boolean hasValue, final Consumer<String> action) {
        addArgument(name, hasValue, (arg, result) -> action.accept(arg));
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
        argumentMap.get().put("--" + name, hasValue ? new ArgumentAction(new ArgumentAction(action)) : new ArgumentAction(action));
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
        String startOfArgumentValue = null;

        final ArgumentParseResult result = new ArgumentParseResult();

        for (int i = 0; i < argsLength; i++) {
            final String arg = args[i];

            if (!arg.endsWith(".class")) {
                final ArgumentAction argumentAction = argumentMap.get().get(arg);

                if (argumentAction != null) {
                    if (!argumentAction.hasNext()) {
                        // Run the action directly, has no value
                        argumentAction.run(arg, result);
                    } else {
                        // Run the action after the value is encountered if the value is given
                        if (i != argsLength - 1) {
                            startOfArgumentValue = arg;
                        } else {
                            // No value given, we are on the last argument
                            // Invalid values will give a different error
                            Logging.error("value missing for argument \"" + arg + "\"");
                        }
                    }
                    continue;
                } else if (startOfArgumentValue == null && arg.startsWith("--") && !arg.contains(".")) {
                    Logging.error("unrecognized argument: " + arg);
                }

                if (startOfArgumentValue != null) {
                    // argumentAction will be null here, can't use that.
                    final ArgumentAction previous = argumentMap.get().get(startOfArgumentValue);

                    startOfArgumentValue = null;
                    previous./*getNext().*/run(arg, result);
                } else if (!arg.startsWith("--")) { // probably an unrecognized argument
                    archivePath.append(arg);

                    if (i < argsLength - 1) {
                        archivePath.append(" ");
                    }
                }
            } else {
                result.setHasPrintedAtLeastOneVersion(true);

                // Display version of a single class file
                printSingleClassFile(arg);
            }
        }

        result.setArchivePath(archivePath.toString().trim());

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
            Logging.error("invalid class file version: " + e.getMessage());
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
            Logging.error("file does not exist: " + arg);
            return;
        }

        if (!file.isFile()) {
            Logging.error("can't process a directory: " + arg);
            return;
        }

        if (!file.canRead()) {
            Logging.error("can't read the file: " + arg);
            return;
        }

        final ClassFileVersion version;

        try {
            version = getClassFileVersion(file);
        } catch (final FileNotFoundException e) {
            throw handleError(e); // We checked that the file exists.. How?
        } catch (final IOException e) {
            Logging.error("error when processing class: " + e.getMessage());
            return;
        }

        Logging.info(version.toStringAddJavaVersionToo());
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
     * - Then call JarEntryVersionConsumer#shouldSkip to determine if an entry should
     * be skipped. This for skipping the compiler generated synthetic classes.
     *
     * @param path The path of the JAR file.
     * @return A new JAR file object with Multi-Release JAR support.
     */
    private static final JarFile newJarFile(final String path) throws IOException {
        return MultiReleaseJarFile.newJarFile(path);
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
        return (current / total) * Constants.ONE_HUNDRED;
    }

    /**
     * Returns class file versions in a {@link JarFile} archive.
     * <p>
     * Construct the {@link JarFile} instance with {@link BytecodeVersionAnalyzer#newJarFile(String)} method
     * for Multi-Release JAR support.
     * <p>
     * This method uses versionedStream method if available (Java 10+), skips META-INF/versions, refreshes
     * entry (to get versioned one on Java 9+) and uses JarEntryVersionConsumer#shouldSkip.
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
        final Stream<JarEntry> stream = VersionedJarFileStream.stream(jar);

        // create processor
        try (final ZipFile zip = new ZipFile(jar.getName())) {
            final JarEntryVersionConsumer jarEntryVersionConsumer = new JarEntryVersionConsumer(jar, zip);

            // create a process tracker to track progress of entries processed
            final ProgressTracker tracker = new ProgressTrackerBuilder()
                .interval(500L, TimeUnit.MILLISECONDS) // every 500ms
                .current(jarEntryVersionConsumer.entries::size) // current supplier
                .notify((current, total) -> Logging.info("Processing entries... (" + current + (total != -1 ? ("/" + total + ")") : ")")))
                .build();

            if (track) {
                tracker.start();
            }

            final Timing timer = new Timing();
            timer.start();

            // start processing - process will be tracked with above code.
            final Consumer<Stream<JarEntry>> processEntries = entriesStream -> entriesStream.forEach(jarEntryVersionConsumer);
            StreamUtils.parallel(stream, processEntries, threads.get(), async, "Bytecode version analyzer entry processor thread");

            timer.stop();
            tracker.stop(); // process is completed, stop tracking it.

            if (track) {
                Logging.info("Processed " + jarEntryVersionConsumer.entries.size() + " entries in " + timer);
            }

            return jarEntryVersionConsumer.classes;
        } catch (final IOException e) {
            throw handleError(e);
        }
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
        try (final FileInputStream fis = new FileInputStream(file);
             final InputStream in = StreamUtils.buffered(fis)) {
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
    static final ClassFileVersion getClassFileVersion(final InputStream in) throws IOException {
        try (final DataInputStream data = new DataInputStream(in)) {
            verifyJavaClassFileIdentifier(data);

            // Note: Do not reverse the order, minor comes first.
            final int minor = Constants.HEXADECIMAL_ALL_BITS_ONE & data.readShort();
            final int major = Constants.HEXADECIMAL_ALL_BITS_ONE & data.readShort();

            return ClassFileVersion.get(major, minor);
        }
    }

    /**
     * Verifies a class by checking if the provided {@link DataInput} representing class
     * contains the {@link Constants#JAVA_CLASS_FILE_IDENTIFIER}.
     *
     * @param data The {@link DataInput} representing the class to verify.
     */
    private static final void verifyJavaClassFileIdentifier(final DataInput data) throws IOException {
        // Identifier for Java class files
        if (data.readInt() != Constants.JAVA_CLASS_FILE_IDENTIFIER && verify) {
            throw new IOException("invalid Java class");
        }
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
     * Tries to get the resource by the given name from the JAR as an InputStream.
     * 
     * @return The {@link InputStream} for the given name or null if not exists/can't find.
     */
    private static final InputStream getResource(final String pathOrName) {
        final Thread currentThread = Thread.currentThread();
        InputStream stream = currentThread.getContextClassLoader().getResourceAsStream(pathOrName);
        if (stream == null) {
            try {
                final Path path = Paths.get(Objects.requireNonNull(currentThread.getContextClassLoader().getResource(".")).toURI());
                final File file = new File(path.getParent().getParent().toString(), pathOrName);

                if (file.exists()) {
                    stream = new FileInputStream(file);
                }
            } catch (final FileNotFoundException | URISyntaxException e) {
                return null;
            }
        }
        return stream;
    }

    /**
     * Gets the properties of project.
     * 
     * @return Properties of the project.
     */
    private static final Properties getProjectProperties() {
        if (projectProperties != null) {
            return projectProperties;
        }

        projectProperties = new Properties();

        try {
            projectProperties.load(getResource("project.properties"));
        } catch (final IOException e) {
            throw handleError(e);
        }

        return projectProperties;
    }

    /**
     * Loads the pom.xml file either from the content root (works when running/building from IDE)
     * or the META-INF/maven directory, using the {@link BytecodeVersionAnalyzer#groupId} and {@link BytecodeVersionAnalyzer#artifactId}.
     */
    private static final void loadPom() {
        InputStream pomStream = getResource("META-INF/maven/" + groupId.get() + "/" + artifactId.get() + "/pom.xml");

        if (pomStream == null) {
            pomStream = getResource("pom.xml");
        }

        try (final InputStream stream = pomStream) {
            final MavenXpp3Reader reader = new MavenXpp3Reader();

            try (final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(StreamUtils.buffered(stream), StandardCharsets.UTF_8))) {
                model = reader.read(bufferedReader);
            }
        } catch (final IOException | XmlPullParserException e) {
            throw handleError(e);
        }
    }

    /**
     * Displays the CLI help message, only printing standard arguments.
     */
    private static final void displayHelp() {
        displayHelp(false);
    }

    /**
     * Displays the CLI help message; optionally printing all arguments.
     */
    private static final void displayHelp(final boolean explicitHelp) {
        printHeader();
        Logging.info("Usage:");
        Logging.info();
        Logging.info("Analyze bytecode version of class files from the provided JAR file:");
        Logging.info("[--print-if-below <major.minor or Java version>] [--print-if-above <major.minor or Java version>] <paths-to-jars>");
        Logging.info();
        Logging.info("Show bytecode version of a class file:");
        Logging.info("[--print-if-below <major.minor or Java version>] [--print-if-above <major.minor or Java version>] <paths-to-class-files>");
        Logging.info();
        Logging.info("Additional arguments:");
        Logging.info();
        Logging.info("--filter <filter text>: Filters the --print-if-above and --print-if-below messages to those that contain the specified text only.");
        Logging.info("i.e great for filtering to your package only, or a specific library's package only.");
        Logging.info();
        if (!explicitHelp) {
            Logging.info("--help: Displays all arguments including experimental and non-standard ones.");
        } else {
            Logging.info("All arguments:");
            loadArguments();
            argumentMap.get().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new))
                .forEach((key, value) -> {
                        if (value.hasNext()) {
                            Logging.info(key + " <value>");
                        } else {
                            Logging.info(key);
                        }
                    }
                );
        }
        Logging.info();
    }

    /**
     * Prints the CLI header.
     */
    private static final void printHeader() {
        Logging.info();
        Logging.info("Bytecode Version Analyzer v" + getVersion());
        Logging.info("Created by Mustafa Öncel @ LifeMC. © " + Year.now().getValue() + " GNU General Public License v3.0");
        Logging.info();
        Logging.info("Source code can be found at: " + getSourceUrl());
        Logging.info();
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
    public static final RuntimeException handleError(final Throwable error) {
        Logging.error();
        Logging.error("An error occurred when running Bytecode Version Analyzer.");
        Logging.error("Please report the error below by creating a new issue on " + getIssuesUrl());
        Logging.error();

        error.printStackTrace();

        // Can be thrown to stop the code execution.
        // Allows for using i.e non null final variables initialized inside catch blocks.
        return StopCodeExecution.INSTANCE;
    }

    /**
     * Returns true if the processing is done buffered, i.e. {@link InputStream InputStreams} will be
     * {@link BufferedInputStream BufferedInputStreams}.
     * 
     * @return True if the processing is done buffered.
     */
    public static final boolean isBuffered() {
        return buffered;
    }

    /**
     * Returns true if the parallel processing is enabled.
     * 
     * Does not guarantee it will be done on more than one thread, though.
     * 
     * @return True if the parallel processing is enabled.
     * @see BytecodeVersionAnalyzer#isEffectivelyParallel()
     */
    public static final boolean isParallel() {
        return parallel;
    }

    /**
     * Checks if entry processing will run on parallel with more than one threads.
     * If this method returns true, thread-safe collections should be used instead of normal ones.
     *
     * @return True if the entry processing will run on parallel with more than one threads.
     */
    static final boolean isEffectivelyParallel() {
        return parallel && threads.getSyncIfNull() > 1;
    }

    /**
     * Returns a new {@link HashMap}, concurrent or not, depending on {@link BytecodeVersionAnalyzer#isEffectivelyParallel()}.
     *
     * @param <K> The type of keys for {@link HashMap}.
     * @param <V> The type of values for {@link HashSet}.
     * @return A new {@link HashMap}, concurrent or not, depending on {@link BytecodeVersionAnalyzer#isEffectivelyParallel()}.
     */
    static final <K, V> Map<K, V> newHashMap() {
        return isEffectivelyParallel() ? new ConcurrentHashMap<>() : new HashMap<>();
    }

    /**
     * Returns a new {@link HashSet}, concurrent or not, depending on {@link BytecodeVersionAnalyzer#isEffectivelyParallel()}.
     *
     * @param <E> The type of elements for {@link HashSet}.
     * @return A new {@link HashSet}, concurrent or not, depending on {@link BytecodeVersionAnalyzer#isEffectivelyParallel()}.
     */
    public static final <E> Set<E> newHashSet() {
        return isEffectivelyParallel() ? ConcurrentHashMap.newKeySet() : new HashSet<>();
    }
}
