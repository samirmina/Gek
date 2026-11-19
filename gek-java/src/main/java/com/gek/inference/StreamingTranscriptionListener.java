package com.gek.inference;

/**
 * Receives incremental (partial) and finalized transcription updates from a streaming STT session.
 * Implementations must be thread-safe; callbacks may arrive on a background thread.
 */
public interface StreamingTranscriptionListener {
    /** Called with the latest partial (non-final) hypothesis for the current utterance/window. */
    default void onPartial(String text) {}

    /** Called when a stable/final transcript segment is produced. */
    default void onFinal(String text) {}

    /** Called on session-level error (session may terminate). */
    default void onError(Throwable error) {}

    /** Called when the session ends (normally or after stop). */
    default void onClosed() {}
}
