"""
CVRPLib (.vrp) ローダのテスト。

期待値はローダと無関係な方法で出したもの (CVRPLib の一覧表と、DEMAND_SECTION を
awk で合計した値)。ローダの出力から期待値を作ると、読み違いがそのまま正解になる。
"""

from __future__ import annotations

import math
from pathlib import Path

import pytest

from vrp.instances import load_vrp
from vrp.instances.cvrplib import parse_vrp

DATA = Path(__file__).parent / "data" / "cvrplib"

# (ファイル名, 顧客数, 容量, 総需要, 名前の k)
X_INSTANCES = [
    ("X-n101-k25", 100, 206, 5147, 25),
    ("X-n106-k14", 105, 600, 7864, 14),
]


@pytest.mark.parametrize("name, n, capacity, total, k", X_INSTANCES, ids=[x[0] for x in X_INSTANCES])
def test_x_instance_matches_the_published_numbers(name, n, capacity, total, k):
    inst = load_vrp(DATA / f"{name}.vrp")
    assert inst.name == name
    assert inst.n == n
    assert inst.capacity == capacity
    assert sum(inst.demands) == total
    # 名前の k は上限ではなく ⌈総需要 / 容量⌉。だから num_vehicles には入れない (#11)
    assert math.ceil(total / capacity) == k
    assert inst.num_vehicles is None


def test_x_n101_rows_land_on_the_right_nodes():
    """番号と添字の対応 (ファイルの i 番 = 添字 i-1) を、先頭と末尾の行で確かめる。"""
    inst = load_vrp(DATA / "X-n101-k25.vrp")
    assert inst.coords[0] == (365, 689)      # depot (ファイルの 1 番)
    assert inst.coords[100] == (615, 750)    # ファイルの 101 番
    assert inst.demands[1] == 38 and inst.demands[100] == 35
    # (365,689)-(146,180): sqrt(219^2 + 509^2) = 554.12 -> 554
    assert inst.D[0][1] == 554


# ---------------------------------------------------------------- 未対応の形式

TINY = """\
NAME : tiny
COMMENT : hand written
TYPE : CVRP
DIMENSION : 3
EDGE_WEIGHT_TYPE : EUC_2D
CAPACITY : 10
NODE_COORD_SECTION
1 0 0
2 3 4
3 0 2
DEMAND_SECTION
1 0
2 4
3 5
DEPOT_SECTION
1
-1
EOF
"""


def _edit(old: str, new: str) -> str:
    assert TINY.count(old) == 1, old
    return TINY.replace(old, new)


def test_tiny_text_is_read():
    """以下のエラーのテストが「元の文が読めない」せいで通っていないことの確認。"""
    inst = parse_vrp(TINY)
    assert inst.n == 2 and inst.capacity == 10 and inst.demands == [0, 4, 5]
    assert inst.D[0][1] == 5


def test_crlf_and_missing_eof_are_accepted():
    """CVRPLib の配布ファイルは CRLF。EOF 行は TSPLIB でも省略可能。"""
    text = _edit("EOF\n", "").replace("\n", "\r\n")
    assert parse_vrp(text).demands == [0, 4, 5]


@pytest.mark.parametrize(
    "text, match",
    [
        (_edit("EUC_2D", "GEO"), "EDGE_WEIGHT_TYPE"),          # 距離の定義が違う
        (_edit("TYPE : CVRP", "TYPE : TSP"), "TYPE"),
        (_edit("COMMENT : hand written", "DISTANCE : 50"), "DISTANCE"),   # 知らない制約
        (_edit("DEPOT_SECTION\n1\n", "DEPOT_SECTION\n2\n"), "depot"),
        (_edit("DEPOT_SECTION\n1\n", "DEPOT_SECTION\n1\n2\n"), "depot"),   # depot が2つ
        (_edit("1\n-1\n", "1\n"), "-1"),
        (_edit("3 0 2\n", ""), "NODE_COORD_SECTION"),          # 行が欠けている
        (_edit("2 3 4\n3 0 2\n", "3 0 2\n2 3 4\n"), "番号"),     # 番号が順番どおりでない
        (_edit("2 4\n", "2 4 1\n"), "列数"),
        (_edit("CAPACITY : 10\n", ""), "CAPACITY"),
        (_edit("DEPOT_SECTION", "TIME_WINDOW_SECTION"), "セクション"),
    ],
    ids=["geo", "tsp", "unknown-key", "depot-not-1", "two-depots", "no-terminator",
         "missing-row", "out-of-order", "extra-column", "no-capacity", "unknown-section"],
)
def test_unsupported_or_broken_file_is_rejected(text, match):
    with pytest.raises(ValueError, match=match):
        parse_vrp(text)


def test_error_message_names_the_file(tmp_path):
    path = tmp_path / "broken.vrp"
    path.write_text(_edit("EUC_2D", "GEO"), encoding="utf-8")
    with pytest.raises(ValueError, match="broken.vrp"):
        load_vrp(path)
