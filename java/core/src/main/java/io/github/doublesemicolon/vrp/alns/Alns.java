package io.github.doublesemicolon.vrp.alns;

import io.github.doublesemicolon.vrp.Improvement;
import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.SolveResult;
import io.github.doublesemicolon.vrp.search.Routes;
import io.github.doublesemicolon.vrp.solvers.NearestNeighbor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * ALNS (adaptive large neighborhood search) の骨組み。1 反復は次のとおり:
 * <ol>
 *   <li>今の解を写し、破壊演算子で q 人を抜く (q は [qMin, qMax] の一様乱数)
 *   <li>修復演算子で全員を入れ直す。入れ先が無くなったら、その反復の候補は捨てる
 *   <li>最良解より良ければ最良解にする。焼きなましで受け入れたら、今の解にする
 * </ol>
 * 演算子は今は等しい確率で選ぶ (成績に応じて確率を変えるのは #28)。
 *
 * <p>乱数は {@link Random} をシードから作り、この 1 本だけを使う。Random の生成方法は Java の仕様で
 * 決まっているので、同じシード・同じ入力なら、どの JVM でも同じ解・同じ改善の履歴になる。
 * 乱数を使う順番を変えると結果が変わるので、反復の中の順番 (演算子の選択 → q → 破壊 → 受理) は固定する。
 */
public final class Alns {
    private Alns() {}

    public static final List<Destroy> DESTROYS =
            List.of(new Destroy.RandomRemoval(), new Destroy.WorstRemoval(), new Destroy.RelatedRemoval());
    public static final List<Repair> REPAIRS = List.of(new Repair.GreedyInsertion(), new Repair.RegretInsertion());

    /**
     * @param seed       乱数のシード
     * @param iterations 反復の回数 (時間制限は #29)
     * @param qMin       1 回に抜く人数の下限
     * @param qMax       1 回に抜く人数の上限
     */
    public record Config(long seed, int iterations, int qMin, int qMax) {
        public Config {
            if (iterations < 0 || qMin < 1 || qMax < qMin) {
                throw new IllegalArgumentException("iterations >= 0、1 <= qMin <= qMax: " + iterations + ", " + qMin + ", " + qMax);
            }
        }

        /**
         * 抜く人数は顧客数の 5%〜20% (最低 1 人、最大 50 人)。修復は 1 人入れるたびに残り全員の入れ先を
         * 数え直すので、1 反復の手間は q^2 × 顧客数に比例する。50 人で打ち切るのはそのため。
         */
        public static Config of(Instance inst, long seed, int iterations) {
            int n = inst.n();
            int qMax = Math.max(1, Math.min(50, n / 5));
            int qMin = Math.max(1, Math.min(qMax, n / 20));
            return new Config(seed, iterations, qMin, qMax);
        }
    }

    /** 反復ごとの様子 (テストと、#28 の重みの記録用)。候補が作れなかった反復の candidate は -1。 */
    @FunctionalInterface
    public interface Observer {
        void iteration(int it, Destroy destroy, Repair repair, long candidate, boolean accepted, long current, long best);
    }

    /** 走らせた結果。best は最良解 (呼び出し側の Routes とは別物)。 */
    public record Run(Routes best, List<Improvement> history, Map<String, Object> stats) {}

    /** start (実行可能な解) から ALNS を走らせる。start は書き換えない。 */
    public static Run run(Routes start, Config config, Observer observer) {
        long t0 = System.nanoTime();
        int n = start.instance().n();
        Random rnd = new Random(config.seed());
        Routes current = start.copy();
        Routes best = start.copy();
        Annealing annealing = Annealing.of(start.cost(), config.iterations());

        List<Improvement> history = new ArrayList<>();
        history.add(new Improvement(seconds(t0), best.cost(), 0));
        long accepted = 0;
        long acceptedWorse = 0;
        long failed = 0;
        long[] destroyUses = new long[DESTROYS.size()];
        long[] repairUses = new long[REPAIRS.size()];

        for (int it = 1; it <= config.iterations(); it++) {
            int d = rnd.nextInt(DESTROYS.size());
            int r = rnd.nextInt(REPAIRS.size());
            destroyUses[d]++;
            repairUses[r]++;
            int q = Math.min(n, config.qMin() + rnd.nextInt(config.qMax() - config.qMin() + 1));

            Routes candidate = current.copy();
            int[] removed = DESTROYS.get(d).destroy(candidate, q, rnd);
            boolean repaired = REPAIRS.get(r).repair(candidate, removed);
            boolean ok = false;
            if (!repaired) {
                failed++;
            } else {
                if (candidate.cost() < best.cost()) {
                    best = candidate.copy();
                    history.add(new Improvement(seconds(t0), best.cost(), it));
                }
                if (annealing.accept(candidate.cost(), current.cost(), rnd)) {
                    ok = true;
                    accepted++;
                    if (candidate.cost() > current.cost()) {
                        acceptedWorse++;
                    }
                    current = candidate;
                }
            }
            observer.iteration(it, DESTROYS.get(d), REPAIRS.get(r), repaired ? candidate.cost() : -1, ok,
                    current.cost(), best.cost());
            annealing.cool();
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("seed", config.seed());
        stats.put("iterations", (long) config.iterations());
        stats.put("q_min", (long) config.qMin());
        stats.put("q_max", (long) config.qMax());
        stats.put("accepted", accepted);
        stats.put("accepted_worse", acceptedWorse);
        stats.put("repair_failed", failed);
        stats.put("new_best", (long) history.size() - 1);
        for (int k = 0; k < DESTROYS.size(); k++) {
            stats.put("uses_" + DESTROYS.get(k).name(), destroyUses[k]);
        }
        for (int k = 0; k < REPAIRS.size(); k++) {
            stats.put("uses_" + REPAIRS.get(k).name(), repairUses[k]);
        }
        return new Run(best, history, stats);
    }

    /** 最近傍法の解から ALNS を走らせる。 */
    public static SolveResult solve(Instance inst, Config config) {
        long t0 = System.nanoTime();
        Routes start = Routes.of(inst, NearestNeighbor.build(inst));
        Run run = run(start, config, (it, d, r, cand, acc, cur, best) -> {});
        double elapsed = seconds(t0);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("routes", (long) run.best().count());
        stats.putAll(run.stats());
        return SolveResult.build(inst, "nn+alns", run.best().toSolution(), elapsed, stats, run.history());
    }

    private static double seconds(long t0) {
        return (System.nanoTime() - t0) / 1e9;
    }
}
