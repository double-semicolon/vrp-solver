package io.github.doublesemicolon.vrp.alns;

import io.github.doublesemicolon.vrp.Improvement;
import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.SolveResult;
import io.github.doublesemicolon.vrp.search.NeighborList;
import io.github.doublesemicolon.vrp.search.Neighborhood;
import io.github.doublesemicolon.vrp.search.Routes;
import io.github.doublesemicolon.vrp.search.Vnd;
import io.github.doublesemicolon.vrp.solvers.NearestNeighbor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * ALNS (adaptive large neighborhood search)。1 反復は次のとおり:
 * <ol>
 *   <li>破壊演算子と修復演算子を、重みに比例する確率で 1 つずつ選ぶ
 *   <li>今の解を写し、破壊演算子で q 人を抜く (q は [qMin, qMax] の一様乱数)
 *   <li>修復演算子で全員を入れ直す。入れ先が無くなったら、その反復の候補は捨てる
 *   <li>最良解より良ければ、VND で磨いて (vnd のとき) 最良解にする
 *   <li>焼きなましで受け入れたら、今の解にする
 *   <li>結果に応じて、使った 2 つの演算子に点を付ける。重みは区間ごとに点から更新する ({@link Weights})
 * </ol>
 * adaptive でなければ重みは 1 のまま (演算子を一様に選ぶ)。
 *
 * <p>VND で磨くのは、初期解と、最良解を更新した候補だけ。VND の 1 回は ALNS の 1 反復より桁違いに重いので、
 * 毎反復は掛けない。最良解を更新した候補の周りには良い解がある見込みが高く、更新の回数も少ない。
 *
 * <p>乱数は {@link Random} をシードから作り、この 1 本だけを使う。Random の生成方法は Java の仕様で
 * 決まっているので、同じシード・同じ入力なら、どの JVM でも同じ解・同じ改善の履歴になる。
 * 乱数を使う順番を変えると結果が変わるので、反復の中の順番 (破壊の選択 → 修復の選択 → q → 破壊 → 受理) は
 * 固定する。VND は乱数を使わない。
 */
public final class Alns {
    private Alns() {}

    public static final List<Destroy> DESTROYS =
            List.of(new Destroy.RandomRemoval(), new Destroy.WorstRemoval(), new Destroy.RelatedRemoval());
    public static final List<Repair> REPAIRS = List.of(new Repair.GreedyInsertion(), new Repair.RegretInsertion());

    /** VND で磨くときの近傍リストの人数 (VND 単体の既定と同じ)。 */
    static final int VND_NEAR = 20;

    /**
     * @param seed       乱数のシード
     * @param iterations 反復の回数 (時間制限は #29)
     * @param qMin       1 回に抜く人数の下限
     * @param qMax       1 回に抜く人数の上限
     * @param adaptive   演算子の重みを成績に応じて変えるか (false なら一様に選ぶ)
     * @param vnd        初期解と、最良解を更新した候補を VND で磨くか
     *
     * <p>{@link #of} の既定はどちらも使う。X set の顧客 300 件以下 (43 件)・1000 反復・5 シードで、
     * gap 平均は一様選択・VND なし +4.52%、適応的な重みだけ +3.88%、VND だけ +3.55%、両方 +3.27%。
     * 時間はどれも約 1.35 秒で変わらない。
     */
    public record Config(long seed, int iterations, int qMin, int qMax, boolean adaptive, boolean vnd) {
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
            return new Config(seed, iterations, qMin, qMax, true, true);
        }

        public Config withAdaptive(boolean adaptive) {
            return new Config(seed, iterations, qMin, qMax, adaptive, vnd);
        }

        public Config withVnd(boolean vnd) {
            return new Config(seed, iterations, qMin, qMax, adaptive, vnd);
        }
    }

    /** 反復ごとの様子 (テスト用)。候補が作れなかった反復の candidate は -1。candidate は VND で磨いた後のコスト。 */
    @FunctionalInterface
    public interface Observer {
        void iteration(int it, Destroy destroy, Repair repair, long candidate, boolean accepted, long current, long best);
    }

    /** 走らせた結果。best は最良解 (呼び出し側の Routes とは別物)。 */
    public record Run(Routes best, List<Improvement> history, Map<String, Object> stats) {}

    /** start (実行可能な解) から ALNS を走らせる。start は書き換えない。 */
    public static Run run(Routes start, Config config, Observer observer) {
        return run(start, config, DESTROYS, REPAIRS, observer);
    }

    /** 演算子を差し替えて走らせる (テスト用)。 */
    static Run run(Routes start, Config config, List<Destroy> destroys, List<Repair> repairs, Observer observer) {
        long t0 = System.nanoTime();
        int n = start.instance().n();
        Random rnd = new Random(config.seed());
        List<Neighborhood> nbs = config.vnd()
                ? Vnd.neighborhoods(NeighborList.of(start.instance(), VND_NEAR))
                : List.of();
        long[] vndMoves = {0};
        long vndCalls = 0;

        // 改善の履歴の iteration は ALNS の反復番号。0 番は初期解 (磨いたら、磨いた後の解も 0 番)
        List<Improvement> history = new ArrayList<>();
        history.add(new Improvement(seconds(t0), start.cost(), 0));
        Routes current = start.copy();
        if (config.vnd()) {
            vndCalls++;
            polish(current, nbs, vndMoves);
            if (current.cost() < start.cost()) {
                history.add(new Improvement(seconds(t0), current.cost(), 0));
            }
        }
        Routes best = current.copy();
        Annealing annealing = Annealing.of(current.cost(), config.iterations());
        Weights destroyWeights = new Weights(destroys.size(), config.adaptive());
        Weights repairWeights = new Weights(repairs.size(), config.adaptive());

        long accepted = 0;
        long acceptedWorse = 0;
        long failed = 0;
        long newBest = 0;
        long[] destroyUses = new long[destroys.size()];
        long[] repairUses = new long[repairs.size()];

        for (int it = 1; it <= config.iterations(); it++) {
            int d = destroyWeights.select(rnd);
            int r = repairWeights.select(rnd);
            destroyWeights.used(d);
            repairWeights.used(r);
            destroyUses[d]++;
            repairUses[r]++;
            int q = Math.min(n, config.qMin() + rnd.nextInt(config.qMax() - config.qMin() + 1));

            Routes candidate = current.copy();
            int[] removed = destroys.get(d).destroy(candidate, q, rnd);
            boolean repaired = repairs.get(r).repair(candidate, removed);
            boolean ok = false;
            double points = 0;
            if (!repaired) {
                failed++;
            } else {
                if (candidate.cost() < best.cost()) {
                    if (config.vnd()) {
                        vndCalls++;
                        polish(candidate, nbs, vndMoves);
                    }
                    best = candidate.copy();
                    newBest++;
                    history.add(new Improvement(seconds(t0), best.cost(), it));
                    points = Weights.BEST;
                }
                long before = current.cost();
                if (annealing.accept(candidate.cost(), before, rnd)) {
                    ok = true;
                    accepted++;
                    if (candidate.cost() > before) {
                        acceptedWorse++;
                        points = Weights.WORSE_ACCEPTED;
                    } else if (candidate.cost() < before && points == 0) {
                        points = Weights.BETTER;
                    }
                    current = candidate;
                }
            }
            destroyWeights.reward(d, points);
            repairWeights.reward(r, points);
            if (it % Weights.SEGMENT == 0) {
                destroyWeights.endSegment();
                repairWeights.endSegment();
            }
            observer.iteration(it, destroys.get(d), repairs.get(r), repaired ? candidate.cost() : -1, ok,
                    current.cost(), best.cost());
            annealing.cool();
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("seed", config.seed());
        stats.put("iterations", (long) config.iterations());
        stats.put("q_min", (long) config.qMin());
        stats.put("q_max", (long) config.qMax());
        stats.put("adaptive", config.adaptive());
        stats.put("vnd", config.vnd());
        stats.put("accepted", accepted);
        stats.put("accepted_worse", acceptedWorse);
        stats.put("repair_failed", failed);
        stats.put("new_best", newBest);
        stats.put("vnd_calls", vndCalls);
        stats.put("vnd_moves", vndMoves[0]);
        for (int k = 0; k < destroys.size(); k++) {
            stats.put("uses_" + destroys.get(k).name(), destroyUses[k]);
        }
        for (int k = 0; k < repairs.size(); k++) {
            stats.put("uses_" + repairs.get(k).name(), repairUses[k]);
        }
        // 重みの推移: 最初の値 (1) と、weight_segment 反復ごとの値
        stats.put("weight_segment", (long) Weights.SEGMENT);
        for (int k = 0; k < destroys.size(); k++) {
            stats.put("weights_" + destroys.get(k).name(), destroyWeights.trace(k));
        }
        for (int k = 0; k < repairs.size(); k++) {
            stats.put("weights_" + repairs.get(k).name(), repairWeights.trace(k));
        }
        return new Run(best, history, stats);
    }

    /**
     * VND で磨く (近傍リスト k = 20、don't-look bits なし)。don't-look bits も試したが、VND を呼ぶのは
     * 1 回の実行で平均 9 回ほどで時間の大半は ALNS の反復なので、速くしても全体はほぼ変わらず
     * (X set の顧客 300 件以下で 1.37 秒 → 1.30 秒)、gap は少し悪くなった (+3.27% → +3.34%)。
     */
    private static void polish(Routes s, List<Neighborhood> nbs, long[] moves) {
        long[] applied = Vnd.descend(s, nbs, (k, c) -> {});
        for (long a : applied) {
            moves[0] += a;
        }
    }

    /** 最近傍法の解から ALNS を走らせる。 */
    public static SolveResult solve(Instance inst, Config config) {
        return solve(inst, config, "nn+alns");
    }

    /** 最近傍法の解から ALNS を走らせる。近傍リストを作る時間も含めて測る。 */
    public static SolveResult solve(Instance inst, Config config, String solverName) {
        long t0 = System.nanoTime();
        Routes start = Routes.of(inst, NearestNeighbor.build(inst));
        Run run = run(start, config, (it, d, r, cand, acc, cur, best) -> {});
        double elapsed = seconds(t0);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("routes", (long) run.best().count());
        stats.putAll(run.stats());
        return SolveResult.build(inst, solverName, run.best().toSolution(), elapsed, stats, run.history());
    }

    private static double seconds(long t0) {
        return (System.nanoTime() - t0) / 1e9;
    }
}
