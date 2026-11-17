package com.gek.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelCatalogTest {
    @Test
    void inspectsLocalAssets() {
        AppPaths paths = AppPaths.resolve();
        ModelCatalog.Catalog c = ModelCatalog.inspect(paths);
        assertNotNull(c);
        assertNotNull(c.whisper);
        assertFalse(c.whisper.isEmpty(), "expected whisper models in resources");
        assertTrue(c.whisper.stream().anyMatch(w -> w.complete), "expected at least one complete whisper snapshot");
        assertNotNull(c.argosPairs);
        assertTrue(c.argosPairs.size() >= 10, "expected argos packages");
        assertTrue(c.argosPairs.stream().anyMatch(p -> "en".equals(p.from) && p.to != null));
        assertNotNull(c.pyannote);
        String text = c.toText();
        assertTrue(text.contains("Model catalog"));
        assertTrue(text.contains("Argos translation pairs"));
    }
}
