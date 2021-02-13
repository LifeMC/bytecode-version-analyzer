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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
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
     * Dollar literal pattern that mathces a dollar sign.
     */
    private static final Pattern dollarPattern = Pattern.compile("$", Pattern.LITERAL);
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

    static {
        // In-block to guarantee order of execution. Tools such as IntelliJ can re-arrange field order.
        runtimeVersion = findRuntimeVersion();
        jarFileMultiReleaseConstructor = findJarFileMultiReleaseConstructor();
    }

    /**
     * Private constructor avoid creating new instances.
     */
    private BytecodeVersionAnalyzer() {
        throw new UnsupportedOperationException("Static only class");
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

        runCli(args);

        timing.finish();
        info("Took " + timing);
    }

    /**
     * Checks arguments and processes them.
     *
     * @param args The arguments to check and process.
     */
    private static final void runCli(final String[] args) {
        if (args == null || args.length < 1 || args[0].length() < 1) {
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
        // Parse arguments
        final ArgumentParseResult result = parseArguments(args, args.length, new StringBuilder());

        // Initialize parse result variables
        final StringBuilder archivePath = result.archivePath;
        final boolean printedAtLeastOneVersion = result.printedAtLeastOneVersion;

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
        try {
            jar.close();
        } catch (final IOException e) {
            throw handleError(e);
        }

        final Map<ClassFileVersion, Integer> counter = new HashMap<>();

        analyze(classes, counter, result.printIfBelow, result.printIfAbove, result.filter);
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
     * Parses the arguments and returns the parse result.
     *
     * @param args        The arguments.
     * @param argsLength  The length of arguments.
     * @param archivePath A string builder to append archive path to.
     * @return The parse result of the given arguments.
     */
    private static final ArgumentParseResult parseArguments(final String[] args, final int argsLength, final StringBuilder archivePath) {
        ClassFileVersion printIfBelow = null;
        ClassFileVersion printIfAbove = null;

        boolean printedAtLeastOneVersion = false;
        String filter = null;

        String startOfArgumentValue = null;

        for (int i = 0; i < argsLength; i++) {
            final String arg = args[i];

            if (arg.endsWith(".class")) {
                printedAtLeastOneVersion = true;

                // Display version of a single class file
                printSingleClassFile(arg);

                continue;
            }

            switch (arg) {
                case "--print-if-below":
                    startOfArgumentValue = "printIfBelow";
                    continue;
                case "--print-if-above":
                    startOfArgumentValue = "printIfAbove";
                    continue;
                case "--filter":
                    startOfArgumentValue = "filter";
                    continue;
                case "--debug":
                    debug = true;
                    info("note: debug mode is enabled");

                    continue;
                default:
                    if (startOfArgumentValue == null) {
                        if (arg.startsWith("--") && !arg.contains(".")) {
                            error("unrecognized argument: " + arg + ", skipping...");
                            continue;
                        } else if (debug) {
                            info("not handling unrecognized argument " + arg + " not starting with -- (maybe a class or jar name?)");
                        }

                        break;
                    } else if (debug) {
                        info("will parse value of " + startOfArgumentValue + " argument in next iteration");
                    }
            }

            if (startOfArgumentValue != null) {
                switch (startOfArgumentValue) {
                    case "printIfBelow":
                        startOfArgumentValue = null;
                        printIfBelow = parseClassFileVersionFromUserInput(arg);

                        continue;
                    case "printIfAbove":
                        startOfArgumentValue = null;
                        printIfAbove = parseClassFileVersionFromUserInput(arg);

                        continue;
                    case "filter":
                        startOfArgumentValue = null;
                        filter = arg;

                        continue;
                    default: // startOfArgumentValue set to a non-null value but it is not handled above.
                        throw new IllegalStateException("argument value of argument " + startOfArgumentValue + " (" + arg + ") is not correctly handled");
                }
            }

            archivePath.append(arg);

            if (i < argsLength - 1)
                archivePath.append(" ");
        }

        return new ArgumentParseResult(printIfBelow, printIfAbove, archivePath, printedAtLeastOneVersion, filter);
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
     * - Then call {@link BytecodeVersionAnalyzer#shouldSkip(JarEntry, JarEntry, JarFile)} to determine if an entry should
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
        final DecimalFormat format = new DecimalFormat("##.##");
        format.setRoundingMode(RoundingMode.DOWN);

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
     * entry (to get versioned one on Java 9+) and uses {@link BytecodeVersionAnalyzer#shouldSkip(JarEntry, JarEntry, JarFile)}.
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
        final Map<String, ClassFileVersion> classes = new HashMap<>();

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

        final List<String> entriesByPath = new ArrayList<>();

        for (JarEntry entry : stream.toArray(JarEntry[]::new)) {
            if (!entry.getName().endsWith(".class")) {
                if (entriesByPath.contains(entry.getName())) {
                    warning("duplicate entry: " + entry.getName());
                } else {
                    entriesByPath.add(entry.getName());
                }
            }
            if (!entry.isDirectory() && entry.getName().endsWith(".class") && !entry.getName().contains("META-INF/versions")) {
                final JarEntry oldEntry = entry;
                entry = jar.getJarEntry(entry.getName());

                if (shouldSkip(entry, oldEntry, jar)) {
                    continue;
                }

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

        return classes;
    }

    private static final boolean shouldSkip(final JarEntry entry, final JarEntry oldEntry, final JarFile jar) {
        // Skip the non-versioned compiler generated classes

        // JarEntry or ZipEntry does not implement a equals method, but they implement a hashCode method.
        // So we use it to check equality.
        if (entry.getName().contains("$") && entry.hashCode() == oldEntry.hashCode()) { // Compiler generated class (not necessarily a fully generated class, maybe just a nested class) and is not versioned
            final String[] nestedClassSplit = dollarPattern.split(entry.getName());

            boolean compilerGeneratedNonSourceClass = true;
            try {
                // If it is a fully generated class, compiler formats it like ClassName$<id>.class, where <id> is a number, i.e. 1
                Integer.parseInt(nestedClassSplit[1].replace(".class", ""));
            } catch (final NumberFormatException e) {
                // It is a sub-class
                compilerGeneratedNonSourceClass = false;
            }

            if (compilerGeneratedNonSourceClass) { // A synthetic accessor class, or an anonymous/lambda class.
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
        if (magic != JAVA_CLASS_FILE_IDENTIFIER) {
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
     * Represents the parse result of arguments.
     */
    private static final class ArgumentParseResult {
        /**
         * The printIfBelow argument.
         */
        private final ClassFileVersion printIfBelow;
        /**
         * The printIfAbove argument.
         */
        private final ClassFileVersion printIfAbove;

        /**
         * The archivePath {@link StringBuilder}.
         */
        private final StringBuilder archivePath;
        /**
         * Tracks if we printed class file version of a single class already.
         * If false, we will try to interpret argument as JAR instead.
         */
        private final boolean printedAtLeastOneVersion;
        /**
         * The filter argument.
         * Used for filtering warnings printed by printIfAbove and printIfBelow.
         */
        private final String filter;

        /**
         * Constructs a argument parse result.
         *
         * @param printIfBelow             The printIfBelow argument.
         * @param printIfAbove             The printIfAbove argument.
         * @param archivePath              The {@link StringBuilder} of archive path.
         * @param printedAtLeastOneVersion True if printed a version of single class file.
         * @param filter                   The filter used to filter warning messages printed by printIfBelow and printIfAbove.
         */
        private ArgumentParseResult(final ClassFileVersion printIfBelow, final ClassFileVersion printIfAbove, final StringBuilder archivePath,
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
         * calls both {@link Timing#start()} and {@link Timing#finish()}.
         */
        private final void reset() {
            start();
            finish();
        }

        /**
         * Stops the timing.
         */
        private final void finish() {
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
            super(Long.MAX_VALUE, Spliterator.ORDERED);

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
