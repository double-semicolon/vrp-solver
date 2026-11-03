"""
手で書いた小規模インスタンス。

全探索で厳密最適が求まるサイズに留めてある。Step 2 で CVRPLib の
ファイルを読み込むローダを足すが、これらはテスト用の基準として残す。
"""

from __future__ import annotations

from ..model import Instance

TOY5 = Instance(
    name="toy5",
    coords=[(50, 50), (20, 30), (25, 70), (75, 65), (80, 25), (55, 20)],
    demands=[0, 12, 9, 14, 11, 7],
    capacity=30,
    num_vehicles=2,
)

TOY8 = Instance(
    name="toy8",
    coords=[(50, 50), (18, 32), (22, 72), (40, 85), (72, 78),
            (88, 55), (78, 22), (48, 12), (30, 20)],
    demands=[0, 10, 8, 13, 7, 11, 9, 12, 6],
    capacity=40,
    num_vehicles=3,
)

TOYS = [TOY5, TOY8]
