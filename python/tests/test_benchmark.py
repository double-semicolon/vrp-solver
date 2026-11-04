"""
CVRPLib ベンチマーク (benchmark.py, fetch.py, CLI の bench-x) のテスト。

ネットワークには触らない。X set 全体の代わりに tests/data/cvrplib の 2 件を使い、
取得は 7z の展開部分だけを、テストの中で作ったアーカイブで確かめる。
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path

import pytest

from vrp.benchmark import (
    FORMAT,
    load_bench,
    load_cases,
    run_benchmark,
    save_bench,
    summarize,
)
from vrp.cli import main
from vrp.instances.fetch import extract_7z
from vrp.objective import solution_cost

DATA = Path(__file__).parent / "data" / "cvrplib"
BKS = {"X-n101-k25": 27591, "X-n106-k14": 26362}   # CVRPLib の一覧表の値


@pytest.fixture(scope="module")
def rows():
    return run_benchmark(load_cases(DATA), ["nn", "nn+2opt"])


def test_cases_carry_the_published_bks_in_size_order():
    cases = load_cases(DATA)
    assert [(c.inst.name, c.bks) for c in cases] == list(BKS.items())
    assert load_cases(DATA, max_n=100)[0].inst.name == "X-n101-k25"
    assert len(load_cases(DATA, max_n=100)) == 1


def test_gap_is_measured_against_the_bks(rows):
    for row in rows:
        assert row.bks == BKS[row.instance]
        for res in row.results:
            assert res.feasible
            # gap は BKS 基準。自前で計算した値と一致し、BKS より良くはならない
            expected = (solution_cost_of(row, res) - row.bks) / row.bks * 100
            assert row.gap(res) == pytest.approx(expected)
            assert row.gap(res) > 0


def solution_cost_of(row, res) -> int:
    from vrp.instances import load_vrp
    return solution_cost(load_vrp(DATA / f"{row.instance}.vrp"), res.solution)


def test_summary_matches_the_rows(rows):
    by_solver = {s.solver: s for s in summarize(rows)}
    assert list(by_solver) == ["nn", "nn+2opt"]
    for k, name in enumerate(by_solver):
        gaps = [row.gap(row.results[k]) for row in rows]
        assert by_solver[name].mean_gap == pytest.approx(sum(gaps) / len(gaps))
        assert by_solver[name].max_gap == pytest.approx(max(gaps))
        assert by_solver[name].infeasible == 0
    # 2-opt は最近傍法の解を悪くしない
    assert by_solver["nn+2opt"].mean_gap <= by_solver["nn"].mean_gap


def test_infeasible_results_are_kept_out_of_the_gap(rows):
    """制約を破って安くなった解が gap の平均に混ざると、ソルバが良く見えてしまう。"""
    import copy
    broken = copy.deepcopy(rows)
    res = broken[0].results[0]
    res.feasible, res.cost = False, broken[0].bks // 2    # BKS より安い「解」
    nn = summarize(broken)[0]
    assert nn.infeasible == 1
    assert nn.mean_gap == pytest.approx(broken[1].gap(broken[1].results[0]))


def test_bench_round_trips_through_strict_json(rows, tmp_path):
    path = save_bench(tmp_path / "bench.json", rows)
    d = json.loads(path.read_text(encoding="utf-8"))
    assert d["format"] == FORMAT
    assert "gap" not in json.dumps(d), "gap は cost と bks から決まるので保存しない"
    assert load_bench(path) == rows


def test_unknown_bench_format_is_refused(tmp_path):
    path = tmp_path / "bench.json"
    path.write_text(json.dumps({"format": "vrp-run/1", "rows": []}), encoding="utf-8")
    with pytest.raises(ValueError, match="未知の形式"):
        load_bench(path)


# ---------------------------------------------------------------- 入力の不備


def _copy_data(tmp_path: Path) -> Path:
    d = tmp_path / "x"
    shutil.copytree(DATA, d, ignore=shutil.ignore_patterns("*.md"))
    return d


def test_missing_sol_is_an_error_not_a_skip(tmp_path):
    d = _copy_data(tmp_path)
    (d / "X-n106-k14.sol").unlink()
    with pytest.raises(ValueError, match="X-n106-k14.sol"):
        load_cases(d)


def test_written_cost_that_does_not_rescore_is_an_error(tmp_path):
    """Cost 行と採点し直した値が違えば、ローダか距離計算が壊れている。走らせる前に止める。"""
    d = _copy_data(tmp_path)
    p = d / "X-n101-k25.sol"
    p.write_text(p.read_text(encoding="utf-8").replace("Cost 27591", "Cost 27590"), encoding="utf-8")
    with pytest.raises(ValueError, match="27591"):
        load_cases(d)


def test_infeasible_bks_is_an_error(tmp_path):
    d = _copy_data(tmp_path)
    p = d / "X-n101-k25.sol"
    p.write_text(p.read_text(encoding="utf-8").replace("Route #26: 24 95 73 53 33 32\n", ""),
                 encoding="utf-8")
    with pytest.raises(ValueError, match="実行可能でない"):
        load_cases(d)


def test_empty_directory_is_an_error(tmp_path):
    with pytest.raises(ValueError, match="fetch-x"):
        load_cases(tmp_path)


# ---------------------------------------------------------------- 取得 (7z の展開)


def test_extract_keeps_only_x_set_files(tmp_path):
    py7zr = pytest.importorskip("py7zr")
    src = tmp_path / "src"
    src.mkdir()
    shutil.copy(DATA / "X-n101-k25.vrp", src)
    (src / "readme.vrp").write_text("not an instance", encoding="utf-8")
    (src / "X-n101-k25.txt").write_text("other suffix", encoding="utf-8")
    archive = tmp_path / "x.7z"
    with py7zr.SevenZipFile(archive, "w") as z:
        for p in src.iterdir():
            z.write(p, arcname=f"X/{p.name}")    # 配布物がフォルダ入りでも拾えること

    dest = tmp_path / "dest"
    dest.mkdir()
    written = extract_7z(archive, dest, ".vrp")

    assert [p.name for p in written] == ["X-n101-k25.vrp"]
    assert sorted(p.name for p in dest.iterdir()) == ["X-n101-k25.vrp"]
    assert (dest / "X-n101-k25.vrp").read_bytes() == (DATA / "X-n101-k25.vrp").read_bytes()


def test_extract_refuses_an_archive_without_x_set_files(tmp_path):
    py7zr = pytest.importorskip("py7zr")
    (tmp_path / "a.vrp").write_text("x", encoding="utf-8")
    archive = tmp_path / "x.7z"
    with py7zr.SevenZipFile(archive, "w") as z:
        z.write(tmp_path / "a.vrp", arcname="a.vrp")
    with pytest.raises(ValueError, match="X set"):
        extract_7z(archive, tmp_path, ".vrp")


# ---------------------------------------------------------------- CLI


def test_bench_x_cli_prints_and_saves(tmp_path, capsys):
    main(["bench-x", "--dir", str(DATA), "--save", "--out-dir", str(tmp_path)])
    out = capsys.readouterr().out
    assert "X-n101-k25" in out and "27591" in out
    (saved,) = tmp_path.glob("bench-x-*.json")
    assert [r.instance for r in load_bench(saved)] == list(BKS)


def test_bench_x_cli_refuses_exact_solvers_on_x_sizes(capsys):
    with pytest.raises(SystemExit):
        main(["bench-x", "--dir", str(DATA), "--solvers", "bnb"])
