package io.github.doublesemicolon.vrp.search;

import io.github.doublesemicolon.vrp.Instance;
import java.util.function.Consumer;

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
 */
public final class Swap implements Neighborhood {
    @Override
    public String name() {
        return "swap";
    }

    /** ルート r1 の p1 番目と、ルート r2 の p2 番目を入れ替える。r1 == r2 のときは p1 < p2。 */
    record SwapMove(int r1, int p1, int r2, int p2, long delta) implements Move {
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
    public void scan(Routes s, Consumer<Move> out) {
        Instance inst = s.instance();
        int q = inst.capacity();
        for (int r1 = 0; r1 < s.count(); r1++) {
            int len1 = s.size(r1);
            for (int p1 = 0; p1 < len1; p1++) {
                int u = s.node(r1, p1);
                int a = s.prev(r1, p1);
                int b = s.next(r1, p1);

                // 同じルートの中 (p1 < p2)
                for (int p2 = p1 + 1; p2 < len1; p2++) {
                    int v = s.node(r1, p2);
                    int y = s.next(r1, p2);
                    long delta;
                    if (p2 == p1 + 1) {
                        delta = (long) inst.dist(a, v) + inst.dist(u, y) - inst.dist(a, u) - inst.dist(v, y);
                    } else {
                        int x = s.prev(r1, p2);
                        delta = exchange(inst, a, u, b, v) + exchange(inst, x, v, y, u);
                    }
                    out.accept(new SwapMove(r1, p1, r1, p2, delta));
                }

                // 別のルート (r1 < r2)
                for (int r2 = r1 + 1; r2 < s.count(); r2++) {
                    int len2 = s.size(r2);
                    if (len1 == 1 && len2 == 1) {
                        continue;
                    }
                    for (int p2 = 0; p2 < len2; p2++) {
                        int v = s.node(r2, p2);
                        long d = (long) inst.demand(v) - inst.demand(u);
                        if (s.load(r1) + d > q || s.load(r2) - d > q) {
                            continue;
                        }
                        int x = s.prev(r2, p2);
                        int y = s.next(r2, p2);
                        out.accept(new SwapMove(r1, p1, r2, p2, exchange(inst, a, u, b, v) + exchange(inst, x, v, y, u)));
                    }
                }
            }
        }
    }

    /** prev -> old -> next の old を repl に置き換えたときの変化。 */
    private static long exchange(Instance inst, int prev, int old, int next, int repl) {
        return (long) inst.dist(prev, repl) + inst.dist(repl, next) - inst.dist(prev, old) - inst.dist(old, next);
    }
}
