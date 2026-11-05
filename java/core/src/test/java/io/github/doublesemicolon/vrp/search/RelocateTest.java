package io.github.doublesemicolon.vrp.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Solution;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** relocate のテスト。全近傍に共通のテストは {@link NeighborhoodTestBase}。 */
class RelocateTest extends NeighborhoodTestBase {
    static final Relocate RELOCATE = new Relocate();

    @Override
    Neighborhood neighborhood() {
        return RELOCATE;
    }

    @Override
    List<Solution> naive(Instance inst, Solution sol) {
        return naiveRelocate(sol);
    }

    /** relocate の定義そのまま: 顧客を 1 人抜き (空になったルートは消す)、あらゆる位置か新しいルートに入れる。 */
    static List<Solution> naiveRelocate(Solution sol) {
        List<Solution> out = new ArrayList<>();
        List<List<Integer>> base = NeighborhoodContract.mutable(sol);
        for (int r = 0; r < base.size(); r++) {
            for (int p = 0; p < base.get(r).size(); p++) {
                List<List<Integer>> rest = NeighborhoodContract.mutable(NeighborhoodContract.solution(base));
                int c = rest.get(r).remove(p);
                rest = NeighborhoodContract.mutable(NeighborhoodContract.solution(rest));
                for (int to = 0; to < rest.size(); to++) {
                    for (int t = 0; t <= rest.get(to).size(); t++) {
                        List<List<Integer>> x = NeighborhoodContract.mutable(NeighborhoodContract.solution(rest));
                        x.get(to).add(t, c);
                        out.add(NeighborhoodContract.solution(x));
                    }
                }
                List<List<Integer>> x = NeighborhoodContract.mutable(NeighborhoodContract.solution(rest));
                x.add(new ArrayList<>(List.of(c)));
                out.add(NeighborhoodContract.solution(x));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 個別の場面

    static Instance line(int capacity, OptionalInt k) {
        // depot を原点に、x 軸上に顧客を並べる。距離が手で計算できる
        double[][] coords = {{0, 0}, {1, 0}, {2, 0}, {3, 0}, {10, 0}};
        return new Instance("line", coords, new int[] {0, 1, 1, 1, 1}, capacity, k);
    }

    @Test
    void movingTheOnlyCustomerOutRemovesTheRoute() {
        Instance inst = line(4, OptionalInt.empty());
        Routes s = Routes.of(inst, Solution.of(new int[] {1, 2, 3}, new int[] {4}));
        // 4 を 3 の後ろへ。ルート [4] (往復 20) が消え、[1, 2, 3, 4] (往復 20) になる
        new Relocate.RelocateMove(1, 0, 0, 3, -6).apply(s);
        assertEquals(Solution.of(new int[] {1, 2, 3, 4}), s.toSolution());
        assertEquals(20, s.cost());
    }

    @Test
    void aNewRouteIsOfferedOnlyWhenAVehicleIsLeft() {
        Solution sol = Solution.of(new int[] {1, 2, 3, 4});
        for (int k : new int[] {1, 2}) {
            Instance inst = line(4, OptionalInt.of(k));
            List<Relocate.RelocateMove> toNew = new ArrayList<>();
            Routes s = Routes.of(inst, sol);
            RELOCATE.scan(s, m -> {
                Relocate.RelocateMove r = (Relocate.RelocateMove) m;
                if (r.to() == s.count()) {
                    toNew.add(r);
                }
            });
            assertEquals(k == 1 ? 0 : 4, toNew.size(), "K=" + k);
        }
    }

    @Test
    void capacityIsCheckedAgainstTheTargetRoute() {
        // 容量 2 で [1, 2] と [3]。3 を [1, 2] に入れる手は出ない。[1, 2] から [3] へは出る
        Instance inst = line(2, OptionalInt.empty());
        Routes s = Routes.of(inst, Solution.of(new int[] {1, 2}, new int[] {3, 4}));
        List<Relocate.RelocateMove> moves = new ArrayList<>();
        RELOCATE.scan(s, m -> moves.add((Relocate.RelocateMove) m));
        assertTrue(moves.stream().noneMatch(m -> m.from() != m.to() && m.to() < s.count()),
                "どちらのルートも満載なので、別のルートへは移せない");
        assertTrue(moves.stream().anyMatch(m -> m.to() == s.count()), "新しいルートへは移せる");
    }
}
