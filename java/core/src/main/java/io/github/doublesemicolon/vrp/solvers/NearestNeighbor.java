package io.github.doublesemicolon.vrp.solvers;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Solution;
import io.github.doublesemicolon.vrp.SolveResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 最近傍法による構築 (Python 版の solvers/nearest_neighbor.py)。branch and bound の初期上界にも使う。
 */
public final class NearestNeighbor {
    private NearestNeighbor() {}

    /**
     * 現在地から最も近い未訪問顧客のうち、容量に収まるものへ移動する。
     * 収まるものが無ければ depot に戻って次の車を出す。
     *
     * <p>車両数上限 K は見ていないので、K 台を超える解 (実行不可能解) を返しうる。
     * 使う側が {@code Objective.isFeasible} で確かめること。
     */
    public static Solution build(Instance inst) {
        int n = inst.n();
        boolean[] visited = new boolean[n + 1];
        int left = n;
        List<List<Integer>> sol = new ArrayList<>();

        while (left > 0) {
            List<Integer> route = new ArrayList<>();
            long load = 0;
            int cur = 0;
            while (true) {
                // 同距離のときは顧客番号の小さい方を選ぶ (Python の min(key=(D, c)) と同じ)。
                // 番号の昇順に見て、真に近いときだけ更新すれば、同距離では先に見た小さい番号が残る
                int nxt = -1;
                for (int c = 1; c <= n; c++) {
                    if (!visited[c] && load + inst.demand(c) <= inst.capacity()
                            && (nxt < 0 || inst.dist(cur, c) < inst.dist(cur, nxt))) {
                        nxt = c;
                    }
                }
                if (nxt < 0) {
                    break;
                }
                route.add(nxt);
                load += inst.demand(nxt);
                visited[nxt] = true;
                left--;
                cur = nxt;
            }
            // 空ルートを積むと残りが減らず無限に回る。Instance が「1台に積めない顧客」を
            // 弾いている限り到達しない
            if (route.isEmpty()) {
                throw new IllegalStateException("空の車にも積めない顧客が残っている");
            }
            sol.add(route);
        }
        return new Solution(sol);
    }

    /** SolveResult の形で返す。K 台を超えたら feasible=false のまま返す。 */
    public static SolveResult solve(Instance inst) {
        long t0 = System.nanoTime();
        Solution sol = build(inst);
        double elapsed = BruteForce.seconds(t0);
        return SolveResult.build(inst, "nn", sol, elapsed, Map.of("routes", sol.numRoutes()), List.of());
    }
}
