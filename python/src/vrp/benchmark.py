"""
CVRPLib のインスタンス群にソルバを掛け、BKS との gap と時間を集計する。

ランダムインスタンスの bench (cli.cmd_bench) は「その場の最良」との差しか測れない。
こちらは外部の基準 (既知最良解) との差なので、ソルバの絶対的な位置が分かり、
Step 3 では Java 版の結果をこの形式で突き合わせる。

BKS のコストは .sol の Cost 行を信用せず、目的関数で採点し直した値を使う。
食い違えばローダか距離計算が壊れているので、走らせる前に止める。

保存する JSON には gap を書かない。cost と bks から一意に決まる値を保存すると、
「両者と食い違った gap」という不正な状態を作れてしまう (serialize の D と同じ理由)。
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
from .serialize import dumps, loads, result_from_dict, result_to_dict
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
    p.write_text(text, encoding="utf-8")
    return p


def load_bench(path: str | Path) -> list[BenchRow]:
    d = loads(Path(path).read_text(encoding="utf-8"))
    if d.get("format") != FORMAT:
        raise ValueError(f"未知の形式: {d.get('format')!r} (期待: {FORMAT!r})")
    return [
        BenchRow(r["instance"], int(r["n"]), int(r["bks"]),
                 [result_from_dict(res) for res in r["results"]])
        for r in d["rows"]
    ]
