"""
CLI のテスト。

出力の文面は縛らない (変えるたびにテストが落ちるのは無意味)。
縛るのは「落ちずに動く」「数字が正しい」「保存したファイルが読み戻せる」
「危険な操作を止める」「core が matplotlib に依存しない」の5つ。
"""

from __future__ import annotations

import json
import subprocess
import sys

import pytest

from vrp.cli import _pick_solvers, build_parser, main
from vrp.serialize import load_run
from vrp.solvers import SOLVERS


def test_demo_runs(capsys):
    main(["demo"])
    out = capsys.readouterr().out
    assert "toy5" in out and "toy8" in out
    assert "303" in out, "toy8 の最適値が出ていない"


def test_no_subcommand_falls_back_to_demo(capsys):
    main([])
    assert "toy8" in capsys.readouterr().out


@pytest.mark.parametrize("solver", list(SOLVERS))
def test_solve_with_every_solver(solver: str, capsys):
    main(["solve", "--instance", "toy5", "--solver", solver])
    assert "246" in capsys.readouterr().out, f"{solver} が最適値に届いていない"


def test_compare_shows_the_improvement_of_2opt_on_50_customers(capsys):
    """Step 1 の目的。nn と nn+2opt の差が出ること。"""
    main(["compare", "--instance", "random", "-n", "50", "--seed", "1"])
    out = capsys.readouterr().out
    assert "rand50-uniform-s1" in out
    assert "nn+2opt" in out
    assert "+4.83%" in out, "nn (1432) は nn+2opt (1366) より 4.83% 長い"


def test_compare_on_a_large_instance_picks_only_the_heuristics(capsys):
    """厳密解法は自動的に外れる。--solvers を書かなくても固まらないこと。"""
    main(["compare", "--instance", "random", "-n", "60"])
    out = capsys.readouterr().out
    assert "nn+2opt" in out
    assert "bnb" not in out and "brute" not in out


def test_bench_averages_over_seeds(capsys):
    main(["bench", "-n", "30", "--seeds", "3", "--solvers", "nn", "nn+2opt"])
    out = capsys.readouterr().out
    assert "3 個" in out
    lines = {line.split()[0]: line for line in out.splitlines()[2:]}
    assert lines["nn+2opt"].split()[1] == "+0.00%", "2-opt は最近傍法の解より悪くならない"
    assert lines["nn"].split()[1].startswith("+")


@pytest.mark.parametrize("cmd", [
    ["compare", "--instance", "random", "-n", "60", "--solvers", "brute"],
    ["bench", "-n", "20", "--seeds", "1", "--solvers", "bnb"],
])
def test_exact_solver_on_a_large_instance_is_refused(cmd):
    """
    大きいインスタンスに厳密解法を掛けると事実上終わらない。止まったのか
    計算中なのか分からないまま待つことになるので、走らせる前に断る。
    """
    with pytest.raises(SystemExit, match="大きすぎる"):
        main(cmd)


def test_force_overrides_the_size_guard():
    """
    --force を付ければ断らない。

    目安を超えるサイズで実際に走らせると遅い (全探索 n=10 で 20 秒以上) ので、
    判定だけを直接確かめる。--force が判定まで届いていることは引数の解析で見る。
    """
    assert _pick_solvers(60, ["brute"], force=True) == ["brute"]
    assert build_parser().parse_args(["solve", "--solver", "brute", "--force"]).force


def test_unknown_instance_is_rejected():
    with pytest.raises(SystemExit, match="未知のインスタンス"):
        main(["solve", "--instance", "nonexistent"])


def test_parser_rejects_an_unknown_solver():
    with pytest.raises(SystemExit):
        build_parser().parse_args(["solve", "--solver", "nonexistent"])


def test_save_writes_a_loadable_run(tmp_path, capsys):
    main(["compare", "--instance", "toy5", "--solvers", "nn", "bnb",
          "--save", "--out-dir", str(tmp_path)])
    capsys.readouterr()

    (path,) = list(tmp_path.glob("*.json"))
    inst, results = load_run(path)
    assert inst.name == "toy5"
    assert [r.solver for r in results] == ["nn", "bnb"]
    assert json.loads(path.read_text(encoding="utf-8"))["format"] == "vrp-run/1"


def test_plot_writes_a_png(tmp_path, capsys):
    pytest.importorskip("matplotlib")
    main(["solve", "--instance", "toy8", "--solver", "nn",
          "--plot", "--out-dir", str(tmp_path)])
    capsys.readouterr()

    (path,) = list(tmp_path.glob("*.png"))
    assert path.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n"


def test_plot_subcommand_redraws_from_a_saved_run(tmp_path, capsys):
    """
    実行と作図が分離できていること。

    重い探索を回し直さずに図だけ描き直せるのが JSON を挟んでいる理由なので、
    その経路が通ることを確認する。
    """
    pytest.importorskip("matplotlib")
    main(["compare", "--instance", "toy5", "--solvers", "nn",
          "--save", "--out-dir", str(tmp_path)])
    (run,) = list(tmp_path.glob("*.json"))

    out = tmp_path / "redrawn.png"
    main(["plot", str(run), "--out", str(out)])
    capsys.readouterr()
    assert out.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n"


def test_core_does_not_import_matplotlib():
    """
    探索・JSON・CLI (作図しない経路) は matplotlib 無しで動くこと。

    同じプロセスでは他のテストが matplotlib を読み込み済みなので、別プロセスで確かめる。
    """
    code = (
        "import sys\n"
        "import vrp, vrp.cli, vrp.serialize, vrp.solvers, vrp.instances\n"
        "vrp.cli.main(['compare', '--instance', 'toy5', '--solvers', 'nn', 'nn+2opt'])\n"
        "assert 'matplotlib' not in sys.modules, 'core が matplotlib を読み込んでいる'\n"
    )
    proc = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True,
                          encoding="utf-8")
    assert proc.returncode == 0, proc.stderr
