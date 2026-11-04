package io.github.doublesemicolon.vrp.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Objective;
import io.github.doublesemicolon.vrp.SolveResult;
import io.github.doublesemicolon.vrp.TestData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Python のソルバが書いた結果ファイル (testdata/runs/python) を読み、全ての結果について
 * cost と feasible を Java の目的関数で計算し直すと、Python が書いた値と一致すること。
 *
 * <p>ファイルが今の Python のコードの出力であることは、Python 側の
 * test_testdata_files.py が確かめている。
 */
class PythonWrittenRunsTest {
    // 手作りの結果のうち、cost と feasible をわざと嘘にしてあるもの (読み込みが値を
    // そのまま読むことの確認用)。計算し直しの比較からは外す
    static final String LIES = "stats-of-every-type";

    static Stream<Path> files() throws IOException {
        Path dir = TestData.runs().resolve("python");
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        if (files.size() != 6) {
            throw new IllegalStateException(dir + " のファイル数が 6 でない: " + files);
        }
        return files.stream();
    }

    @ParameterizedTest
    @MethodSource("files")
    void costAndFeasibilityRescoreToWhatPythonWrote(Path file) throws IOException {
        VrpJson.Run run = VrpJson.readRun(file);
        Instance inst = run.instance();
        // 手作りの結果は toy5 に対するもの。それ以外はファイル名 = インスタンス名
        String stem = file.getFileName().toString().replace(".json", "");
        assertEquals(stem.equals("toy5-handmade") ? "toy5" : stem, inst.name());
        for (SolveResult r : run.results()) {
            if (r.solver().equals(LIES)) {
                continue;
            }
            String what = inst.name() + " / " + r.solver();
            assertEquals(inst.name(), r.instance(), what);
            assertEquals(r.cost(), Objective.solutionCost(inst, r.solution()), what);
            assertEquals(r.feasible(), Objective.isFeasible(inst, r.solution()),
                    () -> what + " " + Objective.validate(inst, r.solution()));
        }
    }

    @Test
    void theFilesContainWhatIsWorthCrossChecking() throws IOException {
        List<VrpJson.Run> runs = files().map(p -> {
            try {
                return VrpJson.readRun(p);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).toList();
        List<SolveResult> all = runs.stream().flatMap(r -> r.results().stream())
                .filter(r -> !r.solver().equals(LIES)).toList();
        assertTrue(runs.stream().anyMatch(r -> r.instance().numVehicles().isEmpty()), "上限なしのインスタンス");
        assertTrue(all.stream().anyMatch(r -> !r.feasible()), "実行不可能な結果");
        assertTrue(all.stream().anyMatch(r -> r.stats().containsKey("initial_upper_bound")
                && r.stats().get("initial_upper_bound") == null), "stats の null");
        assertTrue(all.stream().anyMatch(r -> r.history().stream().anyMatch(h -> h.iteration().isEmpty())),
                "history の iteration の null");
        assertTrue(all.stream().anyMatch(r -> r.history().stream().anyMatch(h -> h.iteration().isPresent())),
                "history の iteration の整数");
    }

    @Test
    void valuesAreReadAsWrittenNotRecomputed() throws IOException {
        VrpJson.Run run = VrpJson.readRun(TestData.runs().resolve("python/toy5-handmade.json"));
        SolveResult r = run.results().stream().filter(x -> x.solver().equals(LIES)).findFirst().orElseThrow();
        assertEquals(0, r.cost());
        assertEquals(false, r.feasible());
        assertEquals(1e-7, r.elapsedSec());
        assertEquals(12L, r.stats().get("int"));
        assertEquals(0.1, r.stats().get("float"));
        assertEquals(true, r.stats().get("true"));
        assertTrue(r.stats().containsKey("null") && r.stats().get("null") == null);
        assertEquals("日本語", r.stats().get("text"));
        assertEquals(List.of("int", "float", "true", "null", "text"), List.copyOf(r.stats().keySet()),
                "stats の順序を保つ");
    }
}
