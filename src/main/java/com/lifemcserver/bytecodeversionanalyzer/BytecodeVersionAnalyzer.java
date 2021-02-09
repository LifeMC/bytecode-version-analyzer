package com.lifemcserver.bytecodeversionanalyzer;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Year;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A Java Bytecode Version Analyzer CLI program.
 *
 * Can show the Java class file version target of the given class,
 * classes from a JAR or ZIP archive.
 *
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

    // TODO Read these from pom.xml too
    /**
     * The URL for the source code of the program.
     */
    private static final String sourceUrl = "https://github.com/LifeMC/bytecode-version-analyzer/";
    /**
     * The page where issues for the project can be created.
     */
    private static final String issuesUrl = sourceUrl + "issues/";

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
     * Called by the JVM when the program is double clicked or used from the command line.
     * This a CLI program, so it should instantly close if double clicked.
     *
     * @param args The arguments array passed by the JVM to indicate command line arguments.
     */
    public static final void main(final String[] args) {
        if (args == null || args.length < 1 || args[0].length() < 1) {
            // Display the help and quit, called with no/invalid arguments; maybe just double clicking
            displayHelp();
            return;
        }

        ClassFileVersion printIfBelow = null;
        ClassFileVersion printIfAbove = null;

        StringBuilder archivePath = new StringBuilder();

        boolean printedAtLeastOneVersion = false;
        String startOfArgumentValue = null;

        final int argsLength = args.length;

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

            archivePath.append(arg);

            if (i < argsLength - 1)
                archivePath.append(" ");
        }

        // OK, we are processing an archive
        final ZipFile archive;
        final String path = archivePath.toString();

        try {
            // ZipFile instead of JarFile to be more compatible
            if (!new File(path).exists()) {
                if (!printedAtLeastOneVersion) {
                    error("archive file does not exist: " + path);
                }
                return;
            }

            archive = new ZipFile(path);
        } catch (final IOException e) {
            throw handleError(e);
        }

        // Process the archive
        final Map<String, ClassFileVersion> classes = getClassFileVersionsInArchive(archive);
        final Map<ClassFileVersion, Integer> counter = new HashMap<>();

        for (final Map.Entry<String, ClassFileVersion> entry : classes.entrySet()) {
            final String clazz = entry.getKey();
            final ClassFileVersion version = entry.getValue();

            final int amount = counter.getOrDefault(version, 0);

            counter.remove(version);
            counter.put(version, amount + 1);

            if (printIfBelow != null) {
                if (printIfBelow.isHigherThan(version)) {
                    warning("class " + clazz + " uses version " + version.toStringAddJavaVersionToo() + " which is below specified (" + printIfBelow + ", Java " + printIfBelow.toJavaVersion() + ")");
                }
            }

            if (printIfAbove != null) {
                if (version.isHigherThan(printIfAbove)) {
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

    private static final class EnumerationIterator<T> implements Iterator<T> {
        private final Enumeration<T> enumeration;

        private EnumerationIterator(final Enumeration<T> enumeration) {
            this.enumeration = enumeration;
        }

        @Override
        public final boolean hasNext() {
            return enumeration.hasMoreElements();
        }

        @Override
        public final T next() {
            return enumeration.nextElement();
        }
    }

    private static final class EnumerationIterable<T> implements Iterable<T> {
        private final Enumeration<T> enumeration;

        public EnumerationIterable(final Enumeration<T> enumeration) {
            this.enumeration = enumeration;
        }

        @Override
        public final Iterator<T> iterator() {
            return new EnumerationIterator<>(enumeration);
        }
    }

    private static final Map<String, ClassFileVersion> getClassFileVersionsInArchive(final ZipFile archive) {
        final Map<String, ClassFileVersion> classes = new HashMap<>();

        for (final ZipEntry entry : new EnumerationIterable<>(archive.entries())) {
            if (!entry.isDirectory()) {
                if (entry.getName().endsWith(".class")) {
                    final InputStream in;

                    try {
                        in = archive.getInputStream(entry);
                    } catch (final IOException e) {
                        throw handleError(e);
                    }

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
                        warning("duplicate entry: " + entry.getName());
                    }
                }
            }
        }

        return classes;
    }

    private static final ClassFileVersion getClassFileVersion(final File file) throws IOException {
        return getClassFileVersion(new FileInputStream(file));
    }

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

        return new ClassFileVersion(major, minor);
    }

    private static final class ClassFileVersion {
        private static final Pattern dot = Pattern.compile(".", Pattern.LITERAL);

        private final int major;
        private final int minor;

        private ClassFileVersion(final int major, final int minor) {
            this.major = major;
            this.minor = minor;
        }

        /**
         * Checks if this class file version is higher than the given one.
         *
         * @param other The other class file version.
         *
         * @return True if this class file version is higher either in major or minor than
         * the given one, false otherwise. (same or lower)
         */
        private final boolean isHigherThan(final ClassFileVersion other) {
            if (major > other.major)
                return true;
            return major == other.major && minor > other.minor;
        }

        private final int toJavaVersion() {
            return major - 44;
        }

        @Override
        public final String toString() {
            return major + "." + minor;
        }

        private final String toStringAddJavaVersionToo() {
            return toString() + " (Java " + toJavaVersion() + ")";
        }

        private static final ClassFileVersion fromString(final String string) {
            final String[] splitByDot = dot.split(string);

            if (splitByDot.length != 2) {
                throw new IllegalArgumentException("not in major.minor format: " + string);
            }

            final int major = Integer.parseInt(splitByDot[0]);
            final int minor = Integer.parseInt(splitByDot[1]);

            return new ClassFileVersion(major, minor);
        }

        @Override
        public final boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof ClassFileVersion)) return false;

            final ClassFileVersion version = (ClassFileVersion) o;
            return major == version.major && minor == version.minor;
        }

        @Override
        public final int hashCode() {
            return Objects.hash(major, minor);
        }
    }

    /**
     * Gets the version from the attached maven pom file to the JAR.
     * Returns "Unknown-Version" if it can't get the version.
     *
     * @return The version from the attached maven pom file to the JAR.
     */
    private static final String getVersion() {
        InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream("META-INF/maven/" + groupId + "/" + artifactId + "/pom.xml");

        if (stream == null) {
            try {
                final Path path = Paths.get(Objects.requireNonNull(Thread.currentThread().getContextClassLoader().getResource(".")).toURI());
                final File file = new File(path.getParent().getParent().toString(), "pom.xml");

                if (file.exists()) {
                    stream = new FileInputStream(file);
                }
            } catch (final FileNotFoundException | URISyntaxException e) {
                return "Unknown-Version";
            }

            if (stream == null)
                return "Unknown-Version";
        }

        final MavenXpp3Reader reader = new MavenXpp3Reader();
        final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));

        final Model model;

        try {
            model = reader.read(bufferedReader);
        } catch (final IOException | XmlPullParserException e) {
            return "Unknown-Version";
        }

        return model.getVersion();
    }

    /**
     * Displays the CLI help message.
     */
    private static final void displayHelp() {
        info();
        info("Bytecode Version Analyzer v" + getVersion());
        info("Created by Mustafa Öncel @ LifeMC. © " + Year.now().getValue() + " GNU General Public License v3.0");
        info();
        info("Source code can be found at: " + sourceUrl);
        info();
        info("Usage:");
        info();
        info("Analyze bytecode version of class files from the provided JAR file:");
        info("[--print-if-below <major.minor>] [--print-if-above <major.minor>] <paths-to-jars>");
        info();
        info("Show bytecode version of a class file:");
        info("[--print-if-below <major.minor>] [--print-if-above <major.minor>] <paths-to-class-files>");
        info();
    }

    /**
     * A {@link RuntimeException} that is only thrown for the sole purpose of stopping the code execution.
     *
     * It has a null message, null cause, suppression and stack trace disabled. Constructor is private to
     * promote usage of the singleton instance. Since it has no stack, creating new instances are unnecessary.
     */
    private static final class StopCodeExecution extends RuntimeException {
        public static final StopCodeExecution INSTANCE = new StopCodeExecution();

        private StopCodeExecution() {
            super(null, null, false, false);
        }
    }

    /**
     * Handles an abnormal error case, pointing the user to report it, printing the error
     * and returning an empty exception that can be thrown to stop the code execution.
     *
     * The type of the exception returned can be changed, as such, the return type is
     * just {@link RuntimeException}.
     *
     * @param error The error the handle and print.
     *
     * @return An empty exception that can be thrown to stop the code execution.
     */
    private static final RuntimeException handleError(final Throwable error) {
        error();
        error("An error occurred when running Bytecode Version Analyzer.");
        error("Please report the error below by creating a new issue on " + issuesUrl);
        error();

        // TODO Use a logger
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
}
