package io.github.doublesemicolon.vrp.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Objective;
import io.github.doublesemicolon.vrp.Solution;
import io.github.doublesemicolon.vrp.SolveResult;
import io.github.doublesemicolon.vrp.TestData;
import io.github.doublesemicolon.vrp.cvrplib.Cvrplib;
import io.github.doublesemicolon.vrp.json.Cases;
import io.github.doublesemicolon.vrp.solvers.NearestNeighbor;
import io.github.doublesemicolon.vrp.solvers.TwoOpt;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class VndTest {
    /** 既定の順番の近傍それぞれの、素朴な版。 */
    static final List<BiFunction<Instance, Solution, List<Solution>>> NAIVE = List.of(
            new IntraTwoOptTest()::naive, new RelocateTest()::naive, new SwapTest()::naive,
            new TwoOptStarTest()::naive, new OrOptTest()::naive);

    @Test
    void naiveVersionsAreListedInTheDefaultOrder() {
        List<String> names = Stream.of(new IntraTwoOptTest(), new RelocateTest(), new SwapTest(),
                new TwoOptStarTest(), new OrOptTest()).map(t -> t.neighborhood().name()).toList();
        assertEquals(Vnd.DEFAULT.stream().map(Neighborhood::name).toList(), names);
    }

    static Stream<String> randomInstances() {
        return NeighborhoodTestBase.randomInstances();
    }

    static Instance xn101() throws IOException {
        return Cvrplib.loadVrp(TestData.root().resolve("cvrplib/X-n101-k25.vrp"));
    }

    // ---------------------------------------------------------------- 完了条件 1: どの近傍でも局所最適

    @ParameterizedTest
    @MethodSource("randomInstances")
    void resultIsALocalOptimumOfEveryNeighborhood(String name) {
        assertLocalOptimumOfEveryNeighborhood(NeighborhoodTestBase.stored(name));
    }

    @Test
    void resultIsALocalOptimumOfEveryNeighborhoodOnX() throws IOException {
        assertLocalOptimumOfEveryNeighborhood(xn101());
    }

    /** 素朴な版で 1 手で作れる解を全て作り、計算し直したコストが VND の結果より下がらないことを確かめる。 */
    static void assertLocalOptimumOfEveryNeighborhood(Instance inst) {
        SolveResult r = Vnd.solve(inst);
        assertTrue(r.feasible(), inst.name());
        for (BiFunction<Instance, Solution, List<Solution>> naive : NAIVE) {
            NeighborhoodContract.assertLocalOptimum(inst, r.solution(), naive);
        }
    }

    // ---------------------------------------------------------------- 完了条件 2: 最適値以上、最近傍法 + 2-opt 以下

    /** 速くする仕組みの組み合わせ。顧客 5〜8 件でも絞られるよう、近傍リストは 3 人。 */
    static final List<Vnd.Config> CONFIGS = List.of(
            Vnd.Config.FULL,
            new Vnd.Config(OptionalInt.of(3), false),
            new Vnd.Config(OptionalInt.empty(), true),
            new Vnd.Config(OptionalInt.of(3), true));

    static Stream<Arguments> feasibleCases() {
        return Cases.feasible().stream().flatMap(c -> CONFIGS.stream().map(conf -> Arguments.of(c, conf)));
    }

    @ParameterizedTest
    @MethodSource("feasibleCases")
    void smallCasesLieBetweenTheOptimumAndNnTwoOpt(Cases.Case c, Vnd.Config config) {
        Instance inst = c.instance();
        SolveResult twoOpt = TwoOpt.solve(inst);
        SolveResult vnd = Vnd.solve(inst, config, "nn+vnd");
        // don't-look bits を使うときは保証ではない (speedupsOnlyImproveAndStayFeasible を参照)
        assertTrue(vnd.cost() <= twoOpt.cost(), c.name() + ": 最近傍法 + 2-opt より悪い");
        // 最近傍法が車両数の上限を超えるケース (nn-over) では、どちらも実行不可能になりうる。
        // 近傍は車両数に余裕があるときしかルートを増やさないので、実行可能な解から始めれば実行可能なまま
        if (twoOpt.feasible()) {
            assertTrue(vnd.feasible(), c.name());
        }
        if (vnd.feasible()) {
            assertTrue(c.optimalCost().getAsLong() <= vnd.cost(), c.name() + ": 最適値より良い");
        }
    }

    @ParameterizedTest
    @MethodSource("randomInstances")
    void neverWorseThanNnTwoOptAndUsuallyBetter(String name) {
        Instance inst = NeighborhoodTestBase.stored(name);
        SolveResult twoOpt = TwoOpt.solve(inst);
        SolveResult vnd = Vnd.solve(inst);
        assertTrue(vnd.feasible());
        // 顧客 30〜50 件なら、ルート間の近傍で必ず改善できる (Step 1 の残りはルート割り当ての悪さ)
        assertTrue(vnd.cost() < twoOpt.cost(), name + ": " + vnd.cost() + " vs " + twoOpt.cost());
    }

    static Stream<Arguments> speedupsOnRandomInstances() {
        return randomInstances().flatMap(name -> Stream.of(
                Arguments.of(name, new Vnd.Config(OptionalInt.of(5), false)),
                Arguments.of(name, new Vnd.Config(OptionalInt.empty(), true)),
                Arguments.of(name, new Vnd.Config(OptionalInt.of(5), true))));
    }

    @ParameterizedTest
    @MethodSource("speedupsOnRandomInstances")
    void speedupsOnlyImproveAndStayFeasible(String name, Vnd.Config config) {
        // 速くする仕組みは調べる手を減らすので、局所最適までは保証しない。保証するのは
        // 実行可能なまま改善だけを重ねること。最近傍法 + 2-opt より良くなることは保証ではない
        // (don't-look bits を使うと、最初の 2-opt の段階でも見逃しがありうる) が、
        // ルート間の近傍の効きを考えれば、これが崩れたら何かがおかしい
        Instance inst = NeighborhoodTestBase.stored(name);
        SolveResult r = Vnd.solve(inst, config, "x");
        assertTrue(r.feasible());
        assertEquals(Objective.solutionCost(inst, r.solution()), r.history().get(r.history().size() - 1).cost());
        for (int t = 1; t < r.history().size(); t++) {
            assertTrue(r.history().get(t).cost() < r.history().get(t - 1).cost());
        }
        assertTrue(r.cost() < TwoOpt.solve(inst).cost());
    }

    @Test
    void theFirstPhaseIsStep1TwoOpt() {
        // 2-opt を最初に置いたので、最初にほかの近傍へ進む時点の解は最近傍法 + 2-opt と同じ
        Instance inst = NeighborhoodTestBase.stored("rand50-uniform-s0");
        Routes s = Routes.of(inst, NearestNeighbor.build(inst));
        List<Solution> beforeOtherNeighborhoods = new ArrayList<>();
        Solution[] last = {s.toSolution()};
        Vnd.descend(s, Vnd.DEFAULT, (k, cost) -> {
            if (k != 0 && beforeOtherNeighborhoods.isEmpty()) {
                beforeOtherNeighborhoods.add(last[0]);
            }
            last[0] = s.toSolution();
        });
        assertEquals(TwoOpt.solve(inst).solution(), beforeOtherNeighborhoods.get(0));
    }

    // ---------------------------------------------------------------- VND の進み方

    /** 走査したことと、手を適用したかどうかを記録する近傍。 */
    static final class Recording implements Neighborhood {
        final Neighborhood inner;
        final int index;
        final List<String> log;

        Recording(Neighborhood inner, int index, List<String> log) {
            this.inner = inner;
            this.index = index;
            this.log = log;
        }

        @Override
        public String name() {
            return inner.name();
        }

        @Override
        public void scan(Routes s, IntPredicate look, Consumer<Move> out) {
            boolean[] improving = {false};
            inner.scan(s, look, m -> {
                improving[0] |= m.delta() < 0;
                out.accept(m);
            });
            log.add(index + (improving[0] ? "+" : "-"));
        }
    }

    @Test
    void movesOnWhenStuckAndReturnsToTheFirstAfterAnImprovement() {
        // 0 番 (2-opt) と 1 番 (relocate) を行き来する場面が多いインスタンス
        Instance inst = NeighborhoodTestBase.stored("rand200-clustered-s1");
        List<String> log = new ArrayList<>();
        List<Neighborhood> nbs = new ArrayList<>();
        for (int k = 0; k < Vnd.DEFAULT.size(); k++) {
            nbs.add(new Recording(Vnd.DEFAULT.get(k), k, log));
        }
        Routes s = Routes.of(inst, NearestNeighbor.build(inst));
        long[] applied = Vnd.descend(s, nbs, (k, cost) -> {});

        // 改善できた近傍の次は 0 番、改善できなかった近傍の次はその次の番号
        assertEquals("0", log.get(0).substring(0, 1));
        for (int t = 1; t < log.size(); t++) {
            String prev = log.get(t - 1);
            int k = Integer.parseInt(prev.substring(0, 1));
            int expected = prev.endsWith("+") ? 0 : k + 1;
            assertEquals(String.valueOf(expected), log.get(t).substring(0, 1), "走査の順番: " + log);
        }
        // 最後は全ての近傍が改善できずに終わる
        assertEquals(List.of("0-", "1-", "2-", "3-", "4-"), log.subList(log.size() - 5, log.size()));
        assertTrue(applied[0] > 0 && applied[1] > 0, "2-opt と relocate は効く: " + log);
    }

    // ---------------------------------------------------------------- don't-look bits の規則

    @Test
    void dontLookBitsFollowTheirRules() {
        // 走査のたびに外から観察し、規則どおりのビットを手元で組み立てて、VND が渡す look と突き合わせる。
        // 規則: (1) 近傍 k の走査で、見た顧客のうち改善する手の起点にならなかった顧客は、近傍 k で立てる
        //       (2) 手を適用して前後の顧客が変わった顧客は、全ての近傍で倒す
        Instance inst = NeighborhoodTestBase.stored("rand50-uniform-s0");
        int n = inst.n();
        List<Neighborhood> inner = Vnd.neighborhoods(NeighborList.of(inst, 5));
        boolean[][] bits = new boolean[inner.size()][n + 1];
        int[][][] lastAdjacency = {null};
        long[] counts = new long[3];   // 走査の数、倒した数、look が偽だった数
        List<Neighborhood> observed = new ArrayList<>();
        for (int k = 0; k < inner.size(); k++) {
            final int kk = k;
            observed.add(new Neighborhood() {
                @Override
                public String name() {
                    return inner.get(kk).name();
                }

                @Override
                public void scan(Routes s, IntPredicate look, Consumer<Move> out) {
                    int[][] adj = Vnd.adjacency(s);
                    if (lastAdjacency[0] != null) {
                        for (int c = 1; c <= n; c++) {
                            if (adj[0][c] != lastAdjacency[0][0][c] || adj[1][c] != lastAdjacency[0][1][c]) {
                                for (boolean[] b : bits) {
                                    counts[1] += b[c] ? 1 : 0;
                                    b[c] = false;
                                }
                            }
                        }
                    }
                    lastAdjacency[0] = adj;
                    for (int c = 1; c <= n; c++) {
                        assertEquals(!bits[kk][c], look.test(c), name() + " の走査 " + counts[0] + " で顧客 " + c);
                        counts[2] += look.test(c) ? 0 : 1;
                    }
                    boolean[] improving = new boolean[n + 1];
                    inner.get(kk).scan(s, look, m -> {
                        if (m.delta() < 0) {
                            improving[m.origin(s)] = true;
                        }
                        out.accept(m);
                    });
                    for (int c = 1; c <= n; c++) {
                        bits[kk][c] |= !improving[c];
                    }
                    counts[0]++;
                }
            });
        }
        Vnd.descendDontLook(Routes.of(inst, NearestNeighbor.build(inst)), observed, (k, cost) -> {});
        // 規則がどちらも実際に働いた
        assertTrue(counts[0] > 20 && counts[1] > 0 && counts[2] > 0, java.util.Arrays.toString(counts));
    }

    @Test
    void statsCountTheMovesOfEachNeighborhood() {
        Instance inst = NeighborhoodTestBase.stored("rand50-clustered-s2");
        SolveResult r = Vnd.solve(inst);
        long moves = (Long) r.stats().get("moves");
        long sum = 0;
        for (Neighborhood nb : Vnd.DEFAULT) {
            sum += (Long) r.stats().get("moves_" + nb.name());
        }
        assertEquals(moves, sum);
        assertEquals(moves + 1, r.history().size());
        assertEquals((long) r.solution().numRoutes(), r.stats().get("routes"));
        assertEquals(Objective.solutionCost(inst, r.solution()), r.history().get(r.history().size() - 1).cost());
        for (int t = 1; t < r.history().size(); t++) {
            assertTrue(r.history().get(t).cost() < r.history().get(t - 1).cost());
        }
    }
}
