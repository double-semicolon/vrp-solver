package io.github.doublesemicolon.vrp.json;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Improvement;
import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Solution;
import io.github.doublesemicolon.vrp.SolveResult;
import io.github.doublesemicolon.vrp.TestData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * 読み書きの厳格さ。拒否するファイルは testdata/runs/invalid にあり、Python の
 * test_testdata_files.py も同じファイルを全て拒否することを確かめている。
 */
class StrictReadingTest {

    static Stream<Path> invalidFiles() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(TestData.runs().resolve("invalid"))) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        if (files.size() != 25) {
            throw new IllegalStateException("invalid のファイル数が 25 でない: " + files.size());
        }
        return files.stream();
    }

    @Test
    void validBaseIsAccepted() {
        // 対照。これが読めなければ、下の各ファイルは壊した箇所とは別の理由で落ちている
        assertDoesNotThrow(() -> VrpJson.readRun(TestData.runs().resolve("valid-base.json")));
    }

    /**
     * 拒否の理由まで確かめる。invalid.json は「ファイル名 -> 文言に含まれるべき箇所」の表で、
     * 箇所は両言語で同じ書き方にしてある。null は JSON の構文の段階で拒否されるもの。
     * 箇所を見ないと、別の理由で拒否されたときにも通ってしまう。
     */
    @ParameterizedTest
    @MethodSource("invalidFiles")
    void invalidFileIsRejectedForTheIntendedReason(Path file) throws IOException {
        String name = file.getFileName().toString().replace(".json", "");
        JsonNode expected = VrpJson.parse(Files.readString(TestData.runs().resolve("invalid.json"),
                StandardCharsets.UTF_8));
        assertTrue(expected.has(name), "invalid.json に " + name + " が無い");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> VrpJson.readRun(file));
        assertTrue(e.getMessage().startsWith(name + ".json: "), e.getMessage());
        JsonNode where = expected.get(name);
        if (!where.isNull()) {
            assertTrue(e.getMessage().contains(where.stringValue()), e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 書く側

    static final Instance TINY = new Instance("tiny", new double[][] {{0, 0}, {3, 4}}, new int[] {0, 1}, 1,
            OptionalInt.of(1));

    static SolveResult withElapsed(double elapsed, Map<String, Object> stats, List<Improvement> history) {
        return SolveResult.build(TINY, "dummy", Solution.of(new int[] {1}), elapsed, stats, history);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void nonFiniteValuesAreRefusedNotRewritten(double bad, @TempDir Path dir) {
        // 非有限値は null などに書き換えず例外にする。壊れたファイルも残さない
        List<SolveResult> cases = List.of(
                withElapsed(bad, Map.of(), List.of()),
                withElapsed(0.0, Map.of("bound", bad), List.of()),
                withElapsed(0.0, Map.of(), List.of(new Improvement(bad, 2))));
        for (SolveResult r : cases) {
            Path p = dir.resolve("run.json");
            assertThrows(IllegalArgumentException.class, () -> VrpJson.writeRun(p, TINY, List.of(r)));
            assertFalse(Files.exists(p));
        }
    }

    @Test
    void writtenFileIsReadBackUnchanged(@TempDir Path dir) throws IOException {
        SolveResult r = withElapsed(0.125, Map.of("nodes", 3L), List.of(new Improvement(0.0, 10, 0)));
        Path p = VrpJson.writeRun(dir.resolve("sub/run.json"), TINY, List.of(r));
        VrpJson.Run back = VrpJson.readRun(p);
        assertEquals(TINY, back.instance());
        assertEquals(List.of(r), back.results());
    }
}
