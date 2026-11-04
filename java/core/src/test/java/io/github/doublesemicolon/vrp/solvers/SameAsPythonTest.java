package io.github.doublesemicolon.vrp.solvers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.doublesemicolon.vrp.Improvement;
import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.SolveResult;
import io.github.doublesemicolon.vrp.TestData;
import io.github.doublesemicolon.vrp.json.VrpJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
            "nn", NearestNeighbor::solve,
            "nn+2opt", TwoOpt::solve);

    // 手作りの結果 (ソルバの出力ではない) は除く
    static final String HANDMADE = "toy5-handmade.json";

    static Stream<Arguments> pythonResults() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(TestData.runs().resolve("python"))) {
            files = s.filter(p -> !p.getFileName().toString().equals(HANDMADE)).sorted().toList();
        }
        List<Arguments> out = new ArrayList<>();
        for (Path file : files) {
            VrpJson.Run run = VrpJson.readRun(file);
            for (SolveResult r : run.results()) {
                if (!SOLVERS.containsKey(r.solver())) {
                    throw new IllegalStateException(file + " に知らないソルバ " + r.solver());
                }
                out.add(Arguments.of(run.instance().name() + " / " + r.solver(), run.instance(), r));
            }
        }
        // toy8・toy5-unlimited・nn-over は全 5 種、two-opt-tie とランダム 14 件は nn と nn+2opt
        if (out.size() != 3 * 5 + 15 * 2) {
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
