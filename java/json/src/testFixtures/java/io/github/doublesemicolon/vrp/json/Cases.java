package io.github.doublesemicolon.vrp.json;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.TestData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * testdata/cases/*.json (vrp-case/1) の読み込み。Python の conftest.load_case と同じ。
 *
 * <p>テストでしか使わない形式なので、本体ではなくテスト用の部品 (testFixtures) に置く。
 * Jackson の型は外に出さないので、使う側 (core のテスト) は Jackson を知らなくてよい。
 */
public final class Cases {
    private Cases() {}

    /** 全探索 (参照実装) で求めた正解。optimalCost が空なら実行可能解が存在しない。 */
    public record Case(Instance instance, OptionalLong optimalCost, long nSolutions, long nNodes,
                       String description) {
        public String name() {
            return instance.name();
        }
    }

    public static final String FORMAT = "vrp-case/1";

    private static Map<String, Case> all;

    /** ファイル名の順に並んだ全ケース。11 件でなければ失敗する (黙って減らない)。 */
    public static synchronized Map<String, Case> all() {
        if (all == null) {
            Map<String, Case> m = new LinkedHashMap<>();
            try (Stream<Path> s = Files.list(TestData.cases())) {
                for (Path p : s.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
                    Case c = load(p);
                    m.put(c.name(), c);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (m.size() != 11) {
                throw new IllegalStateException("ケースの数が 11 でない: " + m.keySet());
            }
            // Map.copyOf は順序を保たないので使わない
            all = Collections.unmodifiableMap(m);
        }
        return all;
    }

    public static Case get(String name) {
        Case c = all().get(name);
        if (c == null) {
            throw new IllegalArgumentException("ケースが無い: " + name);
        }
        return c;
    }

    /** 実行可能解があるケース。 */
    public static List<Case> feasible() {
        return all().values().stream().filter(c -> c.optimalCost().isPresent()).toList();
    }

    static Case load(Path path) {
        String name = path.getFileName().toString();
        try {
            JsonNode d = VrpJson.parse(Files.readString(path, StandardCharsets.UTF_8));
            VrpJson.keys(d, "case", "format", "description", "instance", "expected");
            if (!FORMAT.equals(VrpJson.str(d.get("format"), "format"))) {
                throw new IllegalArgumentException("未知の形式: " + d.get("format"));
            }
            Instance inst = VrpJson.instanceFrom(d.get("instance"), "instance");
            if (!name.equals(inst.name() + ".json")) {
                throw new IllegalArgumentException("ファイル名とインスタンス名 " + inst.name() + " が違う");
            }
            JsonNode e = d.get("expected");
            VrpJson.keys(e, "expected", "optimal_cost", "n_solutions", "n_nodes");
            JsonNode opt = e.get("optimal_cost");
            return new Case(inst,
                    opt.isNull() ? OptionalLong.empty() : OptionalLong.of(VrpJson.int64(opt, "expected.optimal_cost")),
                    VrpJson.int64(e.get("n_solutions"), "expected.n_solutions"),
                    VrpJson.int64(e.get("n_nodes"), "expected.n_nodes"),
                    VrpJson.str(d.get("description"), "description"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + ": " + e.getMessage(), e);
        }
    }
}
