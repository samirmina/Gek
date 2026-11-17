package com.gek.inference;

public record NativeProcessResult(int exitCode, String stdout, String stderr, long millis) {
    public boolean ok() { return exitCode == 0; }
}
