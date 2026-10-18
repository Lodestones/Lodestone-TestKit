package gg.lode.testkit.bot;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

/**
 * A headless client logged into a test server in offline mode. Messages are kept as plain text (colours and
 * formatting dropped, translatable text shown as its key).
 */
public interface FakePlayer extends AutoCloseable {

    String name();

    boolean isConnected();

    /** Why the server dropped the bot, or null while connected. */
    String disconnectReason();

    /** Runs a command as the player; the leading slash is optional. */
    void command(String command);

    void chat(String message);

    /** Every chat, system and action-bar message received so far, oldest first. */
    List<String> messages();

    /**
     * Waits for a matching message received since the last {@link #command} or {@link #chat} call (or since
     * login if neither was sent yet).
     *
     * @throws AssertionError on timeout, listing what did arrive
     */
    String awaitMessage(Predicate<String> match, Duration timeout);

    /** Index to pass to {@link #messagesSince(int)} for reading only what a step produced. */
    int mark();

    List<String> messagesSince(int mark);

    /**
     * Waits for the server to open a container (menu/GUI) whose title matches.
     *
     * @throws AssertionError on timeout
     */
    OpenedContainer awaitContainer(Predicate<String> title, Duration timeout);

    /** The open container, or null. */
    OpenedContainer openContainer();

    /** Left-clicks a slot of the open container. */
    void click(int slot);

    void closeContainer();

    @Override
    void close();
}
