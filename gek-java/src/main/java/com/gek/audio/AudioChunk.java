package com.gek.audio;

public record AudioChunk(String source, float[] samples, long timestampMs) {}
