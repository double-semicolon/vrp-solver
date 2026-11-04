package io.github.doublesemicolon.vrp.json;

import io.github.doublesemicolon.vrp.Improvement;
import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Solution;
import io.github.doublesemicolon.vrp.SolveResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 結果ファイル (vrp-run/1) の読み書き (Python 版の serialize.py)。
 *
 * <p>Python 版と同じ規則で厳格に読む。片方だけ緩いと「Python では読めるが Java では
 * 読めないファイル」ができ、突き合わせの意味が無くなる (規則は testdata/README.md)。
 * <ul>
 *   <li>NaN・Infinity、同じキーの重複、知らないキー、欠けたキーはエラー
 *   <li>整数の欄は JSON の整数だけ。cost・iteration・stats は long、それ以外は int に収まること
 *   <li>小数の欄は整数も受け付ける。有限でなければエラー (1e400 は読むと無限大になる)
 *   <li>null を許すのは num_vehicles・history の iteration・stats の値だけ
 * </ul>
 *
 * <p>書くときも非有限値は例外にする。null に置き換えると読み戻したときに元に戻らない。
 * Jackson の型は公開 API に出さない (使う側が Jackson に依存しなくて済むように)。
 */
public final class VrpJson {
    /** 形式を変えたらここを上げる。Python 版の serialize.FORMAT と揃える印。 */
    public static final String RUN_FORMAT = "vrp-run/1";

    private VrpJson() {}

    /** 1つのファイルに入っている、1インスタンスとそこで走らせた結果の一式。 */
    public record Run(Instance instance, List<SolveResult> results) {
        public Run {
            results = List.copyOf(results);
        }
    }

    // NaN・Infinity は Jackson の既定で拒否される (ALLOW_NON_NUMERIC_NUMBERS が無効)。
    // 重複キーは既定では後の値で黙って上書きされるので、明示的に有効にする
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    // 改行は OS によらず \n にする (既定はシステムの改行で、Windows では CRLF になる)
    private static final DefaultPrettyPrinter PRETTY = new DefaultPrettyPrinter()
            .withSeparators(Separators.createDefaultInstance()
                    .withObjectNameValueSpacing(Separators.Spacing.AFTER))
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"));

    // ---------------------------------------------------------------- 読む

    public static Run readRun(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        try {
            return parseRun(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path.getFileName() + ": " + e.getMessage(), e);
        }
    }

    public static Run parseRun(String text) {
        JsonNode root = parse(text);
        JsonNode format = root.isObject() ? root.get("format") : null;
        if (format == null || !format.isString() || !format.stringValue().equals(RUN_FORMAT)) {
            throw new IllegalArgumentException("未知の形式: " + format + " (期待: " + RUN_FORMAT + ")");
        }
        keys(root, "run", "format", "instance", "results");
        Instance inst = instanceFrom(root.get("instance"), "instance");
        List<SolveResult> results = new ArrayList<>();
        JsonNode rs = array(root.get("results"), "results");
        for (int i = 0; i < rs.size(); i++) {
            results.add(resultFrom(rs.get(i), "results[" + i + "]"));
        }
        return new Run(inst, results);
    }

    /** 厳格に JSON を読む。パッケージの外に Jackson の型を出さないため package-private。 */
    static JsonNode parse(String text) {
        try {
            JsonNode node = MAPPER.readTree(text);
            if (node == null || node.isMissingNode()) {
                throw new IllegalArgumentException("JSON が空");
            }
            return node;
        } catch (JacksonException e) {
            throw new IllegalArgumentException("JSON として読めない: " + e.getOriginalMessage(), e);
        }
    }

    static Instance instanceFrom(JsonNode d, String where) {
        keys(d, where, "name", "coords", "demands", "capacity", "num_vehicles");
        JsonNode cs = array(d.get("coords"), where + ".coords");
        double[][] coords = new double[cs.size()][];
        for (int i = 0; i < cs.size(); i++) {
            String at = where + ".coords[" + i + "]";
            JsonNode xy = array(cs.get(i), at);
            if (xy.size() != 2) {
                throw new IllegalArgumentException(at + ": [x, y] の 2 要素でない: " + xy);
            }
            coords[i] = new double[] {dbl(xy.get(0), at), dbl(xy.get(1), at)};
        }
        JsonNode ds = array(d.get("demands"), where + ".demands");
        int[] demands = new int[ds.size()];
        for (int i = 0; i < ds.size(); i++) {
            demands[i] = int32(ds.get(i), where + ".demands[" + i + "]");
        }
        JsonNode nv = d.get("num_vehicles");
        return new Instance(
                str(d.get("name"), where + ".name"),
                coords,
                demands,
                int32(d.get("capacity"), where + ".capacity"),
                // null は上限なし
                nv.isNull() ? OptionalInt.empty() : OptionalInt.of(int32(nv, where + ".num_vehicles")));
    }

    static SolveResult resultFrom(JsonNode d, String where) {
        keys(d, where, "instance", "solver", "solution", "cost", "feasible", "elapsed_sec", "stats", "history");
        JsonNode sol = array(d.get("solution"), where + ".solution");
        List<List<Integer>> routes = new ArrayList<>();
        for (int i = 0; i < sol.size(); i++) {
            String at = where + ".solution[" + i + "]";
            JsonNode r = array(sol.get(i), at);
            List<Integer> route = new ArrayList<>();
            for (int j = 0; j < r.size(); j++) {
                route.add(int32(r.get(j), at + "[" + j + "]"));
            }
            routes.add(route);
        }

        JsonNode st = d.get("stats");
        if (!st.isObject()) {
            throw new IllegalArgumentException(where + ".stats: オブジェクトでない");
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : st.properties()) {
            stats.put(e.getKey(), stat(e.getValue(), where + ".stats." + e.getKey()));
        }

        JsonNode hs = array(d.get("history"), where + ".history");
        List<Improvement> history = new ArrayList<>();
        for (int i = 0; i < hs.size(); i++) {
            String at = where + ".history[" + i + "]";
            JsonNode h = hs.get(i);
            keys(h, at, "elapsed_sec", "cost", "iteration");
            JsonNode it = h.get("iteration");
            history.add(new Improvement(
                    dbl(h.get("elapsed_sec"), at + ".elapsed_sec"),
                    int64(h.get("cost"), at + ".cost"),
                    it.isNull() ? OptionalLong.empty() : OptionalLong.of(int64(it, at + ".iteration"))));
        }

        return new SolveResult(
                str(d.get("instance"), where + ".instance"),
                str(d.get("solver"), where + ".solver"),
                new Solution(routes),
                int64(d.get("cost"), where + ".cost"),
                bool(d.get("feasible"), where + ".feasible"),
                dbl(d.get("elapsed_sec"), where + ".elapsed_sec"),
                stats,
                history);
    }

    // ---------------------------------------------------------------- 書く

    /** 書き始めてから失敗すると壊れたファイルが残るので、先に文字列にしてから書く。 */
    public static Path writeRun(Path path, Instance inst, List<SolveResult> results) throws IOException {
        String text = runToString(inst, results);
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }

    public static String runToString(Instance inst, List<SolveResult> results) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("format", RUN_FORMAT);
        root.set("instance", instanceNode(inst));
        ArrayNode rs = root.putArray("results");
        for (int i = 0; i < results.size(); i++) {
            rs.add(resultNode(results.get(i), "results[" + i + "]"));
        }
        return MAPPER.writer().with(PRETTY).writeValueAsString(root) + "\n";
    }

    private static ObjectNode instanceNode(Instance inst) {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("name", inst.name());
        ArrayNode cs = d.putArray("coords");
        for (double[] xy : inst.coords()) {
            cs.addArray().add(xy[0]).add(xy[1]);   // Instance が有限であることを保証している
        }
        ArrayNode ds = d.putArray("demands");
        for (int v : inst.demands()) {
            ds.add(v);
        }
        d.put("capacity", inst.capacity());
        if (inst.numVehicles().isPresent()) {
            d.put("num_vehicles", inst.numVehicles().getAsInt());
        } else {
            d.putNull("num_vehicles");
        }
        return d;
    }

    private static ObjectNode resultNode(SolveResult r, String where) {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("instance", r.instance());
        d.put("solver", r.solver());
        ArrayNode sol = d.putArray("solution");
        for (List<Integer> route : r.solution().routes()) {
            ArrayNode a = sol.addArray();
            route.forEach(a::add);
        }
        d.put("cost", r.cost());
        d.put("feasible", r.feasible());
        d.put("elapsed_sec", finite(r.elapsedSec(), where + ".elapsed_sec"));
        ObjectNode st = d.putObject("stats");
        for (Map.Entry<String, Object> e : r.stats().entrySet()) {
            String at = where + ".stats." + e.getKey();
            switch (e.getValue()) {
                case null -> st.putNull(e.getKey());
                case Boolean b -> st.put(e.getKey(), b);
                case Long v -> st.put(e.getKey(), v);
                case Double v -> st.put(e.getKey(), finite(v, at));
                case String s -> st.put(e.getKey(), s);
                // SolveResult が型を絞っているので来ないはず
                default -> throw new IllegalArgumentException(at + ": 書けない型 " + e.getValue().getClass());
            }
        }
        ArrayNode hs = d.putArray("history");
        for (int i = 0; i < r.history().size(); i++) {
            Improvement h = r.history().get(i);
            ObjectNode o = hs.addObject();
            o.put("elapsed_sec", finite(h.elapsedSec(), where + ".history[" + i + "].elapsed_sec"));
            o.put("cost", h.cost());
            if (h.iteration().isPresent()) {
                o.put("iteration", h.iteration().getAsLong());
            } else {
                o.putNull("iteration");
            }
        }
        return d;
    }

    private static double finite(double v, String where) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException(where + ": 非有限値は JSON に書けない: " + v);
        }
        return v;
    }

    // ---------------------------------------------------------------- 型の検査

    static void keys(JsonNode d, String where, String... expected) {
        if (d == null || !d.isObject()) {
            throw new IllegalArgumentException(where + ": オブジェクトでない");
        }
        Set<String> want = Set.of(expected);
        List<String> missing = new ArrayList<>();
        for (String k : expected) {
            if (!d.has(k)) {
                missing.add(k);
            }
        }
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : d.properties()) {
            if (!want.contains(e.getKey())) {
                unknown.add(e.getKey());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(where + ": キーが無い: " + missing);
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(where + ": 知らないキー: " + unknown);
        }
    }

    static JsonNode array(JsonNode v, String where) {
        if (!v.isArray()) {
            throw new IllegalArgumentException(where + ": 配列でない: " + v);
        }
        return v;
    }

    static int int32(JsonNode v, String where) {
        if (!v.isIntegralNumber()) {
            throw new IllegalArgumentException(where + ": 整数でない: " + v);
        }
        if (!v.canConvertToInt()) {
            throw new IllegalArgumentException(where + ": 32 bit に収まらない: " + v);
        }
        return v.intValue();
    }

    static long int64(JsonNode v, String where) {
        if (!v.isIntegralNumber()) {
            throw new IllegalArgumentException(where + ": 整数でない: " + v);
        }
        if (!v.canConvertToLong()) {
            throw new IllegalArgumentException(where + ": 64 bit に収まらない: " + v);
        }
        return v.longValue();
    }

    static double dbl(JsonNode v, String where) {
        if (!v.isNumber()) {
            throw new IllegalArgumentException(where + ": 数でない: " + v);
        }
        double x = v.doubleValue();
        if (!Double.isFinite(x)) {
            throw new IllegalArgumentException(where + ": 有限の数でない: " + v);
        }
        return x;
    }

    static String str(JsonNode v, String where) {
        if (!v.isString()) {
            throw new IllegalArgumentException(where + ": 文字列でない: " + v);
        }
        return v.stringValue();
    }

    static boolean bool(JsonNode v, String where) {
        if (!v.isBoolean()) {
            throw new IllegalArgumentException(where + ": 真偽値でない: " + v);
        }
        return v.booleanValue();
    }

    static Object stat(JsonNode v, String where) {
        if (v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isIntegralNumber()) {
            return int64(v, where);
        }
        if (v.isNumber()) {
            return dbl(v, where);
        }
        if (v.isString()) {
            return v.stringValue();
        }
        throw new IllegalArgumentException(where + ": stats の値は null・真偽値・数・文字列のどれか: " + v);
    }
}
