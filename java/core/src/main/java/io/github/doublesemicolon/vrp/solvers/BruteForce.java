package io.github.doublesemicolon.vrp.solvers;

import io.github.doublesemicolon.vrp.Improvement;
import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Objective;
import io.github.doublesemicolon.vrp.Solution;
import io.github.doublesemicolon.vrp.SolveResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * 小規模 CVRP の全探索 (Python 版の solvers/bruteforce.py)。
 *
 * <p>参照実装として凍結する。速くする工夫は入れない。枝刈りや差分計算を入れた探索の
 * 正しさは「これと同じ答えに到達すること」で担保する。書き方も Python 版に合わせ、
 * 集合とリストで素直に書く (Python との対応が一目で分かることを速さより優先する)。
 */
public final class BruteForce {
    private BruteForce() {}

    /**
     * 実行可能解を全列挙し、見つけた順に visit に渡す。同じ解の重複は一切生成しない。
     *
     * <p>列挙の原理: 「未割当のうち最小の顧客 m」を必ず今作るルートに入れる、と決めて再帰する。
     * <ul>
     *   <li>ルートの並べ替え (k! 通り) -> 各ルートの最小顧客が昇順になるので代表は1つ
     *   <li>ルートの逆走 (2^k 通り) -> 先頭 &lt; 末尾 のものだけ生成する
     * </ul>
     * 顧客の組み合わせ・並べ方を試す順は Python の itertools.combinations / permutations と
     * 同じ (辞書順)。これが同じなので、同コストの最適解が複数あっても同じものを返す。
     *
     * @return 探索ノード数 (extend の呼び出し回数。Python の stats["nodes"] と同じ定義)
     */
    public static long enumerate(Instance inst, Consumer<Solution> visit) {
        TreeSet<Integer> all = new TreeSet<>();
        for (int c = 1; c <= inst.n(); c++) {
            all.add(c);
        }
        Enumerator e = new Enumerator(inst, visit);
        e.extend(all, new ArrayList<>());
        return e.nodes;
    }

    /** 全探索で最適解を求める。実行可能解が1つも無ければ IllegalArgumentException。 */
    public static SolveResult solve(Instance inst) {
        long t0 = System.nanoTime();
        Solution[] best = {null};
        long[] bestCost = {Long.MAX_VALUE};
        long[] nSolutions = {0};
        List<Improvement> history = new ArrayList<>();

        long nodes = enumerate(inst, sol -> {
            nSolutions[0]++;
            long c = Objective.solutionCost(inst, sol);
            if (c < bestCost[0]) {
                bestCost[0] = c;
                best[0] = sol;
                history.add(new Improvement(seconds(t0), c, nSolutions[0]));
            }
        });

        double elapsed = seconds(t0);
        if (best[0] == null) {
            throw new IllegalArgumentException(inst.name() + ": 実行可能解が存在しない");
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("nodes", nodes);
        stats.put("solutions", nSolutions[0]);
        return SolveResult.build(inst, "brute", best[0], elapsed, stats, history);
    }

    static double seconds(long t0) {
        return (System.nanoTime() - t0) / 1e9;
    }

    private static final class Enumerator {
        final Instance inst;
        final Consumer<Solution> visit;
        long nodes;

        Enumerator(Instance inst, Consumer<Solution> visit) {
            this.inst = inst;
            this.visit = visit;
        }

        void extend(TreeSet<Integer> remaining, List<List<Integer>> routes) {
            nodes++;

            if (remaining.isEmpty()) {
                visit.accept(new Solution(routes));   // 全員割当済み = 解が1つ完成
                return;
            }
            if (inst.numVehicles().isPresent() && routes.size() == inst.numVehicles().getAsInt()) {
                return;                                // 車が尽きた。行き止まり (空なら上限なし)
            }

            int m = remaining.first();                 // m は必ず「今作るルート」に入れる
            List<Integer> rest = new ArrayList<>(remaining.tailSet(m, false));

            for (int r = 0; r <= rest.size(); r++) {
                combinations(rest, r, 0, new ArrayList<>(), others -> {
                    List<Integer> s = new ArrayList<>();
                    s.add(m);
                    s.addAll(others);                  // m は rest のどれよりも小さいので s は昇順
                    long load = 0;
                    for (int c : s) {
                        load += inst.demand(c);
                    }
                    if (load > inst.capacity()) {
                        return;
                    }
                    permutations(s, new ArrayList<>(), new boolean[s.size()], order -> {
                        if (order.get(0) > order.get(order.size() - 1)) {
                            return;                    // 逆走の重複を潰す (2^k)
                        }
                        TreeSet<Integer> next = new TreeSet<>(remaining);
                        next.removeAll(s);
                        List<List<Integer>> nextRoutes = new ArrayList<>(routes);
                        nextRoutes.add(List.copyOf(order));
                        extend(next, nextRoutes);
                    });
                });
            }
        }
    }

    /** itertools.combinations(items, r) と同じ順 (辞書順) に列挙する。 */
    static void combinations(List<Integer> items, int r, int start, List<Integer> chosen,
                             Consumer<List<Integer>> out) {
        if (chosen.size() == r) {
            out.accept(chosen);
            return;
        }
        for (int i = start; i < items.size(); i++) {
            chosen.add(items.get(i));
            combinations(items, r, i + 1, chosen, out);
            chosen.remove(chosen.size() - 1);
        }
    }

    /** itertools.permutations(items) と同じ順 (位置の辞書順) に列挙する。 */
    static void permutations(List<Integer> items, List<Integer> chosen, boolean[] used,
                             Consumer<List<Integer>> out) {
        if (chosen.size() == items.size()) {
            out.accept(chosen);
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            if (used[i]) {
                continue;
            }
            used[i] = true;
            chosen.add(items.get(i));
            permutations(items, chosen, used, out);
            chosen.remove(chosen.size() - 1);
            used[i] = false;
        }
    }
}
