package io.github.doublesemicolon.vrp.cvrplib;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Solution;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CVRPLib のファイルを読む (Python 版の instances/cvrplib.py)。.vrp (TSPLIB 形式) から
 * {@link Instance} を、.sol から既知最良解 (BKS) を作る。
 *
 * <p>対応するのは X set が使う範囲だけ: TYPE: CVRP / EDGE_WEIGHT_TYPE: EUC_2D /
 * depot は1つで、ノード番号 1。範囲外のファイルは黙って読まずに例外にする。読めてしまうと、
 * 距離の定義や制約が違う問題を解いたまま BKS と比べることになり、しかも気づけない。
 * 知らないヘッダ (例: DISTANCE = ルート長の上限) も同じ理由で拒否する。
 *
 * <p>車両数は常に上限なし。X set の規約は台数自由で、名前の k は上限ではない。
 *
 * <p>数は正規表現で形を確かめてから変換する。{@link Integer#parseInt} は全角数字を、
 * {@link Double#parseDouble} は 0x1p4・1d・NaN を受け付け、Python の int() / float() は
 * 1_000 や nan を受け付ける。標準の関数に任せると、両言語で読めるファイルの範囲が食い違う。
 * エラーの文言は Python 版と同じ (testdata/cvrplib/parse/invalid.tsv で両言語を突き合わせる)。
 */
public final class Cvrplib {
    private Cvrplib() {}

    /** .sol の中身。記載コストは返すだけで信用しない (採点し直すのは呼び出し側)。 */
    public record Bks(Solution solution, long writtenCost) {}

    private static final Set<String> SECTIONS = Set.of("NODE_COORD_SECTION", "DEMAND_SECTION", "DEPOT_SECTION");
    private static final Set<String> KNOWN_KEYS =
            Set.of("NAME", "COMMENT", "TYPE", "DIMENSION", "EDGE_WEIGHT_TYPE", "CAPACITY");
    // X set の .sol のうち 3 件は Cost を "20215.0" の形で書く。小数部がすべて 0 のときだけ受け付ける
    private static final Pattern INTEGRAL = Pattern.compile("([0-9]+)(?:\\.0+)?");
    private static final Pattern INT = Pattern.compile("-?[0-9]+");
    private static final Pattern REAL = Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?");
    // splitlines と同じく \n・\r\n・\r のどれでも行を分ける (CVRPLib の配布ファイルは CRLF)
    private static final Pattern LINES = Pattern.compile("\\R");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    public static Instance loadVrp(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        try {
            return parseVrp(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path.getFileName() + ": " + e.getMessage(), e);
        }
    }

    public static Bks loadSol(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        try {
            return parseSol(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path.getFileName() + ": " + e.getMessage(), e);
        }
    }

    public static Instance parseVrp(String text) {
        Map<String, String> header = new HashMap<>();
        Map<String, List<String[]>> rows = new LinkedHashMap<>();
        for (String s : SECTIONS) {
            rows.put(s, new ArrayList<>());
        }
        String section = null;

        for (String line : LINES.split(text)) {
            String[] tokens = tokens(line);
            if (tokens.length == 0) {
                continue;
            }
            if (tokens[0].equals("EOF")) {
                break;
            }
            if (tokens[0].endsWith("_SECTION")) {
                if (!rows.containsKey(tokens[0])) {
                    throw new IllegalArgumentException("未対応のセクション: " + tokens[0]);
                }
                section = tokens[0];
                continue;
            }
            int colon = line.indexOf(':');
            if (colon >= 0) {
                // ヘッダはセクションより前にしか来ない。セクション内の ":" は壊れたファイル
                if (section != null) {
                    throw new IllegalArgumentException("セクションの途中にヘッダがある: '" + line.strip() + "'");
                }
                String key = line.substring(0, colon).strip();
                if (!KNOWN_KEYS.contains(key)) {
                    throw new IllegalArgumentException("未対応のヘッダ: " + key);
                }
                header.put(key, line.substring(colon + 1).strip());
                continue;
            }
            if (section == null) {
                throw new IllegalArgumentException("解釈できない行: '" + line.strip() + "'");
            }
            rows.get(section).add(tokens);
        }

        expect(header, "TYPE", "CVRP");
        expect(header, "EDGE_WEIGHT_TYPE", "EUC_2D");
        int dimension = intField(header, "DIMENSION");
        int capacity = intField(header, "CAPACITY");

        List<String[]> coordRows = numbered(rows.get("NODE_COORD_SECTION"), "NODE_COORD_SECTION", dimension, 2);
        List<String[]> demandRows = numbered(rows.get("DEMAND_SECTION"), "DEMAND_SECTION", dimension, 1);

        List<Integer> depots = new ArrayList<>();
        for (String[] t : rows.get("DEPOT_SECTION")) {
            depots.add(parseInt(t[0], "DEPOT_SECTION"));
        }
        if (depots.isEmpty() || depots.get(depots.size() - 1) != -1) {
            throw new IllegalArgumentException("DEPOT_SECTION が -1 で終わっていない");
        }
        List<Integer> body = depots.subList(0, depots.size() - 1);
        if (!body.equals(List.of(1))) {
            throw new IllegalArgumentException("depot はノード 1 の1つだけに対応: " + body);
        }

        double[][] coords = new double[dimension][];
        int[] demands = new int[dimension];
        for (int i = 0; i < dimension; i++) {
            coords[i] = new double[] {parseReal(coordRows.get(i)[0], "座標"), parseReal(coordRows.get(i)[1], "座標")};
            demands[i] = parseInt(demandRows.get(i)[0], "需要");
        }
        return new Instance(header.getOrDefault("NAME", ""), coords, demands, capacity, OptionalInt.empty());
    }

    /**
     * 「Route #i: 顧客...」の行と「Cost N」の行から解と記載コストを返す。
     *
     * <p>顧客番号は depot を除いた 1..n で、Instance の添字とそのまま一致する。
     * 番号の範囲や重複はここでは見ない (それは {@code Objective.validate} の仕事)。
     */
    public static Bks parseSol(String text) {
        List<List<Integer>> routes = new ArrayList<>();
        Long cost = null;
        for (String line : LINES.split(text)) {
            String[] tokens = tokens(line);
            if (tokens.length == 0) {
                continue;
            }
            if (tokens[0].equals("Route")) {
                int colon = line.indexOf(':');
                String head = colon < 0 ? line : line.substring(0, colon);
                String body = colon < 0 ? "" : line.substring(colon + 1);
                String[] h = tokens(head);
                if (h.length != 2 || !h[1].equals("#" + (routes.size() + 1))) {
                    throw new IllegalArgumentException("ルート番号が連続していない: '" + head.strip() + "'");
                }
                List<Integer> route = new ArrayList<>();
                for (String t : tokens(body)) {
                    route.add(parseInt(t, "顧客番号"));
                }
                if (route.isEmpty()) {
                    throw new IllegalArgumentException("空のルート: '" + head.strip() + "'");
                }
                routes.add(route);
            } else if (tokens[0].equals("Cost") && tokens.length == 2 && cost == null) {
                Matcher m = INTEGRAL.matcher(tokens[1]);
                if (!m.matches()) {
                    throw new IllegalArgumentException("Cost が整数でない: " + tokens[1]);
                }
                try {
                    cost = Long.parseLong(m.group(1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Cost が long に収まらない: " + tokens[1], e);
                }
            } else {
                throw new IllegalArgumentException("解釈できない行: '" + line.strip() + "'");
            }
        }
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("ルートが1本もない");
        }
        if (cost == null) {
            throw new IllegalArgumentException("Cost の行がない");
        }
        return new Bks(new Solution(routes), cost);
    }

    // ---------------------------------------------------------------- 検査

    private static String[] tokens(String line) {
        String s = line.strip();
        return s.isEmpty() ? new String[0] : SPACES.split(s);
    }

    private static void expect(Map<String, String> header, String key, String value) {
        if (!header.containsKey(key)) {
            throw new IllegalArgumentException(key + " がない");
        }
        if (!header.get(key).equals(value)) {
            throw new IllegalArgumentException("未対応の " + key + ": " + header.get(key) + " (" + value + " のみ対応)");
        }
    }

    private static int intField(Map<String, String> header, String key) {
        if (!header.containsKey(key)) {
            throw new IllegalArgumentException(key + " がない");
        }
        return parseInt(header.get(key), key);
    }

    /**
     * 「番号 値...」の行が 1..DIMENSION の順に過不足なく並んでいることを確かめ、値の部分を返す。
     * 番号を捨てて行の順番だけで読むと、行が欠けたファイルでも後ろのノードがずれたまま読めてしまう。
     */
    private static List<String[]> numbered(List<String[]> rows, String section, int dimension, int width) {
        if (rows.size() != dimension) {
            throw new IllegalArgumentException(section + " が " + rows.size() + " 行 (DIMENSION は " + dimension + ")");
        }
        List<String[]> values = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (r.length != width + 1) {
                throw new IllegalArgumentException(section + " の列数が違う: " + String.join(" ", r));
            }
            if (parseInt(r[0], section) != i + 1) {
                throw new IllegalArgumentException(
                        section + " の番号が連続していない: " + (i + 1) + " 番目の行が " + r[0]);
            }
            String[] v = new String[width];
            System.arraycopy(r, 1, v, 0, width);
            values.add(v);
        }
        return values;
    }

    private static int parseInt(String token, String what) {
        if (!INT.matcher(token).matches()) {
            throw new IllegalArgumentException(what + " が整数でない: '" + token + "'");
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " が int に収まらない: '" + token + "'", e);
        }
    }

    private static double parseReal(String token, String what) {
        if (!REAL.matcher(token).matches()) {
            throw new IllegalArgumentException(what + " が数でない: '" + token + "'");
        }
        double x = Double.parseDouble(token);
        if (!Double.isFinite(x)) {
            throw new IllegalArgumentException(what + " が有限の数でない: '" + token + "'");
        }
        return x;
    }
}
