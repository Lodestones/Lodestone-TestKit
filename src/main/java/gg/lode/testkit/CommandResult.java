package gg.lode.testkit;

import java.util.List;

/**
 * What the server console printed in response to one command, with log prefixes and colours stripped.
 *
 * @param command the command that was run
 * @param lines   the output lines
 */
public record CommandResult(String command, List<String> lines) {

    public String text() {
        return String.join("\n", lines);
    }

    public boolean contains(String text) {
        return lines.stream().anyMatch(l -> l.contains(text));
    }

    /** True when the server didn't recognise the command at all. */
    public boolean isUnknown() {
        return lines.stream().anyMatch(l -> l.startsWith("Unknown or incomplete command") || l.startsWith("Unknown command"));
    }

    /** Fails the test if the command is unknown or printed an internal error. */
    public CommandResult assertOk() {
        if (isUnknown()) throw new AssertionError("Command '" + command + "' is not registered:\n" + text());
        if (contains("An internal error occurred") || contains("Exception")) {
            throw new AssertionError("Command '" + command + "' errored:\n" + text());
        }
        return this;
    }

    public CommandResult assertContains(String expected) {
        if (!contains(expected)) {
            throw new AssertionError("Command '" + command + "' output lacks '" + expected + "':\n" + text());
        }
        return this;
    }
}
