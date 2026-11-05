"""
Java 版との突き合わせに使うファイル (testdata/runs, testdata/distances) のテスト。

Python → Java: runs/python と distances は Python が書いたもの。ここで
  「今のコードで作り直すと同じ内容になる」ことを確かめるので、Java のテストが
  古いファイルと突き合わせて通ってしまうことは無い。
Java → Python: runs/java は Java が書いたもの (GoldenRunTest がバイト列を固定している)。
  ここで Python の load_run が読め、中身が Java 側で組み立てた値どおりであることを確かめる。
  runs/java/bench-x.json は Java の XBench が testdata/cvrplib の 2 件で書いたベンチマーク結果
  (XBenchTest が今の Java のコードの出力であることを確かめている)。ここで Python が同じ 2 件を
  走らせた結果と突き合わせる。X set 全 100 件の突き合わせ (vrp check-bench) を CI で回す代わり。

CI では2つの言語が別々のジョブで走るので、ファイルを介して両方向をつなぐ。
"""

from __future__ import annotations

import json

import pytest
from conftest import TESTDATA
from make_testdata import (
    DIST_DIR,
    DIST_FORMAT,
    HANDMADE,
    INVALID,
    INVALID_DIR,
    INVALID_EXPECTED,
    RUNS_DIR,
    build_runs,
    cvrplib_parse_files,
    dist_text,
    distance_sources,
    invalid_expected_text,
    without_time,
)

from vrp.benchmark import compare_benches, load_bench, load_cases, run_benchmark
from vrp.model import build_distance_matrix
from vrp.objective import is_feasible, solution_cost
from vrp.serialize import load_run, loads

REGEN = "uv run python tests/make_testdata.py で作り直す (意図した変更か確かめてから)"
RUNS = build_runs()


def test_python_runs_cover_exactly_the_specs():
    assert sorted(p.stem for p in RUNS_DIR.glob("*.json")) == sorted(RUNS), REGEN


@pytest.mark.parametrize("name", sorted(RUNS))
def test_python_runs_are_what_the_current_code_writes(name):
    inst, results = RUNS[name]
    back_inst, back = load_run(RUNS_DIR / f"{name}.json")
    assert back_inst == inst, REGEN
    if name == HANDMADE:
        # 手作りの結果は経過時間も固定なので丸ごと比べる
        assert back == results, REGEN
    else:
        assert without_time(back) == without_time(results), REGEN


def _two_opt_ties(inst) -> int:
    """
    最近傍法 + 2-opt の途中で、縮む距離が最大の move が複数あった回数。

    improve_once を使わず、全ての (i, j) の gain を並べて数える。数えた後の
    ルートの更新だけ improve_once に任せる (どれを選ぶかは数には影響しない)。
    """
    from vrp.solvers import nearest_neighbor
    from vrp.solvers.two_opt import gain, improve_once

    ties = 0
    for route in nearest_neighbor(inst):
        while True:
            m = len(route)
            gains = [gain(inst.D, route, i, j) for i in range(m - 1) for j in range(i + 1, m)]
            best = max(gains, default=0)
            if best <= 0:
                break
            ties += gains.count(best) > 1
            improve_once(inst.D, route)
    return ties


def test_python_runs_contain_two_opt_ties():
    """
    2-opt の同点が突き合わせのデータに実際に入っていること。入っていなければ、
    同点の決め方が Java と違っていても突き合わせは通ってしまう。
    """
    ties = {name: _two_opt_ties(inst) for name, (inst, _) in RUNS.items() if name != HANDMADE}
    assert ties["two-opt-tie"] == 1
    assert sum(ties.values()) >= 20, ties


def test_python_runs_contain_the_cases_worth_cross_checking():
    """突き合わせで見たいものが実際に入っていること (選び方を変えて消えたら気づく)。"""
    all_results = [(inst, r) for inst, rs in RUNS.values() for r in rs]
    assert any(inst.num_vehicles is None for inst, _ in all_results)
    assert any(not r.feasible for inst, r in all_results if r.solver != "stats-of-every-type")
    assert any(any(x != int(x) for xy in inst.coords for x in xy) for inst, _ in all_results)
    assert any(r.stats.get("initial_upper_bound", 0) is None for _, r in all_results)


@pytest.mark.parametrize("name", sorted(RUNS))
def test_python_runs_rescore_to_the_written_values(name):
    """Java が計算し直して比べる値が、Python で計算し直しても同じであること。"""
    inst, _ = RUNS[name]
    _, results = load_run(RUNS_DIR / f"{name}.json")
    for r in results:
        if r.solver == "stats-of-every-type":
            continue   # わざと嘘の cost と feasible を書いてある
        assert r.cost == solution_cost(inst, r.solution)
        assert r.feasible == is_feasible(inst, r.solution)


# ---------------------------------------------------------------- 距離行列


SOURCES = distance_sources()


def test_distance_files_cover_exactly_the_sources():
    assert sorted(p.stem for p in DIST_DIR.glob("*.json")) == sorted(n for n, _ in SOURCES), REGEN


@pytest.mark.parametrize("name, coords", SOURCES, ids=[n for n, _ in SOURCES])
def test_distance_file_matches_the_current_definition(name, coords):
    path = DIST_DIR / f"{name}.json"
    d = loads(path.read_text(encoding="utf-8"))
    assert d["format"] == DIST_FORMAT
    assert d["D"] == build_distance_matrix([tuple(c) for c in d["coords"]]), REGEN
    assert path.read_text(encoding="utf-8") == dist_text(name, coords), REGEN


def test_rounding_edge_is_rounded_up_like_int_plus_half():
    """0.49999999999999994 + 0.5 は浮動小数点で 1.0。Java が Math.round を使うと 0 になる。"""
    d = loads((DIST_DIR / "rounding-edges.json").read_text(encoding="utf-8"))
    assert d["D"][0][1] == 1
    assert d["D"][0][2] == 3   # ちょうど 2.5


# ---------------------------------------------------------------- Java が書いたファイル


JAVA_GOLDEN = RUNS_DIR.parent / "java" / "golden.json"


def test_java_written_run_is_readable_by_python():
    inst, results = load_run(JAVA_GOLDEN)

    # Java 側 (GoldenRunTest) で組み立てた値。小数は2進で表せないものと指数表記になるものを含む
    assert inst.name == "java-golden"
    assert inst.coords == [(0.0, 0.0), (0.1, 0.2), (1 / 3, 2 / 3), (1e-7, 12345.678), (-3.0, 4.0)]
    assert inst.demands == [0, 2, 3, 4, 5]
    assert inst.capacity == 9
    assert inst.num_vehicles is None

    assert [r.solver for r in results] == ["feasible", "infeasible"]
    for r in results:
        assert r.cost == solution_cost(inst, r.solution)
        assert r.feasible == is_feasible(inst, r.solution)
    assert [r.feasible for r in results] == [True, False]

    ok = results[0]
    assert ok.elapsed_sec == 1e-7
    assert ok.stats == {"nodes": 12, "ratio": 0.1, "seeded": True, "bound": None, "note": "日本語"}
    assert type(ok.stats["nodes"]) is int and type(ok.stats["ratio"]) is float
    assert [(h.elapsed_sec, h.cost, h.iteration) for h in ok.history] == [
        (0.0, 40, 0), (2.5e-05, ok.cost, None)]


def test_java_written_run_uses_lf_and_strict_json():
    raw = JAVA_GOLDEN.read_bytes()
    assert b"\r" not in raw
    json.loads(raw, parse_constant=lambda c: pytest.fail(f"規格外の値 {c}"))


JAVA_BENCH = RUNS_DIR.parent / "java" / "bench-x.json"


def test_java_written_bench_matches_python_on_the_same_instances():
    java = load_bench(JAVA_BENCH)
    python = run_benchmark(load_cases(TESTDATA / "cvrplib"), ["nn", "nn+2opt"])
    assert [r.instance for r in java] == ["X-n101-k25", "X-n106-k14"]
    assert compare_benches(python, java) == []


def test_java_written_bench_uses_lf():
    assert b"\r" not in JAVA_BENCH.read_bytes()


# ---------------------------------------------------------------- 厳格さ (両言語で同じファイル)
#
# 拒否のテストを言語ごとに書くと、片方だけ緩くなっても気づけない。
# 同じファイルを Java の StrictReadingTest も読み、全て拒否することを確かめる。


def test_invalid_files_are_what_the_generator_writes():
    assert sorted(p.stem for p in INVALID_DIR.glob("*.json")) == sorted(INVALID), REGEN
    for name, (make, _) in INVALID.items():
        assert (INVALID_DIR / f"{name}.json").read_text(encoding="utf-8") == make(), REGEN
    assert INVALID_EXPECTED.read_text(encoding="utf-8") == invalid_expected_text(), REGEN


def test_valid_base_is_accepted():
    """対照。これが読めなければ、下の各ファイルは壊した箇所とは別の理由で落ちている。"""
    load_run(INVALID_DIR.parent / "valid-base.json")


@pytest.mark.parametrize("name", sorted(INVALID))
def test_invalid_file_is_rejected_for_the_intended_reason(name):
    with pytest.raises(ValueError) as e:
        load_run(INVALID_DIR / f"{name}.json")
    msg = str(e.value)
    assert msg.startswith(f"{name}.json: "), msg
    where = INVALID[name][1]
    if where is not None:
        assert where in msg, msg


# ---------------------------------------------------------------- CVRPLib のローダの異常系


def test_cvrplib_parse_files_are_what_the_generator_writes():
    files = cvrplib_parse_files()
    parse_dir = next(iter(files)).parent
    on_disk = {p for p in parse_dir.rglob("*") if p.is_file()}
    assert on_disk == set(files), REGEN
    for path, text in files.items():
        assert path.read_text(encoding="utf-8") == text, REGEN
