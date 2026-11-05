package io.github.doublesemicolon.vrp.search;

import io.github.doublesemicolon.vrp.Improvement;
import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.SolveResult;
import io.github.doublesemicolon.vrp.solvers.NearestNeighbor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VND (variable neighborhood descent): 近傍を順番に並べ、今の近傍で改善できなくなったら次の近傍へ進む。
 * 改善できたら最初の近傍に戻る。最後の近傍でも改善できなければ、全ての近傍で局所最適。
 *
 * <p>既定の順番 ({@link #DEFAULT}) は、1 回の走査が軽い近傍から重い近傍へ:
 * <ol>
 *   <li>2-opt (ルート内): 手の数はルートの長さの 2 乗の和で、ルート間の近傍より桁違いに少ない。
 *       最初に置くと、最初の段階が Step 1 の最近傍法 + 2-opt と全く同じになるので、
 *       VND の結果はそれより悪くならない
 *   <li>relocate: 1 人を動かす。顧客数を n として約 n^2
 *   <li>swap: 2 人の組。約 n^2 / 2 だが、別ルートでは容量で落ちる組が多い
 *   <li>2-opt*: ルートの組ごとの切れ目の組。約 n^2 / 2
 *   <li>Or-opt: 区間の長さ 2 通り × 向き 2 通りで、relocate の約 4 倍
 * </ol>
 * 改善のたびに最初へ戻るので、重い近傍は軽い近傍がすべて局所最適になったときだけ走査する。
 */
public final class Vnd {
    private Vnd() {}

    public static final List<Neighborhood> DEFAULT =
            List.of(new IntraTwoOpt(), new Relocate(), new Swap(), new TwoOptStar(), new OrOpt());

    /** 近傍の番号と、手を適用した後のコストを受け取る。 */
    @FunctionalInterface
    public interface OnMove {
        void accept(int neighborhood, long cost);
    }

    /**
     * @return 近傍ごとの、適用した手の数
     */
    public static long[] descend(Routes s, List<Neighborhood> nbs, OnMove onMove) {
        long[] applied = new long[nbs.size()];
        int k = 0;
        while (k < nbs.size()) {
            if (LocalSearch.step(s, nbs.get(k))) {
                applied[k]++;
                onMove.accept(k, s.cost());
                k = 0;
            } else {
                k++;
            }
        }
        return applied;
    }

    /** 最近傍法の解を、既定の順番の VND で磨く。 */
    public static SolveResult solve(Instance inst) {
        long t0 = System.nanoTime();
        Routes s = Routes.of(inst, NearestNeighbor.build(inst));
        // iteration は適用した手の通し番号。0 番は磨く前の初期解
        List<Improvement> history = new ArrayList<>();
        history.add(new Improvement(seconds(t0), s.cost(), 0));
        long[] applied = descend(s, DEFAULT,
                (k, cost) -> history.add(new Improvement(seconds(t0), cost, history.size())));
        double elapsed = seconds(t0);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("routes", (long) s.count());
        stats.put("moves", (long) history.size() - 1);
        for (int k = 0; k < DEFAULT.size(); k++) {
            stats.put("moves_" + DEFAULT.get(k).name(), applied[k]);
        }
        return SolveResult.build(inst, "nn+vnd", s.toSolution(), elapsed, stats, history);
    }

    private static double seconds(long t0) {
        return (System.nanoTime() - t0) / 1e9;
    }
}
