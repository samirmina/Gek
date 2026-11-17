package com.gek.inference.diarization;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Minimal .npz (zip of .npy) reader for float32/float64 arrays used by PLDA assets. */
public final class Npz {
    private Npz() {}

    public record Array(double[] data, int[] shape, String dtype) {
        public int size() { return data == null ? 0 : data.length; }
        public double[] asVector() {
            if (data == null) return new double[0];
            if (shape == null || shape.length <= 1) return data.clone();
            int total = 1; for (int d : shape) total *= Math.max(1, d);
            if (total == data.length) return data.clone();
            return data.clone();
        }
        public double[][] asMatrix() {
            if (data == null) return new double[0][0];
            if (shape != null && shape.length == 2) {
                int r = shape[0], c = shape[1];
                double[][] m = new double[r][c];
                for (int i = 0; i < r; i++) {
                    System.arraycopy(data, i * c, m[i], 0, c);
                }
                return m;
            }
            // fallback: treat as row vector
            return new double[][]{asVector()};
        }
    }

    public static Map<String, Array> load(Path npzPath) throws IOException {
        Map<String, Array> out = new LinkedHashMap<>();
        try (InputStream fis = Files.newInputStream(npzPath); ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name == null || !name.endsWith(".npy")) continue;
                byte[] bytes = readAll(zis);
                String key = name.substring(name.lastIndexOf('/') + 1);
                if (key.endsWith(".npy")) key = key.substring(0, key.length() - 4);
                out.put(key, parseNpy(bytes));
            }
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) >= 0) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    private static Array parseNpy(byte[] bytes) throws IOException {
        if (bytes.length < 10) throw new IOException("npy too short");
        byte[] magic = Arrays.copyOfRange(bytes, 0, 6);
        if (magic[0] != (byte) 0x93 || magic[1] != 'N' || magic[2] != 'U' || magic[3] != 'M' || magic[4] != 'P' || magic[5] != 'Y') {
            throw new IOException("bad npy magic");
        }
        int major = bytes[6] & 0xFF;
        int headerLen;
        int dataOffset;
        if (major == 1) {
            headerLen = (bytes[8] & 0xFF) | ((bytes[9] & 0xFF) << 8);
            dataOffset = 10 + headerLen;
        } else {
            headerLen = (bytes[8] & 0xFF) | ((bytes[9] & 0xFF) << 8) | ((bytes[10] & 0xFF) << 16) | ((bytes[11] & 0xFF) << 24);
            dataOffset = 12 + headerLen;
        }
        if (dataOffset > bytes.length) throw new IOException("npy header past end");
        String header = new String(bytes, major == 1 ? 10 : 12, headerLen, StandardCharsets.US_ASCII).trim();
        boolean fortran = header.contains("'fortran_order': True") || header.contains("\"fortran_order\": True");
        String descr = match(header, "'descr':\\s*'([^']+)'");
        if (descr == null) descr = match(header, "\"descr\":\\s*\"([^\"]+)\"");
        int[] shape = parseShape(header);
        if (descr == null) throw new IOException("npy missing descr: " + header);
        ByteOrder bo = descr.startsWith(">") ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        boolean f8 = descr.contains("f8") || descr.contains("float64");
        boolean f4 = descr.contains("f4") || descr.contains("float32");
        if (!f8 && !f4) throw new IOException("unsupported npy dtype: " + descr + " header=" + header);
        int count = 1; for (int d : shape) count *= Math.max(1, d);
        ByteBuffer bb = ByteBuffer.wrap(bytes, dataOffset, bytes.length - dataOffset).order(bo);
        double[] data = new double[count];
        if (fortran && shape.length == 2) {
            // convert column-major to row-major
            int rows = shape[0], cols = shape[1];
            for (int c = 0; c < cols; c++) {
                for (int r = 0; r < rows; r++) {
                    double v = f8 ? bb.getDouble() : (double) bb.getFloat();
                    data[r * cols + c] = v;
                }
            }
        } else {
            for (int i = 0; i < count; i++) data[i] = f8 ? bb.getDouble() : (double) bb.getFloat();
        }
        return new Array(data, shape, descr);
    }

    private static String match(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static int[] parseShape(String header) {
        String sh = match(header, "'shape':\\s*\\(([^)]*)\\)");
        if (sh == null) sh = match(header, "\"shape\":\\s*\\(([^)]*)\\)");
        if (sh == null) return new int[]{0};
        sh = sh.trim();
        if (sh.isEmpty()) return new int[]{0};
        String[] parts = sh.split(",");
        java.util.List<Integer> dims = new java.util.ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            try { dims.add(Integer.parseInt(t)); } catch (Exception ignored) {}
        }
        int[] out = new int[dims.size()];
        for (int i = 0; i < dims.size(); i++) out[i] = dims.get(i);
        return out;
    }
}
