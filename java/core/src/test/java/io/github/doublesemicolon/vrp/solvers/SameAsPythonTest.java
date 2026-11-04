package io.github.doublesemicolon.vrp.solvers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.doublesemicolon.vrp.Improvement;
import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.SolveResult;
import io.github.doublesemicolon.vrp.TestData;
import io.github.doublesemicolon.vrp.json.VrpJson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Python のソルバが書いた結果 (testdata/runs/python) と、同じインスタンスで Java のソルバを
 * 走らせた結果が一致すること。経過時間以外の全て (解そのもの・コスト・stats・history の
 * コストと反復回数) を比べる。
 *
 * <p>最適値の一致だけなら、探索順が違っても通る。解そのものと stats (刈った数など) まで
 * 一致するのは、同コストの最適解が複数あるときに同じものを選び、同じ順で刈っているとき。
 */
class SameAsPythonTest {
    static final Map<String, Function<Instance, SolveResult>> SOLVERS = Map.of(
            "brute", BruteForce::solve,
            "bnb", BranchAndBound::solve,
            "bnb+nn", BranchAndBound::solveWithNnSeed,
            "nn", NearestNeighbor::solve);

    static Stream<Arguments> pythonResults() throws IOException {
        List<Arguments> out = new ArrayList<>();
        for (String name : List.of("toy8", "toy5-unlimited", "nn-over", "rand30-uniform-s0", "rand30-clustered-s1")) {
            VrpJson.Run run = VrpJson.readRun(TestData.runs().resolve("python/" + name + ".json"));
            for (SolveResult r : run.results()) {
                if (SOLVERS.containsKey(r.solver())) {
                    out.add(Arguments.of(name + " / " + r.solver(), run.instance(), r));
                }
            }
        }
        // toy8・toy5-unlimited・nn-over で 4 種、ランダム 2 件で nn の 1 種
        if (out.size() != 3 * 4 + 2) {
            throw new IllegalStateException("突き合わせる結果の数が想定と違う: " + out.size());
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pythonResults")
    void javaReproducesPythonExceptForTime(String label, Instance inst, SolveResult python) {
        SolveResult java = SOLVERS.get(python.solver()).apply(inst);
        assertEquals(python.solution(), java.solution(), "解");
        assertEquals(python.cost(), java.cost());
        assertEquals(python.feasible(), java.feasible());
        assertEquals(python.stats(), java.stats(), "stats");
        assertEquals(withoutTime(python.history()), withoutTime(java.history()), "history");
    }

    static List<String> withoutTime(List<Improvement> h) {
        return h.stream().map(i -> i.cost() + "@" + i.iteration()).toList();
    }
}
