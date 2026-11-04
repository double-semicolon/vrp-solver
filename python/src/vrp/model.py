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
    """
    EUC_2D: ユークリッド距離を四捨五入して整数化する。CVRPLib の規約に合わせる。

    Java 版 (Instance.java) と同じ値になるよう、式の形まで揃えてある:
      - 2乗は ** ではなく掛け算。** は C の pow で、正しく丸める保証が無く
        Windows では x*x と 0.2% ほど食い違う。掛け算は IEEE 754 で決まる
      - 丸めは int(d + 0.5)。Java では Math.round ではなくこの形で書く
        (0.49999999999999994 で両者は 1 と 0 に分かれる)
    """
    m = len(coords)
    D = [[0] * m for _ in range(m)]
    for i in range(m):
        xi, yi = coords[i]
        for j in range(i + 1, m):
            xj, yj = coords[j]
            dx, dy = xi - xj, yi - yj
            d = int(math.sqrt(dx * dx + dy * dy) + 0.5)
            D[i][j] = D[j][i] = d
    return D


@dataclass
class Instance:
    name: str
    coords: list[tuple[float, float]]  # index 0 が depot
    demands: list[int]                 # demands[0] は 0
    capacity: int                      # 1台あたりの積載量上限 Q
    # 利用可能な車両数 K。None は上限なし (CVRPLib の X set は台数を固定しない規約)。
    # 「十分大きい数」で代用しないのは、上限なしを数値で偽装すると
    # 表示や JSON で本物の上限と区別できなくなるため。
    num_vehicles: int | None
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
        if self.num_vehicles is not None and self.num_vehicles < 1:
            raise ValueError("車両が1台もない")
        self.D = build_distance_matrix(self.coords)

    @property
    def n(self) -> int:
        """顧客数 (depot を除く)"""
        return len(self.coords) - 1

    @property
    def customers(self) -> list[int]:
        return list(range(1, self.n + 1))
