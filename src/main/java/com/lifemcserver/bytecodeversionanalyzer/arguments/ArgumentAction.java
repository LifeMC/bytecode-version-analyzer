package com.lifemcserver.bytecodeversionanalyzer.arguments;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Represents execution of an argument.
 */
public final class ArgumentAction {
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
    public ArgumentAction(final BiConsumer<String, ArgumentParseResult> action) {
        this.action = action;
        next = null;
    }

    /**
     * Creates a new {@link ArgumentAction}.
     *
     * @param next The next {@link ArgumentAction}.
     */
    public ArgumentAction(final ArgumentAction next) {
        this.next = next;
        action = null;
    }

    /**
     * Runs the action.
     *
     * @param arg    The argument to be passed into action.
     * @param result The mutable result to be processed by action.
     */
    public final void run(final String arg, final ArgumentParseResult result) {
        final BiConsumer<String, ArgumentParseResult> act;

        if (action != null) {
            act = action;
        } else {
            // To avoid recursion
            ArgumentAction lastAction = next;

            while (Objects.requireNonNull(lastAction).next != null) {
                lastAction = lastAction.next;
            }

            act = lastAction.action;
        }

        Objects.requireNonNull(act).accept(arg, result);
    }

    /**
     * Checks if this action has a next {@link ArgumentAction}.
     *
     * @return True if this action has a next {@link ArgumentAction}.
     */
    public final boolean hasNext() {
        return next != null;
    }

    /**
     * Gets the next {@link ArgumentAction}.
     *
     * @return The next {@link ArgumentAction}.
     */
    public final ArgumentAction getNext() {
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
