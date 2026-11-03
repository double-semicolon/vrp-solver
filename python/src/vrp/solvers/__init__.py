"""ソルバ群。全て SolveResult を返す。"""

from __future__ import annotations

from .branch_and_bound import BranchAndBound, solve_branch_and_bound
from .bruteforce import enumerate_solutions, solve_brute_force
from .nearest_neighbor import nearest_neighbor, solve_nearest_neighbor
from .two_opt import solve_nn_2opt, two_opt

__all__ = [
    "BranchAndBound",
    "enumerate_solutions",
    "nearest_neighbor",
    "solve_branch_and_bound",
    "solve_brute_force",
    "solve_nearest_neighbor",
    "solve_nn_2opt",
    "two_opt",
]
