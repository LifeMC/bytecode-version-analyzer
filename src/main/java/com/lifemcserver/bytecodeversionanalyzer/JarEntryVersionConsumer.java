package com.lifemcserver.bytecodeversionanalyzer;

import com.lifemcserver.bytecodeversionanalyzer.extensions.lazies.Lazy;
import com.lifemcserver.bytecodeversionanalyzer.logging.Logging;
import com.lifemcserver.bytecodeversionanalyzer.utils.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The consumer, that processes {@link JarEntry} objects in a {@link JarFile} and stores the class file information.
 * You should first create a new instance of this class as {@link JarFile} as an argument, then supply this to {@link Stream#forEach(Consumer)} method.
 * <p>
 * And then you can get the class file information by accessing {@link JarEntryVersionConsumer#classes}.
 */
final class JarEntryVersionConsumer implements Consumer<JarEntry> {
    /**
     * Matches the literal pattern of text ".class"
     */
    private static final Lazy<Matcher> dotClassPatternMatcher = new Lazy<>(() -> Pattern.compile(".class", Pattern.LITERAL).matcher(""));
    /**
     * The map storing entry (class) names and their versions.
     */
    final Map<String, ClassFileVersion> classes = BytecodeVersionAnalyzer.newHashMap();
    /**
     * A set which stores all entry names, to check for any duplicate.
     */
    final Set<String> entries = BytecodeVersionAnalyzer.newHashSet();
    /**
     * The {@link JarFile} of the {@link JarEntry} to consume.
     */
    private final JarFile jar;
    /**
     * The {@link ZipFile} of the {@link ZipEntry} to use when processing.
     */
    private final ZipFile zip;

    /**
     * Creates a new {@link JarEntryVersionConsumer}.
     *
     * @param jar The {@link JarFile} that will own {@link JarEntry JarEntries}.
     * @param zip The {@link ZipFile} that will own {@link ZipEntry ZipEntries}.
     */
    JarEntryVersionConsumer(final JarFile jar, final ZipFile zip) {
        this.jar = jar;
        this.zip = zip;
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
            Logging.warning("duplicate entry: " + name);
        }
    }

    /**
     * Checks for signing extensions on the given file name and prints an information message
     * if the file extension looks like a signing extension.
     *
     * @param fileName The file name to check for signing extensions.
     */
    private static final void detectSigningFile(final String fileName) {
        if (fileName.startsWith("SIG-") || fileName.endsWith(".RSA") || fileName.endsWith(".DSA") || fileName.endsWith(".SF") || fileName.endsWith(".EC")) {
            Logging.info("found signing file: " + fileName);
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
        try (final InputStream in = StreamUtils.buffered(jar.getInputStream(entry))) {
            classes.put(entryName, BytecodeVersionAnalyzer.getClassFileVersion(in));
        } catch (final IOException e) {
            Logging.error("error when processing class: " + e.getMessage());
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
     * Returns a {@link Matcher} from given {@link Matcher} for given input.
     * <p>
     * This will return a new {@link Matcher} if {@link BytecodeVersionAnalyzer#isEffectivelyParallel()} returns true,
     * resets the existing one (the one that is given as parameter) otherwise.
     *
     * @param matcher The {@link Matcher}.
     * @param input   The input to create or reset the {@link Matcher} for.
     * @return The {@link Matcher} with the given input.
     */
    private static final Matcher matcher(final Matcher matcher, final CharSequence input) {
        return BytecodeVersionAnalyzer.isEffectivelyParallel() ? matcher.pattern().matcher(input) : matcher.reset(input);
    }

    /**
     * Determines if the given {@link JarEntry} should be skipped.
     * <p>
     * {@link JarEntry JarEntries} will be skipped when they are non-versioned compiler generated classes, i.e synthetic classes.
     *
     * @return Whatever the given {@link JarEntry} should be skipped or not.
     */
    private static final boolean shouldSkip(final JarEntry entry, final String entryName, final JarEntry oldEntry, final JarFile jar, final ZipFile zip) {
        // Skip the non-versioned compiler generated classes

        // JarEntry or ZipEntry does not implement a equals method, but they implement a hashCode method.
        // So we use it to check equality.
        if (entry.hashCode() == oldEntry.hashCode() && entryName.contains("$") && oldEntry.hashCode() == zip.getEntry(oldEntry.getName()).hashCode()) { // Compiler generated class (not necessarily a fully generated class, maybe just a nested class) and is not versioned
            final String[] nestedClassSplit = entryName.split("\\$"); // Note: This will not impact performance, String#split has a fast path for single character arguments.

            // If it is a fully generated class, compiler formats it like ClassName$<id>.class, where <id> is a number, i.e. 1
            if (isDigit(matcher(dotClassPatternMatcher.getSyncIfNull(), nestedClassSplit[1]).replaceAll(""))) { // A synthetic accessor class, or an anonymous/lambda class.
                final String baseClassName = nestedClassSplit[0] + ".class";

                final JarEntry baseClassJarEntry = jar.getJarEntry(baseClassName);
                final ZipEntry baseClassEntry = zip.getEntry(baseClassName);

                if (baseClassJarEntry != null && baseClassJarEntry.hashCode() != baseClassEntry.hashCode()) { // Base class is found and versioned
                    Logging.debug(() -> "skipping " + entryName + " (non-versioned compiler generated class whose base class is found and versioned)");
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Processes the given entry.
     *
     * @param entry The {@link JarEntry} to process.
     */
    @Override
    public final void accept(final JarEntry entry) {
        final String name = entry.getName();
        duplicateEntryCheck(entries, name);

        if (!entry.isDirectory()) {
            detectSigningFile(name);

            if (name.endsWith(".class") && !name.contains("META-INF/versions")) {
                final JarEntry newEntry = jar.getJarEntry(name);

                if (shouldSkip(newEntry, name, entry, jar, zip)) {
                    return;
                }

                processClass(classes, jar, newEntry, name);
            }
        }
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
            ", zip=" + zip +
            '}';
    }
}
