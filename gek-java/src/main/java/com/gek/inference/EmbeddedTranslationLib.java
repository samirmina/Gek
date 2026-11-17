package com.gek.inference;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

/** JNA binding for local libgek_translate_shim.so (embedded CPython + ctranslate2/sentencepiece, no subprocess). */
public interface EmbeddedTranslationLib extends Library {

    int gek_translate_init(String pythonHome, String venvSitePackages, PointerByReference errOut);
    Pointer gek_translate(String packagePath, String text, PointerByReference errOut);
    void gek_translate_free_str(Pointer s);

    static EmbeddedTranslationLib tryLoad(String libraryPath) {
        try {
            return Native.load(libraryPath, EmbeddedTranslationLib.class);
        } catch (Throwable t) {
            return null;
        }
    }

    static String takeError(PointerByReference ref, EmbeddedTranslationLib lib) {
        if (ref == null) return null;
        Pointer p = ref.getValue();
        if (p == null || Pointer.nativeValue(p) == 0) return null;
        try {
            return p.getString(0, "UTF-8");
        } finally {
            try { if (lib != null) lib.gek_translate_free_str(p); } catch (Throwable ignored) {}
        }
    }
}
