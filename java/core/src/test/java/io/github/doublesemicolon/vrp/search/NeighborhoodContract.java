package io.github.doublesemicolon.vrp.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Objective;
import io.github.doublesemicolon.vrp.Solution;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * 全ての近傍に掛ける共通のテスト ({@link Neighborhood} の約束)。
 *
 * <p>Step 4 には Python の参照実装が無いので、近傍ごとに「定義どおりに素朴に作った手の集合」を
 * テストの側で用意し、それと突き合わせる。素朴な版は差分計算も容量の判定もせず、
 * 解を丸ごと作って {@link Objective} で検査するだけにする。
 */
final class NeighborhoodContract {
    private NeighborhoodContract() {}

    /**
     * @param naive 解から 1 手で作れる解を、制約を気にせず全て返す (同じ解が何度出てもよい。
     *              近傍の数え方と同じ回数だけ出すこと)
     */
    static void check(Instance inst, Solution start, Neighborhood nb,
                      BiFunction<Instance, Solution, List<Solution>> naive) {
        assertTrue(Objective.isFeasible(inst, start), "前提: 実行可能な解から始める");
        Routes s = Routes.of(inst, start);
        long before = Objective.solutionCost(inst, start);
        String original = canonical(start);

        List<String> emitted = new ArrayList<>();
        nb.scan(s, m -> {
            Routes t = s.copy();
            m.apply(t);
            Solution after = t.toSolution();
            String what = nb.name() + " " + m + " を " + start + " に";
            assertEquals(List.of(), Objective.validate(inst, after), what);
            assertEquals(Objective.solutionCost(inst, after) - before, m.delta(), what + ": delta が計算し直しと違う");
            assertEquals(Objective.solutionCost(inst, after), t.cost(), what + ": Routes の持つコストが違う");
            assertTrue(after.routes().stream().noneMatch(List::isEmpty), what + ": 空のルートが残った");
            String c = canonical(after);
            assertNotEquals(original, c, what + ": 解が変わらない手");
            emitted.add(c);
        });
        // 手を作ったり試したりしても、元の解は変わらない
        assertEquals(start.routes().stream().filter(r -> !r.isEmpty()).toList(), s.toSolution().routes());

        List<String> expected = new ArrayList<>();
        for (Solution sol : naive.apply(inst, start)) {
            if (Objective.isFeasible(inst, sol) && !canonical(sol).equals(original)) {
                expected.add(canonical(sol));
            }
        }
        expected.sort(null);
        emitted.sort(null);
        assertEquals(expected, emitted, nb.name() + " が出す手の集合が、素朴に作った集合と違う: " + start);
    }

    /** 局所最適: 1 手で作れるどの実行可能な解も、コストが下がらない。 */
    static void assertLocalOptimum(Instance inst, Solution sol,
                                   BiFunction<Instance, Solution, List<Solution>> naive) {
        long cost = Objective.solutionCost(inst, sol);
        for (Solution other : naive.apply(inst, sol)) {
            if (Objective.isFeasible(inst, other)) {
                assertTrue(Objective.solutionCost(inst, other) >= cost, other + " の方が安い");
            }
        }
    }

    /**
     * ルートの並び順を無視した解の表現。ルートの向きは区別する (逆順は別の解として数える。
     * コストは同じだが、近傍が逆順を作る手を出すかどうかは近傍の定義の一部)。空のルートは落とす。
     */
    static String canonical(Solution sol) {
        return sol.routes().stream().filter(r -> !r.isEmpty()).map(Object::toString).sorted().toList().toString();
    }

    /** 書き換えられるルートのコピー (素朴な版で使う)。 */
    static List<List<Integer>> mutable(Solution sol) {
        List<List<Integer>> out = new ArrayList<>();
        for (List<Integer> r : sol.routes()) {
            out.add(new ArrayList<>(r));
        }
        return out;
    }

    /** 空のルートを落として Solution にする。 */
    static Solution solution(List<List<Integer>> routes) {
        return new Solution(routes.stream().filter(r -> !r.isEmpty()).toList());
    }
}
