"""
問題の表現。インスタンスと解のデータ構造だけを持つ。

ここにアルゴリズムは置かない。目的関数は objective.py、探索は各ソルバのモジュール。
Step 1 以降でソルバが増えても、このファイルは滅多に変わらないはず。
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

# 解の表現:
#   Route    = 顧客インデックスの列。depot(0) は前後に暗黙で付くので含めない。
#   Solution = Route のリスト。長さが使用車両数。
Route = list[int]
Solution = list[Route]


def build_distance_matrix(coords: list[tuple[float, float]]) -> list[list[int]]:
    """EUC_2D: ユークリッド距離を四捨五入して整数化する。CVRPLib の規約に合わせる。"""
    m = len(coords)
    D = [[0] * m for _ in range(m)]
    for i in range(m):
        xi, yi = coords[i]
        for j in range(i + 1, m):
            xj, yj = coords[j]
            d = int(math.sqrt((xi - xj) ** 2 + (yi - yj) ** 2) + 0.5)
            D[i][j] = D[j][i] = d
    return D


@dataclass
class Instance:
    name: str
    coords: list[tuple[float, float]]  # index 0 が depot
    demands: list[int]                 # demands[0] は 0
    capacity: int                      # 1台あたりの積載量上限 Q
    num_vehicles: int                  # 利用可能な車両数 K
    D: list[list[int]] = field(init=False, repr=False)

    def __post_init__(self) -> None:
        # assert は python -O で消えるので、入力の検査には使わない
        if len(self.coords) != len(self.demands):
            raise ValueError("coords と demands の長さが違う")
        if not self.coords:
            raise ValueError("depot がない")
        if self.demands[0] != 0:
            raise ValueError("depot の需要は 0")
        if min(self.demands) < 0:
            raise ValueError("負の需要がある")
        if max(self.demands) > self.capacity:
            raise ValueError("1台に積めない顧客が存在する")
        if self.num_vehicles < 1:
            raise ValueError("車両が1台もない")
        self.D = build_distance_matrix(self.coords)

    @property
    def n(self) -> int:
        """顧客数 (depot を除く)"""
        return len(self.coords) - 1

    @property
    def customers(self) -> list[int]:
        return list(range(1, self.n + 1))
