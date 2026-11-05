package io.github.doublesemicolon.vrp.bench;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Objective;
import io.github.doublesemicolon.vrp.SolveResult;
import io.github.doublesemicolon.vrp.cvrplib.Cvrplib;
import io.github.doublesemicolon.vrp.json.VrpJson;
import io.github.doublesemicolon.vrp.json.VrpJson.BenchRow;
import io.github.doublesemicolon.vrp.search.Vnd;
import io.github.doublesemicolon.vrp.solvers.NearestNeighbor;
import io.github.doublesemicolon.vrp.solvers.TwoOpt;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * CVRPLib の X set にソルバを掛け、BKS (既知最良解) との gap と時間を出す
 * (Python 版の benchmark.py と vrp bench-x)。
 *
 * <p>結果は Python と同じ vrp-bench/1 で保存し、Python の vrp check-bench で
 * 解が一致することを突き合わせる。
 * <pre>
 *   ./gradlew :bench:run --args="--save"
 *   ./gradlew :bench:run --args="--max-n 300 --dir ../python/data/cvrplib/X"
 * </pre>
 */
public final class XBench {
    private XBench() {}

    static final String X_DIR = "../python/data/cvrplib/X";   // Python 版の vrp fetch-x の置き場所

    /**
     * 掛けられるソルバ。X set は顧客 100 件以上なので、全探索や分枝限定法は入れない
     * (Python 版の --force に当たるものも無い)。順序は保存する結果の順序になる。
     */
    static final Map<String, Function<Instance, SolveResult>> SOLVERS = new LinkedHashMap<>();

    static {
        SOLVERS.put("nn", NearestNeighbor::solve);
        SOLVERS.put("nn+2opt", TwoOpt::solve);
        SOLVERS.put("nn+vnd", Vnd::solve);
    }

    /** bks は .sol の記載ではなく、目的関数で採点し直したコスト。 */
    record Case(Instance inst, long bks) {}

    // ---------------------------------------------------------------- 読み込み

    /**
     * dir の *.vrp を、対になる *.sol と一緒に顧客数の昇順 (同数なら名前順) で読む。
     *
     * <p>.sol が無い、BKS が実行可能でない、採点し直したコストが記載と違う、のどれも
     * エラーにする。黙って飛ばすと集計の母数が変わり、ローダか距離計算が壊れていても気づけない。
     */
    static List<Case> loadCases(Path dir, OptionalInt maxN) throws IOException {
        List<Path> paths;
        try (Stream<Path> s = Files.list(dir)) {
            paths = s.filter(p -> p.getFileName().toString().endsWith(".vrp")).sorted().toList();
        }
        if (paths.isEmpty()) {
            throw new IllegalArgumentException(dir + " に .vrp が無い (先に Python 版の vrp fetch-x で取得する)");
        }
        List<Case> cases = new ArrayList<>();
        for (Path vrp : paths) {
            Instance inst = Cvrplib.loadVrp(vrp);
            if (maxN.isPresent() && inst.n() > maxN.getAsInt()) {
                continue;
            }
            String name = vrp.getFileName().toString();
            Path solPath = vrp.resolveSibling(name.substring(0, name.length() - ".vrp".length()) + ".sol");
            if (!Files.exists(solPath)) {
                throw new IllegalArgumentException(name + " に対応する " + solPath.getFileName() + " が無い");
            }
            Cvrplib.Bks bks = Cvrplib.loadSol(solPath);
            List<String> errors = Objective.validate(inst, bks.solution());
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException(solPath.getFileName() + " の BKS が実行可能でない: "
                        + String.join("; ", errors.subList(0, Math.min(3, errors.size()))));
            }
            long rescored = Objective.solutionCost(inst, bks.solution());
            if (rescored != bks.writtenCost()) {
                throw new IllegalArgumentException(solPath.getFileName() + ": 採点し直したコスト " + rescored
                        + " が記載 " + bks.writtenCost() + " と違う");
            }
            cases.add(new Case(inst, rescored));
        }
        cases.sort(Comparator.comparingInt((Case c) -> c.inst().n()).thenComparing(c -> c.inst().name()));
        return cases;
    }

    // ---------------------------------------------------------------- 実行

    /** 各インスタンスに各ソルバを掛ける。onRow は 1 インスタンス終わるごとに呼ばれる (進捗表示用)。 */
    static List<BenchRow> run(List<Case> cases, List<String> solverNames, Consumer<BenchRow> onRow) {
        List<BenchRow> rows = new ArrayList<>();
        for (Case c : cases) {
            List<SolveResult> results = new ArrayList<>();
            for (String name : solverNames) {
                results.add(SOLVERS.get(name).apply(c.inst()));
            }
            BenchRow row = new BenchRow(c.inst().name(), c.inst().n(), c.bks(), results);
            rows.add(row);
            onRow.accept(row);
        }
        return rows;
    }

    // ---------------------------------------------------------------- コマンド

    record Args(Path dir, OptionalInt maxN, List<String> solvers, boolean save, Path outDir) {}

    static Args parseArgs(String[] argv) {
        Path dir = Path.of(X_DIR);
        OptionalInt maxN = OptionalInt.empty();
        List<String> solvers = List.copyOf(SOLVERS.keySet());
        boolean save = false;
        Path outDir = Path.of("results");
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            switch (a) {
                case "--dir" -> dir = Path.of(value(argv, ++i, a));
                case "--max-n" -> {
                    String v = value(argv, ++i, a);
                    try {
                        maxN = OptionalInt.of(Integer.parseInt(v));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--max-n は整数: " + v);
                    }
                }
                case "--solvers" -> {
                    List<String> names = new ArrayList<>();
                    while (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                        names.add(argv[++i]);
                    }
                    for (String name : names) {
                        if (!SOLVERS.containsKey(name)) {
                            throw new IllegalArgumentException("知らないソルバ: " + name + " (使えるのは "
                                    + String.join(", ", SOLVERS.keySet()) + ")");
                        }
                    }
                    if (names.isEmpty()) {
                        throw new IllegalArgumentException("--solvers の後にソルバ名が無い");
                    }
                    solvers = List.copyOf(names);
                }
                case "--save" -> save = true;
                case "--out-dir" -> outDir = Path.of(value(argv, ++i, a));
                default -> throw new IllegalArgumentException("知らない引数: " + a);
            }
        }
        return new Args(dir, maxN, solvers, save, outDir);
    }

    private static String value(String[] argv, int i, String flag) {
        if (i >= argv.length) {
            throw new IllegalArgumentException(flag + " の後に値が無い");
        }
        return argv[i];
    }

    /** Python 版の保存先の名前 (bench-x{-n上限}-{ソルバ}.json) と揃える。 */
    static Path outPath(Args a) {
        String limit = a.maxN().isPresent() ? "-n" + a.maxN().getAsInt() : "";
        return a.outDir().resolve("bench-x" + limit + "-" + String.join("_", a.solvers()) + ".json");
    }

    static void bench(Args a, PrintStream out) throws IOException {
        List<Case> cases = loadCases(a.dir(), a.maxN());
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("顧客 " + a.maxN().getAsInt() + " 件以下のインスタンスが " + a.dir() + " に無い");
        }
        out.printf("%s: %d インスタンス (顧客 %d〜%d 件)%n", a.dir(), cases.size(),
                cases.get(0).inst().n(), cases.get(cases.size() - 1).inst().n());

        int[] widths = new int[3 + a.solvers().size()];
        List<String> header = new ArrayList<>(List.of("インスタンス", "顧客", "BKS"));
        widths[0] = 16;
        widths[1] = 7;
        widths[2] = 9;
        for (int k = 0; k < a.solvers().size(); k++) {
            header.add(a.solvers().get(k) + " gap / 秒");
            widths[3 + k] = 20;
        }
        out.println("  " + row(header, widths));

        List<BenchRow> rows = run(cases, a.solvers(), r -> {
            List<String> cells = new ArrayList<>(List.of(r.instance(), String.valueOf(r.n()), String.valueOf(r.bks())));
            for (SolveResult res : r.results()) {
                String gap = res.feasible() ? String.format("%+.2f%%", res.gapTo(r.bks())) : "実行不可能";
                cells.add(String.format("%s / %.3f", gap, res.elapsedSec()));
            }
            out.println("  " + row(cells, widths));
            out.flush();
        });

        out.printf("集計 (%d インスタンス)%n", rows.size());
        int[] sw = {12, 13, 13, 11, 11, 0};
        out.println("  " + row(List.of("ソルバ", "gap 平均", "gap 最大", "時間 平均", "時間 合計", "実行不可能"), sw));
        for (int k = 0; k < a.solvers().size(); k++) {
            final int kk = k;
            // 実行不可能解は制約を破って安くなっている可能性があり、gap に混ぜると平均が良く見える
            List<Double> gaps = rows.stream().filter(r -> r.results().get(kk).feasible())
                    .map(r -> r.results().get(kk).gapTo(r.bks())).toList();
            double total = rows.stream().mapToDouble(r -> r.results().get(kk).elapsedSec()).sum();
            long infeasible = rows.size() - gaps.size();
            out.println("  " + row(List.of(
                    a.solvers().get(k),
                    gaps.isEmpty() ? "-" : String.format("%+.2f%%", gaps.stream().mapToDouble(d -> d).average().orElseThrow()),
                    gaps.isEmpty() ? "-" : String.format("%+.2f%%", gaps.stream().mapToDouble(d -> d).max().orElseThrow()),
                    String.format("%.3f 秒", total / rows.size()),
                    String.format("%.2f 秒", total),
                    String.valueOf(infeasible)), sw));
        }

        if (a.save()) {
            out.println("  保存: " + VrpJson.writeBench(outPath(a), rows));
        }
    }

    /** 表示幅 (全角は 2) で揃える。Python 版の _pad と同じ。最後の列は詰めない。 */
    static String row(List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            String c = cells.get(i);
            sb.append(c);
            if (i < cells.size() - 1) {
                sb.append(" ".repeat(Math.max(0, widths[i] - displayWidth(c))));
            }
        }
        return sb.toString();
    }

    static int displayWidth(String s) {
        return s.codePoints().map(cp -> cp < 0x1100 ? 1 : 2).sum();
    }

    public static void main(String[] argv) throws IOException {
        // Windows のコンソールでも日本語が化けないよう、出力を UTF-8 に固定する (エラー出力も)
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        try {
            bench(parseArgs(argv), out);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            System.exit(2);
        }
    }
}
