package io.github.doublesemicolon.vrp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** python/tests/test_model.py の移植と、Java で増えた検査 (非有限値・int のあふれ)。 */
class InstanceTest {

    /** 変更可能な引数一式。テストごとに1か所だけ壊す。 */
    static final class Args {
        String name = "tiny";
        double[][] coords = {{0, 0}, {3, 4}, {0, 2}};
        int[] demands = {0, 2, 3};
        int capacity = 5;
        OptionalInt numVehicles = OptionalInt.of(2);

        Instance build() {
            return new Instance(name, coords, demands, capacity, numVehicles);
        }
    }

    static Instance make(Consumer<Args> edit) {
        Args a = new Args();
        edit.accept(a);
        return a.build();
    }

    static int[][] matrix(double[][] coords) {
        return new Instance("m", coords, new int[coords.length], 1, OptionalInt.empty()).distanceMatrix();
    }

    @Test
    void distanceIsRoundedEuclidean() {
        // 3-4-5 の直角三角形と、四捨五入の境目 (sqrt(2) = 1.41 -> 1, sqrt(5) = 2.24 -> 2)
        int[][] d = matrix(new double[][] {{0, 0}, {3, 4}, {1, 1}, {1, 2}});
        assertEquals(5, d[0][1]);
        assertEquals(1, d[0][2]);
        assertEquals(2, d[0][3]);
    }

    @Test
    void distanceRoundsHalfUp() {
        // 距離 2.5 は 3 になる (CVRPLib の nint)。偶数丸めなら 2
        assertEquals(3, matrix(new double[][] {{0, 0}, {1.5, 2}})[0][1]);
    }

    @Test
    void roundingFollowsPythonNotMathRound() {
        // 0.49999999999999994 + 0.5 は浮動小数点で 1.0 になる。Python の int(d + 0.5) と同じく 1。
        // Math.round は正確に丸めて 0 を返すので、使うと Python と食い違う
        double d = 0.49999999999999994;
        assertEquals(0, Math.round(d), "前提: Math.round はこの値を 0 にする");
        assertEquals(1, Instance.euc2d(d, 0));
    }

    @Test
    void distanceMatrixIsSymmetricWithZeroDiagonal() {
        int[][] d = matrix(new double[][] {{0, 0}, {3, 4}, {7, 1}, {2, 9}});
        for (int i = 0; i < 4; i++) {
            assertEquals(0, d[i][i]);
            for (int j = 0; j < 4; j++) {
                assertEquals(d[i][j], d[j][i]);
            }
        }
    }

    @Test
    void instanceBasics() {
        Instance inst = make(a -> {});
        assertEquals(2, inst.n());
        assertEquals(5, inst.dist(0, 1));
        assertEquals(4, inst.dist(1, 2));   // sqrt(13) = 3.61
        assertEquals(2, inst.dist(0, 2));
    }

    @Test
    void unlimitedVehiclesIsAccepted() {
        // 空は「上限なし」。0 台 (= 車両なし) とは別物として受け付ける
        assertTrue(make(a -> a.numVehicles = OptionalInt.empty()).numVehicles().isEmpty());
    }

    @Test
    void inputArraysAreCopied() {
        Args a = new Args();
        Instance inst = a.build();
        a.coords[1][0] = 100;
        a.demands[1] = 5;
        assertEquals(5, inst.dist(0, 1));
        assertEquals(2, inst.demand(1));
    }

    static Stream<Arguments> invalid() {
        return Stream.of(
                Arguments.of("長さ不一致", (Consumer<Args>) a -> a.demands = new int[] {0, 2}),
                Arguments.of("depot なし", (Consumer<Args>) a -> {
                    a.coords = new double[0][];
                    a.demands = new int[0];
                }),
                Arguments.of("depot に需要", (Consumer<Args>) a -> a.demands = new int[] {1, 2, 3}),
                Arguments.of("負の需要", (Consumer<Args>) a -> a.demands = new int[] {0, -1, 3}),
                Arguments.of("容量超過の顧客", (Consumer<Args>) a -> a.demands = new int[] {0, 2, 6}),
                Arguments.of("車両なし", (Consumer<Args>) a -> a.numVehicles = OptionalInt.of(0)),
                Arguments.of("座標が NaN", (Consumer<Args>) a -> a.coords[1][0] = Double.NaN),
                Arguments.of("座標が無限大", (Consumer<Args>) a -> a.coords[2][1] = Double.POSITIVE_INFINITY),
                Arguments.of("座標が 3 要素", (Consumer<Args>) a -> a.coords[1] = new double[] {1, 2, 3}),
                // 距離 3e9 は int に収まらない。(int) にすると黙って 2147483647 になる
                Arguments.of("距離が int を超える", (Consumer<Args>) a -> a.coords[1] = new double[] {3e9, 0}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalid")
    void invalidInstanceIsRejected(String why, Consumer<Args> edit) {
        assertThrows(IllegalArgumentException.class, () -> make(edit));
    }
}
