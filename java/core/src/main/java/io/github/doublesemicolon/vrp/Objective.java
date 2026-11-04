package io.github.doublesemicolon.vrp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 目的関数と実行可能性チェッカー (Python 版の objective.py)。
 *
 * <p>「解の良さ」と「解が正しいか」の定義はここ1箇所にしかない。
 * 全てのソルバがこのクラスで採点される。
 */
public final class Objective {
    private Objective() {}

    /** depot -> 顧客列 -> depot の距離。空ルートは 0。 */
    public static long routeCost(Instance inst, List<Integer> route) {
        if (route.isEmpty()) {
            return 0;
        }
        for (int c : route) {
            checkNode(inst, c);
        }
        long cost = inst.dist(0, route.get(0)) + inst.dist(route.get(route.size() - 1), 0);
        for (int k = 0; k + 1 < route.size(); k++) {
            cost += inst.dist(route.get(k), route.get(k + 1));
        }
        return cost;
    }

    public static long routeLoad(Instance inst, List<Integer> route) {
        long load = 0;
        for (int c : route) {
            checkNode(inst, c);
            load += inst.demand(c);
        }
        return load;
    }

    public static long solutionCost(Instance inst, Solution sol) {
        long cost = 0;
        for (List<Integer> r : sol.routes()) {
            cost += routeCost(inst, r);
        }
        return cost;
    }

    /** 実行可能でない理由を列挙して返す。空なら実行可能解。文言と順序は Python 版と同じ。 */
    public static List<String> validate(Instance inst, Solution sol) {
        List<String> errors = new ArrayList<>();
        int n = inst.n();

        if (inst.numVehicles().isPresent() && sol.numRoutes() > inst.numVehicles().getAsInt()) {
            errors.add("車両数超過: " + sol.numRoutes() + " 台使用 / 上限 " + inst.numVehicles().getAsInt() + " 台");
        }

        // 出てきた順を保つ (Python の dict と同じ順でエラーを並べる)
        Map<Integer, Integer> visited = new LinkedHashMap<>();
        for (List<Integer> r : sol.routes()) {
            for (int c : r) {
                visited.merge(c, 1, Integer::sum);
            }
        }

        for (int c = 1; c <= n; c++) {
            int cnt = visited.getOrDefault(c, 0);
            if (cnt == 0) {
                errors.add("顧客 " + c + " が未訪問");
            } else if (cnt > 1) {
                errors.add("顧客 " + c + " を " + cnt + " 回訪問");
            }
        }

        for (int c : visited.keySet()) {
            if (c == 0) {
                errors.add("ルート内に depot(0) が含まれている");
            } else if (c < 1 || c > n) {
                errors.add("存在しない顧客 " + c);
            }
        }

        for (int i = 0; i < sol.numRoutes(); i++) {
            // 存在しない番号は積載に数えない (報告は上で済んでいる)
            long load = 0;
            for (int c : sol.routes().get(i)) {
                if (1 <= c && c <= n) {
                    load += inst.demand(c);
                }
            }
            if (load > inst.capacity()) {
                errors.add("ルート " + i + " が容量超過: 積載 " + load + " / 上限 " + inst.capacity());
            }
        }
        return errors;
    }

    public static boolean isFeasible(Instance inst, Solution sol) {
        return validate(inst, sol).isEmpty();
    }

    /**
     * 存在しない番号で距離や需要を引かせない。範囲外の添字は配列の例外になるだけで
     * 済むが、何が悪いのか分かる文言で止める。
     */
    private static void checkNode(Instance inst, int c) {
        if (c < 0 || c > inst.n()) {
            throw new IllegalArgumentException("存在しない顧客 " + c);
        }
    }
}
