"""
Step 0: 小規模CVRP を全探索で厳密に解く。

目的は速度ではなく定義の確定:
  - 解 (Solution) とは何か
  - 実行可能解 (feasible solution) の条件は何か
  - 目的関数 (総走行距離) をどう計算するか
ここで決めた表現とチェッカーを Step 1 以降でそのまま使い回す。

このモジュールは参照実装として凍結する。速くする工夫は入れない。
枝刈りを入れた探索の正しさは「このモジュールと同じ答えに到達すること」で担保する。
"""

from __future__ import annotations

import math
import time
from collections.abc import Iterator
from itertools import combinations, permutations

from ..model import Instance, Solution
from ..objective import solution_cost
from ..result import Improvement, SolveResult


def enumerate_solutions(
    inst: Instance, stats: dict[str, int] | None = None
) -> Iterator[Solution]:
    """
    実行可能解を全列挙する。同じ解の重複は一切生成しない。

    列挙の原理:
      「未割当のうち最小の顧客 m」を必ず今作るルートに入れる、と決めて再帰する。
      1ルート分を確定させてから残りを再帰、を繰り返す。

    重複が出ない理由 (後から弾くのではなく生成規則で保証している):
      - ルートの並べ替え (k! 通り)  -> 各ルートの最小顧客が昇順になるので代表は1つ
      - ルートの逆走 (2^k 通り)     -> 先頭 < 末尾 のものだけ生成する

    stats を渡すと探索ノード数 (extend の呼び出し回数) を stats["nodes"] に記録する。
    """
    demands, Q, K = inst.demands, inst.capacity, inst.num_vehicles
    counter = stats if stats is not None else {}
    counter["nodes"] = 0

    def extend(remaining: frozenset[int], routes: Solution) -> Iterator[Solution]:
        counter["nodes"] += 1

        if not remaining:
            yield routes              # 全員割当済み = 解が1つ完成
            return
        if K is not None and len(routes) == K:   # None は上限なし
            return                    # 車が尽きた。行き止まり

        m = min(remaining)            # m は必ず「今作るルート」に入れる -> k! の重複が消える
        rest = sorted(remaining - {m})

        for r in range(len(rest) + 1):
            for others in combinations(rest, r):
                S = (m,) + others
                if sum(demands[c] for c in S) > Q:
                    continue
                for order in permutations(S):
                    if order[0] > order[-1]:       # 逆走の重複を潰す (2^k)
                        continue
                    yield from extend(remaining - set(S), routes + [list(order)])

    yield from extend(frozenset(inst.customers), [])


def solve_brute_force(inst: Instance) -> SolveResult:
    """全探索で最適解を求める。実行可能解が1つも無ければ ValueError。"""
    t0 = time.perf_counter()
    best: Solution | None = None
    best_cost = math.inf
    n_solutions = 0
    stats: dict = {}
    history: list[Improvement] = []

    for sol in enumerate_solutions(inst, stats):
        n_solutions += 1
        c = solution_cost(inst, sol)
        if c < best_cost:
            best_cost, best = c, [list(r) for r in sol]
            history.append(Improvement(time.perf_counter() - t0, c, iteration=n_solutions))

    elapsed = time.perf_counter() - t0
    if best is None:
        raise ValueError(f"{inst.name}: 実行可能解が存在しない")
    stats["solutions"] = n_solutions
    return SolveResult.build(inst, "brute", best, elapsed, stats, history)
