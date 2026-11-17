package com.gek.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Test/fake audio source that emits predefined chunks on start.
 */
public class SequenceAudioSource implements AudioSource {
    private final String name;
    private final List<AudioChunk> chunks;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Consumer<AudioChunk> consumer;

    public SequenceAudioSource(String name, List<AudioChunk> chunks) {
        this.name = name == null ? "sequence" : name;
        this.chunks = new ArrayList<>(chunks == null ? List.of() : chunks);
    }

    public static SequenceAudioSource ofSamples(String name, String chunkSource, float[]... segments) {
        List<AudioChunk> list = new ArrayList<>();
        long t = System.currentTimeMillis();
        for (float[] s : segments) list.add(new AudioChunk(chunkSource == null ? name : chunkSource, s, t++));
        return new SequenceAudioSource(name, list);
    }

    @Override public void setConsumer(Consumer<AudioChunk> consumer) { this.consumer = consumer; }
    @Override public synchronized void start() {
        if (running.getAndSet(true)) return;
        Consumer<AudioChunk> c = consumer;
        for (AudioChunk ch : chunks) { if (c != null) c.accept(ch); }
        running.set(false);
    }
    @Override public void stop() { running.set(false); }
    @Override public boolean isRunning() { return running.get(); }
    @Override public String name() { return name; }
}
