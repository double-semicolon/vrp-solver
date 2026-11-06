package io.github.doublesemicolon.vrp.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Improvement;
import io.github.doublesemicolon.vrp.SolveResult;
import io.github.doublesemicolon.vrp.TestData;
import io.github.doublesemicolon.vrp.json.VrpJson;
import io.github.doublesemicolon.vrp.json.VrpJson.BenchRow;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * X set のベンチマーク (python/tests/test_benchmark.py の移植と、Python との突き合わせ用ファイル)。
 *
 * <p>X set 全体はリポジトリに無いので、testdata/cvrplib の 2 件を使う。
 */
class XBenchTest {
    static final Path DATA = TestData.root().resolve("cvrplib");
    // Java がこの 2 件で書いたベンチマーク結果。Python の test_testdata_files.py が
    // load_bench で読み、Python の結果と突き合わせる
    static final Path JAVA_BENCH = TestData.runs().resolve("java/bench-x.json");

    static List<BenchRow> rows() throws IOException {
        return XBench.run(XBench.loadCases(DATA, OptionalInt.empty()), List.of("nn", "nn+2opt"), r -> {});
    }

    @Test
    void casesCarryThePublishedBksInSizeOrder() throws IOException {
        // CVRPLib の一覧表の値
        List<XBench.Case> cases = XBench.loadCases(DATA, OptionalInt.empty());
        assertEquals(List.of("X-n101-k25", "X-n106-k14"), cases.stream().map(c -> c.inst().name()).toList());
        assertEquals(List.of(27591L, 26362L), cases.stream().map(XBench.Case::bks).toList());
        assertEquals(List.of("X-n101-k25"),
                XBench.loadCases(DATA, OptionalInt.of(100)).stream().map(c -> c.inst().name()).toList());
    }

    @Test
    void rowsHoldEverySolverInTheRequestedOrder() throws IOException {
        for (BenchRow row : rows()) {
            assertEquals(List.of("nn", "nn+2opt"), row.results().stream().map(SolveResult::solver).toList());
            for (SolveResult res : row.results()) {
                assertTrue(res.feasible(), row.instance());
                assertTrue(res.gapTo(row.bks()) > 0, "BKS より良くはならない");
            }
        }
    }

    @Test
    void vndIsOfferedAndNeverWorseThanNnTwoOpt() throws IOException {
        assertEquals(List.of("nn", "nn+2opt", "nn+vnd", "nn+vnd-k20"), XBench.parseArgs(new String[0]).solvers());
        List<BenchRow> rows = XBench.run(XBench.loadCases(DATA, OptionalInt.empty()), List.of("nn+2opt", "nn+vnd"), r -> {});
        for (BenchRow row : rows) {
            SolveResult twoOpt = row.results().get(0);
            SolveResult vnd = row.results().get(1);
            assertTrue(vnd.feasible(), row.instance());
            assertTrue(vnd.cost() < twoOpt.cost(), row.instance());
            assertTrue(vnd.cost() > row.bks(), row.instance());
        }
    }

    // ---------------------------------------------------------------- 入力の不備

    static Path copyData(Path tmp) throws IOException {
        try (Stream<Path> s = Files.list(DATA)) {
            for (Path p : s.filter(Files::isRegularFile).filter(p -> !p.toString().endsWith(".md")).toList()) {
                Files.copy(p, tmp.resolve(p.getFileName()));
            }
        }
        return tmp;
    }

    @Test
    void missingSolIsAnErrorNotASkip(@TempDir Path tmp) throws IOException {
        Files.delete(copyData(tmp).resolve("X-n106-k14.sol"));
        var e = assertThrows(IllegalArgumentException.class, () -> XBench.loadCases(tmp, OptionalInt.empty()));
        assertTrue(e.getMessage().contains("X-n106-k14.sol"), e.getMessage());
    }

    @Test
    void writtenCostThatDoesNotRescoreIsAnError(@TempDir Path tmp) throws IOException {
        Path p = copyData(tmp).resolve("X-n101-k25.sol");
        Files.writeString(p, Files.readString(p).replace("Cost 27591", "Cost 27590"));
        var e = assertThrows(IllegalArgumentException.class, () -> XBench.loadCases(tmp, OptionalInt.empty()));
        assertTrue(e.getMessage().contains("27591"), e.getMessage());
    }

    @Test
    void infeasibleBksIsAnError(@TempDir Path tmp) throws IOException {
        Path p = copyData(tmp).resolve("X-n101-k25.sol");
        Files.writeString(p, Files.readString(p).replace("Route #26: 24 95 73 53 33 32\n", ""));
        var e = assertThrows(IllegalArgumentException.class, () -> XBench.loadCases(tmp, OptionalInt.empty()));
        assertTrue(e.getMessage().contains("実行可能でない"), e.getMessage());
    }

    @Test
    void emptyDirectoryIsAnError(@TempDir Path tmp) {
        var e = assertThrows(IllegalArgumentException.class, () -> XBench.loadCases(tmp, OptionalInt.empty()));
        assertTrue(e.getMessage().contains("fetch-x"), e.getMessage());
    }

    // ---------------------------------------------------------------- コマンド

    @Test
    void exactSolversAreRefused() {
        var e = assertThrows(IllegalArgumentException.class, () -> XBench.parseArgs(new String[] {"--solvers", "bnb"}));
        assertTrue(e.getMessage().contains("bnb"), e.getMessage());
    }

    @Test
    void savedFileIsNamedLikePythonsAndReadsBack(@TempDir Path tmp) throws IOException {
        XBench.Args a = XBench.parseArgs(new String[] {
                "--dir", DATA.toString(), "--max-n", "200", "--solvers", "nn", "nn+2opt",
                "--save", "--out-dir", tmp.toString()});
        assertEquals(tmp.resolve("bench-x-n200-nn_nn+2opt.json"), XBench.outPath(a));

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        XBench.bench(a, new PrintStream(buf, true, StandardCharsets.UTF_8));
        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("X-n101-k25") && out.contains("27591"), out);

        List<BenchRow> saved = VrpJson.readBench(XBench.outPath(a));
        assertEquals(List.of("X-n101-k25", "X-n106-k14"), saved.stream().map(BenchRow::instance).toList());
        assertEquals(withoutTime(rows()), withoutTime(saved));
    }

    // ---------------------------------------------------------------- Python との突き合わせ用ファイル

    @Test
    void benchFileForPythonIsWhatTheCurrentCodeWrites() throws IOException {
        // 経過時間は走らせるたびに変わるので、ファイルとの比較から外す。
        // ソルバの出力を意図して変えたときは VRP_REGENERATE=1 で書き直す
        List<BenchRow> now = rows();
        if ("1".equals(System.getenv("VRP_REGENERATE"))) {
            VrpJson.writeBench(JAVA_BENCH, now);
        }
        List<BenchRow> stored = VrpJson.readBench(JAVA_BENCH);
        assertEquals(withoutTime(stored), withoutTime(now),
                "Java のソルバの出力が変わった。意図した変更なら VRP_REGENERATE=1 で作り直し、Python のテストも走らせる");
    }

    static List<String> withoutTime(List<BenchRow> rows) {
        return rows.stream().flatMap(r -> r.results().stream().map(res -> String.join(" | ",
                r.instance(), String.valueOf(r.n()), String.valueOf(r.bks()), res.solver(),
                res.solution().toString(), String.valueOf(res.cost()), String.valueOf(res.feasible()),
                res.stats().toString(),
                res.history().stream().map(Improvement::cost).toList().toString(),
                res.history().stream().map(h -> h.iteration().toString()).toList().toString())))
                .toList();
    }

    // #26 で VND に速くする仕組みを足す前のコード (#25 のコミット 8e81cad) で、cvrplib/ の 2 件に
    // nn+vnd を掛けた結果。仕組みを使わない VND が、足す前と同じ解を出すことを確かめる。
    // 近傍の定義を意図して変えない限り、作り直さない (VRP_REGENERATE の対象にもしない)
    static final Path VND_BEFORE_SPEEDUPS = TestData.runs().resolve("java/vnd-x.json");

    @Test
    void vndWithoutSpeedupsGivesTheSameSolutionsAsBeforeTheyWereAdded() throws IOException {
        List<BenchRow> now = XBench.run(XBench.loadCases(DATA, OptionalInt.empty()), List.of("nn+vnd"), r -> {});
        assertEquals(withoutTime(VrpJson.readBench(VND_BEFORE_SPEEDUPS)), withoutTime(now));
    }

    @Test
    void speedupCombinationsAreNamedAfterTheirSettings() {
        assertTrue(XBench.SOLVERS.keySet().containsAll(List.of(
                "nn+vnd-dlb", "nn+vnd-k5", "nn+vnd-k40", "nn+vnd-k10-dlb", "nn+vnd-k20-dlb")));
        assertEquals(3 + 9, XBench.SOLVERS.size());
    }

    @Test
    void writtenBenchReadsBackToTheSameValues() throws IOException {
        List<BenchRow> rows = rows();
        assertEquals(rows, VrpJson.parseBench(VrpJson.benchToString(rows)));
    }
}
