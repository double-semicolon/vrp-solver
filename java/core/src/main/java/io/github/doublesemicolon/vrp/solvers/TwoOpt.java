package io.github.doublesemicolon.vrp.solvers;

import io.github.doublesemicolon.vrp.Improvement;
import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Objective;
import io.github.doublesemicolon.vrp.Solution;
import io.github.doublesemicolon.vrp.SolveResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 2-opt によるルート内の局所探索 (Python 版の solvers/two_opt.py)。
 *
 * <p>ルートの中の辺 2 本を張り替える (= 途中の区間を逆順に辿る) 近傍。
 * <pre>
 *     変更前:  ... a -> b ...... c -> d ...
 *     変更後:  ... a -> c ...... b -> d ...     (b..c の区間を逆順に辿る)
 * </pre>
 * 距離が対称なので、逆順にした区間の内部の距離は変わらない。変わるのは境界の 2 本だけで、
 * 差分の計算は O(1) で済む。ルートをまたがないので、積載量もルート数も変わらない。
 */
public final class TwoOpt {
    private TwoOpt() {}

    /**
     * route の i..j を逆順にしたときに縮む距離。正なら改善。route は depot を含まないので、
     * 端では depot (0) を補う。消える辺は (a,b) と (c,d)、生まれる辺は (a,c) と (b,d)。
     */
    static long gain(Instance inst, List<Integer> route, int i, int j) {
        int a = i > 0 ? route.get(i - 1) : 0;
        int b = route.get(i);
        int c = route.get(j);
        int d = j < route.size() - 1 ? route.get(j + 1) : 0;
        return (long) inst.dist(a, b) + inst.dist(c, d) - inst.dist(a, c) - inst.dist(b, d);
    }

    /**
     * 最も縮む move を 1 つ route に適用し、縮んだ距離を返す (改善が無ければ 0)。
     *
     * <p>同点 (縮む距離が同じ move が複数) のときは、i が最小のもの、その中で j が最小のものを
     * 選ぶ。i, j の昇順に見て、真に大きいときだけ更新しているのでこうなる。Python 版と同じ
     * 決め方で、解が完全に一致することを突き合わせている。&gt;= に変えると最後に見つけた
     * 同点の move を選ぶようになり、解が変わる。
     */
    static long improveOnce(Instance inst, List<Integer> route) {
        int m = route.size();
        long bestGain = 0;
        int bestI = -1;
        int bestJ = -1;
        for (int i = 0; i < m - 1; i++) {
            for (int j = i + 1; j < m; j++) {
                long g = gain(inst, route, i, j);
                if (g > bestGain) {
                    bestGain = g;
                    bestI = i;
                    bestJ = j;
                }
            }
        }
        if (bestI >= 0) {
            Collections.reverse(route.subList(bestI, bestJ + 1));
        }
        return bestGain;
    }

    /**
     * 全ルートを、どの move も改善しなくなるまで磨く。引数の解は壊さず、新しい解を返す。
     *
     * <p>onImprove(適用した move の通し番号, 差分で積み上げたコスト) は move を 1 つ適用する
     * たびに呼ばれる。積み上げたコストはそのままでは信用しない (最終的なコストは
     * {@link SolveResult#build} が計算し直す)。
     */
    public static Solution improve(Instance inst, Solution sol, BiConsumer<Long, Long> onImprove) {
        List<List<Integer>> out = new ArrayList<>();
        for (List<Integer> r : sol.routes()) {
            out.add(new ArrayList<>(r));
        }
        long cost = Objective.solutionCost(inst, sol);
        long applied = 0;
        for (List<Integer> route : out) {
            long g;
            while ((g = improveOnce(inst, route)) > 0) {
                cost -= g;
                applied++;
                onImprove.accept(applied, cost);
            }
        }
        return new Solution(out);
    }

    /** 最近傍法で作った解を 2-opt で磨く。 */
    public static SolveResult solve(Instance inst) {
        long t0 = System.nanoTime();
        Solution start = NearestNeighbor.build(inst);
        // iteration は適用した move の通し番号。0 番は磨く前の初期解
        List<Improvement> history = new ArrayList<>();
        history.add(new Improvement(BruteForce.seconds(t0), Objective.solutionCost(inst, start), 0));
        Solution sol = improve(inst, start,
                (applied, cost) -> history.add(new Improvement(BruteForce.seconds(t0), cost, applied)));
        double elapsed = BruteForce.seconds(t0);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("routes", sol.numRoutes());
        stats.put("moves", history.get(history.size() - 1).iteration().getAsLong());
        return SolveResult.build(inst, "nn+2opt", sol, elapsed, stats, history);
    }
}
