/**
 * A Java Bytecode Version Analyzer CLI program.
 * <p>
 * Can show the Java class file version target of the given class,
 * classes from a JAR or ZIP archive.
 * <p>
 * It can display a metric like % classes use Java 8 or such,
 * or warn legacy/bleeding edge ones, too.
 */
module com.lifemcserver.bytecodeversionanalyzer {
    requires maven.model;
    requires plexus.utils;
}
