package io.github.doublesemicolon.vrp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ソルバの返り値の契約 (Python 版の result.SolveResult)。
 *
 * <p>stats はソルバ固有の数値 (探索ノード数など)。値は null・Boolean・Long・Double・String の
 * どれかに限る。JSON にそのまま書けて、読み戻すと同じ型に戻るものだけを許す。
 * Integer は Long に揃える (同じ値が型の違いで equals にならないのを防ぐ)。
 */
public record SolveResult(
        String instance,
        String solver,
        Solution solution,
        long cost,
        boolean feasible,
        double elapsedSec,
        Map<String, Object> stats,
        List<Improvement> history) {

    public SolveResult {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(solver, "solver");
        Objects.requireNonNull(solution, "solution");
        // Map.copyOf は null の値を許さないので、順序を保つ LinkedHashMap に写す
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : stats.entrySet()) {
            copy.put(Objects.requireNonNull(e.getKey()), normalizeStat(e.getKey(), e.getValue()));
        }
        stats = Collections.unmodifiableMap(copy);
        history = List.copyOf(history);
    }

    /**
     * コストと実行可能性は必ず {@link Objective} で計算し直す。
     *
     * <p>ソルバが自分で積算した値をそのまま信じると、差分計算のバグが
     * そのまま結果として通ってしまう。ここが最後の関門。
     */
    public static SolveResult build(Instance inst, String solver, Solution solution, double elapsedSec,
                                    Map<String, Object> stats, List<Improvement> history) {
        return new SolveResult(inst.name(), solver, solution, Objective.solutionCost(inst, solution),
                Objective.isFeasible(inst, solution), elapsedSec, stats, history);
    }

    /** 基準コストからの相対差 (%)。基準を 0% とする。 */
    public double gapTo(long referenceCost) {
        return (double) (cost - referenceCost) / referenceCost * 100;
    }

    private static Object normalizeStat(String key, Object v) {
        if (v == null || v instanceof Boolean || v instanceof Long || v instanceof Double || v instanceof String) {
            return v;
        }
        if (v instanceof Integer i) {
            return i.longValue();
        }
        throw new IllegalArgumentException(
                "stats." + key + " の値は null・Boolean・Long・Double・String のどれか: " + v.getClass().getName());
    }
}
