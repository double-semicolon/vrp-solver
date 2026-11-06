package io.github.doublesemicolon.vrp.search;

import io.github.doublesemicolon.vrp.Improvement;
import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.SolveResult;
import io.github.doublesemicolon.vrp.solvers.NearestNeighbor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

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
 *
 * <p>速くする仕組みを 2 つ選べる ({@link Config})。どちらも調べる手を減らすので、質が落ちうる。
 * <ul>
 *   <li>近傍リスト: relocate と swap の相手を、各顧客の近くの k 人に関係する手に絞る
 *       (この 2 つが走査時間の 9 割を占める)
 *   <li>don't-look bits: 近傍ごと・顧客ごとのビット。ある顧客を起点にした手に改善が無かったら立て、
 *       以後その近傍ではその顧客を起点にしない。手を適用したら、前後の顧客が変わった顧客のビットを
 *       全ての近傍で倒す。離れた顧客にとって良い移し先ができても気づけないのが弱点
 * </ul>
 * どちらも使わなければ、使う仕組みを足す前と全く同じ探索になる。
 */
public final class Vnd {
    private Vnd() {}

    public static final List<Neighborhood> DEFAULT = neighborhoods(null);

    /** 既定の順番の近傍。near が null なら relocate と swap を絞らない。 */
    public static List<Neighborhood> neighborhoods(NeighborList near) {
        return List.of(new IntraTwoOpt(), new Relocate(near), new Swap(near), new TwoOptStar(), new OrOpt());
    }

    /**
     * @param k         近傍リストの人数。空なら絞らない
     * @param dontLook  don't-look bits を使うか
     */
    public record Config(OptionalInt k, boolean dontLook) {
        /** 絞らない。#25 の VND と同じ探索。 */
        public static final Config FULL = new Config(OptionalInt.empty(), false);

        /**
         * 既定: 近傍リスト 20 人、don't-look bits なし。X set では絞らない場合と gap が同等
         * (+7.24% と +7.49%) で 3.3 倍速い。k = 10 も同等だったが、差が誤差程度なら絞りすぎない方にする。
         * don't-look bits はさらに 5 倍速いが gap が約 1 ポイント悪くなるので、既定にはしない
         * (ALNS の中で何度も呼ぶときに使うかは #28 で決める)。
         */
        public static final Config DEFAULT = new Config(OptionalInt.of(20), false);
    }

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

    /** don't-look bits を使う VND。近傍の進め方は {@link #descend(Routes, List, OnMove)} と同じ。 */
    public static long[] descendDontLook(Routes s, List<Neighborhood> nbs, OnMove onMove) {
        int n = s.instance().n();
        boolean[][] skip = new boolean[nbs.size()][n + 1];   // true = 見ない
        long[] applied = new long[nbs.size()];
        int k = 0;
        while (k < nbs.size()) {
            boolean[] bits = skip[k];
            boolean[] improving = new boolean[n + 1];
            int[][] before = adjacency(s);
            boolean moved = LocalSearch.step(s, nbs.get(k), c -> !bits[c], c -> improving[c] = true);
            for (int c = 1; c <= n; c++) {
                if (!improving[c]) {
                    bits[c] = true;
                }
            }
            if (moved) {
                int[][] after = adjacency(s);
                for (int c = 1; c <= n; c++) {
                    if (before[0][c] != after[0][c] || before[1][c] != after[1][c]) {
                        for (boolean[] b : skip) {
                            b[c] = false;
                        }
                    }
                }
                applied[k]++;
                onMove.accept(k, s.cost());
                k = 0;
            } else {
                k++;
            }
        }
        return applied;
    }

    /** 顧客ごとの前と後ろ ({前, 後ろ}、depot は 0)。 */
    static int[][] adjacency(Routes s) {
        int n = s.instance().n();
        int[] prev = new int[n + 1];
        int[] next = new int[n + 1];
        for (int r = 0; r < s.count(); r++) {
            for (int i = 0; i < s.size(r); i++) {
                int c = s.node(r, i);
                prev[c] = s.prev(r, i);
                next[c] = s.next(r, i);
            }
        }
        return new int[][] {prev, next};
    }

    /** 最近傍法の解を、既定の順番で絞らない VND で磨く。 */
    public static SolveResult solve(Instance inst) {
        return solve(inst, Config.FULL, "nn+vnd");
    }

    /** 最近傍法の解を、既定の順番の VND で磨く。近傍リストを作る時間も含めて測る。 */
    public static SolveResult solve(Instance inst, Config config, String solverName) {
        long t0 = System.nanoTime();
        NeighborList near = config.k().isPresent() ? NeighborList.of(inst, config.k().getAsInt()) : null;
        List<Neighborhood> nbs = neighborhoods(near);
        Routes s = Routes.of(inst, NearestNeighbor.build(inst));
        // iteration は適用した手の通し番号。0 番は磨く前の初期解
        List<Improvement> history = new ArrayList<>();
        history.add(new Improvement(seconds(t0), s.cost(), 0));
        OnMove record = (k, cost) -> history.add(new Improvement(seconds(t0), cost, history.size()));
        long[] applied = config.dontLook() ? descendDontLook(s, nbs, record) : descend(s, nbs, record);
        double elapsed = seconds(t0);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("routes", (long) s.count());
        stats.put("moves", (long) history.size() - 1);
        for (int k = 0; k < nbs.size(); k++) {
            stats.put("moves_" + nbs.get(k).name(), applied[k]);
        }
        return SolveResult.build(inst, solverName, s.toSolution(), elapsed, stats, history);
    }

    private static double seconds(long t0) {
        return (System.nanoTime() - t0) / 1e9;
    }
}
