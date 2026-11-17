package com.gek.inference.diarization;

import com.gek.audio.WavReader;
import com.gek.inference.DiarizationEngine;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Java/ONNX Runtime diarization pipeline for exported pyannote segmentation+embedding models.
 * Uses reflection so the project can compile/run without onnxruntime on the classpath; when
 * ORT and ONNX artifacts are present, inference is executed in-JVM.
 *
 * Designed for speaker-diarization-community-1 style assets (config.yaml + plda/*.npz) with
 * comparable exported segmentation.onnx and embedding.onnx placed under pyannote/onnx/.
 * Clustering: VBx (HMM variational Bayes refinement) when available, else AHC baseline.
 */
public final class OnnxOrtDiarizationPipeline {
    private OnnxOrtDiarizationPipeline() {}

    public static List<DiarizationEngine.Segment> diarize(Path wavPath, Path pyannoteDir) throws Exception {
        OnnxDiarizationModels.Paths onnx = OnnxDiarizationModels.resolve(pyannoteDir);
        if (!onnx.ready()) {
            throw new IllegalStateException("ONNX diarization models not found/usable. Need segmentation.onnx and embedding.onnx OR embedding_encoder.onnx+resnet_seg_1_weight.npy+resnet_seg_1_bias.npy under " + pyannoteDir + "/onnx");
        }
        if (!OnnxDiarizationModels.ortAvailable()) {
            throw new IllegalStateException("Java ONNX Runtime not present (ai.onnxruntime). Add onnxruntime dependency to run native diarization pipeline");
        }
        DiarizationConfig cfg = DiarizationConfig.load(pyannoteDir);
        PldaTransform plda = PldaTransform.load(pyannoteDir);
        WavReader.AudioData audio = WavReader.readMono(wavPath, 16000);

        // Windowing (defaults; tuned for pyannote-style 10s chunks)
        double winSec = 10.0;
        double hopSec = 2.0; // fewer windows for CPU/runtime feasibility
        int sr = audio.sampleRate();
        int win = Math.max(1, (int) Math.round(winSec * sr));
        int hop = Math.max(1, (int) Math.round(hopSec * sr));
        float[] samples = audio.samples();

        float[][] projW = null; float[] projB = null;
        boolean splitEmb = onnx.splitEmbedding();
        if (splitEmb) {
            projW = loadNpyMatrixFloat(onnx.embeddingWeight()); // (256,5120)
            projB = loadNpyVectorFloat(onnx.embeddingBias());   // (256)
        }

        ClassLoader cl = OnnxOrtDiarizationPipeline.class.getClassLoader();
        Class<?> ortEnvClz = Class.forName("ai.onnxruntime.OrtEnvironment", true, cl);
        Class<?> ortSessionClz = Class.forName("ai.onnxruntime.OrtSession", true, cl);
        Class<?> sessionOptsClz = Class.forName("ai.onnxruntime.OrtSession$SessionOptions", true, cl);
        Class<?> onnxTensorClz = Class.forName("ai.onnxruntime.OnnxTensor", true, cl);
        Class<?> onnxValueClz = Class.forName("ai.onnxruntime.OnnxValue", true, cl);

        Object env = ortEnvClz.getMethod("getEnvironment").invoke(null);
        Object sopts = sessionOptsClz.getConstructor().newInstance();
        try {
            trySetIntraThreads(sessionOptsClz, sopts, Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
            Method create = ortEnvClz.getMethod("createSession", String.class, sessionOptsClz);
            Object segSession = create.invoke(env, onnx.segmentation().toAbsolutePath().toString(), sopts);
            Object embSession = create.invoke(env, onnx.embedding().toAbsolutePath().toString(), sopts);
            try {
                String segInput = firstInputName(ortSessionClz, segSession);
                String embInput = firstInputName(ortSessionClz, embSession);

                List<VbxClusterer.Item> items = new ArrayList<>();
                if (samples.length == 0) return Collections.emptyList();
                for (int start = 0; start < samples.length; start += hop) {
                    int end = Math.min(samples.length, start + win);
                    if (end - start < sr / 2) break; // skip very short tail
                    float[] chunk = Arrays.copyOfRange(samples, start, end);
                    if (chunk.length < win) chunk = Arrays.copyOf(chunk, win); // pad
                    double t0 = start / (double) sr;
                    double t1 = end / (double) sr;

                    float act = runSegmentationActivity(ortEnvClz, ortSessionClz, onnxTensorClz, onnxValueClz, env, segSession, segInput, chunk);
                    // skip low-activity windows when exclusion configured; keep minimal speech presence
                    if (cfg.excludeOverlap && act < 0.15f) continue;

                    float[] emb;
                    if (splitEmb) {
                        float[] frameStats = runEmbedding(ortEnvClz, ortSessionClz, onnxTensorClz, onnxValueClz, env, embSession, embInput, chunk);
                        emb = projectSplitEmbedding(frameStats, projW, projB);
                    } else {
                        emb = runEmbedding(ortEnvClz, ortSessionClz, onnxTensorClz, onnxValueClz, env, embSession, embInput, chunk);
                    }
                    if (emb == null || emb.length == 0) continue;
                    double[] temb = plda.transformEmbedding(emb);
                    items.add(new VbxClusterer.Item(t0, t1, temb));
                    if (end >= samples.length) break;
                }
                if (items.isEmpty()) {
                    // fallback: single speaker over whole file
                    return List.of(new DiarizationEngine.Segment(0.0, audio.durationSeconds(), "SPEAKER_00"));
                }
                int[] labels = VbxClusterer.cluster(items, plda, cfg);
                return VbxClusterer.toSegments(items, labels);
            } finally {
                closeQuietly(segSession);
                closeQuietly(embSession);
            }
        } finally {
            closeQuietly(sopts);
        }
    }

    private static String firstInputName(Class<?> ortSessionClz, Object session) throws Exception {
        @SuppressWarnings("unchecked")
        Set<String> names = (Set<String>) ortSessionClz.getMethod("getInputNames").invoke(session);
        if (names == null || names.isEmpty()) throw new IllegalStateException("ONNX session has no inputs");
        return names.iterator().next();
    }

    private static void trySetIntraThreads(Class<?> sessionOptsClz, Object sopts, int n) {
        try { sessionOptsClz.getMethod("setIntraOpNumThreads", int.class).invoke(sopts, n); } catch (Exception ignored) {}
        try { sessionOptsClz.getMethod("setInterOpNumThreads", int.class).invoke(sopts, 1); } catch (Exception ignored) {}
    }

    private static float[] projectSplitEmbedding(float[] frameFeat, float[][] w, float[] b) {
        if (frameFeat == null || frameFeat.length == 0 || w == null || b == null) return null;
        int dOut = b.length;
        int in = (w.length > 0) ? w[0].length : 0;
        float[] stats;
        if (frameFeat.length == in) {
            stats = frameFeat;
        } else {
            int D = 2560;
            if (frameFeat.length % D == 0) {
                int F = frameFeat.length / D;
                stats = maskedStatsPoolGlobal(frameFeat, D, F);
            } else if (in > 0 && frameFeat.length >= in) {
                stats = Arrays.copyOf(frameFeat, in);
            } else {
                stats = Arrays.copyOf(frameFeat, in > 0 ? in : frameFeat.length);
            }
        }
        float[] out = new float[dOut];
        for (int i = 0; i < dOut; i++) {
            double s = b[i];
            float[] row = w[i];
            int lim = Math.min(row.length, stats.length);
            for (int j = 0; j < lim; j++) s += (double) row[j] * (double) stats[j];
            out[i] = (float) s;
        }
        return out;
    }

    private static float[] maskedStatsPoolGlobal(float[] frameFeat, int D, int F) {
        double[] mean = new double[D];
        for (int d = 0; d < D; d++) {
            double sum = 0;
            int base = d * F;
            for (int f = 0; f < F; f++) sum += frameFeat[base + f];
            mean[d] = sum / Math.max(1, F);
        }
        double[] std = new double[D];
        for (int d = 0; d < D; d++) {
            double s = 0;
            int base = d * F;
            double m = mean[d];
            for (int f = 0; f < F; f++) {
                double diff = frameFeat[base + f] - m;
                s += diff * diff;
            }
            std[d] = Math.sqrt(s / Math.max(1, F) + 1e-8);
        }
        float[] stats = new float[D * 2];
        for (int d = 0; d < D; d++) { stats[d] = (float) mean[d]; stats[D + d] = (float) std[d]; }
        return stats;
    }

    private static float[][] loadNpyMatrixFloat(java.nio.file.Path path) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(path);
        if (bytes.length < 10 || bytes[0] != (byte)0x93) throw new IllegalArgumentException("bad npy: " + path);
        int major = bytes[6] & 0xFF;
        int headerLen = major == 1 ? ((bytes[8] & 0xFF) | ((bytes[9] & 0xFF) << 8)) : (java.nio.ByteBuffer.wrap(bytes, 8, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
        int prefix = major == 1 ? 10 : 12;
        String header = new String(bytes, prefix, headerLen, java.nio.charset.StandardCharsets.US_ASCII);
        boolean f64 = header.contains("f8") || header.contains("float64");
        boolean f32 = header.contains("f4") || header.contains("float32");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("shape'?:\\s*\\(([^)]*)\\)").matcher(header);
        if (!m.find()) m = java.util.regex.Pattern.compile("shape\\\":\\s*\\(([^)]*)\\)").matcher(header);
        int[] shape = new int[]{0,0};
        if (m.find()) {
            String[] parts = m.group(1).replace(" ", "").split(",");
            java.util.List<Integer> dims = new java.util.ArrayList<>();
            for (String p : parts) if (!p.isEmpty()) try { dims.add(Integer.parseInt(p)); } catch (Exception ignored) {}
            if (dims.size() == 2) { shape[0]=dims.get(0); shape[1]=dims.get(1); }
            else if (dims.size() == 1) { shape[0]=dims.get(0); shape[1]=1; }
        }
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(bytes, prefix + headerLen, bytes.length - prefix - headerLen).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        float[][] out = new float[shape[0]][shape[1]];
        for (int i = 0; i < shape[0]; i++) for (int j = 0; j < shape[1]; j++) {
            out[i][j] = f64 ? (float) bb.getDouble() : (f32 ? bb.getFloat() : bb.getFloat());
        }
        return out;
    }

    private static float[] loadNpyVectorFloat(java.nio.file.Path path) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(path);
        if (bytes.length < 10 || bytes[0] != (byte)0x93) throw new IllegalArgumentException("bad npy: " + path);
        int major = bytes[6] & 0xFF;
        int headerLen = major == 1 ? ((bytes[8] & 0xFF) | ((bytes[9] & 0xFF) << 8)) : (java.nio.ByteBuffer.wrap(bytes, 8, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
        int prefix = major == 1 ? 10 : 12;
        String header = new String(bytes, prefix, headerLen, java.nio.charset.StandardCharsets.US_ASCII);
        boolean f64 = header.contains("f8") || header.contains("float64");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("shape'?:\\s*\\(([^)]*)\\)").matcher(header);
        if (!m.find()) m = java.util.regex.Pattern.compile("shape\\\":\\s*\\(([^)]*)\\)").matcher(header);
        int n = 0;
        if (m.find()) {
            String[] parts = m.group(1).replace(" ", "").split(",");
            for (String p : parts) if (!p.isEmpty()) try { n = n == 0 ? Integer.parseInt(p) : n * Integer.parseInt(p); } catch (Exception ignored) {}
        }
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(bytes, prefix + headerLen, bytes.length - prefix - headerLen).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = f64 ? (float) bb.getDouble() : bb.getFloat();
        return out;
    }

    private static float runSegmentationActivity(Class<?> ortEnvClz, Class<?> ortSessionClz, Class<?> onnxTensorClz,
                                                 Class<?> onnxValueClz, Object env, Object session, String inputName, float[] chunk) {
        Object tensor = null; Object result = null;
        try {
            long[] shape = new long[]{1, 1, chunk.length};
            Method createTensor = onnxTensorClz.getMethod("createTensor", Class.forName("ai.onnxruntime.OrtEnvironment"), FloatBuffer.class, long[].class);
            tensor = createTensor.invoke(null, env, FloatBuffer.wrap(chunk), shape);
            result = runSession(ortSessionClz, onnxValueClz, session, inputName, tensor);
            float[] out = extractFirstFloatArray(onnxValueClz, result);
            if (out == null || out.length == 0) return 1.0f;
            double sum = 0; int n = 0;
            for (float v : out) {
                float x = Float.isFinite(v) ? v : 0f;
                if (x < 0f || x > 1f) x = (float) (1.0 / (1.0 + Math.exp(-Math.max(-20.0, Math.min(20.0, x)))));
                sum += x; n++;
            }
            return n == 0 ? 1.0f : (float) (sum / n);
        } catch (Throwable t) {
            return 1.0f;
        } finally {
            closeQuietly(result);
            closeQuietly(tensor);
        }
    }

    private static float[] runEmbedding(Class<?> ortEnvClz, Class<?> ortSessionClz, Class<?> onnxTensorClz,
                                        Class<?> onnxValueClz, Object env, Object session, String inputName, float[] chunk) {
        Object tensor = null; Object result = null;
        try {
            long[] shape = new long[]{1, 1, chunk.length};
            Method createTensor = onnxTensorClz.getMethod("createTensor", Class.forName("ai.onnxruntime.OrtEnvironment"), FloatBuffer.class, long[].class);
            tensor = createTensor.invoke(null, env, FloatBuffer.wrap(chunk), shape);
            result = runSession(ortSessionClz, onnxValueClz, session, inputName, tensor);
            return extractFirstFloatArray(onnxValueClz, result);
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(result);
            closeQuietly(tensor);
        }
    }

    private static Object runSession(Class<?> ortSessionClz, Class<?> onnxValueClz, Object session, String inputName, Object tensor) throws Exception {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put(inputName, tensor);
        Method run = ortSessionClz.getMethod("run", Map.class);
        return run.invoke(session, inputs);
    }

    private static float[] extractFirstFloatArray(Class<?> onnxValueClz, Object result) throws Exception {
        if (result == null) return null;
        try {
            Method get0 = result.getClass().getMethod("get", int.class);
            Object val = get0.invoke(result, 0);
            float[] arr = toFloatArray(onnxValueClz, val);
            if (arr != null) return arr;
        } catch (NoSuchMethodException ignored) {}
        if (result instanceof Iterable<?> it) {
            for (Object e : it) {
                Object val = e;
                try {
                    Method gv = e.getClass().getMethod("getValue");
                    val = gv.invoke(e);
                } catch (Exception ignored) {}
                float[] arr = toFloatArray(onnxValueClz, val);
                if (arr != null) return arr;
            }
        }
        return null;
    }

    private static float[] toFloatArray(Class<?> onnxValueClz, Object value) {
        if (value == null) return null;
        try {
            Method getValue = onnxValueClz.isInstance(value) ? onnxValueClz.getMethod("getValue") : value.getClass().getMethod("getValue");
            Object v = getValue.invoke(value);
            return flattenToFloat(v);
        } catch (Exception e) {
            return flattenToFloat(value);
        }
    }

    private static float[] flattenToFloat(Object v) {
        if (v == null) return null;
        if (v instanceof float[] fa) return fa.clone();
        if (v instanceof double[] da) { float[] o = new float[da.length]; for (int i=0;i<da.length;i++) o[i]=(float)da[i]; return o; }
        if (v.getClass().isArray()) {
            int len = Array.getLength(v);
            if (len == 0) return new float[0];
            Object first = Array.get(v, 0);
            if (first == null) return new float[0];
            if (!first.getClass().isArray() && !(first instanceof float[]) && !(first instanceof double[])) {
                float[] out = new float[len];
                for (int i = 0; i < len; i++) {
                    Object o = Array.get(v, i);
                    if (o instanceof Number n) out[i] = n.floatValue();
                }
                return out;
            }
            List<Float> acc = new ArrayList<>();
            flattenRec(v, acc);
            float[] out = new float[acc.size()];
            for (int i = 0; i < acc.size(); i++) out[i] = acc.get(i);
            return out;
        }
        if (v instanceof Number n) return new float[]{n.floatValue()};
        return null;
    }

    private static void flattenRec(Object arr, List<Float> acc) {
        if (arr == null) return;
        if (arr instanceof float[] fa) { for (float f : fa) acc.add(f); return; }
        if (arr instanceof double[] da) { for (double d : da) acc.add((float) d); return; }
        if (arr.getClass().isArray()) {
            int len = Array.getLength(arr);
            for (int i = 0; i < len; i++) flattenRec(Array.get(arr, i), acc);
        } else if (arr instanceof Number n) acc.add(n.floatValue());
    }

    private static void closeQuietly(Object o) {
        if (o == null) return;
        try {
            Method close = o.getClass().getMethod("close");
            close.invoke(o);
        } catch (Exception ignored) {}
    }
}
