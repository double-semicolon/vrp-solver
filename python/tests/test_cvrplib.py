"""
CVRPLib のローダ (.vrp と .sol) のテスト。

期待値はローダと無関係な方法で出したもの (CVRPLib の一覧表と、DEMAND_SECTION を
awk で合計した値)。ローダの出力から期待値を作ると、読み違いがそのまま正解になる。
"""

from __future__ import annotations

import math
from pathlib import Path

import pytest
from conftest import TESTDATA

from vrp.instances import load_sol, load_vrp
from vrp.instances.cvrplib import parse_sol, parse_vrp
from vrp.model import Instance
from vrp.objective import solution_cost, validate

DATA = TESTDATA / "cvrplib"

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


# ---------------------------------------------------------------- 未対応の形式・壊れたファイル
#
# 壊れたファイルは testdata/cvrplib/parse/invalid/ にあり (作るのは make_testdata.py)、
# Java の CvrplibTest も同じファイルを同じ理由で拒否することを確かめている。

PARSE = DATA / "parse"
TINY = (PARSE / "valid.vrp").read_text(encoding="utf-8")
INVALID = [tuple(line.split("\t")) for line in
           (PARSE / "invalid.tsv").read_text(encoding="utf-8").splitlines()]


def test_tiny_text_is_read():
    """異常系のテストが「元の文が読めない」せいで通っていないことの確認 (対照)。"""
    inst = parse_vrp(TINY)
    assert inst.n == 2 and inst.capacity == 10 and inst.demands == [0, 4, 5]
    assert inst.D[0][1] == 5


def test_crlf_and_missing_eof_are_accepted():
    """CVRPLib の配布ファイルは CRLF。EOF 行は TSPLIB でも省略可能。

    CRLF はファイルにせずここで作る。.gitattributes が eol=lf なので、置いても LF になる。
    """
    text = TINY.replace("EOF\n", "").replace("\n", "\r\n")
    assert parse_vrp(text).demands == [0, 4, 5]


def test_invalid_table_covers_every_file():
    assert sorted(n for n, _ in INVALID) == sorted(p.name for p in (PARSE / "invalid").iterdir())


@pytest.mark.parametrize("name, why", INVALID, ids=[n for n, _ in INVALID])
def test_unsupported_or_broken_file_is_rejected(name, why):
    load = load_vrp if name.endswith(".vrp") else load_sol
    with pytest.raises(ValueError) as e:
        load(PARSE / "invalid" / name)
    msg = str(e.value)
    assert msg.startswith(f"{name}: ") and why in msg, msg


def test_error_message_names_the_file(tmp_path):
    path = tmp_path / "broken.vrp"
    path.write_text(TINY.replace("EUC_2D", "GEO"), encoding="utf-8")
    with pytest.raises(ValueError, match="broken.vrp"):
        load_vrp(path)


# ---------------------------------------------------------------- BKS (.sol)
#
# ローダと距離計算を、外部の正解 (公表されている既知最良解) で確かめる。
# 番号の対応や丸めが1か所でもずれていれば、実行可能にならないかコストが合わない。
# ただし X set の座標は整数なので、距離がちょうど .5 になることはなく
# (整数の平方根は整数か無理数)、四捨五入と偶数丸めの違いはここでは出ない。
# その違いは test_model.py の test_distance_rounds_half_up が受け持つ。

# (ファイル名, CVRPLib の一覧表に載っている BKS のコスト, BKS のルート数)
BKS = [
    ("X-n101-k25", 27591, 26),
    ("X-n106-k14", 26362, 14),
]


@pytest.mark.parametrize("name, published, n_routes", BKS, ids=[b[0] for b in BKS])
def test_bks_is_feasible_and_rescores_to_the_published_cost(name, published, n_routes):
    inst = load_vrp(DATA / f"{name}.vrp")
    routes, written = load_sol(DATA / f"{name}.sol")

    assert validate(inst, routes) == []
    # ファイルの Cost 行は信用せず、目的関数で採点し直した値を公表値と比べる
    assert solution_cost(inst, routes) == published
    assert written == published
    assert len(routes) == n_routes


def test_bks_can_use_more_routes_than_the_k_in_the_name():
    """
    X-n101-k25 の BKS は 26 ルート。名前の k (=25) を上限にすると BKS が
    実行不可能になる。num_vehicles を None にした (#11) 根拠をデータで残す。
    """
    inst = load_vrp(DATA / "X-n101-k25.vrp")
    routes, _ = load_sol(DATA / "X-n101-k25.sol")
    capped = Instance(name=inst.name, coords=inst.coords, demands=inst.demands,
                      capacity=inst.capacity, num_vehicles=25)
    assert any("車両数超過" in e for e in validate(capped, routes))
    assert validate(inst, routes) == []


SOL = (PARSE / "valid.sol").read_text(encoding="utf-8")


def test_tiny_sol_is_read():
    assert parse_sol(SOL) == ([[1, 2], [3]], 42)


@pytest.mark.parametrize("written", ["42.0", "42.000000"])
def test_integral_cost_written_as_decimal_is_accepted(written):
    """X-n284-k15 などの BKS は Cost を小数の形で書いている (値は整数)。"""
    assert parse_sol(SOL.replace("Cost 42", f"Cost {written}"))[1] == 42
