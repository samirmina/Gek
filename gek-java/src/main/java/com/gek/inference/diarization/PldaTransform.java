package com.gek.inference.diarization;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/** Loads xvec_transform.npz and plda.npz and applies embedding transform + PLDA scoring helpers. */
public final class PldaTransform {
    public final double[] mean1;   // (256)
    public final double[][] lda;   // (256,128)
    public final double[] mean2;   // (128)
    public final double[] mu;      // (128)
    public final double[][] tr;    // (128,128)
    public final double[] psi;     // (128)

    private PldaTransform(double[] mean1, double[][] lda, double[] mean2, double[] mu, double[][] tr, double[] psi) {
        this.mean1 = mean1; this.lda = lda; this.mean2 = mean2; this.mu = mu; this.tr = tr; this.psi = psi;
    }

    public static PldaTransform load(Path pyannoteDir) throws IOException {
        Path xPath = pyannoteDir.resolve("plda").resolve("xvec_transform.npz");
        Path pPath = pyannoteDir.resolve("plda").resolve("plda.npz");
        if (!Files.isRegularFile(xPath) || !Files.isRegularFile(pPath)) {
            throw new IOException("PLDA assets missing under " + pyannoteDir + " (expected plda/xvec_transform.npz and plda/plda.npz)");
        }
        Map<String, Npz.Array> x = Npz.load(xPath);
        Map<String, Npz.Array> p = Npz.load(pPath);
        double[] mean1 = req(x, "mean1").asVector();
        double[][] lda = req(x, "lda").asMatrix();
        double[] mean2 = req(x, "mean2").asVector();
        double[] mu = req(p, "mu").asVector();
        double[][] tr = req(p, "tr").asMatrix();
        double[] psi = req(p, "psi").asVector();
        return new PldaTransform(mean1, lda, mean2, mu, tr, psi);
    }

    private static Npz.Array req(Map<String, Npz.Array> m, String k) throws IOException {
        Npz.Array a = m.get(k);
        if (a == null) throw new IOException("missing array '" + k + "' in npz");
        return a;
    }

    /** Apply x-vector transform (mean1 -> LDA -> mean2 -> length normalize) to raw embedding. */
    public double[] transformEmbedding(float[] embedding) {
        if (embedding == null) return new double[0];
        int in = embedding.length;
        int ldaIn = lda.length;
        int ldaOut = lda.length == 0 ? 0 : lda[0].length;
        // center with mean1 (truncate/pad defensively)
        double[] v = new double[ldaIn];
        for (int i = 0; i < ldaIn; i++) {
            double x = i < in ? embedding[i] : 0.0;
            double m1 = i < mean1.length ? mean1[i] : 0.0;
            v[i] = x - m1;
        }
        // LDA projection: out[j] = sum_i v[i]*lda[i][j]
        double[] proj = new double[ldaOut];
        for (int i = 0; i < ldaIn; i++) {
            double vi = v[i];
            double[] row = lda[i];
            for (int j = 0; j < ldaOut; j++) proj[j] += vi * row[j];
        }
        // center with mean2
        for (int j = 0; j < proj.length; j++) {
            double m2 = j < mean2.length ? mean2[j] : 0.0;
            proj[j] -= m2;
        }
        l2normalize(proj);
        return proj;
    }

    public static void l2normalize(double[] v) {
        double s = 0.0;
        for (double x : v) s += x * x;
        double n = Math.sqrt(s);
        if (n > 1e-12) for (int i = 0; i < v.length; i++) v[i] /= n;
    }

    /** Simple PLDA log-likelihood-ratio score (same vs different speaker) for transformed embeddings. */
    public double score(double[] a, double[] b) {
        // Whitening/project into PLDA space: y = tr^T * (x - mu)
        double[] ya = project(a);
        double[] yb = project(b);
        int d = Math.min(ya.length, Math.min(yb.length, psi.length));
        if (d == 0) return 0.0;
        // Two-covariance PLDA LLR approximation:
        // llr = sum_j [ log((psi_j+1)/(psi_j)) + 0.5*( (ya_j^2+yb_j^2)/(psi_j+1) - ((ya_j-yb_j)^2)/(psi_j) ) ] simplified
        // Use a stable standard form:
        double llr = 0.0;
        for (int j = 0; j < d; j++) {
            double pj = Math.max(1e-8, psi[j]);
            double y1 = ya[j], y2 = yb[j];
            // common two-cov model: P(y1,y2|same)/P(y1,y2|diff)
            double same = -0.5 * (Math.log(pj + 1.0) + (y1 - y2) * (y1 - y2) / pj + (y1 + y2) * (y1 + y2) / (pj + 2.0));
            double diff = -0.5 * (Math.log(pj + 1.0) + (y1 * y1 + y2 * y2) / (pj + 1.0));
            llr += (same - diff);
        }
        return llr;
    }

    private double[] project(double[] x) {
        int dim = tr.length == 0 ? 0 : tr[0].length;
        double[] centered = new double[Math.min(x == null ? 0 : x.length, mu.length)];
        for (int i = 0; i < centered.length; i++) centered[i] = x[i] - mu[i];
        // y = tr^T * centered (treat tr rows/cols consistently with saved form)
        // try y[j] = sum_i centered[i]*tr[i][j]
        double[] y = new double[Math.min(dim, psi.length)];
        for (int i = 0; i < tr.length && i < centered.length; i++) {
            double ci = centered[i];
            double[] row = tr[i];
            for (int j = 0; j < y.length && j < row.length; j++) y[j] += ci * row[j];
        }
        return y;
    }
}
