"""ソルバ群。全て SolveResult を返す。"""

from __future__ import annotations

from .bruteforce import enumerate_solutions, solve_brute_force

__all__ = [
    "enumerate_solutions",
    "solve_brute_force",
]
