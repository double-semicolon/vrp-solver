package io.github.doublesemicolon.vrp.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Solution;
import io.github.doublesemicolon.vrp.SolveResult;
import io.github.doublesemicolon.vrp.json.VrpJson.BenchRow;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * ベンチマーク結果 (vrp-bench/1) の読み書き。結果 1 件分の規則は vrp-run/1 と共通なので
 * (StrictReadingTest が確かめている)、ここでは行 (rows[i]) の欄だけを確かめる。
 * Python の load_bench も同じ規則で読む (test_benchmark.py の test_bench_is_read_strictly_like_runs)。
 */
class BenchJsonTest {
    static List<BenchRow> rows() {
        Instance inst = new Instance("tiny", new double[][] {{0, 0}, {3, 4}}, new int[] {0, 1}, 1, OptionalInt.empty());
        SolveResult r = SolveResult.build(inst, "nn", Solution.of(new int[] {1}), 0.25, Map.of(), List.of());
        return List.of(new BenchRow("tiny", 1, 10, List.of(r)));
    }

    @Test
    void roundTrips() {
        assertEquals(rows(), VrpJson.parseBench(VrpJson.benchToString(rows())));
    }

    @Test
    void doesNotWriteTheGap() {
        // gap は cost と bks から決まるので保存しない
        assertTrue(!VrpJson.benchToString(rows()).contains("gap"));
    }

    @Test
    void runFileIsNotABenchFile() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> VrpJson.parseBench("{\"format\": \"vrp-run/1\", \"rows\": []}"));
        assertTrue(e.getMessage().startsWith("未知の形式"), e.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "\"n\": 1|\"n\": \"1\"|rows[0].n: 整数でない",
        "\"bks\": 10|\"bks\": 10.0|rows[0].bks: 整数でない",
        "\"bks\": 10|\"bks\": 10, \"gap\": 1.5|rows[0]: 知らないキー: [gap]",
        "\"rows\": [|\"extra\": 1, \"rows\": [|bench: 知らないキー: [extra]",
    })
    void rowFieldsAreReadStrictly(String from, String to, String where) {
        String text = VrpJson.benchToString(rows());
        assertTrue(text.contains(from), from);
        var e = assertThrows(IllegalArgumentException.class, () -> VrpJson.parseBench(text.replace(from, to)));
        assertTrue(e.getMessage().startsWith(where), e.getMessage());
    }
}
