package io.github.doublesemicolon.vrp.alns;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Objective;
import io.github.doublesemicolon.vrp.Solution;
import io.github.doublesemicolon.vrp.json.Cases;
import io.github.doublesemicolon.vrp.search.Routes;
import io.github.doublesemicolon.vrp.solvers.BruteForce;
import io.github.doublesemicolon.vrp.solvers.NearestNeighbor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OperatorsTest {
    // ---------------------------------------------------------------- 完了条件 3: 修復の後は全顧客を 1 回ずつ、制約を満たす

    static Stream<Arguments> operatorPairs() {
        List<Arguments> out = new ArrayList<>();
        for (Destroy d : Alns.DESTROYS) {
            for (Repair r : Alns.REPAIRS) {
                out.add(Arguments.of(d, r));
            }
        }
        return out.stream();
    }

    @ParameterizedTest
    @MethodSource("operatorPairs")
    void repairVisitsEveryCustomerOnceWithinTheConstraints(Destroy d, Repair r) {
        // 車両数に余裕の無いケースも含める。そこでは修復が失敗しうるが、成功したら必ず実行可能
        long failed = 0;
        long checked = 0;
        for (Cases.Case c : Cases.feasible()) {
            Instance inst = c.instance();
            List<Solution> starts = new ArrayList<>();
            BruteForce.enumerate(inst, starts::add);
            Random rnd = new Random(42);
            for (Solution start : starts.subList(0, Math.min(starts.size(), 200))) {
                for (int q = 1; q <= inst.n(); q++) {
                    boolean ok = destroyAndRepair(inst, start, d, r, q, rnd);
                    failed += ok ? 0 : 1;
                    checked++;
                }
            }
        }
        for (String name : List.of("rand30-uniform-s0", "rand50-clustered-s2", "rand200-clustered-s1")) {
            Instance inst = AlnsTest.stored(name);
            Solution start = NearestNeighbor.build(inst);
            Random rnd = new Random(42);
            for (int k = 0; k < 30; k++) {
                // 車両数の上限が無いので、修復は必ず成功する
                assertTrue(destroyAndRepair(inst, start, d, r, 1 + rnd.nextInt(Math.min(50, inst.n())), rnd), name);
                checked++;
            }
        }
        assertTrue(checked > 1000 && failed < checked, checked + " 回中 " + failed + " 回失敗");
    }

    /** 壊して直し、途中と結果を確かめる。修復に失敗したら false。 */
    static boolean destroyAndRepair(Instance inst, Solution start, Destroy d, Repair r, int q, Random rnd) {
        Routes s = Routes.of(inst, start);
        int[] removed = d.destroy(s, q, rnd);
        String what = d.name() + "/" + r.name() + " q=" + q + " " + start;

        // 破壊: q 人の別々の顧客を抜き、残りと合わせて全員。空のルートは残らない
        assertEquals(q, removed.length, what);
        Set<Integer> all = new HashSet<>();
        for (int c : removed) {
            assertTrue(all.add(c), what + ": " + c + " を 2 回抜いた");
            assertEquals(-1, s.routeOf(c), what);
        }
        for (int k = 0; k < s.count(); k++) {
            assertTrue(s.size(k) > 0, what + ": 空のルート");
            for (int i = 0; i < s.size(k); i++) {
                assertTrue(all.add(s.node(k, i)), what);
                assertEquals(k, s.routeOf(s.node(k, i)), what);
                assertEquals(i, s.posOf(s.node(k, i)), what);
            }
        }
        assertEquals(inst.n(), all.size(), what);
        assertEquals(Objective.solutionCost(inst, s.toSolution()), s.cost(), what);

        if (!r.repair(s, removed)) {
            return false;
        }
        Solution after = s.toSolution();
        assertEquals(List.of(), Objective.validate(inst, after), what);
        assertEquals(Objective.solutionCost(inst, after), s.cost(), what);
        return true;
    }

    @Test
    void repairFailsWhenNoRouteCanTakeTheCustomer() {
        // 容量 2、車両 2 台で [1, 2] と [3, 4] (どちらも満載)。顧客 5 を入れるルートも、増やせる車両も無い
        Instance inst = line(2, OptionalInt.of(2));
        for (Repair r : Alns.REPAIRS) {
            Routes s = Routes.of(inst, Solution.of(new int[] {1, 2}, new int[] {3, 4}));
            assertFalse(r.repair(s, new int[] {5}), r.name());
        }
    }

    // ---------------------------------------------------------------- 個別の演算子

    static Instance line(int capacity, OptionalInt k) {
        // depot を原点に、x 軸上に顧客を並べる。距離が手で計算できる
        double[][] coords = {{0, 0}, {1, 0}, {2, 0}, {3, 0}, {10, 0}, {11, 0}};
        return new Instance("line", coords, new int[] {0, 1, 1, 1, 1, 1}, capacity, k);
    }

    @Test
    void worstRemovalTakesTheMostCostlyCustomerFirst() {
        // [1, 4, 2]: 抜くと減る量は 1 が d(0,1)+d(1,4)-d(0,4) = 1+9-10 = 0、
        // 4 が d(1,4)+d(4,2)-d(1,2) = 9+8-1 = 16、2 が d(4,2)+d(2,0)-d(4,0) = 8+2-10 = 0。4 が最初
        Instance inst = line(5, OptionalInt.empty());
        Routes s = Routes.of(inst, Solution.of(new int[] {1, 4, 2}));
        // p を大きくすると、乱数によらず先頭 (最も減る顧客) を選ぶ
        int[] removed = new Destroy.WorstRemoval(1e9).destroy(s, 2, new Random(0));
        // 4 を抜くと [1, 2] で、減る量は 1 が 1+1-2=0、2 が 1+2-1=2。2 番目は 2
        assertArrayEquals(new int[] {4, 2}, removed);
    }

    @Test
    void relatedRemovalTakesCustomersNearTheRemovedOnes() {
        // 最初の 1 人は無作為。以後は抜いた顧客に最も近い顧客を抜く (p が大きいとき)
        Instance inst = line(5, OptionalInt.empty());
        for (int seed = 0; seed < 20; seed++) {
            Routes s = Routes.of(inst, Solution.of(new int[] {1, 2, 3}, new int[] {4, 5}));
            int[] removed = new Destroy.RelatedRemoval(1e9).destroy(s, 2, new Random(seed));
            int gap = Math.abs(removed[0] - removed[1]);
            assertEquals(1, gap, "隣の顧客を抜く: " + java.util.Arrays.toString(removed));
        }
    }

    @Test
    void greedyInsertsTheCheapestFirst() {
        // [2, 3] と [4, 5] に 1 を入れ直す。どちらの先頭も 0 (1+1-2 と 1+9-10) で同じなので、番号の小さいルート
        Instance inst = line(5, OptionalInt.empty());
        Routes s = Routes.of(inst, Solution.of(new int[] {1, 2, 3}, new int[] {4, 5}));
        s.removeCustomer(1);
        assertTrue(new Repair.GreedyInsertion().repair(s, new int[] {1}));
        assertEquals(Solution.of(new int[] {1, 2, 3}, new int[] {4, 5}), s.toSolution());
    }

    @Test
    void regretInsertsTheCustomerWithTheMostToLoseFirst() {
        // [1, 2] と [4] に、3 と 5 を戻す。ルートごとの最も安い入れ先は
        //   3: [1, 2] に 2 (1 と 2 の間)、[4] に 0 (4 の前)、新しいルートに 6 → regret は 2 - 0 = 2
        //   5: [1, 2] に 18、[4] に 2 (4 の前)、新しいルートに 22 → regret は 18 - 2 = 16
        // 貪欲は安い 3 を先に入れ、regret は失うものが大きい 5 を先に入れる。
        // 容量 2 なら先に入れた方が [4] を取り、[1, 2] は満載なので、後の方は新しいルートになる
        Instance tight = line(2, OptionalInt.empty());
        Routes g = Routes.of(tight, Solution.of(new int[] {1, 2}, new int[] {4}));
        Routes rg = g.copy();
        new Repair.GreedyInsertion().repair(g, new int[] {3, 5});
        new Repair.RegretInsertion().repair(rg, new int[] {3, 5});
        // 貪欲: 3 を [4] の前 (0)、5 は新しいルート (22)。計 4 + 20 + 22 = 46
        // regret: 5 を [4] の前 (2)、3 は新しいルート (6)。計 4 + 22 + 6 = 32
        assertEquals(Solution.of(new int[] {1, 2}, new int[] {3, 4}, new int[] {5}), g.toSolution());
        assertEquals(Solution.of(new int[] {1, 2}, new int[] {5, 4}, new int[] {3}), rg.toSolution());
        assertEquals(46, g.cost());
        assertEquals(32, rg.cost());
    }

    @Test
    void annealingStartsByAcceptingFivePercentWorseHalfTheTime() {
        Annealing a = Annealing.of(10_000, 1000);
        // 500 (5%) 悪い候補を受け入れる確率は 0.5
        assertEquals(0.5, Math.exp(-500 / a.temperature()), 1e-12);
        double start = a.temperature();
        for (int k = 0; k < 999; k++) {
            a.cool();
        }
        assertEquals(Annealing.END_RATIO, a.temperature() / start, 1e-9);
        Random rnd = new Random(0);
        assertTrue(a.accept(100, 100, rnd) && a.accept(99, 100, rnd));
        int accepted = 0;
        Annealing hot = Annealing.of(10_000, 1000);
        for (int k = 0; k < 10_000; k++) {
            accepted += hot.accept(10_500, 10_000, rnd) ? 1 : 0;
        }
        assertEquals(5000, accepted, 200);
    }
}
