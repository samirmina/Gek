package com.gek.audio;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class RecordingTtsEngine implements TtsEngine {
    public record Utterance(String text, String lang) {}
    private final List<Utterance> utterances = new CopyOnWriteArrayList<>();
    private volatile boolean stopped = false;
    @Override public void speak(String text, String lang) { utterances.add(new Utterance(text, lang)); }
    @Override public void stop() { stopped = true; }
    @Override public boolean isSupported() { return true; }
    public List<Utterance> utterances() { return List.copyOf(utterances); }
    public boolean stopped() { return stopped; }
}
