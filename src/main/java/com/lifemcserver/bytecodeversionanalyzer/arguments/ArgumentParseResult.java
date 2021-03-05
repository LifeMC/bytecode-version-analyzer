package com.lifemcserver.bytecodeversionanalyzer.arguments;

import com.lifemcserver.bytecodeversionanalyzer.ClassFileVersion;

/**
 * Represents the parse result of arguments.
 */
public final class ArgumentParseResult {
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
    private boolean hasPrintedAtLeastOneVersion;
    /**
     * The filter argument.
     * Used for filtering warnings printed by printIfAbove and printIfBelow.
     */
    private String filter;

    /**
     * Constructs an empty {@link ArgumentParseResult}.
     * The fields should be initialized later if this constructor is used.
     */
    public ArgumentParseResult() {
        /* implicit super-call */
    }

    /**
     * Constructs a argument parse result. The fields can be changed later.
     * To create an empty one with default field values (null, false, etc.), use the no-arg constructor.
     *
     * @param printIfBelow             The printIfBelow argument.
     * @param printIfAbove             The printIfAbove argument.
     * @param archivePath              The {@link String} of archive path.
     * @param hasPrintedAtLeastOneVersion True if printed a version of single class file.
     * @param filter                   The filter used to filter warning messages printed by printIfBelow and printIfAbove.
     */
    public ArgumentParseResult(final ClassFileVersion printIfBelow, final ClassFileVersion printIfAbove, final String archivePath,
                               final boolean hasPrintedAtLeastOneVersion, final String filter) {
        this.printIfBelow = printIfBelow;
        this.printIfAbove = printIfAbove;

        this.archivePath = archivePath;
        this.hasPrintedAtLeastOneVersion = hasPrintedAtLeastOneVersion;

        this.filter = filter;
    }

    /**
     * Gets {@link ArgumentParseResult#printIfBelow}.
     *
     * @return {@link ArgumentParseResult#printIfBelow}
     */
    public final ClassFileVersion getPrintIfBelow() {
        return printIfBelow;
    }

    /**
     * Sets {@link ArgumentParseResult#printIfBelow}.
     *
     * @param printIfBelow The new {@link ArgumentParseResult#printIfBelow}
     */
    public final void setPrintIfBelow(final ClassFileVersion printIfBelow) {
        this.printIfBelow = printIfBelow;
    }

    /**
     * Gets {@link ArgumentParseResult#printIfAbove}.
     *
     * @return {@link ArgumentParseResult#printIfAbove}
     */
    public final ClassFileVersion getPrintIfAbove() {
        return printIfAbove;
    }

    /**
     * Sets {@link ArgumentParseResult#printIfAbove}.
     *
     * @param printIfAbove The new {@link ArgumentParseResult#printIfAbove}
     */
    public final void setPrintIfAbove(final ClassFileVersion printIfAbove) {
        this.printIfAbove = printIfAbove;
    }

    /**
     * Gets {@link ArgumentParseResult#archivePath}.
     *
     * @return {@link ArgumentParseResult#archivePath}
     */
    public final String getArchivePath() {
        return archivePath;
    }

    /**
     * Sets {@link ArgumentParseResult#archivePath}.
     *
     * @param archivePath The new {@link ArgumentParseResult#archivePath}
     */
    public final void setArchivePath(final String archivePath) {
        this.archivePath = archivePath;
    }

    /**
     * Gets {@link ArgumentParseResult#hasPrintedAtLeastOneVersion}.
     *
     * @return {@link ArgumentParseResult#hasPrintedAtLeastOneVersion}
     */
    public final boolean getHasPrintedAtLeastOneVersion() {
        return hasPrintedAtLeastOneVersion;
    }

    /**
     * Sets {@link ArgumentParseResult#hasPrintedAtLeastOneVersion}.
     *
     * @param hasPrintedAtLeastOneVersion The new {@link ArgumentParseResult#hasPrintedAtLeastOneVersion}
     */
    public final void setHasPrintedAtLeastOneVersion(final boolean hasPrintedAtLeastOneVersion) {
        this.hasPrintedAtLeastOneVersion = hasPrintedAtLeastOneVersion;
    }

    /**
     * Gets {@link ArgumentParseResult#filter}.
     *
     * @return {@link ArgumentParseResult#filter}
     */
    public final String getFilter() {
        return filter;
    }

    /**
     * Sets {@link ArgumentParseResult#filter}.
     *
     * @param filter The new {@link ArgumentParseResult#filter}
     */
    public final void setFilter(final String filter) {
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
            ", printedAtLeastOneVersion=" + hasPrintedAtLeastOneVersion +
            ", filter='" + filter + '\'' +
            '}';
    }
}
