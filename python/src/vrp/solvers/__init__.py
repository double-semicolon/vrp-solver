"""ソルバ群。全て SolveResult を返す。"""

from __future__ import annotations

from .branch_and_bound import BranchAndBound, solve_branch_and_bound
from .bruteforce import enumerate_solutions, solve_brute_force
from .nearest_neighbor import nearest_neighbor, solve_nearest_neighbor

__all__ = [
    "BranchAndBound",
    "enumerate_solutions",
    "nearest_neighbor",
    "solve_branch_and_bound",
    "solve_brute_force",
    "solve_nearest_neighbor",
]
