"""
ランダムインスタンスの生成。

toy5/toy8 は厳密解が出せる代わりに小さすぎて、局所探索の効き方が見えない。
Step 1 以降で「改善したか」を比べるための材料。

シードを固定するのは必須。同じ問題を解いていないと改善幅の比較に
意味が無くなる。標準ライブラリの random.Random だけを使い、
グローバルな乱数状態には触らない (他の実行に影響させない)。
"""

from __future__ import annotations

import math
import random
from typing import Literal

from ..model import Instance, Solution

Layout = Literal["uniform", "clustered"]


def first_fit_decreasing(demands: list[int], capacity: int) -> Solution:
    """
    容量だけを見て顧客を車両に詰める。ルート内の順序は気にしない。

    需要の大きい順に、入る最初の車両へ入れる。距離は無視するので解としては
    悪いが、容量制約を満たす割り当ての実例 (実行可能解の証拠) にはなる。
    """
    order = sorted(range(1, len(demands)), key=lambda c: -demands[c])
    routes: Solution = []
    loads: list[int] = []
    for c in order:
        for i, load in enumerate(loads):
            if load + demands[c] <= capacity:
                routes[i].append(c)
                loads[i] += demands[c]
                break
        else:
            routes.append([c])
            loads.append(demands[c])
    return routes


def random_instance(
    n: int,
    seed: int,
    layout: Layout = "uniform",
    *,
    area: float = 100.0,
    demand_range: tuple[int, int] = (1, 20),
    customers_per_route: int = 8,
    vehicle_slack: int = 2,
    name: str | None = None,
) -> Instance:
    """
    顧客 n 件のインスタンスを作る。depot は領域の中央に置く。

    layout:
      "uniform"   一様分布。ルートが放射状になりやすい
      "clustered" 数個のクラスタに固まる。ルート間の移動の効きが一様分布と
                  明確に違うので、両方見ておく価値がある

    容量は「1ルートあたり平均 customers_per_route 件」になるように逆算する。
    車両数は first_fit_decreasing で実際に詰められた台数に vehicle_slack 台を
    足す。詰め方の実例があるので、実行可能解が必ず存在する。
    """
    lo, hi = demand_range
    if n < 1:
        raise ValueError("顧客が1件もない")
    if not 1 <= lo <= hi:
        raise ValueError(f"demand_range が不正: {demand_range}")
    if customers_per_route < 1:
        raise ValueError("customers_per_route は 1 以上")
    if vehicle_slack < 0:
        raise ValueError("vehicle_slack は 0 以上")

    rng = random.Random(seed)

    depot = (area / 2, area / 2)
    if layout == "uniform":
        pts = [(rng.uniform(0, area), rng.uniform(0, area)) for _ in range(n)]
    elif layout == "clustered":
        n_clusters = max(2, round(n / 12))
        centers = [(rng.uniform(0, area), rng.uniform(0, area)) for _ in range(n_clusters)]
        sigma = area / (6 * math.sqrt(n_clusters))   # 小さいほどクラスタが締まる
        pts = []
        for i in range(n):
            cx, cy = centers[i % n_clusters]      # クラスタに均等に配る
            x = min(area, max(0.0, rng.gauss(cx, sigma)))
            y = min(area, max(0.0, rng.gauss(cy, sigma)))
            pts.append((x, y))
    else:
        raise ValueError(f"未知の layout: {layout}")

    demands = [0] + [rng.randint(lo, hi) for _ in range(n)]
    total = sum(demands)

    target_routes = max(2, round(n / customers_per_route))
    capacity = max(max(demands), math.ceil(total / target_routes))
    num_vehicles = len(first_fit_decreasing(demands, capacity)) + vehicle_slack

    return Instance(
        name=name or f"rand{n}-{layout}-s{seed}",
        coords=[depot] + pts,
        demands=demands,
        capacity=capacity,
        num_vehicles=num_vehicles,
    )
