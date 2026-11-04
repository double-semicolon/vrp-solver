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
import java.util.function.BiConsumer;

/**
 * コスト下界による枝刈り付きの深さ優先探索 (Python 版の solvers/branch_and_bound.py)。
 *
 * <p>列挙の骨格は {@link BruteForce#enumerate} と同一で、試す順も同じ (顧客は常に番号の昇順)。
 * 探索ノード数・刈った数・見つけた解の順序まで Python 版と一致する。
 *
 * <p>枝刈りの根拠: 距離が非負なので、確定済みルートの合計コストが暫定最適以上なら、
 * その先にどんな解があっても暫定最適を下回れない。同コストの解も捨てる (&gt;= で刈る)。
 *
 * <p>上界は数値ではなく「暫定解 (incumbent)」として持つ。数値だけだと、それがたまたま
 * 最適コストと等しいときに全ての解が刈られ、上界を達成する解を持たないまま終わる。
 *
 * <p>Python 版との違い: 未割当の顧客を集合ではなく int のビット集合で持つ (n &lt;= 30)。
 * 下位のビットから見れば番号の昇順になるので、探索順は変わらない。また、初期解の渡し方を
 * 引数の組み合わせではなく入口のメソッドで分けたので、「incumbent と最近傍法の初期解を
 * 同時に指定する」誤りはそもそも書けない (Python 版では実行時に ValueError にしている)。
 */
public final class BranchAndBound {
    private final Instance inst;
    private final boolean prune;
    private Solution best;
    private long bestCost;
    // 統計。nodes は extend の呼び出し回数で、BruteForce の nodes と同じ定義
    long nodes;
    long pruned;
    long leaves;
    long routePrunes;
    private final List<Improvement> history = new ArrayList<>();
    private long t0;
    // 完成解に到達するたびに呼ぶ (テスト用。枝刈りを止めた探索木が全探索と同じことを確かめる)
    BiConsumer<Solution, Long> onLeaf = (s, c) -> {};

    BranchAndBound(Instance inst, Solution incumbent, boolean prune) {
        if (inst.n() > 30) {
            throw new IllegalArgumentException("顧客が 30 件を超えるインスタンスは厳密には解けない: " + inst.n());
        }
        this.inst = inst;
        this.prune = prune;
        this.best = incumbent;
        this.bestCost = incumbent == null ? Long.MAX_VALUE : Objective.solutionCost(inst, incumbent);
    }

    // ------------------------------------------------------------ 入口

    /** 上界なしで解く。 */
    public static SolveResult solve(Instance inst) {
        return run(inst, null, "bnb", null);
    }

    /**
     * 実行可能解 incumbent のコストを初期上界にして解く。実行不可能な解のコストは上界に
     * 使えない (制約を破って安くなった値で刈ると真の最適解を落とす) ので例外にする。
     */
    public static SolveResult solve(Instance inst, Solution incumbent) {
        if (!Objective.isFeasible(inst, incumbent)) {
            throw new IllegalArgumentException("incumbent が実行可能解ではない");
        }
        return run(inst, incumbent, "bnb", null);
    }

    /**
     * 最近傍法の解を初期上界にして解く。最近傍法は K 台を超えうるので、実行不可能なら
     * 黙って上界なしに戻す (使えたかどうかは stats の nn_seed に残す)。
     */
    public static SolveResult solveWithNnSeed(Instance inst) {
        Solution nn = NearestNeighbor.build(inst);
        boolean usable = Objective.isFeasible(inst, nn);
        return run(inst, usable ? nn : null, "bnb+nn", usable);
    }

    private static SolveResult run(Instance inst, Solution incumbent, String name, Boolean nnSeed) {
        BranchAndBound b = new BranchAndBound(inst, incumbent, true);
        Map<String, Object> stats = b.search();
        // 上界なしは null で表す (Python の None。無限大は JSON に書けない)
        stats.put("initial_upper_bound", incumbent == null ? null : Objective.solutionCost(inst, incumbent));
        if (nnSeed != null) {
            stats.put("nn_seed", nnSeed);
        }
        return SolveResult.build(inst, name, b.best, b.elapsed, stats, b.history);
    }

    private double elapsed;

    /** 探索して統計を返す。実行可能解が無ければ IllegalArgumentException。 */
    Map<String, Object> search() {
        t0 = System.nanoTime();
        if (best != null) {
            // 初期上界も「時刻0の暫定解」として履歴に載せる
            history.add(new Improvement(0.0, bestCost));
        }
        int all = 0;
        for (int c = 1; c <= inst.n(); c++) {
            all |= 1 << c;
        }
        extend(all, new ArrayList<>(), 0);
        elapsed = BruteForce.seconds(t0);
        if (best == null) {
            throw new IllegalArgumentException(inst.name() + ": 実行可能解が存在しない");
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("nodes", nodes);
        stats.put("pruned", pruned);
        stats.put("route_prunes", routePrunes);
        stats.put("leaves", leaves);
        return stats;
    }

    Solution best() {
        return best;
    }

    // ------------------------------------------------------------ 再帰

    /** remaining を routes に割り当て終えるまで再帰する。cost は確定済みルートの合計。 */
    private void extend(int remaining, List<List<Integer>> routes, long cost) {
        nodes++;

        if (prune && cost >= bestCost) {
            pruned++;
            return;
        }

        if (remaining == 0) {
            // 冒頭の枝刈りを通過している = cost < bestCost は保証されている
            leaves++;
            bestCost = cost;
            best = new Solution(routes);
            history.add(new Improvement(BruteForce.seconds(t0), cost, nodes));
            onLeaf.accept(best, cost);
            return;
        }

        if (inst.numVehicles().isPresent() && routes.size() == inst.numVehicles().getAsInt()) {
            return;                       // 車が尽きた。行き止まり (空なら上限なし)
        }

        int m = Integer.numberOfTrailingZeros(remaining);   // 最小の顧客
        int[] rest = bits(remaining & ~(1 << m));

        for (int r = 0; r <= rest.length; r++) {
            forEachCombination(rest, r, 0, 1 << m, s -> {
                long load = 0;
                for (int c : bits(s)) {
                    load += inst.demand(c);
                }
                if (load > inst.capacity()) {
                    return;
                }
                // S の並べ替えは1顧客ずつ伸ばしながら列挙する。1手あたり距離を1つ足すだけで済み、
                // 途中のコストでも刈れる
                for (int first : bits(s)) {
                    long rcost = inst.dist(0, first);
                    if (prune && cost + rcost >= bestCost) {
                        routePrunes++;
                        continue;
                    }
                    List<Integer> route = new ArrayList<>();
                    route.add(first);
                    buildRoute(remaining, routes, cost, route, s & ~(1 << first), rcost);
                }
            });
        }
    }

    /**
     * 1本のルートを1顧客ずつ伸ばして S の並べ替えを列挙する。rcost は depot -> route の距離で、
     * 最後の depot への復路はまだ足していない。復路は非負なので、rcost の時点で刈ってよい。
     */
    private void buildRoute(int remaining, List<List<Integer>> routes, long baseCost,
                            List<Integer> route, int left, long rcost) {
        int last = route.get(route.size() - 1);

        if (left == 0) {
            long total = baseCost + rcost + inst.dist(last, 0);
            int used = 0;
            for (int c : route) {
                used |= 1 << c;
            }
            List<List<Integer>> next = new ArrayList<>(routes);
            next.add(List.copyOf(route));
            extend(remaining & ~used, next, total);
            return;
        }

        boolean fin = Integer.bitCount(left) == 1;   // 次に置く顧客がこのルートの末尾になる
        for (int c : bits(left)) {
            if (fin && c < route.get(0)) {
                continue;                 // 逆走の重複を潰す (末尾 >= 先頭 のものだけ残す)
            }
            long step = rcost + inst.dist(last, c);
            if (prune && baseCost + step >= bestCost) {
                routePrunes++;
                continue;
            }
            route.add(c);
            buildRoute(remaining, routes, baseCost, route, left & ~(1 << c), step);
            route.remove(route.size() - 1);
        }
    }

    /** ビット集合の要素を昇順に返す。 */
    private static int[] bits(int set) {
        int[] out = new int[Integer.bitCount(set)];
        for (int i = 0; set != 0; i++) {
            int low = Integer.numberOfTrailingZeros(set);
            out[i] = low;
            set &= set - 1;
        }
        return out;
    }

    /** itertools.combinations(items, r) と同じ順に、選んだ要素を base に足したビット集合を渡す。 */
    private static void forEachCombination(int[] items, int r, int start, int chosen,
                                           java.util.function.IntConsumer out) {
        if (r == 0) {
            out.accept(chosen);
            return;
        }
        for (int i = start; i <= items.length - r; i++) {
            forEachCombination(items, r - 1, i + 1, chosen | (1 << items[i]), out);
        }
    }
}
