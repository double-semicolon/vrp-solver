package io.github.doublesemicolon.vrp.alns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Improvement;
import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Objective;
import io.github.doublesemicolon.vrp.SolveResult;
import io.github.doublesemicolon.vrp.TestData;
import io.github.doublesemicolon.vrp.Solution;
import io.github.doublesemicolon.vrp.json.Cases;
import io.github.doublesemicolon.vrp.json.VrpJson;
import io.github.doublesemicolon.vrp.solvers.BruteForce;
import io.github.doublesemicolon.vrp.search.Routes;
import io.github.doublesemicolon.vrp.solvers.NearestNeighbor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AlnsTest {
    static Instance stored(String name) {
        try {
            return VrpJson.readRun(TestData.runs().resolve("python/" + name + ".json")).instance();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** ALNS が反復の前に作る、VND で磨いた初期解 (VND は乱数を使わないので、同じものになる)。 */
    static Routes polished(Routes start) {
        Routes s = start.copy();
        io.github.doublesemicolon.vrp.search.Vnd.descend(s, io.github.doublesemicolon.vrp.search.Vnd.neighborhoods(
                io.github.doublesemicolon.vrp.search.NeighborList.of(s.instance(), Alns.VND_NEAR)), (k, c) -> {});
        return s;
    }

    static Stream<String> randomInstances() {
        return Stream.of("rand30-uniform-s0", "rand50-clustered-s2");
    }

    // ---------------------------------------------------------------- 完了条件 1: 同じシードなら同じ結果

    @ParameterizedTest
    @MethodSource("randomInstances")
    void sameSeedGivesTheSameSolutionAndHistory(String name) {
        Instance inst = stored(name);
        SolveResult a = Alns.solve(inst, Alns.Config.of(inst, 7, 300));
        SolveResult b = Alns.solve(inst, Alns.Config.of(inst, 7, 300));
        assertEquals(a.solution(), b.solution());
        assertEquals(a.stats(), b.stats());
        assertEquals(withoutTime(a.history()), withoutTime(b.history()));

        // 対照: シードを変えると、改善の履歴が変わる (乱数が実際に効いている)
        SolveResult c = Alns.solve(inst, Alns.Config.of(inst, 8, 300));
        assertNotEquals(withoutTime(a.history()), withoutTime(c.history()));
    }

    static List<String> withoutTime(List<Improvement> history) {
        return history.stream().map(h -> h.iteration().getAsLong() + ":" + h.cost()).toList();
    }

    // ---------------------------------------------------------------- 完了条件 2: 最良解のコストは増えない

    @ParameterizedTest
    @MethodSource("randomInstances")
    void acceptingWorseSolutionsNeverRaisesTheBest(String name) {
        Instance inst = stored(name);
        Routes start = Routes.of(inst, NearestNeighbor.build(inst));
        // 反復の前に、初期解は VND で磨かれている
        long[] prevBest = {polished(start).cost()};
        long[] seen = new long[3];   // 悪い候補を受け入れた回数、今の解が最良より悪かった回数、反復数
        long[] prevCurrent = {prevBest[0]};
        Alns.Run run = Alns.run(start, Alns.Config.of(inst, 1, 500), (it, d, r, cand, accepted, current, best) -> {
            assertTrue(best <= prevBest[0], "最良解のコストが増えた: 反復 " + it);
            assertTrue(best <= current, "最良解が今の解より悪い: 反復 " + it);
            if (accepted && cand > prevCurrent[0]) {
                seen[0]++;
            }
            if (current > best) {
                seen[1]++;
            }
            seen[2]++;
            prevBest[0] = best;
            prevCurrent[0] = current;
        });
        // 悪い解を実際に受け入れていなければ、このテストは何も確かめていない
        assertTrue(seen[0] > 0 && seen[1] > 0, java.util.Arrays.toString(seen));
        assertEquals(500, seen[2]);
        assertEquals(prevBest[0], run.best().cost());
        assertEquals(seen[0], run.stats().get("accepted_worse"));
        assertEquals(Objective.solutionCost(inst, run.best().toSolution()), run.best().cost());
        assertEquals(List.of(), Objective.validate(inst, run.best().toSolution()));
        // 履歴は最良解が更新されたときだけ。コストは真に下がり続ける
        for (int k = 1; k < run.history().size(); k++) {
            assertTrue(run.history().get(k).cost() < run.history().get(k - 1).cost());
        }
        assertTrue(run.best().cost() < start.cost(), "最近傍法の解を改善できる");
    }

    @ParameterizedTest
    @MethodSource("tightCases")
    void aFailedRepairIsDiscarded(String name) {
        // 車両数か容量に余裕の無いケースでは、修復が入れ先を失うことがある。途中までしか入っていない解は
        // 顧客が欠けている分だけ安いので、捨てずに使うと「最良解」になってしまう
        Instance inst = Cases.get(name).instance();
        List<Solution> starts = new ArrayList<>();
        BruteForce.enumerate(inst, starts::add);
        Routes start = Routes.of(inst, starts.get(starts.size() - 1));
        // 1 人だけ抜くなら元の場所に戻せるので、修復は失敗しない。2 人以上を抜く
        Alns.Run run = Alns.run(start, new Alns.Config(5, 300, 2, inst.n(), false, false), (it, d, r, cand, acc, cur, best) -> {});
        assertTrue((Long) run.stats().get("repair_failed") > 0, "修復が一度も失敗しないと、このテストは何も確かめていない");
        assertEquals(List.of(), Objective.validate(inst, run.best().toSolution()));
        assertTrue(run.best().cost() >= Cases.get(name).optimalCost().getAsLong());
    }

    static Stream<String> tightCases() {
        // toy8 の車両数がきついケースは、試した範囲では修復が失敗しなかった (容量の余裕が大きい)
        return Stream.of("toy5", "toy5-tight");
    }

    @Test
    void startIsNotModified() {
        Instance inst = stored("rand30-uniform-s0");
        Routes start = Routes.of(inst, NearestNeighbor.build(inst));
        var before = start.toSolution();
        Alns.run(start, Alns.Config.of(inst, 3, 50), (it, d, r, cand, acc, cur, best) -> {});
        assertEquals(before, start.toSolution());
    }

    @Test
    void zeroIterationsReturnsTheStart() {
        Instance inst = stored("rand30-uniform-s0");
        SolveResult r = Alns.solve(inst, Alns.Config.of(inst, 0, 0).withVnd(false));
        assertEquals(NearestNeighbor.build(inst), r.solution());
        assertEquals(1, r.history().size());
        // VND で磨くなら、磨いた初期解
        Routes start = Routes.of(inst, NearestNeighbor.build(inst));
        SolveResult p = Alns.solve(inst, Alns.Config.of(inst, 0, 0));
        assertEquals(polished(start).toSolution(), p.solution());
        assertEquals(2, p.history().size());
    }

    @Test
    void configScalesTheRemovalSizeWithTheInstance() {
        Instance small = stored("rand30-uniform-s0");
        Instance large = stored("rand200-clustered-s1");
        assertEquals(new Alns.Config(0, 10, 1, 6, true, true), Alns.Config.of(small, 0, 10));
        assertEquals(new Alns.Config(0, 10, 10, 40, true, true), Alns.Config.of(large, 0, 10));
    }

    // ---------------------------------------------------------------- #28: 適応的な重み

    /** 修復の悪い見本: 入れられる隙間のうち、一番高い隙間に入れる (新しいルートも候補)。 */
    static final class CostliestInsertion implements Repair {
        @Override
        public String name() {
            return "costliest";
        }

        @Override
        public boolean repair(Routes s, int[] removed) {
            for (int c : removed) {
                Repair.Insertion worst = null;
                for (int r = 0; r < s.count(); r++) {
                    if (s.load(r) + s.instance().demand(c) > s.instance().capacity()) {
                        continue;
                    }
                    for (int t = 0; t <= s.size(r); t++) {
                        int x = t == 0 ? 0 : s.node(r, t - 1);
                        int y = t == s.size(r) ? 0 : s.node(r, t);
                        long cost = (long) s.instance().dist(x, c) + s.instance().dist(c, y) - s.instance().dist(x, y);
                        if (worst == null || cost > worst.cost()) {
                            worst = new Repair.Insertion(c, r, t, cost);
                        }
                    }
                }
                if (s.canAddRoute() && (worst == null || 2L * s.instance().dist(0, c) > worst.cost())) {
                    worst = new Repair.Insertion(c, s.count(), 0, 0);
                }
                if (worst == null) {
                    return false;
                }
                s.insertCustomer(c, worst.route(), worst.pos());
            }
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    static List<Double> weights(Alns.Run run, String op) {
        return (List<Double>) run.stats().get("weights_" + op);
    }

    @Test
    void adaptiveWeightsShiftTowardTheOperatorThatWorks() {
        Instance inst = stored("rand50-clustered-s2");
        Routes start = Routes.of(inst, NearestNeighbor.build(inst));
        List<Destroy> destroys = List.of(new Destroy.RandomRemoval());
        List<Repair> repairs = List.of(new Repair.GreedyInsertion(), new CostliestInsertion());
        Alns.Config config = Alns.Config.of(inst, 4, 1000).withVnd(false);

        Alns.Run adaptive = Alns.run(start, config, destroys, repairs, (it, d, r, c, a, cur, b) -> {});
        List<Double> greedy = weights(adaptive, "greedy");
        List<Double> costliest = weights(adaptive, "costliest");
        assertEquals(1000 / 100 + 1, greedy.size());
        assertEquals(1.0, greedy.get(0));
        assertTrue(costliest.get(costliest.size() - 1) < greedy.get(greedy.size() - 1) / 3,
                greedy + " / " + costliest);
        assertTrue((Long) adaptive.stats().get("uses_greedy") > 2 * (Long) adaptive.stats().get("uses_costliest"),
                adaptive.stats().toString());

        // 対照: 固定の重みなら、どちらも 1 のままで、ほぼ半々に選ぶ
        Alns.Run uniform = Alns.run(start, config.withAdaptive(false), destroys, repairs, (it, d, r, c, a, cur, b) -> {});
        assertEquals(java.util.Collections.nCopies(11, 1.0), weights(uniform, "greedy"));
        assertEquals(java.util.Collections.nCopies(11, 1.0), weights(uniform, "costliest"));
        assertEquals(500, (Long) uniform.stats().get("uses_greedy"), 60);
    }

    /**
     * 記録された重みの推移を、観察した各反復の結果から点を数え直して再現する。
     * 点の規則 (最良解の更新 33、今の解より良い 9、悪いが受理 13、それ以外 0) と、
     * 区間ごとの更新式の両方を確かめる。
     */
    @ParameterizedTest
    @MethodSource("randomInstances")
    void recordedWeightsFollowFromTheScoringRules(String name) {
        Instance inst = stored(name);
        Routes start = Routes.of(inst, NearestNeighbor.build(inst));
        Alns.Config config = Alns.Config.of(inst, 2, 750);
        List<String> ops = new ArrayList<>();
        Alns.DESTROYS.forEach(d -> ops.add(d.name()));
        Alns.REPAIRS.forEach(r -> ops.add(r.name()));
        java.util.Map<String, double[]> score = new java.util.HashMap<>();
        java.util.Map<String, long[]> uses = new java.util.HashMap<>();
        java.util.Map<String, List<Double>> expected = new java.util.HashMap<>();
        for (String op : ops) {
            score.put(op, new double[1]);
            uses.put(op, new long[1]);
            expected.put(op, new ArrayList<>(List.of(1.0)));
        }
        // 反復の前の、今の解と最良解のコスト。初期解は VND で磨かれてから始まる
        long[] prev = {polished(start).cost(), polished(start).cost()};
        long[] seenPoints = new long[4];
        Alns.Run[] holder = new Alns.Run[1];
        holder[0] = Alns.run(start, config, (it, d, r, cand, accepted, current, best) -> {
            double points;
            if (cand >= 0 && best < prev[1]) {
                points = Weights.BEST;
            } else if (accepted && cand < prev[0]) {
                points = Weights.BETTER;
            } else if (accepted && cand > prev[0]) {
                points = Weights.WORSE_ACCEPTED;
            } else {
                points = 0;
            }
            seenPoints[points == Weights.BEST ? 0 : points == Weights.BETTER ? 1 : points == Weights.WORSE_ACCEPTED ? 2 : 3]++;
            for (String op : List.of(d.name(), r.name())) {
                score.get(op)[0] += points;
                uses.get(op)[0]++;
            }
            if (it % Weights.SEGMENT == 0) {
                for (String op : ops) {
                    List<Double> e = expected.get(op);
                    double w = e.get(e.size() - 1);
                    if (uses.get(op)[0] > 0) {
                        w = w * (1 - Weights.REACTION) + Weights.REACTION * score.get(op)[0] / uses.get(op)[0];
                    }
                    e.add(w);
                    score.get(op)[0] = 0;
                    uses.get(op)[0] = 0;
                }
            }
            prev[0] = current;
            prev[1] = best;
        });
        for (String op : ops) {
            List<Double> got = weights(holder[0], op);
            assertEquals(750 / 100 + 1, got.size(), op);
            for (int k = 0; k < got.size(); k++) {
                assertEquals(expected.get(op).get(k), got.get(k), 1e-9, op + " の区間 " + k);
            }
        }
        // 全種類の点が実際に付いていなければ、規則を確かめたことにならない
        assertTrue(seenPoints[0] > 0 && seenPoints[1] > 0 && seenPoints[2] > 0 && seenPoints[3] > 0,
                java.util.Arrays.toString(seenPoints));
    }

    // ---------------------------------------------------------------- #28: VND で磨く

    /**
     * 顧客 30〜50 件では、最良解を更新した候補が磨く前から VND の局所最適になっていることが多く
     * (rand30 では磨いた初期解を 400 反復で一度も更新できない)、磨く処理を外しても気づけない。
     * 反復の中の VND が実際に手を適用する、顧客 200 件のインスタンスで確かめる。
     */
    static Stream<String> polishingInstances() {
        return Stream.of("rand200-clustered-s1", "rand200-uniform-s1");
    }

    @ParameterizedTest
    @MethodSource("polishingInstances")
    void bestIsALocalOptimumOfVndWhenPolishing(String name) {
        Instance inst = stored(name);
        Routes start = Routes.of(inst, NearestNeighbor.build(inst));
        List<io.github.doublesemicolon.vrp.search.Neighborhood> nbs = io.github.doublesemicolon.vrp.search.Vnd
                .neighborhoods(io.github.doublesemicolon.vrp.search.NeighborList.of(inst, Alns.VND_NEAR));

        Alns.Run polished = Alns.run(start, Alns.Config.of(inst, 6, 400), (it, d, r, c, a, cur, b) -> {});
        long[] applied = io.github.doublesemicolon.vrp.search.Vnd.descend(polished.best().copy(), nbs, (k, c) -> {});
        assertEquals(0, java.util.Arrays.stream(applied).sum(), "VND でまだ改善できた: " + java.util.Arrays.toString(applied));
        // 初期解と、最良解を更新するたびに 1 回ずつ
        assertEquals((Long) polished.stats().get("new_best") + 1, polished.stats().get("vnd_calls"));
        // 初期解を磨いた分より多く手を適用している = 反復の中で最良解を更新した候補も磨いている
        // (最良解が VND の局所最適であることだけでは、候補がたまたま局所最適だった場合と区別できない)
        long startMoves = java.util.Arrays.stream(io.github.doublesemicolon.vrp.search.Vnd.descend(start.copy(), nbs,
                (k, c) -> {})).sum();
        assertTrue((Long) polished.stats().get("new_best") > 0);
        assertTrue((Long) polished.stats().get("vnd_moves") > startMoves,
                polished.stats().get("vnd_moves") + " / 初期解だけで " + startMoves);
        // 0 番の履歴は初期解と、それを磨いた解
        assertEquals(start.cost(), polished.history().get(0).cost());
        assertEquals(0, polished.history().get(1).iteration().getAsLong());

        // 対照: 磨かなければ、VND で改善できる (上の確かめ方が意味を持つ)。反復が多いと磨かなくても
        // 局所最適に届くことがあるので、短く走らせる
        Alns.Run raw = Alns.run(start, Alns.Config.of(inst, 6, 30).withVnd(false), (it, d, r, c, a, cur, b) -> {});
        assertTrue(java.util.Arrays.stream(io.github.doublesemicolon.vrp.search.Vnd.descend(raw.best().copy(), nbs,
                (k, c) -> {})).sum() > 0);
        assertEquals(0L, raw.stats().get("vnd_calls"));
    }
}
