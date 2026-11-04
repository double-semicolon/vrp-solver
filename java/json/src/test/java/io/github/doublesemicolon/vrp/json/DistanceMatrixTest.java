package io.github.doublesemicolon.vrp.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.TestData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * Python で計算した距離行列 (testdata/distances) と、同じ座標から Java で計算した
 * 距離行列が、全ての要素で一致すること。
 *
 * <p>小数の座標 (ランダムインスタンス) と、丸めの境目 (rounding-edges) を含む。
 * 整数座標の toy だけでは、2乗の計算や 0.5 の扱いの違いは表に出ない。
 */
class DistanceMatrixTest {

    static Stream<Path> files() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(TestData.distances())) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        // ケース 11 + ランダム 2 + X set 2 + 丸めの境目 1
        if (files.size() != 16) {
            throw new IllegalStateException("距離行列のファイル数が 16 でない: " + files.size());
        }
        return files.stream();
    }

    @ParameterizedTest
    @MethodSource("files")
    void javaMatchesPython(Path file) throws IOException {
        JsonNode d = VrpJson.parse(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals("vrp-dist/1", d.get("format").stringValue());

        JsonNode cs = d.get("coords");
        double[][] coords = new double[cs.size()][];
        for (int i = 0; i < cs.size(); i++) {
            coords[i] = new double[] {cs.get(i).get(0).doubleValue(), cs.get(i).get(1).doubleValue()};
        }
        int[][] actual = new Instance("dist", coords, new int[coords.length], 0, OptionalInt.empty())
                .distanceMatrix();

        JsonNode rows = d.get("D");
        assertEquals(coords.length, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            int[] expected = new int[rows.get(i).size()];
            for (int j = 0; j < expected.length; j++) {
                expected[j] = rows.get(i).get(j).intValue();
            }
            assertArrayEquals(expected, actual[i], file.getFileName() + " の " + i + " 行目");
        }
    }
}
