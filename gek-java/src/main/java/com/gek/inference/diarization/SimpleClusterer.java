package com.gek.inference.diarization;

import java.util.*;

/**
 * VBx-inspired speaker clustering over transformed embeddings using PLDA LLR scores.
 * Steps: agglomerative initialization (AHC, average-link with Fa/Fb calibration as in config),
 * then iterative VBx refinement (soft assignment re-estimation with PLDA log-likelihoods and
 * Dirichlet/prior control via Fa/Fb). Pure-Java, no external deps.
 *
 * This is a practical VBx-style implementation aligned to config params (threshold/Fa/Fb);
 * exact parity with pyannote's full VBx/HMM+VAD-constrained resegmentation depends on
 * model-specific segmentation outputs and is best-effort in this pipeline.
 */
public final class SimpleClusterer {
    private SimpleClusterer() {}

    public static class Item {
        public final double start;
        public final double end;
        public final double[] emb; // transformed
        public Item(double start, double end, double[] emb) { this.start = start; this.end = end; this.emb = emb; }
    }

    public static int[] cluster(List<Item> items, PldaTransform plda, DiarizationConfig cfg) {
        int n = items == null ? 0 : items.size();
        if (n == 0) return new int[0];
        if (n == 1) return new int[]{0};

        double thr = cfg == null ? 0.6 : cfg.threshold;
        double fa = (cfg == null || cfg.fa <= 0) ? 0.07 : cfg.fa;
        double fb = (cfg == null || cfg.fb <= 0) ? 0.8 : cfg.fb;
        double prior = Math.log(fa / fb);

        // 1) AHC initialization (average-link on calibrated PLDA LLR)
        int[] labels = ahcInit(items, plda, thr, prior);
        int k = countClusters(labels);

        // 2) VBx-style iterative refinement
        labels = vbxRefine(items, plda, labels, k, fa, fb, thr, 10);
        labels = compactLabels(labels);
        return labels;
    }

    private static int[] ahcInit(List<Item> items, PldaTransform plda, double thr, double prior) {
        int n = items.size();
        List<List<Integer>> clusters = new ArrayList<>();
        for (int i = 0; i < n; i++) { List<Integer> c = new ArrayList<>(); c.add(i); clusters.add(c); }

        boolean merged = true;
        while (merged && clusters.size() > 1) {
            merged = false;
            double best = Double.NEGATIVE_INFINITY;
            int bi = -1, bj = -1;
            for (int i = 0; i < clusters.size(); i++) {
                for (int j = i + 1; j < clusters.size(); j++) {
                    double s = avgScore(items, clusters.get(i), clusters.get(j), plda);
                    double cs = s + prior;
                    if (cs > best) { best = cs; bi = i; bj = j; }
                }
            }
            if (bi >= 0 && best >= thr) {
                clusters.get(bi).addAll(clusters.get(bj));
                clusters.remove(bj);
                merged = true;
            }
        }
        int[] labels = new int[n];
        for (int c = 0; c < clusters.size(); c++) {
            for (int idx : clusters.get(c)) labels[idx] = c;
        }
        return labels;
    }

    private static int[] vbxRefine(List<Item> items, PldaTransform plda, int[] initLabels, int kInit, double fa, double fb, double thr, int maxIter) {
        int n = items.size();
        int k = Math.max(1, kInit);
        // build initial responsibilities from hard labels
        double[][] gamma = new double[n][k];
        for (int i = 0; i < n; i++) {
            int l = initLabels[i];
            if (l < 0 || l >= k) l = 0;
            gamma[i][l] = 1.0;
        }
        // VB parameters: speaker means in transformed space (PLDA-llr space proxy via embeddings)
        int dim = items.get(0).emb.length;
        double loop = fb / Math.max(1e-9, fa); // transition/loop prior proxy
        double[] pi = new double[k];
        Arrays.fill(pi, 1.0 / k);

        double[][] means = new double[k][dim];
        for (int iter = 0; iter < maxIter; iter++) {
            // M-step: update means and pi
            Arrays.fill(pi, 0.0);
            for (int c = 0; c < k; c++) Arrays.fill(means[c], 0.0);
            for (int i = 0; i < n; i++) {
                for (int c = 0; c < k; c++) {
                    double g = gamma[i][c];
                    pi[c] += g;
                    double[] e = items.get(i).emb;
                    for (int d = 0; d < dim && d < e.length; d++) means[c][d] += g * e[d];
                }
            }
            double piSum = 0;
            for (int c = 0; c < k; c++) {
                if (pi[c] > 1e-9) {
                    for (int d = 0; d < dim; d++) means[c][d] /= pi[c];
                    // normalize mean to stabilize PLDA scoring
                    normalize(means[c]);
                } else {
                    Arrays.fill(means[c], 0.0);
                }
                piSum += pi[c];
            }
            if (piSum <= 0) break;
            for (int c = 0; c < k; c++) pi[c] = Math.max(1e-6, pi[c] / piSum);

            // E-step: responsibilities from PLDA scores to cluster means + pi prior + temporal smoothness proxy
            boolean changed = false;
            double[][] newGamma = new double[n][k];
            for (int i = 0; i < n; i++) {
                double[] logp = new double[k];
                double max = Double.NEGATIVE_INFINITY;
                for (int c = 0; c < k; c++) {
                    double ll = (plda != null) ? plda.score(items.get(i).emb, means[c]) : -dotDist(items.get(i).emb, means[c]);
                    // prior and mild self-loop/temporal bias from neighbors
                    double bias = Math.log(pi[c] + 1e-9);
                    if (i > 0) bias += 0.15 * Math.log(loop + 1e-9) * gamma[i - 1][c];
                    if (i + 1 < n) bias += 0.05 * Math.log(loop + 1e-9) * gamma[i + 1][c];
                    logp[c] = ll + bias;
                    if (logp[c] > max) max = logp[c];
                }
                double sum = 0;
                for (int c = 0; c < k; c++) { newGamma[i][c] = Math.exp(logp[c] - max); sum += newGamma[i][c]; }
                if (sum <= 0) { Arrays.fill(newGamma[i], 1.0 / k); }
                else for (int c = 0; c < k; c++) newGamma[i][c] /= sum;
            }
            // convergence check (hard label stability proxy)
            for (int i = 0; i < n; i++) {
                if (argmax(newGamma[i]) != argmax(gamma[i])) { changed = true; break; }
            }
            gamma = newGamma;
            // prune empty clusters if many
            int[] hard = hardLabels(gamma);
            k = Math.max(1, countClusters(compactLabels(hard.clone())));
            if (countClusters(hard) != gamma[0].length) {
                gamma = reprojectGamma(hard, k, n);
            }
            if (!changed) break;
        }

        int[] hard = hardLabels(gamma);
        // post-merge close speakers by threshold on mean scores
        hard = mergeClose(items, plda, hard, thr, fa, fb);
        return hard;
    }

    private static int[] mergeClose(List<Item> items, PldaTransform plda, int[] labels, double thr, double fa, double fb) {
        int[] lab = compactLabels(labels.clone());
        boolean merged = true;
        double prior = Math.log(Math.max(1e-9, fa) / Math.max(1e-9, fb));
        while (merged) {
            merged = false;
            int k = countClusters(lab);
            if (k <= 1) break;
            // compute means
            int dim = items.get(0).emb.length;
            double[][] means = new double[k][dim];
            int[] cnt = new int[k];
            for (int i = 0; i < items.size(); i++) {
                int c = lab[i]; if (c < 0 || c >= k) continue;
                cnt[c]++;
                double[] e = items.get(i).emb;
                for (int d = 0; d < dim && d < e.length; d++) means[c][d] += e[d];
            }
            for (int c = 0; c < k; c++) if (cnt[c] > 0) { for (int d = 0; d < dim; d++) means[c][d] /= cnt[c]; normalize(means[c]); }
            double best = Double.NEGATIVE_INFINITY; int bi=-1,bj=-1;
            for (int i = 0; i < k; i++) for (int j = i+1; j < k; j++) {
                double s = (plda != null ? plda.score(means[i], means[j]) : -dotDist(means[i], means[j])) + prior;
                if (s > best) { best = s; bi = i; bj = j; }
            }
            if (bi >= 0 && best >= thr) {
                for (int i = 0; i < lab.length; i++) {
                    if (lab[i] == bj) lab[i] = bi;
                    else if (lab[i] > bj) lab[i]--;
                }
                merged = true;
            }
        }
        return compactLabels(lab);
    }

    private static double[][] reprojectGamma(int[] hard, int k, int n) {
        int[] comp = compactLabels(hard.clone());
        int kk = Math.max(1, countClusters(comp));
        double[][] g = new double[n][kk];
        for (int i = 0; i < n; i++) {
            int c = comp[i];
            if (c < 0 || c >= kk) c = 0;
            g[i][c] = 1.0;
        }
        return g;
    }

    private static int[] hardLabels(double[][] gamma) {
        int n = gamma.length;
        int[] h = new int[n];
        for (int i = 0; i < n; i++) h[i] = argmax(gamma[i]);
        return h;
    }

    private static int argmax(double[] a) {
        int bi = 0; double bv = a.length > 0 ? a[0] : Double.NEGATIVE_INFINITY;
        for (int i = 1; i < a.length; i++) if (a[i] > bv) { bv = a[i]; bi = i; }
        return bi;
    }

    private static int countClusters(int[] labels) {
        int max = -1;
        for (int v : labels) if (v > max) max = v;
        return max + 1;
    }

    private static int[] compactLabels(int[] labels) {
        Map<Integer,Integer> map = new LinkedHashMap<>();
        int next = 0;
        int[] out = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            int l = labels[i];
            Integer m = map.get(l);
            if (m == null) { m = next++; map.put(l, m); }
            out[i] = m;
        }
        return out;
    }

    private static void normalize(double[] v) {
        double s = 0; for (double x : v) s += x*x;
        double n = Math.sqrt(s);
        if (n > 1e-12) for (int i=0;i<v.length;i++) v[i]/=n;
    }

    private static double dotDist(double[] a, double[] b) {
        double s = 0; int n = Math.min(a.length,b.length);
        for (int i=0;i<n;i++){ double d=a[i]-b[i]; s+=d*d; }
        return s;
    }

    private static double avgScore(List<Item> items, List<Integer> a, List<Integer> b, PldaTransform plda) {
        if (plda == null) return Double.NEGATIVE_INFINITY;
        double sum = 0; int cnt = 0;
        for (int i : a) for (int j : b) { sum += plda.score(items.get(i).emb, items.get(j).emb); cnt++; }
        return cnt == 0 ? Double.NEGATIVE_INFINITY : sum / cnt;
    }

    public static List<com.gek.inference.DiarizationEngine.Segment> toSegments(List<Item> items, int[] labels) {
        List<com.gek.inference.DiarizationEngine.Segment> out = new ArrayList<>();
        if (items == null || items.isEmpty() || labels == null) return out;
        int i = 0;
        while (i < items.size()) {
            int lab = labels[i];
            double start = items.get(i).start;
            double end = items.get(i).end;
            int j = i + 1;
            while (j < items.size() && labels[j] == lab) {
                end = Math.max(end, items.get(j).end);
                j++;
            }
            out.add(new com.gek.inference.DiarizationEngine.Segment(start, end, "SPEAKER_" + String.format("%02d", lab)));
            i = j;
        }
        return out;
    }
}
