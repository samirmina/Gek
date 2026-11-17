package com.gek.inference;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

/** JNA binding for local libgek_whisper_shim.so (in-process whisper.cpp, no subprocess). */
public interface WhisperCppLib extends Library {

    class ShimResult extends Structure {
        public Pointer text;
        public Pointer lang;
        public int n_segments;
        public ShimResult() {}
        @Override protected List<String> getFieldOrder() {
            return Arrays.asList("text", "lang", "n_segments");
        }
        public static class ByReference extends ShimResult implements Structure.ByReference {}
    }

    Pointer gek_whisper_init(String modelPath);
    int gek_whisper_transcribe(Pointer ctx, float[] samples, int nSamples, String langHint, ShimResult out);
    void gek_whisper_free_result(ShimResult out);
    void gek_whisper_free(Pointer ctx);
    String gek_whisper_version();

    static WhisperCppLib tryLoad(String libraryPath) {
        try {
            return Native.load(libraryPath, WhisperCppLib.class);
        } catch (Throwable t) {
            return null;
        }
    }
}
