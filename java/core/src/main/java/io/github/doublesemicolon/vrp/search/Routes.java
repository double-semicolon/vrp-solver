package io.github.doublesemicolon.vrp.search;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Solution;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 局所探索の中で書き換える解。{@link Solution} は変更できないので、探索の間はこちらを使う。
 *
 * <p>各ルートを {@code int[]} で持つ。探索では手の評価 (読むだけ) が適用より桁違いに多いので、
 * 読む速さを優先する ({@code List<Integer>} は値を読むたびに箱から取り出す)。手を適用するときは、
 * 変わったルートの配列を作り直して差し替える。配列そのものは書き換えないので、{@link #copy()} は
 * 配列を共有してよい。
 *
 * <p>ルートごとの積載量とコスト、全体のコストも持つ。どれも差し替えたルートから計算し直す値で、
 * 手の delta を積み上げた値ではない (差分計算のバグがここに紛れ込まないように)。
 * 空のルートは持たない。ルート数がそのまま使う車両数になる。
 *
 * <p>顧客ごとに、いまどのルートの何番目にいるかも持つ (近傍リストで絞った近傍が、
 * 近くの顧客の位置を引くのに使う)。これもルートを差し替えるたびに数え直す。
 */
public final class Routes {
    private final Instance inst;
    private final List<int[]> nodes;
    private final List<Long> loads;
    private final List<Long> costs;
    private long cost;
    private final int[] routeOf;
    private final int[] posOf;

    private Routes(Instance inst, List<int[]> nodes, List<Long> loads, List<Long> costs, long cost,
                   int[] routeOf, int[] posOf) {
        this.inst = inst;
        this.nodes = nodes;
        this.loads = loads;
        this.costs = costs;
        this.cost = cost;
        this.routeOf = routeOf;
        this.posOf = posOf;
    }

    /** 空のルートは落とす。顧客番号の範囲や重複は検査しない (実行可能性は呼び出し側の前提)。 */
    public static Routes of(Instance inst, Solution sol) {
        int[] routeOf = new int[inst.n() + 1];
        Arrays.fill(routeOf, -1);
        Routes s = new Routes(inst, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), 0,
                routeOf, new int[inst.n() + 1]);
        for (List<Integer> r : sol.routes()) {
            if (!r.isEmpty()) {
                s.add(r.stream().mapToInt(Integer::intValue).toArray());
            }
        }
        return s;
    }

    public Routes copy() {
        return new Routes(inst, new ArrayList<>(nodes), new ArrayList<>(loads), new ArrayList<>(costs), cost,
                routeOf.clone(), posOf.clone());
    }

    public Solution toSolution() {
        return new Solution(nodes.stream().map(r -> Arrays.stream(r).boxed().toList()).toList());
    }

    public Instance instance() {
        return inst;
    }

    public int count() {
        return nodes.size();
    }

    public int size(int r) {
        return nodes.get(r).length;
    }

    public int node(int r, int i) {
        return nodes.get(r)[i];
    }

    /** ルート r の i 番目の 1 つ前。先頭なら depot (0)。 */
    public int prev(int r, int i) {
        return i == 0 ? 0 : nodes.get(r)[i - 1];
    }

    /** ルート r の i 番目の 1 つ後。末尾なら depot (0)。 */
    public int next(int r, int i) {
        int[] a = nodes.get(r);
        return i == a.length - 1 ? 0 : a[i + 1];
    }

    /** 顧客 c がいるルートの番号。どのルートにもいなければ -1。 */
    public int routeOf(int c) {
        return routeOf[c];
    }

    /** 顧客 c がルートの何番目にいるか。 */
    public int posOf(int c) {
        return posOf[c];
    }

    public long load(int r) {
        return loads.get(r);
    }

    public long cost() {
        return cost;
    }

    /** ルートを 1 本増やしても車両数の上限に収まるか。 */
    public boolean canAddRoute() {
        return inst.numVehicles().isEmpty() || count() < inst.numVehicles().getAsInt();
    }

    // ---------------------------------------------------------------- 顧客 1 人ずつの出し入れ (ALNS の破壊と修復)

    /**
     * 顧客 c をルートから抜く。ルートが空になったら消す。抜いた後の解は全顧客を訪問しない
     * (ALNS の修復で入れ直す)。抜いた顧客の {@link #routeOf} は -1 になる。
     */
    public void removeCustomer(int c) {
        int r = routeOf[c];
        if (r < 0) {
            throw new IllegalArgumentException("顧客 " + c + " はどのルートにもいない");
        }
        int p = posOf[c];
        int[] src = nodes.get(r);
        int[] out = new int[src.length - 1];
        System.arraycopy(src, 0, out, 0, p);
        System.arraycopy(src, p + 1, out, p, out.length - p);
        if (out.length == 0) {
            remove(r);
        } else {
            replace(r, out);
        }
        routeOf[c] = -1;
    }

    /** 顧客 c をルート r の pos 番目に入れる。r == count() なら新しいルートを作る。容量や車両数は確かめない。 */
    public void insertCustomer(int c, int r, int pos) {
        if (routeOf[c] >= 0) {
            throw new IllegalArgumentException("顧客 " + c + " は既にルート " + routeOf[c] + " にいる");
        }
        if (r == count()) {
            add(new int[] {c});
            return;
        }
        int[] src = nodes.get(r);
        int[] out = new int[src.length + 1];
        System.arraycopy(src, 0, out, 0, pos);
        out[pos] = c;
        System.arraycopy(src, pos, out, pos + 1, src.length - pos);
        replace(r, out);
    }

    // ---------------------------------------------------------------- 手の適用 (Move から呼ぶ)

    /** ルート r の配列のコピー。手はこれを組み替えて {@link #replace} に渡す。 */
    int[] nodesCopy(int r) {
        return nodes.get(r).clone();
    }

    void replace(int r, int[] route) {
        if (route.length == 0) {
            throw new IllegalArgumentException("空のルートは remove で消す");
        }
        cost += routeCost(route) - costs.get(r);
        nodes.set(r, route);
        loads.set(r, routeLoad(route));
        costs.set(r, routeCost(route));
        index(r);
    }

    void add(int[] route) {
        if (route.length == 0) {
            throw new IllegalArgumentException("空のルートは足さない");
        }
        long c = routeCost(route);
        nodes.add(route);
        loads.add(routeLoad(route));
        costs.add(c);
        cost += c;
        index(nodes.size() - 1);
    }

    void remove(int r) {
        cost -= costs.get(r);
        nodes.remove(r);
        loads.remove(r);
        costs.remove(r);
        // 後ろのルートの番号が 1 つずつ詰まる
        for (int k = r; k < nodes.size(); k++) {
            index(k);
        }
    }

    private void index(int r) {
        int[] route = nodes.get(r);
        for (int i = 0; i < route.length; i++) {
            routeOf[route[i]] = r;
            posOf[route[i]] = i;
        }
    }

    private long routeCost(int[] route) {
        long c = inst.dist(0, route[0]) + inst.dist(route[route.length - 1], 0);
        for (int k = 0; k + 1 < route.length; k++) {
            c += inst.dist(route[k], route[k + 1]);
        }
        return c;
    }

    private long routeLoad(int[] route) {
        long load = 0;
        for (int c : route) {
            load += inst.demand(c);
        }
        return load;
    }
}
