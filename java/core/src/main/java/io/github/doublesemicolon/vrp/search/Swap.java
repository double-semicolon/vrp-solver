package io.github.doublesemicolon.vrp.search;

import io.github.doublesemicolon.vrp.Instance;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/**
 * swap: 2 人の顧客の位置を入れ替える。同じルートの中でも、別のルート同士でもよい。
 * <pre>
 *     ... a -> u -> b ...  ... x -> v -> y ...   は   ... a -> v -> b ...  ... x -> u -> y ...
 * </pre>
 * 離れた 2 人なら、u の前後 (a, b) と v の前後 (x, y) の辺がそれぞれ付け替わる。
 * 同じルートで隣り合う 2 人 (a -> u -> v -> b) は、辺 (u, v) を共有しているので式が違う:
 * a -> v -> u -> b になり、変わるのは (a, u) と (v, b) が (a, v) と (u, b) になる分だけ。
 *
 * <p>別のルート同士では、入れ替えた後の両方のルートで容量を確かめる。
 * 1 人ずつのルート同士の入れ替えは、ルートの並び順が変わるだけなので出さない。
 *
 * <p>近傍リストを渡すと、片方がもう片方の近くの k 人に入っている組だけに絞る。組は 1 回だけ出す:
 * v が u の近くの k 人なら u を起点に出すが、u も v の近くの k 人で v の番号の方が小さければ、v を起点に出す。
 */
public final class Swap implements Neighborhood {
    private final NeighborList near;   // null なら絞らない

    public Swap() {
        this(null);
    }

    public Swap(NeighborList near) {
        this.near = near;
    }

    @Override
    public String name() {
        return "swap";
    }

    /**
     * ルート r1 の p1 番目と、ルート r2 の p2 番目を入れ替える。絞らないときは、r1 == r2 なら p1 < p2、
     * そうでなければ r1 < r2。近傍リストで絞ったときは、(r1, p1) が起点の顧客で、順序は決めない。
     */
    record SwapMove(int r1, int p1, int r2, int p2, long delta) implements Move {
        /** ルート r1 の p1 番目の顧客。 */
        @Override
        public int origin(Routes s) {
            return s.node(r1, p1);
        }

        @Override
        public void apply(Routes s) {
            if (r1 == r2) {
                int[] a = s.nodesCopy(r1);
                int t = a[p1];
                a[p1] = a[p2];
                a[p2] = t;
                s.replace(r1, a);
                return;
            }
            int[] a = s.nodesCopy(r1);
            int[] b = s.nodesCopy(r2);
            int t = a[p1];
            a[p1] = b[p2];
            b[p2] = t;
            s.replace(r1, a);
            s.replace(r2, b);
        }
    }

    @Override
    public void scan(Routes s, IntPredicate look, Consumer<Move> out) {
        for (int r1 = 0; r1 < s.count(); r1++) {
            int len1 = s.size(r1);
            for (int p1 = 0; p1 < len1; p1++) {
                int u = s.node(r1, p1);
                if (!look.test(u)) {
                    continue;
                }
                if (near != null) {
                    scanNear(s, r1, p1, u, out);
                    continue;
                }
                // 同じルートの中 (p1 < p2)
                for (int p2 = p1 + 1; p2 < len1; p2++) {
                    out.accept(new SwapMove(r1, p1, r1, p2, intraDelta(s, r1, p1, p2)));
                }

                // 別のルート (r1 < r2)
                for (int r2 = r1 + 1; r2 < s.count(); r2++) {
                    int len2 = s.size(r2);
                    if (len1 == 1 && len2 == 1) {
                        continue;
                    }
                    for (int p2 = 0; p2 < len2; p2++) {
                        if (fits(s, r1, p1, r2, p2)) {
                            out.accept(new SwapMove(r1, p1, r2, p2, interDelta(s, r1, p1, r2, p2)));
                        }
                    }
                }
            }
        }
    }

    private void scanNear(Routes s, int r1, int p1, int u, Consumer<Move> out) {
        for (int v : near.near(u)) {
            if (v < u && near.contains(v, u)) {
                continue;   // v を起点に出す
            }
            int r2 = s.routeOf(v);
            int p2 = s.posOf(v);
            if (r1 == r2) {
                out.accept(new SwapMove(r1, p1, r2, p2, intraDelta(s, r1, Math.min(p1, p2), Math.max(p1, p2))));
            } else if (!(s.size(r1) == 1 && s.size(r2) == 1) && fits(s, r1, p1, r2, p2)) {
                out.accept(new SwapMove(r1, p1, r2, p2, interDelta(s, r1, p1, r2, p2)));
            }
        }
    }

    /** 同じルートの p1 番目と p2 番目 (p1 < p2) を入れ替えたときの変化。 */
    private static long intraDelta(Routes s, int r, int p1, int p2) {
        Instance inst = s.instance();
        int u = s.node(r, p1);
        int v = s.node(r, p2);
        int a = s.prev(r, p1);
        int y = s.next(r, p2);
        if (p2 == p1 + 1) {
            return (long) inst.dist(a, v) + inst.dist(u, y) - inst.dist(a, u) - inst.dist(v, y);
        }
        return exchange(inst, a, u, s.next(r, p1), v) + exchange(inst, s.prev(r, p2), v, y, u);
    }

    /** 別のルートの 2 人を入れ替えたときの変化。 */
    private static long interDelta(Routes s, int r1, int p1, int r2, int p2) {
        Instance inst = s.instance();
        int u = s.node(r1, p1);
        int v = s.node(r2, p2);
        return exchange(inst, s.prev(r1, p1), u, s.next(r1, p1), v) + exchange(inst, s.prev(r2, p2), v, s.next(r2, p2), u);
    }

    /** 別のルートの 2 人を入れ替えても、両方のルートが容量に収まるか。 */
    private static boolean fits(Routes s, int r1, int p1, int r2, int p2) {
        Instance inst = s.instance();
        long d = (long) inst.demand(s.node(r2, p2)) - inst.demand(s.node(r1, p1));
        return s.load(r1) + d <= inst.capacity() && s.load(r2) - d <= inst.capacity();
    }

    /** prev -> old -> next の old を repl に置き換えたときの変化。 */
    private static long exchange(Instance inst, int prev, int old, int next, int repl) {
        return (long) inst.dist(prev, repl) + inst.dist(repl, next) - inst.dist(prev, old) - inst.dist(old, next);
    }
}
