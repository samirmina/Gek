package com.gek.inference.diarization;

import java.util.*;

/**
 * VBx (Variational Bayes HMM) clustering for diarization using PLDA-transformed embeddings.
 * Implements iterative VB refinement with HMM state transitions (loop probability derived from
 * Fa/Fb and segment durations), PLDA log-likelihoods, and Dirichlet-like speaker priors.
 * Falls back to AHC initialization. Pure-Java.
 *
 * References: VBx (Diez et al., Bayesian HMM clustering of x-vectors) as used in pyannote VBxClustering;
 * aligned to config params: Fa, Fb, threshold.
 */
public final class VbxClusterer {
    private VbxClusterer() {}

    public static class Item {
        public final double start;
        public final double end;
        public final double[] emb; // PLDA-transformed
        public Item(double start, double end, double[] emb) { this.start = start; this.end = end; this.emb = emb; }
    }

    public static int[] cluster(List<Item> items, PldaTransform plda, DiarizationConfig cfg) {
        int n = items == null ? 0 : items.size();
        if (n == 0) return new int[0];
        if (n == 1) return new int[]{0};

        double thr = cfg == null ? 0.6 : cfg.threshold;
        double fa = (cfg == null || cfg.fa <= 0) ? 0.07 : cfg.fa;
        double fb = (cfg == null || cfg.fb <= 0) ? 0.8 : cfg.fb;

        // AHC init
        int[] init = ahcInit(items, plda, thr, fa, fb);
        int k = Math.max(1, countClusters(init));
        if (k <= 1 && n > 1) {
            // try splitting by threshold sensitivity? keep as 1
        }

        // VBx refinement
        double[][] gamma = vbxHmm(items, plda, init, k, fa, fb, 20, 1e-4);
        int[] labels = hardLabels(gamma);
        labels = mergeByThreshold(items, plda, labels, thr, fa, fb);
        return compactLabels(labels);
    }

    /** HMM-VBx: E-step forward-backward with PLDA emission scores and transition probs, M-step updates pi. */
    private static double[][] vbxHmm(List<Item> items, PldaTransform plda, int[] init, int k, double fa, double fb, int maxIter, double tol) {
        int n = items.size();
        k = Math.max(1, Math.min(k, n));
        double[][] gamma = oneHot(init, n, k);
        double[] pi = uniform(k);
        double pLoop = loopProb(items, fa, fb);

        for (int iter = 0; iter < maxIter; iter++) {
            // compute speaker-specific emission statistics proxy: cluster means
            double[][] means = estimateMeans(items, gamma, k);
            // emission log-likelihoods L[t][s]
            double[][] logEmit = new double[n][k];
            for (int t = 0; t < n; t++) {
                for (int s = 0; s < k; s++) {
                    double ll = emission(items.get(t).emb, means[s], plda);
                    logEmit[t][s] = ll;
                }
            }
            // forward-backward
            double[][] newGamma = forwardBackward(logEmit, pi, pLoop);
            if (newGamma == null) newGamma = gamma;
            // update pi (speaker prior) with floor
            double[] newPi = new double[k];
            for (int s = 0; s < k; s++) {
                double sum = 0;
                for (int t = 0; t < n; t++) sum += newGamma[t][s];
                newPi[s] = sum + 1e-6; // Dirichlet floor
            }
            normalizeVec(newPi);

            double diff = maxAbsDiff(gamma, newGamma);
            gamma = newGamma;
            pi = newPi;
            // prune/compact if clusters collapsed
            int[] hard = hardLabels(gamma);
            int kk = countClusters(compactLabels(hard.clone()));
            if (kk < k && kk >= 1) {
                gamma = compactGamma(gamma, hard, kk);
                pi = uniform(kk);
                k = kk;
            }
            if (diff < tol) break;
        }
        return gamma;
    }

    private static double emission(double[] emb, double[] mean, PldaTransform plda) {
        if (plda != null) {
            if (mean == null || allZero(mean)) return -1e6;
            return plda.score(emb, mean);
        }
        if (mean == null) return -1e6;
        // negative squared distance fallback
        double s = 0; int d = Math.min(emb.length, mean.length);
        for (int i = 0; i < d; i++) { double diff = emb[i]-mean[i]; s += diff*diff; }
        return -0.5 * s;
    }

    private static boolean allZero(double[] v) {
        if (v == null) return true;
        for (double x : v) if (Math.abs(x) > 1e-12) return false;
        return true;
    }

    private static double loopProb(List<Item> items, double fa, double fb) {
        // VBx uses Ploop = (N*Fa - K + K*Fb) related forms; approximate from segment durations and Fa/Fb
        double avgDur = 0;
        for (Item it : items) avgDur += Math.max(0.01, it.end - it.start);
        avgDur /= Math.max(1, items.size());
        // higher Fa -> more likely same speaker continues; Fb penalizes; clamp to sensible range
        double base = 1.0 - (fa * 0.5);
        base = Math.max(0.5, Math.min(0.999, base + 0.3 * avgDur / (avgDur + 1.0)));
        // incorporate Fb
        base = Math.max(0.3, Math.min(0.999, base * (1.0 - 0.2 * fb + 0.1)));
        return base;
    }

    private static double[][] forwardBackward(double[][] logEmit, double[] pi, double pLoop) {
        int T = logEmit.length, K = pi.length;
        double pStay = pLoop;
        double pSwitch = (1.0 - pStay) / Math.max(1, K - 1);
        if (K == 1) { double[][] g = new double[T][1]; for(int t=0;t<T;t++) g[t][0]=1.0; return g; }

        // log transition: A[i][j]
        double[][] logA = new double[K][K];
        for (int i = 0; i < K; i++) for (int j = 0; j < K; j++) logA[i][j] = Math.log(i == j ? Math.max(1e-12, pStay) : Math.max(1e-12, pSwitch));

        double[][] alpha = new double[T][K];
        double[][] beta = new double[T][K];
        // forward
        for (int s = 0; s < K; s++) alpha[0][s] = Math.log(Math.max(1e-12, pi[s])) + logEmit[0][s];
        logNormalize(alpha[0]);
        for (int t = 1; t < T; t++) {
            for (int s = 0; s < K; s++) {
                double[] vals = new double[K];
                for (int ps = 0; ps < K; ps++) vals[ps] = alpha[t - 1][ps] + logA[ps][s];
                alpha[t][s] = logSumExp(vals) + logEmit[t][s];
            }
            logNormalize(alpha[t]);
        }
        // backward
        Arrays.fill(beta[T - 1], 0.0); // log(1)
        for (int t = T - 2; t >= 0; t--) {
            for (int s = 0; s < K; s++) {
                double[] vals = new double[K];
                for (int ns = 0; ns < K; ns++) vals[ns] = logA[s][ns] + logEmit[t + 1][ns] + beta[t + 1][ns];
                beta[t][s] = logSumExp(vals);
            }
            // no need to normalize beta for gamma ratio except stability; keep reasonable
            double mx = max(beta[t]);
            for (int s = 0; s < K; s++) beta[t][s] -= mx;
        }
        // gamma
        double[][] gamma = new double[T][K];
        for (int t = 0; t < T; t++) {
            double mx = Double.NEGATIVE_INFINITY;
            for (int s = 0; s < K; s++) { gamma[t][s] = alpha[t][s] + beta[t][s]; if (gamma[t][s] > mx) mx = gamma[t][s]; }
            double sum = 0;
            for (int s = 0; s < K; s++) { gamma[t][s] = Math.exp(gamma[t][s] - mx); sum += gamma[t][s]; }
            if (sum <= 0) Arrays.fill(gamma[t], 1.0 / K); else for (int s = 0; s < K; s++) gamma[t][s] /= sum;
        }
        return gamma;
    }

    private static double[][] estimateMeans(List<Item> items, double[][] gamma, int k) {
        int n = items.size();
        int dim = items.get(0).emb.length;
        double[][] means = new double[k][dim];
        double[] w = new double[k];
        for (int t = 0; t < n; t++) {
            for (int s = 0; s < k; s++) {
                double g = gamma[t][s];
                w[s] += g;
                double[] e = items.get(t).emb;
                for (int d = 0; d < dim && d < e.length; d++) means[s][d] += g * e[d];
            }
        }
        for (int s = 0; s < k; s++) {
            if (w[s] > 1e-9) for (int d = 0; d < dim; d++) means[s][d] /= w[s];
            else Arrays.fill(means[s], 0.0);
            normalize(means[s]);
        }
        return means;
    }

    private static int[] ahcInit(List<Item> items, PldaTransform plda, double thr, double fa, double fb) {
        int n = items.size();
        List<List<Integer>> clusters = new ArrayList<>();
        for (int i = 0; i < n; i++) { List<Integer> c = new ArrayList<>(); c.add(i); clusters.add(c); }
        double prior = Math.log(Math.max(1e-9, fa) / Math.max(1e-9, fb));
        boolean merged = true;
        while (merged && clusters.size() > 1) {
            merged = false;
            double best = Double.NEGATIVE_INFINITY; int bi=-1,bj=-1;
            for (int i = 0; i < clusters.size(); i++) for (int j = i+1; j < clusters.size(); j++) {
                double s = avgScore(items, clusters.get(i), clusters.get(j), plda) + prior;
                if (s > best) { best = s; bi = i; bj = j; }
            }
            if (bi >= 0 && best >= thr) { clusters.get(bi).addAll(clusters.get(bj)); clusters.remove(bj); merged = true; }
        }
        int[] labels = new int[n];
        for (int c = 0; c < clusters.size(); c++) for (int idx : clusters.get(c)) labels[idx] = c;
        return labels;
    }

    private static int[] mergeByThreshold(List<Item> items, PldaTransform plda, int[] labels, double thr, double fa, double fb) {
        int[] lab = compactLabels(labels.clone());
        boolean merged = true;
        double prior = Math.log(Math.max(1e-9, fa) / Math.max(1e-9, fb));
        while (merged) {
            merged = false; int k = countClusters(lab); if (k <= 1) break;
            double[][] means = estimateMeans(items, oneHot(lab, lab.length, k), k);
            double best = Double.NEGATIVE_INFINITY; int bi=-1,bj=-1;
            for (int i=0;i<k;i++) for(int j=i+1;j<k;j++){
                double s = emission(means[i], means[j], plda) + prior; // symmetric enough for merge decision
                if (s > best) { best=s; bi=i; bj=j; }
            }
            if (bi>=0 && best >= thr) {
                for (int i=0;i<lab.length;i++){ if(lab[i]==bj) lab[i]=bi; else if(lab[i]>bj) lab[i]--; }
                merged = true;
            }
        }
        return lab;
    }

    private static double avgScore(List<Item> items, List<Integer> a, List<Integer> b, PldaTransform plda) {
        if (plda == null) return Double.NEGATIVE_INFINITY;
        double sum = 0; int cnt = 0;
        for (int i : a) for (int j : b) { sum += plda.score(items.get(i).emb, items.get(j).emb); cnt++; }
        return cnt == 0 ? Double.NEGATIVE_INFINITY : sum / cnt;
    }

    private static double[][] oneHot(int[] labels, int n, int k) {
        double[][] g = new double[n][k];
        for (int i = 0; i < n; i++) { int l = (i < labels.length ? labels[i] : 0); if (l<0||l>=k) l=0; g[i][l]=1.0; }
        return g;
    }
    private static double[] uniform(int k){ double[] p=new double[k]; Arrays.fill(p,1.0/k); return p; }
    private static void normalizeVec(double[] v){ double s=0; for(double x:v)s+=x; if(s<=0){Arrays.fill(v,1.0/v.length);return;} for(int i=0;i<v.length;i++) v[i]/=s; }
    private static void normalize(double[] v){ double s=0; for(double x:v)s+=x*x; double n=Math.sqrt(s); if(n>1e-12) for(int i=0;i<v.length;i++) v[i]/=n; }
    private static void logNormalize(double[] v){ double m=max(v); double s=0; for(int i=0;i<v.length;i++){v[i]=Math.exp(v[i]-m); s+=v[i];} if(s>0) for(int i=0;i<v.length;i++) v[i]=Math.log(Math.max(1e-300,v[i]/s)); }
    private static double logSumExp(double[] v){ double m=max(v); if(Double.isInfinite(m)) return m; double s=0; for(double x:v) s+=Math.exp(x-m); return m+Math.log(s); }
    private static double max(double[] v){ double m=Double.NEGATIVE_INFINITY; for(double x:v) if(x>m)m=x; return m; }
    private static double maxAbsDiff(double[][] a,double[][] b){ double m=0; for(int i=0;i<a.length;i++) for(int j=0;j<a[i].length;j++){ double d=Math.abs(a[i][j]-b[i][j]); if(d>m)m=d;} return m; }
    private static int[] hardLabels(double[][] g){ int[] h=new int[g.length]; for(int i=0;i<g.length;i++){int bi=0; double bv=g[i][0]; for(int j=1;j<g[i].length;j++) if(g[i][j]>bv){bv=g[i][j]; bi=j;} h[i]=bi;} return h; }
    private static int countClusters(int[] labels){ int m=-1; for(int v:labels) if(v>m)m=v; return m+1; }
    private static int[] compactLabels(int[] labels){ Map<Integer,Integer> map=new LinkedHashMap<>(); int next=0; int[] out=new int[labels.length]; for(int i=0;i<labels.length;i++){ int l=labels[i]; Integer mm=map.get(l); if(mm==null){mm=next++; map.put(l,mm);} out[i]=mm;} return out; }
    private static double[][] compactGamma(double[][] gamma, int[] hard, int kk){ int[] comp=compactLabels(hard.clone()); int n=gamma.length; int k=Math.max(1,countClusters(comp)); double[][] g=new double[n][k]; for(int i=0;i<n;i++){ for(int j=0;j<gamma[i].length;j++){ int old=j; // map old cluster id by representative
            } } // simpler: rebuild from hard
        g=new double[n][k]; for(int i=0;i<n;i++){ int c=comp[i]; if(c<0||c>=k)c=0; g[i][c]=1.0;} return g; }

    public static List<com.gek.inference.DiarizationEngine.Segment> toSegments(List<Item> items, int[] labels) {
        List<com.gek.inference.DiarizationEngine.Segment> out = new ArrayList<>();
        if (items == null || items.isEmpty() || labels == null) return out;
        int i = 0;
        while (i < items.size()) {
            int lab = labels[i];
            double start = items.get(i).start;
            double end = items.get(i).end;
            int j = i + 1;
            while (j < items.size() && labels[j] == lab) { end = Math.max(end, items.get(j).end); j++; }
            out.add(new com.gek.inference.DiarizationEngine.Segment(start, end, "SPEAKER_" + String.format("%02d", lab)));
            i = j;
        }
        return out;
    }
}
