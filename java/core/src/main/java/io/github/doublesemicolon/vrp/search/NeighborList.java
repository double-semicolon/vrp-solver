package io.github.doublesemicolon.vrp.search;

import io.github.doublesemicolon.vrp.Instance;
import java.util.Arrays;

/**
 * 近傍リスト: 顧客ごとに、距離の近い順に k 人の顧客 (depot は含まない)。同じ距離なら番号の小さい順。
 * 良い解では、顧客はたいてい近くの顧客の隣にいる。そこで relocate と swap の相手を、
 * 近くの k 人に関係する手だけに絞る ({@link Relocate#Relocate(NeighborList)}、{@link Swap#Swap(NeighborList)})。
 *
 * <p>「v が u の近くの k 人に入るか」を O(1) で引けるよう、(n+1)^2 の表も持つ (n = 1000 で 1 MB)。
 */
public final class NeighborList {
    private final int k;
    private final int[][] near;
    private final boolean[] contains;
    private final int stride;

    private NeighborList(int k, int[][] near, boolean[] contains, int stride) {
        this.k = k;
        this.near = near;
        this.contains = contains;
        this.stride = stride;
    }

    /** k が顧客数 - 1 以上なら、自分以外の全員になる。 */
    public static NeighborList of(Instance inst, int k) {
        if (k < 1) {
            throw new IllegalArgumentException("k は 1 以上: " + k);
        }
        int n = inst.n();
        int kk = Math.min(k, n - 1);
        int stride = n + 1;
        int[][] near = new int[n + 1][];
        boolean[] contains = new boolean[stride * stride];
        near[0] = new int[0];
        // (距離, 番号) を 1 つの long に詰めて整列する。上位 32 ビットが距離、下位が番号なので、
        // 整列すると距離の近い順・同じ距離なら番号の小さい順になる (箱に入れて比べるより 1 桁速い)
        long[] keys = new long[n - 1];
        for (int u = 1; u <= n; u++) {
            int m = 0;
            for (int v = 1; v <= n; v++) {
                if (v != u) {
                    keys[m++] = ((long) inst.dist(u, v) << 32) | v;
                }
            }
            Arrays.sort(keys);
            near[u] = new int[kk];
            for (int i = 0; i < kk; i++) {
                near[u][i] = (int) keys[i];
            }
            for (int v : near[u]) {
                contains[u * stride + v] = true;
            }
        }
        return new NeighborList(kk, near, contains, stride);
    }

    public int k() {
        return k;
    }

    /** 顧客 u の近くの k 人 (近い順)。書き換えないこと。 */
    public int[] near(int u) {
        return near[u];
    }

    /** v が u の近くの k 人に入るか。u, v のどちらかが depot (0) なら false。 */
    public boolean contains(int u, int v) {
        return contains[u * stride + v];
    }

    @Override
    public String toString() {
        return "NeighborList(k=" + k + ", " + Arrays.stream(near).mapToInt(a -> a.length).sum() + " 組)";
    }
}
