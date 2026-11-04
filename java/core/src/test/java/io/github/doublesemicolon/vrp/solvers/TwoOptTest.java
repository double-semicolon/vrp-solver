package io.github.doublesemicolon.vrp.solvers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Improvement;
import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Objective;
import io.github.doublesemicolon.vrp.Solution;
import io.github.doublesemicolon.vrp.SolveResult;
import io.github.doublesemicolon.vrp.TestData;
import io.github.doublesemicolon.vrp.json.Cases;
import io.github.doublesemicolon.vrp.json.VrpJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 2-opt のテスト (python/tests/test_two_opt.py の移植)。
 *
 * <p>差分計算 (gain) は速さのための近道で、間違えても探索は黙って動き続ける。
 * だから gain は必ずルートのコストの計算し直しと突き合わせる。
 */
class TwoOptTest {

    static Instance stored(String name) {
        try {
            return VrpJson.readRun(TestData.runs().resolve("python/" + name + ".json")).instance();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // toy 2 件と、Python が保存した顧客 50 件のランダムインスタンス 10 件
    static Stream<Instance> instances() {
        List<Instance> out = new ArrayList<>(List.of(Cases.get("toy5").instance(), Cases.get("toy8").instance()));
        for (int s = 0; s < 5; s++) {
            for (String lay : List.of("uniform", "clustered")) {
                out.add(stored("rand50-" + lay + "-s" + s));
            }
        }
        return out.stream();
    }

    static List<Integer> reversed(List<Integer> route, int i, int j) {
        List<Integer> r = new ArrayList<>(route);
        Collections.reverse(r.subList(i, j + 1));
        return r;
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 10, 25})
    void gainMatchesFullRecomputation(int m) {
        // 端 (i == 0 で depot から入る辺、j == m-1 で depot へ戻る辺) を取り違えるのが典型的なバグ
        Instance inst = stored("rand50-uniform-s0");
        Random rng = new Random(m);
        List<Integer> customers = new ArrayList<>();
        for (int c = 1; c <= inst.n(); c++) {
            customers.add(c);
        }
        for (int t = 0; t < 20; t++) {
            Collections.shuffle(customers, rng);
            List<Integer> route = List.copyOf(customers.subList(0, m));
            for (int i = 0; i < m; i++) {
                for (int j = i + 1; j < m; j++) {
                    long expected = Objective.routeCost(inst, route) - Objective.routeCost(inst, reversed(route, i, j));
                    assertEquals(expected, TwoOpt.gain(inst, route, i, j), route + " " + i + " " + j);
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("instances")
    void accumulatedCostMatchesRecomputation(Instance inst) {
        SolveResult res = TwoOpt.solve(inst);
        assertEquals(res.cost(), res.history().get(res.history().size() - 1).cost());
        assertEquals(Objective.solutionCost(inst, res.solution()), res.cost());
    }

    @ParameterizedTest
    @MethodSource("instances")
    void resultIsFeasibleAndNeverWorse(Instance inst) {
        Solution start = NearestNeighbor.build(inst);
        SolveResult res = TwoOpt.solve(inst);
        assertEquals(List.of(), Objective.validate(inst, res.solution()));
        assertTrue(res.cost() <= Objective.solutionCost(inst, start));
        assertEquals("nn+2opt", res.solver());
        // ルート内の並べ替えしかしないので、どの客がどのルートかは変わらない
        for (int k = 0; k < start.numRoutes(); k++) {
            List<Integer> a = new ArrayList<>(start.routes().get(k));
            List<Integer> b = new ArrayList<>(res.solution().routes().get(k));
            Collections.sort(a);
            Collections.sort(b);
            assertEquals(a, b);
        }
    }

    @ParameterizedTest
    @MethodSource("instances")
    void resultIsALocalOptimum(Instance inst) {
        // gain を使わずルートのコストで直接確かめる。gain が改善を見落とすバグは gain では気づけない
        for (List<Integer> route : TwoOpt.solve(inst).solution().routes()) {
            long base = Objective.routeCost(inst, route);
            for (int i = 0; i < route.size(); i++) {
                for (int j = i + 1; j < route.size(); j++) {
                    assertTrue(Objective.routeCost(inst, reversed(route, i, j)) >= base, route + " " + i + " " + j);
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("instances")
    void historyStartsFromNnAndStrictlyDecreases(Instance inst) {
        SolveResult res = TwoOpt.solve(inst);
        List<Improvement> h = res.history();
        assertEquals(Objective.solutionCost(inst, NearestNeighbor.build(inst)), h.get(0).cost());
        for (int k = 0; k < h.size(); k++) {
            assertEquals(k, h.get(k).iteration().getAsLong());
            if (k > 0) {
                assertTrue(h.get(k).cost() < h.get(k - 1).cost());
            }
        }
        assertEquals((long) h.size() - 1, res.stats().get("moves"));
    }

    @Test
    void reachesTheOptimumOnToys() {
        // toy8 では最近傍法の 318 から 1 手で最適 303 に届く。toy5 は最初から最適
        for (String name : List.of("toy5", "toy8")) {
            Cases.Case c = Cases.get(name);
            assertEquals(c.optimalCost().getAsLong(), TwoOpt.solve(c.instance()).cost(), name);
        }
    }

    @Test
    void improvesOn50CustomerInstances() {
        instances().filter(i -> i.n() == 50).forEach(inst -> assertTrue(
                TwoOpt.solve(inst).cost() < Objective.solutionCost(inst, NearestNeighbor.build(inst)), inst.name()));
    }

    @Test
    void doesNotModifyTheInput() {
        Instance toy8 = Cases.get("toy8").instance();
        Solution start = NearestNeighbor.build(toy8);
        Solution snapshot = new Solution(start.routes());
        TwoOpt.improve(toy8, start, (a, c) -> {});
        assertEquals(snapshot, start);
    }

    @Test
    void doesNotFixAVehicleLimitViolation() {
        // ルート数を変えない近傍なので、K 台超過の初期解は超過したまま出てくる
        SolveResult res = TwoOpt.solve(Cases.get("nn-over").instance());
        assertEquals(3, res.solution().numRoutes());
        assertFalse(res.feasible());
    }

    @Test
    void tieGoesToTheSmallestIThenTheSmallestJ() {
        // 最近傍法のルート [3, 1, 2, 4, 5, 6] で (3, 5) と (4, 5) がどちらも 1 縮む。
        // i の小さい (3, 5) を選ぶので [3, 1, 2, 6, 5, 4] になる ((4, 5) なら [3, 1, 2, 4, 6, 5])
        Instance inst = stored("two-opt-tie");
        List<Integer> route = new ArrayList<>(NearestNeighbor.build(inst).routes().get(0));
        assertEquals(List.of(3, 1, 2, 4, 5, 6), route);
        assertEquals(1, TwoOpt.gain(inst, route, 3, 5));
        assertEquals(1, TwoOpt.gain(inst, route, 4, 5));

        assertEquals(1, TwoOpt.improveOnce(inst, route));
        assertEquals(List.of(3, 1, 2, 6, 5, 4), route);
    }

    @Test
    void tieWithTheSameIGoesToTheSmallestJ() {
        // 同点の決め方の後半 (i が同じなら j の小さい方)。上のテストは i が違う同点なので、
        // こちらが無いと「同じ i の中で j を大きい順に見る」誤りに気づけない。
        // ルート [1, 2, 3, 4, 5, 6] で (1, 3) と (1, 5) がどちらも 4 縮む。(1, 3) を選ぶ
        double[][] coords = {{5, 5}, {7, 4}, {1, 6}, {2, 8}, {5, 7}, {1, 2}, {10, 5}};
        Instance inst = new Instance("two-opt-tie-same-i", coords, new int[] {0, 1, 1, 1, 1, 1, 1}, 6,
                java.util.OptionalInt.of(1));
        List<Integer> route = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6));
        assertEquals(4, TwoOpt.gain(inst, route, 1, 3));
        assertEquals(4, TwoOpt.gain(inst, route, 1, 5));

        assertEquals(4, TwoOpt.improveOnce(inst, route));
        assertEquals(List.of(1, 4, 3, 2, 5, 6), route);
    }
}
