package io.github.doublesemicolon.vrp.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.doublesemicolon.vrp.Improvement;
import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Objective;
import io.github.doublesemicolon.vrp.Solution;
import io.github.doublesemicolon.vrp.SolveResult;
import io.github.doublesemicolon.vrp.TestData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * Java が書く結果ファイルを testdata/runs/java/golden.json に固定する。
 *
 * <p>このテストは「今の書き出し処理が golden.json と同じバイト列を出す」ことを確かめ、
 * Python の test_testdata_files.py は「golden.json を load_run で読むと、ここで組み立てた
 * 値になる」ことを確かめる。2つ合わせて、Java が書いたものを Python が読めることになる。
 *
 * <p>書き出しを意図して変えたときは、環境変数 VRP_REGENERATE=1 を付けて走らせると
 * ファイルを書き直す (そのあと Python のテストも走らせて確かめる)。
 */
class GoldenRunTest {
    static final Path GOLDEN = TestData.runs().resolve("java/golden.json");

    static Instance instance() {
        // 2進で表せない小数、循環小数、指数表記になる小さい数、負の数を含める
        double[][] coords = {{0, 0}, {0.1, 0.2}, {1.0 / 3, 2.0 / 3}, {1e-7, 12345.678}, {-3, 4}};
        return new Instance("java-golden", coords, new int[] {0, 2, 3, 4, 5}, 9, OptionalInt.empty());
    }

    static List<SolveResult> results(Instance inst) {
        Solution ok = Solution.of(new int[] {1, 2}, new int[] {3}, new int[] {4});   // 積載 5, 4, 5
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("nodes", 12L);
        stats.put("ratio", 0.1);
        stats.put("seeded", true);
        stats.put("bound", null);
        stats.put("note", "日本語");
        long cost = Objective.solutionCost(inst, ok);
        return List.of(
                SolveResult.build(inst, "feasible", ok, 1e-7, stats,
                        List.of(new Improvement(0.0, 40, 0), new Improvement(2.5e-5, cost))),
                SolveResult.build(inst, "infeasible", Solution.of(new int[] {1, 2, 3, 4}), 0.5,   // 積載 14
                        Map.of(), List.of()));
    }

    @Test
    void writerProducesTheGoldenFile() throws IOException {
        Instance inst = instance();
        String text = VrpJson.runToString(inst, results(inst));
        if ("1".equals(System.getenv("VRP_REGENERATE"))) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, text, StandardCharsets.UTF_8);
        }
        assertEquals(Files.readString(GOLDEN, StandardCharsets.UTF_8), text,
                "書き出しが変わった。意図した変更なら VRP_REGENERATE=1 で作り直し、Python のテストも走らせる");
    }

    @Test
    void goldenFileReadsBackToTheSameValues() throws IOException {
        Instance inst = instance();
        VrpJson.Run run = VrpJson.readRun(GOLDEN);
        assertEquals(inst, run.instance());
        assertEquals(results(inst), run.results());
    }
}
