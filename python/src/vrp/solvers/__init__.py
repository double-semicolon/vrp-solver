"""
ソルバ群。全て SolveResult を返す。

SOLVERS は CLI が名前から実装を引くための登録表。ここに1行足せば
--solver で選べるようになる。
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from ..model import Instance
from ..result import SolveResult
from .branch_and_bound import BranchAndBound, solve_branch_and_bound
from .bruteforce import enumerate_solutions, solve_brute_force
from .nearest_neighbor import nearest_neighbor, solve_nearest_neighbor
from .two_opt import solve_nn_2opt, two_opt


@dataclass(frozen=True)
class SolverSpec:
    run: Callable[[Instance], SolveResult]
    exact: bool          # 厳密解を返すか
    max_customers: int   # これを超えると現実的な時間で終わらない目安
    description: str


# max_customers は 1 ルート約 4 件のランダムインスタンスで「10 秒以内に終わる」
# 最大の顧客数。実測: 全探索 n=9 で 2 秒 / n=10 で 28 秒、B&B n=11 で 8 秒 /
# n=12 で 70 秒。最近傍法の初期解はランダムインスタンスではほとんど効かない。
SOLVERS: dict[str, SolverSpec] = {
    "nn": SolverSpec(solve_nearest_neighbor, False, 100_000, "最近傍法"),
    "nn+2opt": SolverSpec(solve_nn_2opt, False, 100_000, "最近傍法 + 2-opt (ルート内)"),
    "brute": SolverSpec(solve_brute_force, True, 9, "全探索 (参照実装)"),
    "bnb": SolverSpec(solve_branch_and_bound, True, 11, "枝刈り付き全探索"),
    "bnb+nn": SolverSpec(
        lambda inst: solve_branch_and_bound(inst, seed_with_nn=True),
        True, 11, "枝刈り付き全探索 (最近傍法の解を初期上界に使う)",
    ),
}

__all__ = [
    "SOLVERS",
    "BranchAndBound",
    "SolverSpec",
    "enumerate_solutions",
    "nearest_neighbor",
    "solve_branch_and_bound",
    "solve_brute_force",
    "solve_nearest_neighbor",
    "solve_nn_2opt",
    "two_opt",
]
