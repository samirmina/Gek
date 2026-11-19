package com.gek.inference;

import com.gek.core.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class NativeRuntimeProbeTest {
    @TempDir Path tmp;

    @Test
    void looksLikeValidGgmlByNameAndSize() throws Exception {
        Method m = NativeRuntimeProbe.class.getDeclaredMethod("looksLikeValidGgml", Path.class, String.class);
        m.setAccessible(true);

        Path p = tmp.resolve("ggml-base.bin");
        Files.write(p, new byte[130 * 1024 * 1024]);
        assertTrue((boolean) m.invoke(null, p, "ggml-base.bin"));

        Path tiny = tmp.resolve("ggml-tiny.bin");
        Files.write(tiny, new byte[70 * 1024 * 1024]);
        assertTrue((boolean) m.invoke(null, tiny, "ggml-tiny.bin"));

        Path smallBad = tmp.resolve("ggml-small.bin");
        Files.write(smallBad, new byte[1024]);
        assertFalse((boolean) m.invoke(null, smallBad, "ggml-small.bin"));

        Path unknown = tmp.resolve("ggml-custom.bin");
        Files.write(unknown, new byte[60 * 1024 * 1024]);
        assertTrue((boolean) m.invoke(null, unknown, "ggml-custom.bin"));

        Path missing = tmp.resolve("ggml-medium.bin");
        assertFalse((boolean) m.invoke(null, missing, "ggml-medium.bin"));
    }

    @Test
    void findGgmlWhisperModelsAndPickPreferred() throws Exception {
        Path root = tmp.resolve("proj");
        Files.createDirectories(root.resolve("resources/models/whisper-ggml"));
        Files.createDirectories(root.resolve("gek-java/native/models"));
        // create valid-ish sizes for names to pass filter
        Files.write(root.resolve("resources/models/whisper-ggml/ggml-base.bin"), new byte[130 * 1024 * 1024]);
        Files.write(root.resolve("gek-java/native/models/ggml-small.bin"), new byte[430 * 1024 * 1024]);
        Files.write(root.resolve("resources/models/whisper-ggml/ggml-tiny.bin"), new byte[70 * 1024 * 1024]);

        String prev = System.getProperty("gek.projectRoot");
        System.setProperty("gek.projectRoot", root.toString());
        try {
            AppPaths paths = AppPaths.resolve();
            List<Path> models = NativeRuntimeProbe.findGgmlWhisperModels(paths);
            assertFalse(models.isEmpty());
            assertTrue(models.stream().anyMatch(p -> p.getFileName().toString().contains("base")));
            // dedupe by name keeps one per filename key
            long baseCount = models.stream().filter(p -> p.getFileName().toString().equals("ggml-base.bin")).count();
            assertEquals(1, baseCount);

            Method pick = NativeRuntimeProbe.class.getDeclaredMethod("pickGgmlModel", List.class, String.class);
            pick.setAccessible(true);
            @SuppressWarnings("unchecked")
            Optional<Path> pSmall = (Optional<Path>) pick.invoke(null, models, "small");
            assertTrue(pSmall.isPresent());
            assertTrue(pSmall.get().getFileName().toString().contains("small"));

            @SuppressWarnings("unchecked")
            Optional<Path> pBase = (Optional<Path>) pick.invoke(null, models, "base");
            assertTrue(pBase.isPresent());
            assertTrue(pBase.get().getFileName().toString().contains("base"));

            @SuppressWarnings("unchecked")
            Optional<Path> fallback = (Optional<Path>) pick.invoke(null, models, "large");
            assertTrue(fallback.isPresent(), "should fallback to some available model");

            @SuppressWarnings("unchecked")
            Optional<Path> none = (Optional<Path>) pick.invoke(null, List.of(), "small");
            assertTrue(none.isEmpty());
        } finally {
            if (prev == null) System.clearProperty("gek.projectRoot"); else System.setProperty("gek.projectRoot", prev);
        }
    }

    @Test
    void resolvePyannoteAssetDirPrefersCommunityDir() throws Exception {
        Path root = tmp.resolve("r");
        Path base = root.resolve("resources/models/pyannote");
        Path comm = base.resolve("speaker-diarization-community-1");
        Files.createDirectories(comm);
        String prev = System.getProperty("gek.projectRoot");
        System.setProperty("gek.projectRoot", root.toString());
        try {
            AppPaths paths = AppPaths.resolve();
            Path p = NativeRuntimeProbe.resolvePyannoteAssetDir(paths);
            assertEquals(comm.toAbsolutePath().normalize(), p.toAbsolutePath().normalize());
            // base only
            Path root2 = tmp.resolve("r2");
            Files.createDirectories(root2.resolve("resources/models/pyannote"));
            System.setProperty("gek.projectRoot", root2.toString());
            AppPaths paths2 = AppPaths.resolve();
            Path p2 = NativeRuntimeProbe.resolvePyannoteAssetDir(paths2);
            assertTrue(p2.toString().endsWith("pyannote"));
            // missing
            Path root3 = tmp.resolve("r3");
            Files.createDirectories(root3.resolve("resources/models"));
            System.setProperty("gek.projectRoot", root3.toString());
            assertNull(NativeRuntimeProbe.resolvePyannoteAssetDir(AppPaths.resolve()));
        } finally {
            if (prev == null) System.clearProperty("gek.projectRoot"); else System.setProperty("gek.projectRoot", prev);
        }
    }

    @Test
    void findArgosPackageRequiresSentencePieceTokenizer() throws Exception {
        Path root = tmp.resolve("argos");
        Path pkg = root.resolve("resources/models/argos/packages/translate-en_fr-1_0");
        Files.createDirectories(pkg.resolve("model"));
        Files.writeString(pkg.resolve("model/model.bin"), "x");
        Files.writeString(pkg.resolve("metadata.json"), "{\"from_code\":\"en\",\"to_code\":\"fr\"}");
        Files.writeString(pkg.resolve("sentencepiece.model"), "spm");
        Path bpe = root.resolve("resources/models/argos/packages/translate-fr_en-1_0");
        Files.createDirectories(bpe.resolve("model"));
        Files.writeString(bpe.resolve("model/model.bin"), "x");
        Files.writeString(bpe.resolve("metadata.json"), "{\"from_code\":\"fr\",\"to_code\":\"en\"}");
        Files.writeString(bpe.resolve("bpe.model"), "bpe");
        String prev = System.getProperty("gek.projectRoot");
        System.setProperty("gek.projectRoot", root.toString());
        try {
            AppPaths paths = AppPaths.resolve();
            assertTrue(NativeRuntimeProbe.findArgosPackage(paths, "en", "fr").isPresent());
            assertTrue(NativeRuntimeProbe.findArgosPackage(paths, "fr", "en").isEmpty());
            assertTrue(NativeRuntimeProbe.findArgosPackage(paths, "auto", "fr").isEmpty());
            assertTrue(NativeRuntimeProbe.findArgosPackage(null, "en", "fr").isEmpty());
        } finally {
            if (prev == null) System.clearProperty("gek.projectRoot"); else System.setProperty("gek.projectRoot", prev);
        }
    }

    @Test
    void descriptionMethodsDoNotThrow() {
        AppPaths paths = AppPaths.resolve();
        assertNotNull(NativeRuntimeProbe.describeSttStatus(paths, "small"));
        assertNotNull(NativeRuntimeProbe.describeTranslationStatus(paths));
        assertNotNull(NativeRuntimeProbe.describeDiarizationStatus(paths));
        assertNotNull(NativeRuntimeProbe.resolveSttRuntime(paths, "small"));
        assertNotNull(NativeRuntimeProbe.resolveProcessSttRuntime(paths, "small"));
        assertNotNull(NativeRuntimeProbe.resolveInJvmSttRuntime(paths, "small"));
        assertNotNull(NativeRuntimeProbe.resolveTranslationRuntime(paths));
        assertNotNull(NativeRuntimeProbe.resolveInJvmTranslationRuntime(paths));
        assertNotNull(NativeRuntimeProbe.resolveDiarizationRuntime(paths));
        assertNotNull(NativeRuntimeProbe.findGgmlWhisperModels(paths));
    }
}
