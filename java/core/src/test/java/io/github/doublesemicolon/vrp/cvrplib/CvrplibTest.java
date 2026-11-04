package io.github.doublesemicolon.vrp.cvrplib;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Objective;
import io.github.doublesemicolon.vrp.Solution;
import io.github.doublesemicolon.vrp.TestData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * python/tests/test_cvrplib.py の移植。期待値はローダと無関係な方法で出したもの
 * (CVRPLib の一覧表と、DEMAND_SECTION を awk で合計した値)。
 */
class CvrplibTest {
    static final Path DATA = TestData.root().resolve("cvrplib");
    static final Path PARSE = DATA.resolve("parse");

    @ParameterizedTest
    @CsvSource({
        // ファイル名, 顧客数, 容量, 総需要, 名前の k
        "X-n101-k25, 100, 206, 5147, 25",
        "X-n106-k14, 105, 600, 7864, 14",
    })
    void xInstanceMatchesThePublishedNumbers(String name, int n, int capacity, int total, int k) throws IOException {
        Instance inst = Cvrplib.loadVrp(DATA.resolve(name + ".vrp"));
        assertEquals(name, inst.name());
        assertEquals(n, inst.n());
        assertEquals(capacity, inst.capacity());
        assertEquals(total, Arrays.stream(inst.demands()).sum());
        // 名前の k は上限ではなく ⌈総需要 / 容量⌉。だから車両数は上限なしにする
        assertEquals(k, (total + capacity - 1) / capacity);
        assertTrue(inst.numVehicles().isEmpty());
    }

    @Test
    void xN101RowsLandOnTheRightNodes() throws IOException {
        // 番号と添字の対応 (ファイルの i 番 = 添字 i-1) を、先頭と末尾の行で確かめる
        Instance inst = Cvrplib.loadVrp(DATA.resolve("X-n101-k25.vrp"));
        assertArrayEquals(new double[] {365, 689}, inst.coords()[0]);    // depot (ファイルの 1 番)
        assertArrayEquals(new double[] {615, 750}, inst.coords()[100]);  // ファイルの 101 番
        assertEquals(38, inst.demand(1));
        assertEquals(35, inst.demand(100));
        // (365,689)-(146,180): sqrt(219^2 + 509^2) = 554.12 -> 554
        assertEquals(554, inst.dist(0, 1));
    }

    // ---------------------------------------------------------------- BKS (.sol)
    //
    // ローダと距離計算を、外部の正解 (公表されている既知最良解) で確かめる。
    // 番号の対応や丸めが1か所でもずれていれば、実行可能にならないかコストが合わない。

    @ParameterizedTest
    @CsvSource({
        // ファイル名, CVRPLib の一覧表に載っている BKS のコスト, BKS のルート数
        "X-n101-k25, 27591, 26",
        "X-n106-k14, 26362, 14",
    })
    void bksIsFeasibleAndRescoresToThePublishedCost(String name, long published, int nRoutes) throws IOException {
        Instance inst = Cvrplib.loadVrp(DATA.resolve(name + ".vrp"));
        Cvrplib.Bks bks = Cvrplib.loadSol(DATA.resolve(name + ".sol"));

        assertEquals(List.of(), Objective.validate(inst, bks.solution()));
        // ファイルの Cost 行は信用せず、目的関数で採点し直した値を公表値と比べる
        assertEquals(published, Objective.solutionCost(inst, bks.solution()));
        assertEquals(published, bks.writtenCost());
        assertEquals(nRoutes, bks.solution().numRoutes());
    }

    @Test
    void bksCanUseMoreRoutesThanTheKInTheName() throws IOException {
        // X-n101-k25 の BKS は 26 ルート。名前の k (=25) を上限にすると BKS が実行不可能になる
        Instance inst = Cvrplib.loadVrp(DATA.resolve("X-n101-k25.vrp"));
        Solution bks = Cvrplib.loadSol(DATA.resolve("X-n101-k25.sol")).solution();
        Instance capped = new Instance(inst.name(), inst.coords(), inst.demands(), inst.capacity(), OptionalInt.of(25));
        assertTrue(Objective.validate(capped, bks).stream().anyMatch(e -> e.contains("車両数超過")));
        assertEquals(List.of(), Objective.validate(inst, bks));
    }

    // ---------------------------------------------------------------- 手書きの小さいファイル

    static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void tinyTextIsRead() throws IOException {
        // 異常系のテストが「元の文が読めない」せいで通っていないことの確認 (対照)
        Instance inst = Cvrplib.parseVrp(read(PARSE.resolve("valid.vrp")));
        assertEquals(2, inst.n());
        assertEquals(10, inst.capacity());
        assertArrayEquals(new int[] {0, 4, 5}, inst.demands());
        assertEquals(5, inst.dist(0, 1));
    }

    @Test
    void crlfAndMissingEofAreAccepted() throws IOException {
        // CRLF はファイルにせずここで作る (.gitattributes が eol=lf なので、置いても LF になる)
        String text = read(PARSE.resolve("valid.vrp")).replace("EOF\n", "").replace("\n", "\r\n");
        assertArrayEquals(new int[] {0, 4, 5}, Cvrplib.parseVrp(text).demands());
    }

    @Test
    void tinySolIsRead() throws IOException {
        Cvrplib.Bks bks = Cvrplib.parseSol(read(PARSE.resolve("valid.sol")));
        assertEquals(Solution.of(new int[] {1, 2}, new int[] {3}), bks.solution());
        assertEquals(42, bks.writtenCost());
    }

    @ParameterizedTest
    @ValueSource(strings = {"42.0", "42.000000"})
    void integralCostWrittenAsDecimalIsAccepted(String written) throws IOException {
        // X-n284-k15 などの BKS は Cost を小数の形で書いている (値は整数)
        String text = read(PARSE.resolve("valid.sol")).replace("Cost 42", "Cost " + written);
        assertEquals(42, Cvrplib.parseSol(text).writtenCost());
    }

    // ---------------------------------------------------------------- 異常系 (Python と同じファイル)

    static List<String[]> table() throws IOException {
        return read(PARSE.resolve("invalid.tsv")).lines().map(l -> l.split("\t")).toList();
    }

    static Stream<Arguments> invalid() throws IOException {
        return table().stream().map(r -> Arguments.of(r[0], r[1]));
    }

    @Test
    void invalidTableCoversEveryFile() throws IOException {
        List<String> files;
        try (Stream<Path> s = Files.list(PARSE.resolve("invalid"))) {
            files = s.map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertEquals(files, table().stream().map(r -> r[0]).sorted().toList());
        assertTrue(files.size() >= 28, "ファイルが減っている: " + files.size());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalid")
    void unsupportedOrBrokenFileIsRejected(String name, String why) {
        Path p = PARSE.resolve("invalid").resolve(name);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> {
                    if (name.endsWith(".vrp")) {
                        Cvrplib.loadVrp(p);
                    } else {
                        Cvrplib.loadSol(p);
                    }
                });
        assertTrue(e.getMessage().startsWith(name + ": ") && e.getMessage().contains(why), e.getMessage());
    }

    @Test
    void errorMessageNamesTheFile(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("broken.vrp");
        Files.writeString(p, read(PARSE.resolve("valid.vrp")).replace("EUC_2D", "GEO"), StandardCharsets.UTF_8);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Cvrplib.loadVrp(p));
        assertTrue(e.getMessage().startsWith("broken.vrp: "), e.getMessage());
    }
}
