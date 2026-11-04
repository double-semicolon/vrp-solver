package io.github.doublesemicolon.vrp.solvers;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Solution;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** テスト共通のヘルパ (Python の conftest.canonical / naive_all_solutions)。 */
final class Oracle {
    private Oracle() {}

    /**
     * 解を「同じ解」と見なせる形に正規化する。ルートの逆走 (距離が対称なのでコスト同一) と、
     * ルート同士の並び順は解の同一性に影響しないので、この2つを潰した形を代表にする。
     */
    static List<List<Integer>> canonical(Solution sol) {
        List<List<Integer>> out = new ArrayList<>();
        for (List<Integer> r : sol.routes()) {
            List<Integer> rev = new ArrayList<>(r);
            java.util.Collections.reverse(rev);
            out.add(LEX.compare(r, rev) <= 0 ? List.copyOf(r) : List.copyOf(rev));
        }
        out.sort(LEX);
        return List.copyOf(out);
    }

    static final Comparator<List<Integer>> LEX = (a, b) -> {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = Integer.compare(a.get(i), b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    };

    /**
     * 実行可能解の集合を、列挙器とは無関係な素朴な方法で作る独立オラクル。
     *
     * <p>顧客の全順列 (giant tour) を作り、それを K 個以下の連続する区間に切る。遅いが、
     * 列挙器が取りこぼしていないことを確かめる基準として信頼できる。実行可能性は
     * Objective.validate を使わず自前で判定する (テスト対象と同じ関数で判定すると、
     * そこにバグがあったとき両方が同じように間違えて気づけない)。
     */
    static Set<List<List<Integer>>> naiveAllSolutions(Instance inst) {
        int n = inst.n();
        // 上限なしは n 台と同じ。1ルートに顧客が最低1人いるので n 本を超えない
        int k = inst.numVehicles().orElse(n);
        Set<List<List<Integer>>> found = new HashSet<>();
        List<Integer> customers = new ArrayList<>();
        for (int c = 1; c <= n; c++) {
            customers.add(c);
        }
        BruteForce.permutations(customers, new ArrayList<>(), new boolean[n], tour -> {
            List<Integer> cutPoints = new ArrayList<>();
            for (int i = 1; i < n; i++) {
                cutPoints.add(i);
            }
            for (int nCuts = 0; nCuts < Math.min(k, n); nCuts++) {
                BruteForce.combinations(cutPoints, nCuts, 0, new ArrayList<>(), cuts -> {
                    List<List<Integer>> sol = new ArrayList<>();
                    int a = 0;
                    List<Integer> bounds = new ArrayList<>(cuts);
                    bounds.add(n);
                    for (int b : bounds) {
                        sol.add(List.copyOf(tour.subList(a, b)));
                        a = b;
                    }
                    boolean ok = sol.stream().allMatch(
                            r -> r.stream().mapToLong(inst::demand).sum() <= inst.capacity());
                    if (ok) {
                        found.add(canonical(new Solution(sol)));
                    }
                });
            }
        });
        return found;
    }
}
