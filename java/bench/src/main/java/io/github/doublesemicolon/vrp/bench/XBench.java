package io.github.doublesemicolon.vrp.bench;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.alns.Alns;
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
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *   ./gradlew :bench:run --args="--max-n 300 --solvers nn+alns-uniform nn+alns-adaptive --seeds 5"
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

    /** 近傍リストの人数の候補 (#26 の測定)。 */
    static final List<Integer> NEAR_KS = List.of(5, 10, 20, 40);

    static {
        SOLVERS.put("nn", NearestNeighbor::solve);
        SOLVERS.put("nn+2opt", TwoOpt::solve);
        SOLVERS.put("nn+vnd", Vnd::solve);
        // VND を速くする仕組みの組み合わせ。-k{人数} は近傍リスト、-dlb は don't-look bits
        for (boolean dontLook : new boolean[] {false, true}) {
            List<OptionalInt> ks = new ArrayList<>(List.of(OptionalInt.empty()));
            NEAR_KS.forEach(k -> ks.add(OptionalInt.of(k)));
            for (OptionalInt k : ks) {
                if (k.isEmpty() && !dontLook) {
                    continue;   // nn+vnd そのもの
                }
                String name = "nn+vnd" + (k.isPresent() ? "-k" + k.getAsInt() : "") + (dontLook ? "-dlb" : "");
                Vnd.Config config = new Vnd.Config(k, dontLook);
                SOLVERS.put(name, inst -> Vnd.solve(inst, config, name));
            }
        }
    }

    /** ALNS の反復回数 (仮の値。時間制限は #29)。 */
    static final int ALNS_ITERATIONS = 1000;

    /**
     * 乱数のシードを取るソルバ。--solvers では名前の後に -s{シード} を付ける (付けなければシード 0)。
     * --seeds N を付けると、ここにある名前を -s0 〜 -s{N-1} の N 個に広げる。
     */
    static final Map<String, BiFunction<Instance, Long, SolveResult>> SEEDED = new LinkedHashMap<>();

    static {
        SEEDED.put("nn+alns", (inst, seed) -> Alns.solve(inst, Alns.Config.of(inst, seed, ALNS_ITERATIONS)));
        // 適応的な重みと VND の効果を分けて測るための組み合わせ (#28)。uniform は演算子を一様に選ぶ
        for (boolean vnd : new boolean[] {false, true}) {
            for (boolean adaptive : new boolean[] {false, true}) {
                String name = "nn+alns-" + (adaptive ? "adaptive" : "uniform") + (vnd ? "-vnd" : "");
                SEEDED.put(name, (inst, seed) -> Alns.solve(inst,
                        Alns.Config.of(inst, seed, ALNS_ITERATIONS).withAdaptive(adaptive).withVnd(vnd), name));
            }
        }
    }

    private static final Pattern WITH_SEED = Pattern.compile("(.+)-s(0|[1-9][0-9]{0,8})");

    /** 名前からソルバを引く。知らない名前なら空。結果のソルバ名は、シードを付けた名前 (付けなければ元の名前)。 */
    static Optional<Function<Instance, SolveResult>> solver(String name) {
        if (SOLVERS.containsKey(name)) {
            return Optional.of(SOLVERS.get(name));
        }
        String base = name;
        long seed = 0;
        Matcher m = WITH_SEED.matcher(name);
        if (!SEEDED.containsKey(name) && m.matches()) {
            base = m.group(1);
            seed = Long.parseLong(m.group(2));
        }
        BiFunction<Instance, Long, SolveResult> f = SEEDED.get(base);
        if (f == null) {
            return Optional.empty();
        }
        long s = seed;
        return Optional.of(inst -> renamed(f.apply(inst, s), name));
    }

    private static SolveResult renamed(SolveResult r, String name) {
        return new SolveResult(r.instance(), name, r.solution(), r.cost(), r.feasible(), r.elapsedSec(), r.stats(),
                r.history());
    }

    /** --seeds で広げた後のソルバ名。seeds が 0 なら広げない。 */
    static List<String> expand(List<String> names, int seeds) {
        List<String> out = new ArrayList<>();
        for (String name : names) {
            if (seeds > 0 && SEEDED.containsKey(name)) {
                for (int s = 0; s < seeds; s++) {
                    out.add(name + "-s" + s);
                }
            } else {
                out.add(name);
            }
        }
        return out;
    }

    /** --solvers を付けないときに掛けるソルバ。 */
    static final List<String> DEFAULT_SOLVERS = List.of("nn", "nn+2opt", "nn+vnd", "nn+vnd-k20");

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
                results.add(solver(name).orElseThrow().apply(c.inst()));
            }
            BenchRow row = new BenchRow(c.inst().name(), c.inst().n(), c.bks(), results);
            rows.add(row);
            onRow.accept(row);
        }
        return rows;
    }

    // ---------------------------------------------------------------- コマンド

    /** solvers は指定したとおりの名前。走らせるのは seeds で広げた {@link #solversToRun()}。 */
    record Args(Path dir, OptionalInt maxN, List<String> solvers, int seeds, boolean save, Path outDir) {
        List<String> solversToRun() {
            return expand(solvers, seeds);
        }
    }

    static Args parseArgs(String[] argv) {
        Path dir = Path.of(X_DIR);
        OptionalInt maxN = OptionalInt.empty();
        List<String> solvers = DEFAULT_SOLVERS;
        int seeds = 0;
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
                        if (solver(name).isEmpty()) {
                            throw new IllegalArgumentException("知らないソルバ: " + name + " (使えるのは "
                                    + String.join(", ", SOLVERS.keySet()) + "、シードを取る "
                                    + String.join(", ", SEEDED.keySet()) + " (-s{シード} を付けられる))");
                        }
                    }
                    if (names.isEmpty()) {
                        throw new IllegalArgumentException("--solvers の後にソルバ名が無い");
                    }
                    solvers = List.copyOf(names);
                }
                case "--seeds" -> {
                    String v = value(argv, ++i, a);
                    try {
                        seeds = Integer.parseInt(v);
                    } catch (NumberFormatException e) {
                        seeds = -1;
                    }
                    if (seeds < 1) {
                        throw new IllegalArgumentException("--seeds は 1 以上の整数: " + v);
                    }
                }
                case "--save" -> save = true;
                case "--out-dir" -> outDir = Path.of(value(argv, ++i, a));
                default -> throw new IllegalArgumentException("知らない引数: " + a);
            }
        }
        return new Args(dir, maxN, solvers, seeds, save, outDir);
    }

    private static String value(String[] argv, int i, String flag) {
        if (i >= argv.length) {
            throw new IllegalArgumentException(flag + " の後に値が無い");
        }
        return argv[i];
    }

    /** Python 版の保存先の名前 (bench-x{-n上限}-{ソルバ}.json) と揃える。--seeds を付けたら末尾に -seeds{N}。 */
    static Path outPath(Args a) {
        String limit = a.maxN().isPresent() ? "-n" + a.maxN().getAsInt() : "";
        String seeds = a.seeds() > 0 ? "-seeds" + a.seeds() : "";
        return a.outDir().resolve("bench-x" + limit + "-" + String.join("_", a.solvers()) + seeds + ".json");
    }

    static void bench(Args a, PrintStream out) throws IOException {
        List<Case> cases = loadCases(a.dir(), a.maxN());
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("顧客 " + a.maxN().getAsInt() + " 件以下のインスタンスが " + a.dir() + " に無い");
        }
        out.printf("%s: %d インスタンス (顧客 %d〜%d 件)%n", a.dir(), cases.size(),
                cases.get(0).inst().n(), cases.get(cases.size() - 1).inst().n());

        List<String> solvers = a.solversToRun();
        int[] widths = new int[3 + solvers.size()];
        List<String> header = new ArrayList<>(List.of("インスタンス", "顧客", "BKS"));
        widths[0] = 16;
        widths[1] = 7;
        widths[2] = 9;
        for (int k = 0; k < solvers.size(); k++) {
            header.add(solvers.get(k) + " gap / 秒");
            widths[3 + k] = 20;
        }
        out.println("  " + row(header, widths));

        List<BenchRow> rows = run(cases, solvers, r -> {
            List<String> cells = new ArrayList<>(List.of(r.instance(), String.valueOf(r.n()), String.valueOf(r.bks())));
            for (SolveResult res : r.results()) {
                String gap = res.feasible() ? String.format("%+.2f%%", res.gapTo(r.bks())) : "実行不可能";
                cells.add(String.format("%s / %.3f", gap, res.elapsedSec()));
            }
            out.println("  " + row(cells, widths));
            out.flush();
        });

        out.printf("集計 (%d インスタンス)%n", rows.size());
        int nameWidth = solvers.stream().mapToInt(String::length).max().orElse(0) + 2;
        int[] sw = {Math.max(12, nameWidth), 13, 13, 11, 11, 0};
        out.println("  " + row(List.of("ソルバ", "gap 平均", "gap 最大", "時間 平均", "時間 合計", "実行不可能"), sw));
        for (int k = 0; k < solvers.size(); k++) {
            final int kk = k;
            // 実行不可能解は制約を破って安くなっている可能性があり、gap に混ぜると平均が良く見える
            List<Double> gaps = rows.stream().filter(r -> r.results().get(kk).feasible())
                    .map(r -> r.results().get(kk).gapTo(r.bks())).toList();
            double total = rows.stream().mapToDouble(r -> r.results().get(kk).elapsedSec()).sum();
            long infeasible = rows.size() - gaps.size();
            out.println("  " + row(List.of(
                    solvers.get(k),
                    gaps.isEmpty() ? "-" : String.format("%+.2f%%", gaps.stream().mapToDouble(d -> d).average().orElseThrow()),
                    gaps.isEmpty() ? "-" : String.format("%+.2f%%", gaps.stream().mapToDouble(d -> d).max().orElseThrow()),
                    String.format("%.3f 秒", total / rows.size()),
                    String.format("%.2f 秒", total),
                    String.valueOf(infeasible)), sw));
        }

        if (a.seeds() > 1) {
            printSeedSummary(a, rows, out);
        }
        if (a.save()) {
            out.println("  保存: " + VrpJson.writeBench(outPath(a), rows));
        }
    }

    /**
     * シードを取るソルバを、シードをまとめて集計する。gap 平均は「シードごとの gap 平均」の平均で、
     * 最小・最大はシードごとの gap 平均のばらつき。実行不可能な解は数を表示し、gap からは外す。
     */
    static void printSeedSummary(Args a, List<BenchRow> rows, PrintStream out) {
        List<String> solvers = a.solversToRun();
        List<String> bases = a.solvers().stream().filter(SEEDED::containsKey).toList();
        out.printf("シードをまとめた集計 (%d シード)%n", a.seeds());
        int nameWidth = bases.stream().mapToInt(String::length).max().orElse(0) + 2;
        int[] sw = {Math.max(12, nameWidth), 13, 23, 11, 0};
        out.println("  " + row(List.of("ソルバ", "gap 平均", "シード間 (最小〜最大)", "時間 平均", "実行不可能"), sw));
        for (String base : bases) {
            List<Double> means = new ArrayList<>();
            double time = 0;
            long infeasible = 0;
            for (int s = 0; s < a.seeds(); s++) {
                int k = solvers.indexOf(base + "-s" + s);
                List<Double> gaps = new ArrayList<>();
                for (BenchRow r : rows) {
                    SolveResult res = r.results().get(k);
                    time += res.elapsedSec();
                    if (res.feasible()) {
                        gaps.add(res.gapTo(r.bks()));
                    } else {
                        infeasible++;
                    }
                }
                gaps.stream().mapToDouble(d -> d).average().ifPresent(means::add);
            }
            out.println("  " + row(List.of(
                    base,
                    means.isEmpty() ? "-" : String.format("%+.2f%%", means.stream().mapToDouble(d -> d).average().orElseThrow()),
                    means.isEmpty() ? "-" : String.format("%+.2f%% 〜 %+.2f%%",
                            means.stream().mapToDouble(d -> d).min().orElseThrow(),
                            means.stream().mapToDouble(d -> d).max().orElseThrow()),
                    String.format("%.3f 秒", time / (rows.size() * a.seeds())),
                    String.valueOf(infeasible)), sw));
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
