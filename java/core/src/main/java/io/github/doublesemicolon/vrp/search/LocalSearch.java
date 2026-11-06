package io.github.doublesemicolon.vrp.search;

import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.LongConsumer;

/** 近傍 1 つでの局所探索。複数の近傍を組み合わせるのは {@link Vnd}。 */
public final class LocalSearch {
    private LocalSearch() {}

    /**
     * 近傍の中で最もコストが下がる手を適用し続け、改善する手が無くなったら止まる
     * (best improvement。2-opt と同じ)。同じだけ下がる手が複数あれば、最初に渡されたもの。
     *
     * @param onMove 手を 1 つ適用するたびに、適用後のコストを受け取る
     * @return 適用した手の数
     */
    public static long descend(Routes s, Neighborhood nb, LongConsumer onMove) {
        long applied = 0;
        while (step(s, nb)) {
            applied++;
            onMove.accept(s.cost());
        }
        return applied;
    }

    /** 最もコストが下がる手を 1 つ適用する。改善する手が無ければ何もせず false。 */
    static boolean step(Routes s, Neighborhood nb) {
        return step(s, nb, c -> true, null);
    }

    /**
     * look で起点を絞って走査し、最もコストが下がる手を 1 つ適用する。
     * improvingOrigin が null でなければ、改善する手の起点の顧客を (重複を含めて) 全て渡す。
     */
    static boolean step(Routes s, Neighborhood nb, IntPredicate look, IntConsumer improvingOrigin) {
        Move[] best = {null};
        nb.scan(s, look, m -> {
            if (m.delta() < 0) {
                if (improvingOrigin != null) {
                    improvingOrigin.accept(m.origin(s));
                }
                if (best[0] == null || m.delta() < best[0].delta()) {
                    best[0] = m;
                }
            }
        });
        if (best[0] == null) {
            return false;
        }
        long before = s.cost();
        best[0].apply(s);
        // Routes のコストは差し替えたルートから計算し直した値なので、delta と独立に決まる。
        // 食い違うのは差分計算のバグで、放っておくと「改善した」と思い込んで止まらなくなる
        if (s.cost() != before + best[0].delta()) {
            throw new IllegalStateException(nb.name() + " の delta " + best[0].delta() + " が実際の変化 "
                    + (s.cost() - before) + " と違う: " + best[0]);
        }
        return true;
    }
}
