package io.github.doublesemicolon.vrp.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Solution;
import io.github.doublesemicolon.vrp.json.Cases;
import io.github.doublesemicolon.vrp.solvers.NearestNeighbor;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

class LocalSearchTest {
    /** relocate の delta を 1 だけ小さく偽る近傍。差分計算のバグの代わり。 */
    static final class Lying implements Neighborhood {
        @Override
        public String name() {
            return "lying";
        }

        @Override
        public void scan(Routes s, IntPredicate look, Consumer<Move> out) {
            new Relocate().scan(s, look, m -> {
                Relocate.RelocateMove r = (Relocate.RelocateMove) m;
                out.accept(new Relocate.RelocateMove(r.from(), r.fromPos(), r.to(), r.toPos(), r.delta() - 1));
            });
        }
    }

    @Test
    void aDeltaThatDisagreesWithTheRecomputedCostStopsTheSearch() {
        // 偽った delta を信じると、実際は改善しない手を「改善」として適用し続け、止まらない
        // 最近傍法の解は relocate で改善できる (RelocateTest)。1 手目を適用した時点で止まる
        Instance inst = RelocateTest.stored("rand50-uniform-s0");
        Routes s = Routes.of(inst, NearestNeighbor.build(inst));
        var e = assertThrows(IllegalStateException.class, () -> LocalSearch.descend(s, new Lying(), c -> {}));
        assertTrue(e.getMessage().contains("lying の delta"), e.getMessage());
    }

    @Test
    void stopsImmediatelyWhenNothingImproves() {
        Instance inst = Cases.get("toy5").instance();
        Routes s = Routes.of(inst, Solution.of(new int[] {1, 2, 3, 4, 5}));
        Routes optimum = s.copy();
        LocalSearch.descend(optimum, new Relocate(), c -> {});
        long cost = optimum.cost();
        assertEquals(0, LocalSearch.descend(optimum, new Relocate(), c -> {}));
        assertEquals(cost, optimum.cost());
    }
}
