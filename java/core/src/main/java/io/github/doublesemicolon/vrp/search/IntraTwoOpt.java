package io.github.doublesemicolon.vrp.search;

import io.github.doublesemicolon.vrp.Instance;
import java.util.function.Consumer;

/**
 * ルート内の 2-opt を近傍の形にしたもの。中身は Step 1 の {@link io.github.doublesemicolon.vrp.solvers.TwoOpt}
 * と同じで、ルートの i..j 番目を逆順に辿る。
 * <pre>
 *     前:  ... a -> b ...... c -> d ...
 *     後:  ... a -> c ...... b -> d ...
 * </pre>
 * delta = d(a,c) + d(b,d) - d(a,b) - d(c,d)。ルートをまたがないので積載量もルート数も変わらない。
 *
 * <p>ルート全体を逆順にする手 (delta は 0) も出す。ルートの向きが変わるので、解としては別物になる。
 * 手を出す順番 (ルート、i、j の昇順) と {@link LocalSearch} の同点の決め方 (最初に渡された手) が
 * Step 1 の 2-opt の決め方 (i 最小、その中で j 最小) と同じなので、局所探索の結果も一致する。
 */
public final class IntraTwoOpt implements Neighborhood {
    @Override
    public String name() {
        return "2-opt";
    }

    /** ルート r の i..j 番目を逆順にする (i < j)。 */
    record IntraTwoOptMove(int r, int i, int j, long delta) implements Move {
        @Override
        public void apply(Routes s) {
            int[] a = s.nodesCopy(r);
            for (int lo = i, hi = j; lo < hi; lo++, hi--) {
                int t = a[lo];
                a[lo] = a[hi];
                a[hi] = t;
            }
            s.replace(r, a);
        }
    }

    @Override
    public void scan(Routes s, Consumer<Move> out) {
        Instance inst = s.instance();
        for (int r = 0; r < s.count(); r++) {
            int len = s.size(r);
            for (int i = 0; i < len - 1; i++) {
                int a = s.prev(r, i);
                int b = s.node(r, i);
                long ab = inst.dist(a, b);
                for (int j = i + 1; j < len; j++) {
                    int c = s.node(r, j);
                    int d = s.next(r, j);
                    out.accept(new IntraTwoOptMove(r, i, j,
                            (long) inst.dist(a, c) + inst.dist(b, d) - ab - inst.dist(c, d)));
                }
            }
        }
    }
}
