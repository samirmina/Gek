package com.gek.inference;

import java.io.Closeable;

/**
 * Handle to a running streaming speech-to-text session (warm backend producing partial/final results).
 * Chat voice-typing and live-captioning pipelines consume these events independently.
 */
public interface StreamingSttSession extends Closeable {
    /** Request graceful stop/finalization. Implementations should emit any final text before closing. */
    void stop();

    /** True while the session is active/able to produce results. */
    boolean isRunning();

    /** Best-effort aggregated final transcript produced so far (may be empty). */
    String finalTranscript();

    @Override
    void close();
}
