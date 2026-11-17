package com.gek.audio;

import javax.sound.sampled.LineUnavailableException;
import java.util.function.Consumer;

public class MicAudioSource implements AudioSource {
    private final MicCapture mic;
    public MicAudioSource(int sampleRate, float chunkSeconds, String deviceNameHint) {
        this.mic = new MicCapture(sampleRate, chunkSeconds, deviceNameHint);
    }
    public MicAudioSource(MicCapture mic) { this.mic = mic; }
    @Override public void setConsumer(Consumer<AudioChunk> consumer) { mic.setConsumer(consumer); }
    @Override public void start() throws LineUnavailableException { mic.start(); }
    @Override public void stop() { mic.stop(); }
    @Override public boolean isRunning() { return mic.isRunning(); }
    @Override public String name() { return "mic"; }
}
