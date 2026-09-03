package me.lovkar.errands;

/**
 * Implemented onto mc_talking's {@code GeminiWsClient} by GeminiWsClientMixin: what
 * the client's CURRENT connection has done so far. Both values are reset every time
 * a connection completes its setup, so a reconnected session starts from zero.
 * Read by {@link SessionReaper}.
 */
public interface SessionActivity {

    /** Audio or a finished turn came out of the current connection. */
    boolean colonist_errands$spoke();

    /** When the current connection completed its setup (wall clock, ms) - 0 before the first setup. */
    long colonist_errands$setupAt();
}
