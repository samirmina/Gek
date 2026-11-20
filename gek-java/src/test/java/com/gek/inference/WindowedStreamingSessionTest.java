package com.gek.inference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic behavioral tests for {@link WindowedStreamingSession}: partial emissions, finalization
 * on stop, and listener lifecycle. Uses a fast in-memory fake STT engine (no model/process IO beyond
 * temp WAV writing) to exercise the rolling-window streaming contract.
 */
class WindowedStreamingSessionTest {

    @TempDir Path tmp;

    static class FakeStt implements SpeechToTextEngine {
        volatile String text = "";
        volatile String resultLang = null; // if null, defaults from hint for backward-compatible tests
        final AtomicInteger transcribeCalls = new AtomicInteger();
        @Override public CompletableFuture<Void> warmup() { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<TranscriptionResult> transcribe(Path wavPath, String languageHint) {
            transcribeCalls.incrementAndGet();
            assertTrue(wavPath != null && java.nio.file.Files.exists(wavPath), "decode reads a temp wav snapshot");
            String lang = resultLang != null ? resultLang : (languageHint == null ? "en" : languageHint);
            return CompletableFuture.completedFuture(new TranscriptionResult(text, lang, 1.0, 0.1));
        }
        @Override public void close() {}
    }

    private static float[] audio(float seconds) {
        int n = Math.max(1, (int) (seconds * 16000f));
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = (float) Math.sin(i * 0.05);
        return a;
    }

    @Test
    void stopEmitsFinalTextAndCloses() throws Exception {
        FakeStt stt = new FakeStt();
        stt.text = "hello world";
        List<String> partials = new CopyOnWriteArrayList<>();
        AtomicReference<String> finalText = new AtomicReference<>();
        CountDownLatch closed = new CountDownLatch(1);
        StreamingTranscriptionListener l = new StreamingTranscriptionListener() {
            @Override public void onPartial(String text) { partials.add(text); }
            @Override public void onFinal(String text) { finalText.set(text); }
            @Override public void onClosed() { closed.countDown(); }
        };

        WindowedStreamingSession session = new WindowedStreamingSession(stt, "en", l, tmp);
        session.acceptSamples(audio(0.6f));
        session.stop();

        assertTrue(closed.await(5, TimeUnit.SECONDS), "session must close and emit final");
        assertEquals("hello world", finalText.get());
        assertEquals("hello world", session.finalTranscript());
        assertFalse(session.isRunning());
        assertTrue(stt.transcribeCalls.get() >= 1, "finalization decodes buffered audio");
        session.close(); // idempotent
    }

    @Test
    void emitsPartialWhileAudioArrives() throws Exception {
        FakeStt stt = new FakeStt();
        stt.text = "live partial";
        List<String> partials = new CopyOnWriteArrayList<>();
        AtomicReference<String> finalText = new AtomicReference<>();
        CountDownLatch gotPartial = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        StreamingTranscriptionListener l = new StreamingTranscriptionListener() {
            @Override public void onPartial(String text) { partials.add(text); gotPartial.countDown(); }
            @Override public void onFinal(String text) { finalText.set(text); }
            @Override public void onClosed() { closed.countDown(); }
        };

        WindowedStreamingSession session = new WindowedStreamingSession(stt, "en", l, tmp);
        // >= MIN_PARTIAL_SECONDS (0.35s) so scheduler tick can emit a partial
        session.acceptSamples(audio(0.6f));

        assertTrue(gotPartial.await(3, TimeUnit.SECONDS),
                "streaming session must emit at least one chat-style partial while active");
        assertTrue(partials.stream().anyMatch("live partial"::equals), "partial carries evolving text");

        session.stop();
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertEquals("live partial", finalText.get(), "final transcript equals last decoded text");
    }

    @Test
    void emptyAudioFinalizesToEmpty() throws Exception {
        FakeStt stt = new FakeStt();
        stt.text = "should not be used";
        AtomicReference<String> finalText = new AtomicReference<>("unset");
        CountDownLatch closed = new CountDownLatch(1);
        StreamingTranscriptionListener l = new StreamingTranscriptionListener() {
            @Override public void onFinal(String text) { finalText.set(text); }
            @Override public void onClosed() { closed.countDown(); }
        };

        WindowedStreamingSession session = new WindowedStreamingSession(stt, "en", l, tmp);
        session.stop();

        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertEquals("", finalText.get(), "no buffered speech -> empty final");
        assertEquals(0, stt.transcribeCalls.get(), "no decode without audio");
    }

    @Test
    void partialOnlyEmittedWhenTextChanges() throws Exception {
        FakeStt stt = new FakeStt();
        stt.text = "same";
        List<String> partials = new CopyOnWriteArrayList<>();
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        StreamingTranscriptionListener l = new StreamingTranscriptionListener() {
            @Override public void onPartial(String text) { partials.add(text); first.countDown(); }
            @Override public void onClosed() { closed.countDown(); }
        };

        WindowedStreamingSession session = new WindowedStreamingSession(stt, "en", l, tmp);
        session.acceptSamples(audio(0.6f));
        assertTrue(first.await(3, TimeUnit.SECONDS));
        // allow a couple more scheduler ticks while text stays the same
        Thread.sleep(1600);
        session.stop();
        assertTrue(closed.await(5, TimeUnit.SECONDS));

        long sameCount = partials.stream().filter("same"::equals).count();
        assertEquals(1, sameCount, "unchanged transcript must not spam duplicate partials");
    }

    @Test
    void stopIsIdempotentAndClosesOnce() throws Exception {
        FakeStt stt = new FakeStt();
        stt.text = "done";
        AtomicInteger finals = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        CountDownLatch closed = new CountDownLatch(1);
        StreamingTranscriptionListener l = new StreamingTranscriptionListener() {
            @Override public void onFinal(String text) { finals.incrementAndGet(); }
            @Override public void onClosed() { closes.incrementAndGet(); closed.countDown(); }
        };

        WindowedStreamingSession session = new WindowedStreamingSession(stt, "en", l, tmp);
        session.acceptSamples(audio(0.5f));
        session.stop();
        session.stop();
        session.close();
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        // give any stray callbacks time to surface
        Thread.sleep(300);
        assertEquals(1, finals.get(), "exactly one final emission");
        assertEquals(1, closes.get(), "exactly one close notification");
    }

    @Test
    void emitsDetectedLanguageOnPartialAndFinal() throws Exception {
        // Regression for Arabic/plain non-English: session must propagate STT-detected language to listeners
        // (not drop it), so chat/live can route/fallback correctly.
        FakeStt stt = new FakeStt();
        stt.text = "مرحبا";
        stt.resultLang = "ar";
        List<String> partialLangs = new CopyOnWriteArrayList<>();
        AtomicReference<String> finalText = new AtomicReference<>();
        AtomicReference<String> finalLang = new AtomicReference<>("unset");
        CountDownLatch gotPartial = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        StreamingTranscriptionListener l = new StreamingTranscriptionListener() {
            @Override public void onPartial(String text) { /* 1-arg compat */ }
            @Override public void onPartial(String text, String language) {
                if (text != null && !text.isBlank()) { partialLangs.add(language); gotPartial.countDown(); }
            }
            @Override public void onFinal(String text) { finalText.set(text); }
            @Override public void onFinal(String text, String language) { finalText.set(text); finalLang.set(language); }
            @Override public void onClosed() { closed.countDown(); }
        };

        WindowedStreamingSession session = new WindowedStreamingSession(stt, "auto", l, tmp);
        session.acceptSamples(audio(0.6f));

        assertTrue(gotPartial.await(3, TimeUnit.SECONDS), "expected language-aware partial");
        assertTrue(partialLangs.stream().anyMatch("ar"::equals), "partial must carry detected Arabic language");
        session.stop();
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertEquals("مرحبا", finalText.get());
        assertEquals("ar", finalLang.get(), "final must carry detected Arabic language");
    }

    @Test
    void normalizesAutoDetectedLanguageToNullForCallbacks() throws Exception {
        // Engines may report auto/detected markers; session should normalize to null (unknown/auto) for consumers.
        FakeStt stt = new FakeStt();
        stt.text = "hello";
        stt.resultLang = "auto-detect";
        AtomicReference<String> finalLang = new AtomicReference<>("unset");
        CountDownLatch closed = new CountDownLatch(1);
        StreamingTranscriptionListener l = new StreamingTranscriptionListener() {
            @Override public void onFinal(String text, String language) { finalLang.set(language); }
            @Override public void onClosed() { closed.countDown(); }
        };

        WindowedStreamingSession session = new WindowedStreamingSession(stt, "auto", l, tmp);
        session.acceptSamples(audio(0.6f));
        session.stop();
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertEquals("hello", session.finalTranscript());
        assertNull(finalLang.get(), "auto-detect marker must be normalized to null");
    }
}
