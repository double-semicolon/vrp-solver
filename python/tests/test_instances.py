"""
ランダムインスタンス生成のテスト。

生成器が壊れると、それを使った全ての実験結果が静かに無意味になる。
「同じシードなら完全に同じ問題」と「必ず実行可能解が存在する」の2つが要。
"""

from __future__ import annotations

import math
import random

import pytest

from vrp.instances import random_instance
from vrp.instances.generate import first_fit_decreasing
from vrp.objective import validate

LAYOUTS = ["uniform", "clustered"]
SIZES = [1, 5, 20, 50, 200]


@pytest.mark.parametrize("layout", LAYOUTS)
@pytest.mark.parametrize("n", SIZES)
def test_same_seed_gives_identical_instance(n: int, layout: str):
    """改善幅の比較は同じ問題を解いていることが前提。ここが崩れると全部無意味になる。"""
    a = random_instance(n, seed=7, layout=layout)
    b = random_instance(n, seed=7, layout=layout)
    assert a.coords == b.coords
    assert a.demands == b.demands
    assert (a.capacity, a.num_vehicles, a.name) == (b.capacity, b.num_vehicles, b.name)


# 生成器のコードを変えると、同じシードでも別の問題になる。プロセス内で2回作って
# 比べるだけでは気づけないので、値を焼いておく。ここが動いたら、過去の実験結果と
# 比較できなくなる変更をしたということ。意図した変更なら値を更新する。
SNAPSHOTS = [
    # layout, capacity, num_vehicles, 総需要, 顧客1, 顧客2
    ("uniform", 71, 8, 426, (84.442, 75.795), (42.057, 25.892)),
    ("clustered", 74, 8, 444, (73.534, 77.411), (50.334, 20.5)),
]


@pytest.mark.parametrize("snap", SNAPSHOTS, ids=[s[0] for s in SNAPSHOTS])
def test_seed_0_gives_the_recorded_instance(snap):
    layout, capacity, num_vehicles, total, p1, p2 = snap
    inst = random_instance(50, seed=0, layout=layout)
    assert inst.name == f"rand50-{layout}-s0"
    assert (inst.capacity, inst.num_vehicles, sum(inst.demands)) == (capacity, num_vehicles, total)
    assert [tuple(round(v, 3) for v in p) for p in inst.coords[1:3]] == [p1, p2]


@pytest.mark.parametrize("layout", LAYOUTS)
def test_different_seeds_give_different_instances(layout: str):
    assert random_instance(30, seed=1, layout=layout).coords != \
           random_instance(30, seed=2, layout=layout).coords


def test_does_not_disturb_the_global_random_state():
    """
    random.Random のインスタンスを使い、グローバルな乱数状態に触らないこと。

    ここでグローバルを使うと、生成器を呼ぶ順番で他の実験の乱数が変わる。
    """
    random.seed(12345)
    before = random.random()
    random.seed(12345)
    random_instance(40, seed=99)
    assert random.random() == before


@pytest.mark.parametrize("layout", LAYOUTS)
@pytest.mark.parametrize("n", SIZES)
def test_instance_is_solvable_even_without_slack(n: int, layout: str):
    """
    実行可能解が存在するインスタンスしか作らないこと。

    生成器が車両数を決めるのに使った詰め方を、validate() で検査する。
    余裕 0 台でも通れば、車両数がちょうど足りる最も厳しい場合でも解ける。
    """
    inst = random_instance(n, seed=3, layout=layout, vehicle_slack=0)
    witness = first_fit_decreasing(inst.demands, inst.capacity)

    assert inst.n == n
    assert len(witness) == inst.num_vehicles
    assert validate(inst, witness) == []


@pytest.mark.parametrize("layout", LAYOUTS)
def test_depot_is_at_the_center_and_points_are_inside_the_area(layout: str):
    inst = random_instance(60, seed=5, layout=layout, area=100.0)
    assert inst.coords[0] == (50.0, 50.0)
    assert all(0.0 <= x <= 100.0 and 0.0 <= y <= 100.0 for x, y in inst.coords)


def test_demands_stay_in_the_requested_range():
    inst = random_instance(100, seed=4, demand_range=(3, 7))
    assert inst.demands[0] == 0
    assert all(3 <= d <= 7 for d in inst.demands[1:])


def test_clustered_is_actually_more_clustered_than_uniform():
    """
    layout の違いが探索に効いてほしいので、実際に固まっていることを見る。

    最近傍距離の中央値で比べる。クラスタ配置のほうが近くに仲間がいるはず。
    """
    def median_nn_distance(inst):
        D = inst.D
        return sorted(
            min(D[i][j] for j in inst.customers if j != i) for i in inst.customers
        )[inst.n // 2]

    uni = median_nn_distance(random_instance(80, seed=11, layout="uniform"))
    clu = median_nn_distance(random_instance(80, seed=11, layout="clustered"))
    assert clu < uni


@pytest.mark.parametrize("customers_per_route", [4, 8, 12])
def test_route_count_is_close_to_the_target(customers_per_route: int):
    """容量は「1ルートあたり平均 customers_per_route 件」から逆算している。"""
    n = 120
    inst = random_instance(n, seed=2, customers_per_route=customers_per_route, vehicle_slack=0)
    target = n / customers_per_route
    # 下界 (総需要 / 容量) は狙いを超えず、実際の台数も狙いから大きく外れない
    assert math.ceil(sum(inst.demands) / inst.capacity) <= math.ceil(target)
    assert inst.num_vehicles <= math.ceil(target) + 1


def test_unknown_layout_is_rejected():
    with pytest.raises(ValueError, match="layout"):
        random_instance(10, seed=1, layout="spiral")


@pytest.mark.parametrize("kwargs", [
    dict(n=0),
    dict(demand_range=(0, 5)),
    dict(demand_range=(8, 3)),
    dict(customers_per_route=0),
    dict(vehicle_slack=-1),
])
def test_invalid_arguments_are_rejected(kwargs):
    args = dict(n=10, seed=1) | kwargs
    with pytest.raises(ValueError):
        random_instance(**args)
