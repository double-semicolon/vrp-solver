"""
JSON 入出力のテスト。

この形式は Step 3 で Java 側と突き合わせる契約になり、Step 5 では API の
レスポンスになる。壊れると気づきにくい割に影響範囲が広いので厚めに縛る。
"""

from __future__ import annotations

import json
import math

import pytest

from vrp.instances import TOY5, TOY8, random_instance
from vrp.result import Improvement, SolveResult
from vrp.serialize import (
    FORMAT,
    instance_from_dict,
    instance_to_dict,
    load_run,
    loads,
    result_from_dict,
    result_to_dict,
    save_run,
)
from vrp.solvers import SOLVERS, solve_branch_and_bound, solve_nn_2opt

INSTANCES = [TOY5, TOY8, random_instance(40, seed=4), random_instance(25, seed=5, layout="clustered")]
IDS = [i.name for i in INSTANCES]


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_instance_round_trip(inst):
    back = instance_from_dict(instance_to_dict(inst))
    assert back.name == inst.name
    assert back.coords == inst.coords
    assert back.demands == inst.demands
    assert (back.capacity, back.num_vehicles) == (inst.capacity, inst.num_vehicles)
    # 距離行列は保存せず coords から作り直す。一致しなければ丸めがずれている。
    assert back.D == inst.D


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_result_round_trip_through_text(inst):
    """dict に変換するだけでなく、文字列を経由しても元に戻ること (float の精度など)。"""
    res = solve_nn_2opt(inst)
    text = json.dumps(result_to_dict(res), allow_nan=False)
    assert result_from_dict(loads(text)) == res


def test_history_survives_the_round_trip():
    res = solve_branch_and_bound(TOY8)
    assert len(res.history) > 1, "この前提が崩れたらテストの意味が無い"
    back = result_from_dict(result_to_dict(res))
    assert back.history == res.history
    assert all(isinstance(h, Improvement) for h in back.history)


def test_absent_upper_bound_round_trips_as_null():
    """初期上界が無いことはソルバの時点で None (= null) にしてあるので、往復で失われない。"""
    res = solve_branch_and_bound(TOY5)
    assert res.stats["initial_upper_bound"] is None
    assert result_from_dict(loads(json.dumps(result_to_dict(res)))) == res


@pytest.mark.parametrize("bad", [math.inf, -math.inf, math.nan])
def test_non_finite_values_are_refused_not_rewritten(tmp_path, bad):
    """
    inf / NaN は書かずに例外にする。null に書き換えると往復で値が変わり、
    しかもソルバ側の問題が黙って隠れる。壊れたファイルも残さない。
    """
    res = SolveResult.build(TOY5, "dummy", [[1, 2, 5], [3, 4]], elapsed_sec=0.0,
                            stats={"bound": bad})
    path = tmp_path / "run.json"
    with pytest.raises(ValueError):
        save_run(path, TOY5, [res])
    assert not path.exists()


@pytest.mark.parametrize("token", ["Infinity", "-Infinity", "NaN"])
def test_non_standard_constants_are_refused_when_reading(tmp_path, token):
    """json.loads は既定でこれらを読んでしまう。他の実装が書いたファイルも厳格に読む。"""
    p = tmp_path / "run.json"
    save_run(p, TOY5, [SOLVERS["nn"].run(TOY5)])
    p.write_text(p.read_text(encoding="utf-8").replace('"elapsed_sec": ', f'"elapsed_sec": {token}, "x": ', 1),
                 encoding="utf-8")
    with pytest.raises(ValueError, match="規格外"):
        load_run(p)


def test_save_and_load_run_with_every_solver(tmp_path):
    inst = TOY8
    results = [spec.run(inst) for spec in SOLVERS.values()]
    path = save_run(tmp_path / "sub" / "run.json", inst, results)

    assert path.exists(), "親ディレクトリが無くても作られること"
    back_inst, back_results = load_run(path)
    assert back_inst.D == inst.D
    assert back_results == results


def test_saved_file_is_strict_json(tmp_path):
    path = save_run(tmp_path / "run.json", TOY5, [SOLVERS["nn"].run(TOY5)])
    raw = path.read_text(encoding="utf-8")

    d = json.loads(raw, parse_constant=lambda c: pytest.fail(f"規格外の値 {c}"))
    assert d["format"] == FORMAT
    assert d["instance"]["name"] == "toy5"
    assert len(d["results"]) == 1


def test_unknown_format_is_rejected(tmp_path):
    """形式を変えたときに古いファイルを黙って読まないこと。"""
    p = tmp_path / "old.json"
    p.write_text(json.dumps({"format": "vrp-run/0", "instance": {}, "results": []}), encoding="utf-8")
    with pytest.raises(ValueError, match="形式"):
        load_run(p)
