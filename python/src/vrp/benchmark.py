"""
CVRPLib のインスタンス群にソルバを掛け、BKS との gap と時間を集計する。

ランダムインスタンスの bench (cli.cmd_bench) は「その場の最良」との差しか測れない。
こちらは外部の基準 (既知最良解) との差なので、ソルバの絶対的な位置が分かり、
Step 3 では Java 版の結果をこの形式で突き合わせる。

BKS のコストは .sol の Cost 行を信用せず、目的関数で採点し直した値を使う。
食い違えばローダか距離計算が壊れているので、走らせる前に止める。

保存する JSON には gap を書かない。cost と bks から一意に決まる値を保存すると、
「両者と食い違った gap」という不正な状態を作れてしまう (serialize の D と同じ理由)。

Java 版 (java/bench の XBench) も同じ形式で書く。読むときは serialize の load_run と同じ
規則で厳格に読み、compare_benches で 2 つのファイルの解が一致するかを突き合わせる。
"""

from __future__ import annotations

import statistics
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from .instances.cvrplib import load_sol, load_vrp
from .model import Instance
from .objective import solution_cost, validate
from .result import SolveResult
from .serialize import _int, _keys, _list, _str, dumps, loads, result_from_dict, result_to_dict
from .solvers import SOLVERS

FORMAT = "vrp-bench/1"


@dataclass
class BenchCase:
    inst: Instance
    bks: int            # 採点し直した BKS のコスト


@dataclass
class BenchRow:
    instance: str
    n: int
    bks: int
    results: list[SolveResult]

    def gap(self, res: SolveResult) -> float:
        return res.gap_to(self.bks)


@dataclass
class SolverSummary:
    solver: str
    mean_gap: float | None      # 実行可能な結果だけで取る。1つも無ければ None
    max_gap: float | None
    mean_sec: float
    total_sec: float
    infeasible: int


# ---------------------------------------------------------------- 読み込み


def load_cases(directory: str | Path, max_n: int | None = None) -> list[BenchCase]:
    """
    directory の *.vrp を、対になる *.sol と一緒に顧客数の昇順で読む。

    .sol が無いインスタンスは gap を出せないので、黙って飛ばさずエラーにする。
    飛ばすと、集計の母数が知らないうちに変わる。
    """
    d = Path(directory)
    paths = sorted(d.glob("*.vrp"))
    if not paths:
        raise ValueError(f"{d} に .vrp が無い (先に vrp fetch-x で取得する)")

    cases = []
    for vrp in paths:
        inst = load_vrp(vrp)
        if max_n is not None and inst.n > max_n:
            continue
        sol_path = vrp.with_suffix(".sol")
        if not sol_path.exists():
            raise ValueError(f"{vrp.name} に対応する {sol_path.name} が無い")
        routes, written = load_sol(sol_path)
        errors = validate(inst, routes)
        if errors:
            raise ValueError(f"{sol_path.name} の BKS が実行可能でない: {'; '.join(errors[:3])}")
        rescored = solution_cost(inst, routes)
        if rescored != written:
            raise ValueError(f"{sol_path.name}: 採点し直したコスト {rescored} が記載 {written} と違う")
        cases.append(BenchCase(inst, rescored))
    cases.sort(key=lambda c: (c.inst.n, c.inst.name))
    return cases


# ---------------------------------------------------------------- 実行と集計


def run_benchmark(
    cases: list[BenchCase],
    solver_names: list[str],
    on_row: Callable[[BenchRow], None] | None = None,
) -> list[BenchRow]:
    """各インスタンスに各ソルバを掛ける。on_row は1インスタンス終わるごとに呼ばれる (進捗表示用)。"""
    rows = []
    for case in cases:
        results = [SOLVERS[name].run(case.inst) for name in solver_names]
        row = BenchRow(case.inst.name, case.inst.n, case.bks, results)
        rows.append(row)
        if on_row is not None:
            on_row(row)
    return rows


def summarize(rows: list[BenchRow]) -> list[SolverSummary]:
    if not rows:
        return []
    out = []
    for k, solver in enumerate(r.solver for r in rows[0].results):
        results = [(row, row.results[k]) for row in rows]
        # 実行不可能解は制約を破って安くなっている可能性があり、gap に混ぜると平均が良く見える
        gaps = [row.gap(res) for row, res in results if res.feasible]
        secs = [res.elapsed_sec for _, res in results]
        out.append(SolverSummary(
            solver=solver,
            mean_gap=statistics.mean(gaps) if gaps else None,
            max_gap=max(gaps) if gaps else None,
            mean_sec=statistics.mean(secs),
            total_sec=sum(secs),
            infeasible=sum(1 for _, res in results if not res.feasible),
        ))
    return out


# ---------------------------------------------------------------- 保存


def save_bench(path: str | Path, rows: list[BenchRow]) -> Path:
    # 書き始めてから失敗すると壊れたファイルが残るので、先に文字列にする
    text = dumps({
        "format": FORMAT,
        "rows": [
            {"instance": r.instance, "n": r.n, "bks": r.bks,
             "results": [result_to_dict(res) for res in r.results]}
            for r in rows
        ],
    })
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    # 改行は OS によらず LF (save_run と同じ。Java 版の出力と揃える)
    p.write_text(text, encoding="utf-8", newline="\n")
    return p


def load_bench(path: str | Path) -> list[BenchRow]:
    """
    load_run と同じ規則で厳格に読む (知らないキー・欠けたキー・"100" のような文字列の数はエラー)。
    Java 版が書いたファイルを読む入口なので、ここが緩いと突き合わせの意味が薄れる。
    """
    path = Path(path)
    try:
        d = loads(path.read_text(encoding="utf-8"))
        if not isinstance(d, dict) or d.get("format") != FORMAT:
            got = d.get("format") if isinstance(d, dict) else d
            raise ValueError(f"未知の形式: {got!r} (期待: {FORMAT!r})")
        _keys(d, "bench", "format", "rows")
        rows = []
        for i, r in enumerate(_list(d["rows"], "rows")):
            at = f"rows[{i}]"
            _keys(r, at, "instance", "n", "bks", "results")
            rows.append(BenchRow(
                _str(r["instance"], f"{at}.instance"),
                _int(r["n"], f"{at}.n"),
                _int(r["bks"], f"{at}.bks", 64),
                [result_from_dict(res, f"{at}.results[{k}]")
                 for k, res in enumerate(_list(r["results"], f"{at}.results"))],
            ))
        return rows
    except ValueError as e:   # json.JSONDecodeError も ValueError の派生
        raise ValueError(f"{path.name}: {e}") from None


# ---------------------------------------------------------------- 突き合わせ


def compare_benches(left: list[BenchRow], right: list[BenchRow]) -> list[str]:
    """
    2 つのベンチマーク結果を、経過時間以外の全て (解・コスト・実行可能か・stats・
    history のコストと反復回数) で突き合わせ、食い違いを 1 行ずつ返す。一致すれば空。

    (インスタンス, ソルバ) ごとに最初の食い違いだけを出す。解が違えば、後のコストや
    history も違って当然なので、並べても原因は分からない。最初に食い違うルートが手がかり。
    """
    out = []
    by_name = {r.instance: r for r in right}
    left_names = {r.instance for r in left}
    out += [f"{r.instance}: 右にしか無い" for r in right if r.instance not in left_names]
    for lr in left:
        rr = by_name.get(lr.instance)
        if rr is None:
            out.append(f"{lr.instance}: 左にしか無い")
            continue
        if (lr.n, lr.bks) != (rr.n, rr.bks):
            out.append(f"{lr.instance}: 顧客数と BKS: 左 {lr.n}, {lr.bks} / 右 {rr.n}, {rr.bks}")
        right_solvers = {res.solver: res for res in rr.results}
        left_solvers = {res.solver for res in lr.results}
        out += [f"{lr.instance} / {res.solver}: 右にしか無い"
                for res in rr.results if res.solver not in left_solvers]
        for res in lr.results:
            other = right_solvers.get(res.solver)
            diff = "左にしか無い" if other is None else _first_difference(res, other)
            if diff is not None:
                out.append(f"{lr.instance} / {res.solver}: {diff}")
    return out


def _first_difference(a: SolveResult, b: SolveResult) -> str | None:
    if a.solution != b.solution:
        for k, (ra, rb) in enumerate(zip(a.solution, b.solution)):
            if ra != rb:
                return f"ルート {k} が違う: 左 {ra} / 右 {rb}"
        return f"ルート数が違う: 左 {len(a.solution)} / 右 {len(b.solution)}"
    for field in ("cost", "feasible", "stats"):
        va, vb = getattr(a, field), getattr(b, field)
        if va != vb:
            return f"{field} が違う: 左 {va} / 右 {vb}"
    ha = [(h.cost, h.iteration) for h in a.history]
    hb = [(h.cost, h.iteration) for h in b.history]
    if ha != hb:
        for k, (x, y) in enumerate(zip(ha, hb)):
            if x != y:
                return f"history[{k}] (コスト, 反復回数) が違う: 左 {x} / 右 {y}"
        return f"history の長さが違う: 左 {len(ha)} / 右 {len(hb)}"
    return None
