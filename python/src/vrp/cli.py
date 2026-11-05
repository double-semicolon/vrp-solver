"""
コマンドラインからの実行口。ソルバを走らせて表示・保存・作図する。
アルゴリズムはここに書かない。

  vrp demo                                      toy5/toy8 で探索量を比べる
  vrp solve   --instance toy8 --solver bnb      1つ解く
  vrp compare --instance random -n 50 --plot    複数ソルバを比べて JSON と PNG を出す
  vrp bench   -n 50 --seeds 20                  多数のシードで改善幅の平均を取る
  vrp fetch-x                                   CVRPLib の X set を data/cvrplib/X に取得する
  vrp bench-x --max-n 300 --save                X set で BKS との gap を測る
  vrp check-bench a.json b.json                 2 つのベンチマーク結果 (Python 版と Java 版など) を突き合わせる
  vrp plot    results/xxx.json                  保存済みの結果から作図し直す

matplotlib は作図するときにだけ import する。入っていない環境でも
探索と JSON 出力は動く。
"""

from __future__ import annotations

import argparse
import math
import statistics
import sys
import unicodedata
from pathlib import Path

from .benchmark import compare_benches, load_bench, load_cases, run_benchmark, save_bench, summarize
from .instances import TOYS, random_instance
from .model import Instance, Solution
from .objective import route_cost, route_load, solution_cost, validate
from .result import SolveResult
from .serialize import load_run, save_run
from .solvers import SOLVERS, solve_branch_and_bound, solve_brute_force

TOY_BY_NAME = {i.name: i for i in TOYS}
X_DIR = "data/cvrplib/X"   # fetch-x の置き場所。リポジトリには含めない


# ---------------------------------------------------------------- 表示


def _pad(s: str, width: int) -> str:
    """全角を2桁として数えて右を埋める。f-string の :<n は文字数で数えるので日本語だと崩れる。"""
    w = sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in s)
    return s + " " * max(0, width - w)


def show(inst: Instance, sol: Solution, label: str) -> None:
    print(f"[{label}] 総距離 = {solution_cost(inst, sol)}")
    for i, r in enumerate(sol):
        path = " -> ".join(["0"] + [str(c) for c in r] + ["0"])
        print(f"  車{i}: {path}"
              f"  (積載 {route_load(inst, r)}/{inst.capacity}, 距離 {route_cost(inst, r)})")
    errs = validate(inst, sol)
    print(f"  実行可能: {'OK' if not errs else 'NG -> ' + '; '.join(errs)}")


def describe(inst: Instance) -> None:
    K = inst.num_vehicles
    vehicles = "上限なし" if K is None else f"{K} 台"
    print(f"インスタンス {inst.name}: 顧客 {inst.n} 件, 容量 {inst.capacity}, "
          f"車両 {vehicles}, 総需要 {sum(inst.demands)}")
    print(f"  必要最小台数 (下界) = {math.ceil(sum(inst.demands) / inst.capacity)}")


def _print_gaps(results: list[SolveResult]) -> None:
    """実行可能な解のうち最良を 0% として、各ソルバがどれだけ離れているかを出す。"""
    feasible = [r for r in results if r.feasible]
    if not feasible:
        return
    best = min(r.cost for r in feasible)
    print(f"  最良 {best} を 0% としたときの差:")
    for r in results:
        # 実行不可能解は制約を破って安くなっている可能性があり、比べる意味が無い
        mark = f"{r.gap_to(best):+.2f}%" if r.feasible else "実行不可能"
        print(f"    {_pad(r.solver, 12)} {mark}")


# ---------------------------------------------------------------- インスタンスの決定


def _build_instance(args: argparse.Namespace, seed: int | None = None) -> Instance:
    if args.instance == "random":
        return random_instance(args.n, seed=args.seed if seed is None else seed, layout=args.layout)
    if args.instance in TOY_BY_NAME:
        return TOY_BY_NAME[args.instance]
    raise SystemExit(f"未知のインスタンス: {args.instance} "
                     f"(選択肢: {', '.join(TOY_BY_NAME)}, random)")


def _pick_solvers(n: int, requested: list[str] | None, force: bool) -> list[str]:
    """
    走らせるソルバを決める。省略時はサイズ的に回せるもの全部。

    厳密解法を大きすぎるインスタンスに掛けると、止まったのか計算中なのか
    分からないまま待つことになるので、走らせる前に断る。
    """
    if not requested:
        names = [name for name, s in SOLVERS.items() if n <= s.max_customers]
        if not names:
            raise SystemExit(f"顧客 {n} 件に掛けられるソルバが無い")
        return names
    too_big = [name for name in requested if n > SOLVERS[name].max_customers]
    if too_big and not force:
        detail = ", ".join(f"{name} (目安 n<={SOLVERS[name].max_customers})" for name in too_big)
        raise SystemExit(
            f"顧客 {n} 件は次のソルバには大きすぎる: {detail}\n"
            f"本当に走らせるなら --force を付ける。"
        )
    return requested


def _run(inst: Instance, names: list[str], verbose: bool = True) -> list[SolveResult]:
    results = []
    for name in names:
        res = SOLVERS[name].run(inst)
        results.append(res)
        if verbose:
            flag = "" if res.feasible else "  [実行不可能]"
            print(f"  {_pad(name, 12)} 距離 {res.cost:>7}  {res.elapsed_sec:7.3f} 秒"
                  f"  {res.stats}{flag}")
    return results


def _output(inst: Instance, results: list[SolveResult], args: argparse.Namespace) -> None:
    stem = f"{inst.name}-{'_'.join(r.solver for r in results)}"
    out_dir = Path(args.out_dir)

    if args.save:
        print(f"  保存: {save_run(out_dir / (stem + '.json'), inst, results)}")
    if args.plot:
        from . import viz          # matplotlib が要るのでここまで import を遅らせる
        fig = viz.plot_comparison(inst, results)
        print(f"  作図: {viz.save(fig, out_dir / (stem + '.png'))}")


# ---------------------------------------------------------------- サブコマンド


def cmd_demo(args: argparse.Namespace) -> None:
    """toy5/toy8 で全探索・枝刈り・構築法・局所探索を並べる。Step 0〜1 の要約。"""
    for inst in TOYS:
        print("=" * 68)
        describe(inst)

        ref = solve_brute_force(inst)
        ref_nodes = ref.stats["nodes"]
        print(f"  {_pad('[全探索]', 20)} ノード {ref_nodes:>6}"
              f"  解の総数 {ref.stats['solutions']:>5} (重複なし)  {ref.elapsed_sec:.3f} 秒")

        for label, seed in (("[枝刈り]", False), ("[枝刈り+最近傍上界]", True)):
            res = solve_branch_and_bound(inst, seed_with_nn=seed)
            if res.cost != ref.cost:
                raise SystemExit(f"枝刈りが最適解を削っている: {res.cost} != {ref.cost}")
            cut = (1 - res.stats["nodes"] / ref_nodes) * 100
            ub = res.stats["initial_upper_bound"]
            ub_txt = "" if ub is None else f"  初期上界 {ub}"
            print(f"  {_pad(label, 20)} ノード {res.stats['nodes']:>6} ({cut:5.1f}% 削減)"
                  f"  {res.elapsed_sec:.3f} 秒{ub_txt}")

        show(inst, ref.solution, "最適解 (全探索)")
        heuristics = _run(inst, ["nn", "nn+2opt"], verbose=False)
        for res in heuristics:
            show(inst, res.solution, res.solver)
        _print_gaps([ref, *heuristics])

    # 壊れた解を validate に通して、チェッカーが機能することを確認する
    print("=" * 68)
    print("チェッカーの動作確認 (わざと不正な解を渡す)")
    toy5 = TOY_BY_NAME["toy5"]
    show(toy5, [[1, 2, 3], [4]], "顧客5が抜け・容量超過")
    show(toy5, [[1, 2], [3], [4, 5]], "車両3台使用 (上限2台)")


def cmd_solve(args: argparse.Namespace) -> None:
    inst = _build_instance(args)
    names = _pick_solvers(inst.n, [args.solver], args.force)
    describe(inst)
    results = _run(inst, names)
    show(inst, results[0].solution, results[0].solver)
    _output(inst, results, args)


def cmd_compare(args: argparse.Namespace) -> None:
    inst = _build_instance(args)
    names = _pick_solvers(inst.n, args.solvers, args.force)
    describe(inst)
    results = _run(inst, names)
    _print_gaps(results)
    _output(inst, results, args)


def cmd_bench(args: argparse.Namespace) -> None:
    """
    シードを変えた多数のランダムインスタンスで、最良からの差の平均を取る。

    1つのインスタンスでの差は配置の当たり外れに左右される。README に載せる
    「改善幅」は、このコマンドの出力のように多数のシードで均したものにする。
    """
    names = _pick_solvers(args.n, args.solvers, args.force)
    gaps: dict[str, list[float]] = {name: [] for name in names}
    times: dict[str, list[float]] = {name: [] for name in names}
    infeasible: dict[str, int] = {name: 0 for name in names}

    for seed in range(args.seeds):
        inst = random_instance(args.n, seed=seed, layout=args.layout)
        results = _run(inst, names, verbose=False)
        feasible = [r.cost for r in results if r.feasible]
        best = min(feasible) if feasible else None
        for name, r in zip(names, results):
            times[name].append(r.elapsed_sec)
            if r.feasible and best is not None:
                gaps[name].append(r.gap_to(best))
            else:
                infeasible[name] += 1

    print(f"顧客 {args.n} 件 / {args.layout} / シード 0〜{args.seeds - 1} の {args.seeds} 個")
    widths = [12, 13, 13, 11]
    header = ["ソルバ", "最良比 平均", "最良比 最大", "時間 平均", "実行不可能"]
    print("  " + "".join(_pad(h, w) for h, w in zip(header, widths)) + header[-1])
    for name in names:
        g = gaps[name]
        row = [
            name,
            f"{statistics.mean(g):+.2f}%" if g else "-",
            f"{max(g):+.2f}%" if g else "-",
            f"{statistics.mean(times[name]):.3f} 秒",
        ]
        print("  " + "".join(_pad(c, w) for c, w in zip(row, widths)) + str(infeasible[name]))


def cmd_fetch_x(args: argparse.Namespace) -> None:
    from .instances.fetch import fetch_xset   # py7zr が要るのでここまで import を遅らせる

    written = fetch_xset(args.dest)
    n_vrp = sum(1 for p in written if p.suffix == ".vrp")
    print(f"{args.dest} に {n_vrp} インスタンスと {len(written) - n_vrp} 個の BKS を置いた")


def cmd_bench_x(args: argparse.Namespace) -> None:
    """
    X set の各インスタンスにソルバを掛け、BKS との gap と時間を出す。

    gap の基準は BKS で、その場の最良ではない。ソルバ同士の比較ではなく、
    「既知の最良からどれだけ離れているか」を測る。
    """
    cases = load_cases(args.dir, args.max_n)
    if not cases:
        raise SystemExit(f"顧客 {args.max_n} 件以下のインスタンスが {args.dir} に無い")
    names = _pick_solvers(max(c.inst.n for c in cases), args.solvers, args.force)
    print(f"{args.dir}: {len(cases)} インスタンス (顧客 {cases[0].inst.n}〜{cases[-1].inst.n} 件)")

    widths = [16, 7, 9] + [20] * len(names)
    header = ["インスタンス", "顧客", "BKS"] + [f"{n} gap / 秒" for n in names]
    print("  " + "".join(_pad(h, w) for h, w in zip(header, widths)))

    def show_row(row) -> None:
        cells = [row.instance, str(row.n), str(row.bks)]
        for res in row.results:
            gap = f"{row.gap(res):+.2f}%" if res.feasible else "実行不可能"
            cells.append(f"{gap} / {res.elapsed_sec:.2f}")
        print("  " + "".join(_pad(c, w) for c, w in zip(cells, widths)), flush=True)

    rows = run_benchmark(cases, names, on_row=show_row)

    print(f"集計 ({len(rows)} インスタンス)")
    widths = [12, 13, 13, 11, 11]
    header = ["ソルバ", "gap 平均", "gap 最大", "時間 平均", "時間 合計", "実行不可能"]
    print("  " + "".join(_pad(h, w) for h, w in zip(header, widths)) + header[-1])
    for s in summarize(rows):
        cells = [
            s.solver,
            "-" if s.mean_gap is None else f"{s.mean_gap:+.2f}%",
            "-" if s.max_gap is None else f"{s.max_gap:+.2f}%",
            f"{s.mean_sec:.2f} 秒",
            f"{s.total_sec:.1f} 秒",
        ]
        print("  " + "".join(_pad(c, w) for c, w in zip(cells, widths)) + str(s.infeasible))

    if args.save:
        limit = "" if args.max_n is None else f"-n{args.max_n}"
        path = Path(args.out_dir) / f"bench-x{limit}-{'_'.join(names)}.json"
        print(f"  保存: {save_bench(path, rows)}")


def cmd_check_bench(args: argparse.Namespace) -> None:
    """
    2 つのベンチマーク結果 (vrp-bench/1) の解が一致するかを確かめ、時間を並べる。

    Step 3 の完了判定に使う (左に Python 版、右に Java 版の結果を渡す)。
    食い違えば終了コード 1 にする。時間は比べるだけで、一致の判定には使わない。
    """
    left, right = load_bench(args.left), load_bench(args.right)
    print(f"左: {args.left} ({len(left)} インスタンス)")
    print(f"右: {args.right} ({len(right)} インスタンス)")

    by_name = {r.instance: r for r in right}
    common = [(lr, by_name[lr.instance]) for lr in left if lr.instance in by_name]
    solvers = [res.solver for res in left[0].results] if left else []
    widths = [12, 13, 13]
    print("時間 (両方にあるインスタンスの合計)")
    print("  " + "".join(_pad(h, w) for h, w in zip(["ソルバ", "左", "右"], widths)) + "右 / 左")
    for name in solvers:
        pairs = [(next((x for x in lr.results if x.solver == name), None),
                  next((x for x in rr.results if x.solver == name), None)) for lr, rr in common]
        pairs = [(a, b) for a, b in pairs if a is not None and b is not None]
        if not pairs:
            continue
        ta = sum(a.elapsed_sec for a, _ in pairs)
        tb = sum(b.elapsed_sec for _, b in pairs)
        ratio = f"{tb / ta:.2f} 倍" if ta > 0 else "-"
        cells = [name, f"{ta:.2f} 秒", f"{tb:.2f} 秒"]
        print("  " + "".join(_pad(c, w) for c, w in zip(cells, widths)) + ratio)

    diffs = compare_benches(left, right)
    n_results = sum(len(r.results) for r in left)
    if not diffs:
        print(f"一致: {len(left)} インスタンス、{n_results} 件の結果で、経過時間以外が全て同じ")
        return
    print(f"食い違い: {len(diffs)} 件")
    for d in diffs:
        print(f"  {d}")
    raise SystemExit(1)


def cmd_plot(args: argparse.Namespace) -> None:
    """保存済みの結果から作図し直す。重い探索をやり直さずに図だけ差し替えられる。"""
    from . import viz

    inst, results = load_run(args.run)
    describe(inst)
    out = Path(args.out) if args.out else Path(args.run).with_suffix(".png")
    print(f"  作図: {viz.save(viz.plot_comparison(inst, results), out)}")


# ---------------------------------------------------------------- 引数


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="vrp", description="CVRP ソルバ")
    sub = p.add_subparsers(dest="command")

    def add_random_args(sp):
        sp.add_argument("-n", type=int, default=50, help="random のときの顧客数 (既定: 50)")
        sp.add_argument("--layout", default="uniform", choices=["uniform", "clustered"])
        sp.add_argument("--force", action="store_true",
                        help="大きすぎるインスタンスでも厳密解法を走らせる")

    def add_instance_args(sp):
        sp.add_argument("--instance", default="toy8",
                        help=", ".join(TOY_BY_NAME) + ", random (既定: toy8)")
        sp.add_argument("--seed", type=int, default=1, help="random のときの乱数シード")
        add_random_args(sp)

    def add_output_args(sp):
        sp.add_argument("--save", action="store_true", help="結果を JSON で保存する")
        sp.add_argument("--plot", action="store_true", help="PNG を出力する (matplotlib が要る)")
        sp.add_argument("--out-dir", default="results", help="出力先 (既定: results)")

    sub.add_parser("demo", help="toy5/toy8 で各ソルバを並べる")

    sp = sub.add_parser("solve", help="1つのソルバで解く")
    sp.add_argument("--solver", default="nn", choices=list(SOLVERS))
    add_instance_args(sp)
    add_output_args(sp)

    sp = sub.add_parser("compare", help="複数のソルバを同じインスタンスで比べる")
    sp.add_argument("--solvers", nargs="+", choices=list(SOLVERS),
                    help="省略時はサイズ的に回せるもの全部")
    add_instance_args(sp)
    add_output_args(sp)

    sp = sub.add_parser("bench", help="多数のシードのランダムインスタンスで平均を取る")
    sp.add_argument("--solvers", nargs="+", choices=list(SOLVERS),
                    help="省略時はサイズ的に回せるもの全部")
    sp.add_argument("--seeds", type=int, default=20, help="シードの個数 (既定: 20)")
    add_random_args(sp)

    sp = sub.add_parser("fetch-x", help="CVRPLib の X set を取得する (py7zr が要る)")
    sp.add_argument("--dest", default=X_DIR, help=f"置き場所 (既定: {X_DIR})")

    sp = sub.add_parser("bench-x", help="X set で BKS との gap を測る")
    sp.add_argument("--dir", default=X_DIR, help=f".vrp と .sol の置き場所 (既定: {X_DIR})")
    sp.add_argument("--max-n", type=int, help="この顧客数以下のインスタンスだけ使う")
    sp.add_argument("--solvers", nargs="+", choices=list(SOLVERS), default=["nn", "nn+2opt"],
                    help="既定: nn nn+2opt")
    sp.add_argument("--force", action="store_true",
                    help="大きすぎるインスタンスでも厳密解法を走らせる")
    add_output_args(sp)

    sp = sub.add_parser("check-bench", help="2 つのベンチマーク結果の解が一致するか確かめる")
    sp.add_argument("left", help="bench-x の結果 (例: Python 版)")
    sp.add_argument("right", help="bench-x の結果 (例: Java 版)")

    sp = sub.add_parser("plot", help="保存済みの JSON から作図し直す")
    sp.add_argument("run", help="save_run が書いた JSON")
    sp.add_argument("--out", help="出力先 PNG (既定: 入力と同じ場所)")

    return p


def main(argv: list[str] | None = None) -> None:
    # Windows のコンソールは既定が cp932/cp1252 なので、日本語の出力が
    # UnicodeEncodeError で落ちる。表示のためだけの都合なのでここで吸収する。
    # SystemExit のメッセージは stderr に出るので、そちらも忘れずに。
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")

    args = build_parser().parse_args(argv)
    handlers = {"demo": cmd_demo, "solve": cmd_solve, "compare": cmd_compare,
                "bench": cmd_bench, "fetch-x": cmd_fetch_x, "bench-x": cmd_bench_x,
                "check-bench": cmd_check_bench, "plot": cmd_plot}
    handlers[args.command or "demo"](args)


if __name__ == "__main__":
    main()
