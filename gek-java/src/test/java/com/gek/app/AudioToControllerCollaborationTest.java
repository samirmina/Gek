package com.gek.app;

import com.gek.audio.AudioChunk;
import com.gek.audio.SpeechSegmenter;
import com.gek.audio.WavWriter;
import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import com.gek.db.HistoryRepository;
import com.gek.db.TranslationRecord;
import com.gek.inference.SpeechToTextEngine;
import com.gek.inference.TranscriptionResult;
import com.gek.inference.TranslationEngine;
import com.gek.inject.InputAction;
import com.gek.inject.InputExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In-process component/integration calibration: synthetic audio -> SpeechSegmenter -> controller
 * processing with fake STT/translation/input, persisting to real SQLite history.
 */
class AudioToControllerCollaborationTest {
    @TempDir Path tmp;
    private String prev;

    @BeforeEach void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("resources/data"));
        Files.createDirectories(tmp.resolve("resources/models"));
        Files.createDirectories(tmp.resolve("resources/data/synonyms"));
        prev = System.getProperty("gek.projectRoot");
        System.setProperty("gek.projectRoot", tmp.toString());
    }
    @AfterEach void tearDown() {
        if (prev == null) System.clearProperty("gek.projectRoot"); else System.setProperty("gek.projectRoot", prev);
    }

    static class SeqStt implements SpeechToTextEngine {
        final AtomicInteger i = new AtomicInteger();
        final String[] texts;
        SeqStt(String... texts) { this.texts = texts; }
        @Override public CompletableFuture<Void> warmup() { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<TranscriptionResult> transcribe(Path wavPath, String languageHint) {
            int idx = Math.min(i.getAndIncrement(), Math.max(0, texts.length - 1));
            String t = texts.length == 0 ? "" : texts[idx];
            return CompletableFuture.completedFuture(new TranscriptionResult(t, "en", 0.9, 0.2));
        }
        @Override public void close() {}
    }
    static class PassTr implements TranslationEngine {
        @Override public CompletableFuture<String> translate(String text, String sourceLang, String targetLang) {
            return CompletableFuture.completedFuture(text);
        }
        @Override public void close() {}
    }
    static class CollectInput implements InputExecutor {
        final List<String> typed = new CopyOnWriteArrayList<>();
        @Override public boolean execute(InputAction action) {
            if (action != null && action.kind() == InputAction.Kind.TYPE_TEXT && action.text() != null) typed.add(action.text());
            return true;
        }
        @Override public String name() { return "collect"; }
    }

    @Test
    void segmentedSpeechDrivesDictationAndPersists() throws Exception {
        // two speech bursts separated by silence -> >=2 segments
        float[] burst = sine(4800, 300, 0.35);
        float[] gap = new float[9600];
        float[] all = concat(concat(concat(burst, gap), burst), gap);

        List<float[]> segments = new ArrayList<>();
        SpeechSegmenter seg = new SpeechSegmenter(16000f, 0.01f, 120, 0.1f, 4.0f, 0.0f);
        seg.setOnSegment(segments::add);
        feed(seg, all);
        seg.flush();
        assertTrue(segments.size() >= 1, "expected at least one segment");

        AppPaths paths = AppPaths.resolve();
        AppSettings s = AppSettings.defaults();
        s.source_language = "en"; s.target_language = "en"; s.dictation_insert_mode = "transcript";
        CollectInput input = new CollectInput();
        try (HistoryRepository h = new HistoryRepository(paths.dbPath());
             DictationController c = new DictationController(paths, s,
                     new SeqStt("alpha", "beta", "gamma"), new PassTr(), h, input)) {
            for (float[] smp : segments) c.processForTest(smp);
            assertEquals(segments.size(), input.typed.size());
            assertEquals(segments.size(), h.count());
            List<TranslationRecord> recent = h.recent(10);
            assertFalse(recent.isEmpty());
            for (TranslationRecord r : recent) {
                assertEquals("dictation", r.sourceName());
                assertEquals("mic", r.audioSource());
                assertEquals("en", r.originalLang());
            }
        }
    }

    @Test
    void segmentedSpeechDrivesLiveTranslateFlow() throws Exception {
        float[] burst = sine(4800, 250, 0.3);
        float[] gap = new float[9600];
        float[] all = concat(concat(burst, gap), gap);

        List<float[]> segments = new ArrayList<>();
        SpeechSegmenter seg = new SpeechSegmenter(16000f, 0.01f, 120, 0.1f, 4.0f, 0.0f);
        seg.setOnSegment(segments::add);
        feed(seg, all);
        seg.flush();
        assertFalse(segments.isEmpty());

        AppPaths paths = AppPaths.resolve();
        AppSettings s = AppSettings.defaults();
        s.source_language = "en"; s.target_language = "en"; s.tts_enabled = false;
        List<String[]> heard = new CopyOnWriteArrayList<>();
        try (HistoryRepository h = new HistoryRepository(paths.dbPath());
             LiveTranslateController c = new LiveTranslateController(paths, s,
                     new SeqStt("hello from segment"), new PassTr(), h)) {
            c.setTranscriptListener((o,t,m,src) -> heard.add(new String[]{o,t,m,src}));
            for (float[] smp : segments) c.processForTest(smp, "loopback-test");
            assertFalse(heard.isEmpty(), "expected transcript emission");
            for (String[] e : heard) {
                assertEquals("en→en", e[2]);
                assertEquals("loopback-test", e[3]);
                assertNotNull(e[1]);
                assertFalse(e[1].isBlank());
            }
            assertTrue(h.count() >= 1);
            TranslationRecord r = h.recent(1).get(0);
            assertEquals("live", r.sourceName());
            assertEquals("loopback-test", r.audioSource());
        }
    }

    @Test
    void wavRoundTripFeedsSegmentationSmoke() throws Exception {
        float[] speech = sine(8000, 220, 0.3);
        float[] silence = new float[8000];
        float[] all = concat(speech, silence);
        Path wav = tmp.resolve("pipe.wav");
        WavWriter.writeFloatMono16k(wav, all);
        com.gek.audio.WavReader.AudioData audio = com.gek.audio.WavReader.readMono(wav, 16000);
        assertEquals(all.length, audio.samples().length);

        List<float[]> segments = new ArrayList<>();
        SpeechSegmenter seg = new SpeechSegmenter(16000f, 0.01f, 120, 0.1f, 4.0f, 0.0f);
        seg.setOnSegment(segments::add);
        feed(seg, audio.samples());
        seg.flush();
        assertFalse(segments.isEmpty());
    }

    private static void feed(SpeechSegmenter seg, float[] all) {
        int chunk = 4000;
        for (int off = 0; off < all.length; off += chunk) {
            int n = Math.min(chunk, all.length - off);
            float[] c = new float[n];
            System.arraycopy(all, off, c, 0, n);
            seg.accept(new AudioChunk("file", c, 0));
        }
    }

    private static float[] sine(int n, double hz, double amp) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = (float) (amp * Math.sin(2 * Math.PI * hz * i / 16000.0));
        return a;
    }

    private static float[] concat(float[] a, float[] b) {
        float[] r = new float[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
