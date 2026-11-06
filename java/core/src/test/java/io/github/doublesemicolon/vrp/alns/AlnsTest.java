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
        long[] prevBest = {start.cost()};
        long[] seen = new long[3];   // 悪い候補を受け入れた回数、今の解が最良より悪かった回数、反復数
        long[] prevCurrent = {start.cost()};
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
        Alns.Run run = Alns.run(start, new Alns.Config(5, 300, 2, inst.n()), (it, d, r, cand, acc, cur, best) -> {});
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
        SolveResult r = Alns.solve(inst, Alns.Config.of(inst, 0, 0));
        assertEquals(NearestNeighbor.build(inst), r.solution());
        assertEquals(1, r.history().size());
    }

    @Test
    void configScalesTheRemovalSizeWithTheInstance() {
        Instance small = stored("rand30-uniform-s0");
        Instance large = stored("rand200-clustered-s1");
        assertEquals(new Alns.Config(0, 10, 1, 6), Alns.Config.of(small, 0, 10));
        assertEquals(new Alns.Config(0, 10, 10, 40), Alns.Config.of(large, 0, 10));
    }
}
