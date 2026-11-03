"""
Step 1a: 最近傍法による構築。Step 1 の局所探索はここから出発する。

構築法は「速く、そこそこの解」を作る役。最適性は求めない。
branch and bound の初期上界にも使う。
"""

from __future__ import annotations

import time

from ..model import Instance, Route, Solution
from ..result import SolveResult


def nearest_neighbor(inst: Instance) -> Solution:
    """
    現在地から最も近い未訪問顧客のうち、容量に収まるものへ移動する。
    収まるものが無ければ depot に戻って次の車を出す。

    車両数上限 K は見ていないので、K 台を超える解 (実行不可能解) を返しうる。
    K を守るには詰め直しが要り、それはもう最近傍法ではない。上界や初期解として
    使う側が is_feasible で確かめること。
    """
    D = inst.D
    unvisited = set(inst.customers)
    sol: Solution = []

    while unvisited:
        route: Route = []
        load = 0
        cur = 0
        while True:
            cand = [c for c in unvisited if load + inst.demands[c] <= inst.capacity]
            if not cand:
                break
            # 同距離のときは顧客番号の小さい方を選ぶ。
            # これが無いと勝者が set のイテレーション順に依存し、結果の再現が保証されない。
            nxt = min(cand, key=lambda c: (D[cur][c], c))
            route.append(nxt)
            load += inst.demands[nxt]
            unvisited.remove(nxt)
            cur = nxt

        # 空ルートを積むと unvisited が減らず外側の while が無限に回る。
        # Instance が「1台に積めない顧客」を弾いている限り到達しない。
        if not route:
            raise RuntimeError(f"空の車にも積めない顧客が残っている: {sorted(unvisited)}")

        sol.append(route)

    return sol


def solve_nearest_neighbor(inst: Instance) -> SolveResult:
    """最近傍法を SolveResult の形で返す。K 台を超えたら feasible=False のまま返す。"""
    t0 = time.perf_counter()
    sol = nearest_neighbor(inst)
    elapsed = time.perf_counter() - t0
    return SolveResult.build(inst, "nn", sol, elapsed, stats={"routes": len(sol)})
