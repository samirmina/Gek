package com.gek.inference;

/**
 * Optional streaming/partial STT capability. Implementations emit interim partial transcripts while
 * audio is being fed and a final transcript when the session completes. This is the foundation for
 * chat-style voice typing (separate from live-caption/overlay transcription pipelines).
 */
public interface StreamingSpeechToTextEngine extends SpeechToTextEngine {
    /**
     * Start a streaming transcription session. The returned session accepts audio (or manages its own
     * capture, implementation-defined) and reports partial/final results to {@code listener}.
     *
     * @param languageHint language hint ("auto"/null for auto-detect), same semantics as one-shot transcribe
     * @param listener     receives partial/final/error/closed callbacks
     */
    StreamingSttSession startSession(String languageHint, StreamingTranscriptionListener listener);

    /** @return true when a warm/streaming backend is available for low-latency partials. */
    default boolean streamingAvailable() { return false; }
}
