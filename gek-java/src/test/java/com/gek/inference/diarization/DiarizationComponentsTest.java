package com.gek.inference.diarization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DiarizationComponentsTest {
    @TempDir Path tmp;

    @Test
    void diarizationConfigDefaultsAndLoad() throws Exception {
        DiarizationConfig def = DiarizationConfig.load(tmp);
        assertEquals(0.6, def.threshold, 1e-9);
        assertEquals(0.07, def.fa, 1e-9);
        assertEquals(0.8, def.fb, 1e-9);
        assertEquals(0.0, def.minDurationOff, 1e-9);
        assertTrue(def.excludeOverlap);

        Path cfg = tmp.resolve("config.yaml");
        Files.writeString(cfg, "params:\n  clustering:\n    threshold: 0.42\n    Fa: 0.11\n    Fb: 0.33\n  segmentation:\n    min_duration_off: 0.25\npipeline:\n  params:\n    embedding_exclude_overlap: false\n");
        DiarizationConfig loaded = DiarizationConfig.load(tmp);
        assertEquals(0.42, loaded.threshold, 1e-9);
        assertEquals(0.11, loaded.fa, 1e-9);
        assertEquals(0.33, loaded.fb, 1e-9);
        assertEquals(0.25, loaded.minDurationOff, 1e-9);
        assertFalse(loaded.excludeOverlap);
    }

    @Test
    void npzLoadsSyntheticArrays() throws Exception {
        Path npz = tmp.resolve("t.npz");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(npz))) {
            putNpy(zos, "v.npy", new int[]{4}, new double[]{1,2,3,4}, "<f8", false);
            putNpy(zos, "m.npy", new int[]{2,3}, new double[]{1,2,3,4,5,6}, "<f4", false);
        }
        Map<String, Npz.Array> m = Npz.load(npz);
        assertEquals(2, m.size());
        assertArrayEquals(new double[]{1,2,3,4}, m.get("v").asVector(), 1e-9);
        double[][] mat = m.get("m").asMatrix();
        assertEquals(2, mat.length); assertEquals(3, mat[0].length);
        assertArrayEquals(new double[]{1,2,3}, mat[0], 1e-6);
        assertArrayEquals(new double[]{4,5,6}, mat[1], 1e-6);
        assertEquals(4, m.get("v").size());
        assertTrue(m.get("v").dtype().contains("f8"));
    }

    @Test
    void npzLoadsFortranOrderMatrix() throws Exception {
        Path npz = tmp.resolve("f.npz");
        // Fortran order for [[1,2,3],[4,5,6]] stored column-major as 1,4,2,5,3,6
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(npz))) {
            putNpy(zos, "mf.npy", new int[]{2,3}, new double[]{1,4,2,5,3,6}, "<f8", true);
        }
        Map<String, Npz.Array> m = Npz.load(npz);
        double[][] mat = m.get("mf").asMatrix();
        assertArrayEquals(new double[]{1,2,3}, mat[0], 1e-9);
        assertArrayEquals(new double[]{4,5,6}, mat[1], 1e-9);
    }

    @Test
    void pldaTransformWithRealAssets() throws Exception {
        Path pyannoteDir = Path.of("/mnt/d/gek/resources/models/pyannote/speaker-diarization-community-1");
        if (!Files.isDirectory(pyannoteDir.resolve("plda"))) return; // environment-dependent
        PldaTransform p = PldaTransform.load(pyannoteDir);
        assertNotNull(p.mean1);
        assertNotNull(p.lda);
        assertNotNull(p.mean2);
        assertNotNull(p.mu);
        assertNotNull(p.tr);
        assertNotNull(p.psi);
        assertTrue(p.psi.length > 0);

        float[] emb = new float[256];
        for (int i = 0; i < emb.length; i++) emb[i] = (float) Math.sin(i * 0.01);
        double[] t = p.transformEmbedding(emb);
        assertTrue(t.length > 0);
        double norm = 0; for (double x : t) norm += x*x;
        assertEquals(1.0, Math.sqrt(norm), 1e-6);

        double[] t2 = p.transformEmbedding(emb.clone());
        double same = p.score(t, t2);
        float[] other = new float[256];
        for (int i = 0; i < other.length; i++) other[i] = (float) Math.cos(i * 0.03 + 1.0);
        double[] to = p.transformEmbedding(other);
        double diff = p.score(t, to);
        assertTrue(Double.isFinite(same));
        assertTrue(Double.isFinite(diff));

        assertEquals(0, p.transformEmbedding(null).length);
        PldaTransform.l2normalize(new double[]{0,0,0}); // no throw
    }

    @Test
    void simpleClustererBasicPaths() {
        DiarizationConfig cfg = new DiarizationConfig(0.6, 0.07, 0.8, 0.0, true);
        assertEquals(0, SimpleClusterer.cluster(null, null, cfg).length);
        assertArrayEquals(new int[]{0}, SimpleClusterer.cluster(List.of(item(0,1,1,0,0,0)), null, cfg));
        assertEquals(0, SimpleClusterer.toSegments(null, null).size());
        assertEquals(0, SimpleClusterer.toSegments(List.of(), new int[0]).size());

        List<SimpleClusterer.Item> items = new ArrayList<>();
        items.add(item(0.0, 1.0, 1,0,0,0));
        items.add(item(1.0, 2.0, 1,0,0,0));
        items.add(item(2.0, 3.0, 0,1,0,0));
        items.add(item(3.0, 4.0, 0,1,0,0));
        int[] labels = SimpleClusterer.cluster(items, null, cfg);
        assertEquals(items.size(), labels.length);
        for (int l : labels) assertTrue(l >= 0);
        var segs = SimpleClusterer.toSegments(items, labels);
        assertFalse(segs.isEmpty());
        double total = 0; for (var s : segs) { total += (s.end()-s.start()); assertNotNull(s.speaker()); assertTrue(s.start() <= s.end()); }
        assertEquals(4.0, total, 1e-9);
    }

    @Test
    void vbxClustererBasicPaths() {
        DiarizationConfig cfg = new DiarizationConfig(0.6, 0.07, 0.8, 0.0, true);
        assertEquals(0, VbxClusterer.cluster(null, null, cfg).length);
        assertArrayEquals(new int[]{0}, VbxClusterer.cluster(List.of(vitem(0,1,1,0,0,0)), null, cfg));
        assertEquals(0, VbxClusterer.toSegments(null, null).size());
        assertEquals(0, VbxClusterer.toSegments(List.of(), new int[0]).size());

        List<VbxClusterer.Item> items = new ArrayList<>();
        items.add(vitem(0.0, 1.0, 1,0,0,0));
        items.add(vitem(1.0, 2.0, 0.9,0.1,0,0));
        items.add(vitem(2.0, 3.0, 0,1,0,0));
        items.add(vitem(3.0, 4.0, 0,0.9,0.1,0));
        int[] labels = VbxClusterer.cluster(items, null, cfg);
        assertEquals(items.size(), labels.length);
        for (int l : labels) assertTrue(l >= 0);
        var segs = VbxClusterer.toSegments(items, labels);
        assertFalse(segs.isEmpty());
        for (var s : segs) { assertNotNull(s.speaker()); assertTrue(s.start() <= s.end()); }
    }

    @Test
    void clusterersWithRealPldaIfAvailable() throws Exception {
        Path pyannoteDir = Path.of("/mnt/d/gek/resources/models/pyannote/speaker-diarization-community-1");
        if (!Files.isDirectory(pyannoteDir.resolve("plda"))) return;
        PldaTransform plda = PldaTransform.load(pyannoteDir);
        DiarizationConfig cfg = DiarizationConfig.load(pyannoteDir);

        List<SimpleClusterer.Item> sItems = new ArrayList<>();
        List<VbxClusterer.Item> vItems = new ArrayList<>();
        for (int sp = 0; sp < 2; sp++) {
            for (int k = 0; k < 3; k++) {
                float[] raw = new float[256];
                for (int i = 0; i < raw.length; i++) raw[i] = (float) (Math.sin(i * 0.02 + sp * 1.7 + k * 0.01));
                double[] t = plda.transformEmbedding(raw);
                double start = (sp * 3 + k) * 1.0;
                sItems.add(new SimpleClusterer.Item(start, start + 1.0, t));
                vItems.add(new VbxClusterer.Item(start, start + 1.0, t));
            }
        }
        int[] sl = SimpleClusterer.cluster(sItems, plda, cfg);
        assertEquals(sItems.size(), sl.length);
        assertFalse(SimpleClusterer.toSegments(sItems, sl).isEmpty());

        int[] vl = VbxClusterer.cluster(vItems, plda, cfg);
        assertEquals(vItems.size(), vl.length);
        assertFalse(VbxClusterer.toSegments(vItems, vl).isEmpty());
    }

    private static SimpleClusterer.Item item(double s, double e, double... emb) { return new SimpleClusterer.Item(s,e,emb); }
    private static VbxClusterer.Item vitem(double s, double e, double... emb) { return new VbxClusterer.Item(s,e,emb); }

    private static void putNpy(ZipOutputStream zos, String name, int[] shape, double[] values, String descr, boolean fortranHeader) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        byte[] header = npyHeader(shape, descr, fortranHeader);
        byte[] data = new byte[values.length * (descr.contains("f4") ? 4 : 8)];
        ByteBuffer bb = ByteBuffer.wrap(data).order(descr.startsWith(">") ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        for (double v : values) {
            if (descr.contains("f4")) bb.putFloat((float) v); else bb.putDouble(v);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(new byte[]{(byte)0x93,'N','U','M','P','Y',1,0});
        bos.write((byte)(header.length & 0xFF));
        bos.write((byte)((header.length >> 8) & 0xFF));
        bos.write(header);
        bos.write(data);
        zos.write(bos.toByteArray());
        zos.closeEntry();
    }

    private static byte[] npyHeader(int[] shape, String descr, boolean fortran) {
        StringBuilder sh = new StringBuilder("(");
        for (int i = 0; i < shape.length; i++) { if (i>0) sh.append(", "); sh.append(shape[i]); }
        if (shape.length == 1) sh.append(",");
        sh.append(")");
        String h = "{'descr': '" + descr + "', 'fortran_order': " + (fortran ? "True" : "False") + ", 'shape': " + sh + ", }";
        int pre = 10; // magic+ver+len
        int pad = 16 - ((pre + h.length() + 1) % 16);
        if (pad == 16) pad = 0;
        StringBuilder sb = new StringBuilder(h);
        for (int i = 0; i < pad; i++) sb.append(' ');
        sb.append('\n');
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
