package io.github.doublesemicolon.vrp;

import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * 問題の表現 (Python 版の model.Instance)。添字 0 が depot、1..n が顧客。
 *
 * <p>作った時点で入力を検査し、距離行列を計算する。以後は変更できない。
 */
public final class Instance {
    private final String name;
    private final double[] xs;
    private final double[] ys;
    private final int[] demands;
    private final int capacity;
    // 上限なしを null や「十分大きい数」で表さない。null は使うたびに NPE を気にする
    // 必要があり、大きい数は本物の上限と区別できない (Python 版の None と同じ理由)
    private final OptionalInt numVehicles;
    private final int[][] dist;

    /**
     * @param coords      各ノードの {x, y}。添字 0 が depot
     * @param demands     各ノードの需要。demands[0] は 0
     * @param capacity    1台あたりの積載量上限 Q
     * @param numVehicles 利用可能な車両数 K。空なら上限なし
     */
    public Instance(String name, double[][] coords, int[] demands, int capacity, OptionalInt numVehicles) {
        this.name = Objects.requireNonNull(name, "name");
        Objects.requireNonNull(coords, "coords");
        Objects.requireNonNull(demands, "demands");
        this.numVehicles = Objects.requireNonNull(numVehicles, "numVehicles");
        if (coords.length != demands.length) {
            throw new IllegalArgumentException("coords と demands の長さが違う");
        }
        if (coords.length == 0) {
            throw new IllegalArgumentException("depot がない");
        }
        if (demands[0] != 0) {
            throw new IllegalArgumentException("depot の需要は 0");
        }
        int m = coords.length;
        xs = new double[m];
        ys = new double[m];
        for (int i = 0; i < m; i++) {
            if (coords[i].length != 2) {
                throw new IllegalArgumentException("座標 " + i + " が {x, y} の 2 要素でない");
            }
            // NaN は (int) で黙って 0 になる。Python では int(nan) が例外になるので揃える
            if (!Double.isFinite(coords[i][0]) || !Double.isFinite(coords[i][1])) {
                throw new IllegalArgumentException("座標 " + i + " が有限の数でない");
            }
            xs[i] = coords[i][0];
            ys[i] = coords[i][1];
        }
        for (int d : demands) {
            if (d < 0) {
                throw new IllegalArgumentException("負の需要がある");
            }
            if (d > capacity) {
                throw new IllegalArgumentException("1台に積めない顧客が存在する");
            }
        }
        if (numVehicles.isPresent() && numVehicles.getAsInt() < 1) {
            throw new IllegalArgumentException("車両が1台もない");
        }
        this.demands = demands.clone();
        this.capacity = capacity;
        this.dist = buildDistanceMatrix(xs, ys);
    }

    /**
     * EUC_2D: ユークリッド距離を四捨五入して整数化する (CVRPLib の規約)。
     *
     * <p>Python 版 (model.build_distance_matrix) と同じ値になるよう、式の形まで揃える。
     * <ul>
     *   <li>2乗は掛け算で書く。掛け算と sqrt は IEEE 754 で結果が決まっているので、
     *       言語や OS が違っても同じ値になる (pow には正しく丸める保証が無い)
     *   <li>丸めは {@code (int) (d + 0.5)}。{@link Math#round} は使わない。
     *       0.49999999999999994 で Python の int(d + 0.5) は 1、Math.round は 0 になる
     * </ul>
     */
    static int euc2d(double dx, double dy) {
        double d = Math.sqrt(dx * dx + dy * dy) + 0.5;
        // (int) は収まらない値を黙って Integer.MAX_VALUE にするので、先に弾く
        if (!(d < 2147483648.0)) {
            throw new IllegalArgumentException("距離が int に収まらない: " + d);
        }
        return (int) d;
    }

    private static int[][] buildDistanceMatrix(double[] xs, double[] ys) {
        int m = xs.length;
        int[][] d = new int[m][m];
        for (int i = 0; i < m; i++) {
            for (int j = i + 1; j < m; j++) {
                d[i][j] = d[j][i] = euc2d(xs[i] - xs[j], ys[i] - ys[j]);
            }
        }
        return d;
    }

    public String name() {
        return name;
    }

    /** 顧客数 (depot を除く)。 */
    public int n() {
        return xs.length - 1;
    }

    public double x(int node) {
        return xs[node];
    }

    public double y(int node) {
        return ys[node];
    }

    public int demand(int node) {
        return demands[node];
    }

    public int capacity() {
        return capacity;
    }

    public OptionalInt numVehicles() {
        return numVehicles;
    }

    /** ノード i と j の距離。ソルバの内側で呼ぶので、範囲の検査は配列に任せる。 */
    public int dist(int i, int j) {
        return dist[i][j];
    }

    /** 距離行列のコピー (テストで Python の値と比べる用)。 */
    public int[][] distanceMatrix() {
        int[][] copy = new int[dist.length][];
        for (int i = 0; i < dist.length; i++) {
            copy[i] = dist[i].clone();
        }
        return copy;
    }

    /** 各ノードの {x, y} のコピー。 */
    public double[][] coords() {
        double[][] c = new double[xs.length][];
        for (int i = 0; i < xs.length; i++) {
            c[i] = new double[] {xs[i], ys[i]};
        }
        return c;
    }

    public int[] demands() {
        return demands.clone();
    }

    /** 値が同じなら等しい。距離行列は座標から決まるので比べない。 */
    @Override
    public boolean equals(Object o) {
        return o instanceof Instance that
                && name.equals(that.name)
                && Arrays.equals(xs, that.xs)
                && Arrays.equals(ys, that.ys)
                && Arrays.equals(demands, that.demands)
                && capacity == that.capacity
                && numVehicles.equals(that.numVehicles);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, Arrays.hashCode(xs), Arrays.hashCode(ys), Arrays.hashCode(demands),
                capacity, numVehicles);
    }

    @Override
    public String toString() {
        return "Instance[" + name + ", n=" + n() + ", Q=" + capacity + ", K="
                + (numVehicles.isPresent() ? numVehicles.getAsInt() : "上限なし") + "]";
    }
}
