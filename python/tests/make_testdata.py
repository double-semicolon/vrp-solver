"""
Java 版と突き合わせるための「Python が書いたファイル」を testdata/ に作り直す。

  uv run python tests/make_testdata.py

  testdata/runs/python/*.json   ソルバの実際の出力 (vrp-run/1)
  testdata/distances/*.json     座標と、Python で計算した距離行列 (vrp-dist/1)

test_testdata_files.py が、今のコードで作り直した内容とファイルが一致することを
確かめる (経過時間は実行のたびに変わるので除く)。ソルバや距離の定義を変えて
テストが落ちたら、意図した変更であることを確かめてからこのスクリプトを走らせる。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from conftest import CASES, TESTDATA

from vrp.instances import TOY5, random_instance
from vrp.model import Instance
from vrp.result import Improvement, SolveResult
from vrp.serialize import save_run
from vrp.solvers import SOLVERS

RUNS_DIR = TESTDATA / "runs" / "python"
DIST_DIR = TESTDATA / "distances"
DIST_FORMAT = "vrp-dist/1"

ALL = list(SOLVERS)
HEURISTICS = ["nn", "nn+2opt"]

# 突き合わせで見たいものが一通り入るように選ぶ:
#   toy8            全ソルバ。history・stats (null を含む) が揃う
#   toy5-unlimited  num_vehicles が null
#   nn-over         最近傍法と 2-opt が実行不可能な解を返す
#   rand30-*        小数の座標。距離の丸めが整数座標の toy より厳しく試される
RUN_SPECS: list[tuple[Instance, list[str]]] = [
    (CASES["toy8"].inst, ALL),
    (CASES["toy5-unlimited"].inst, ALL),
    (CASES["nn-over"].inst, ALL),
    (random_instance(30, seed=0, layout="uniform"), HEURISTICS),
    (random_instance(30, seed=1, layout="clustered"), HEURISTICS),
]


def handmade_results() -> list[SolveResult]:
    """
    ソルバが返さない形の解を手で作る。Java が feasible を計算し直して同じ答えに
    なるかを、違反の種類ごとに確かめるため。toy5 は K=2、容量 30、
    需要は 1..5 の順に 12, 9, 14, 11, 7。
    """
    def build(solver: str, sol: list[list[int]]) -> SolveResult:
        return SolveResult.build(TOY5, solver, sol, elapsed_sec=0.0)

    return [
        build("feasible", [[1, 2, 5], [3, 4]]),                  # 積載 28, 25
        build("capacity-over", [[1, 2, 3, 4, 5]]),               # 積載 53
        build("too-many-vehicles", [[1], [2], [3, 4], [5]]),     # 容量は守っている
        build("duplicate-and-missing", [[1, 1, 2], [3, 4]]),     # 1 が2回、5 が無い
        build("depot-in-route", [[1, 2, 5, 0], [3, 4]]),
        build("empty-route-is-a-vehicle", [[1, 2, 5], [], [3, 4]]),   # 3 台目になる
        SolveResult(
            instance=TOY5.name, solver="stats-of-every-type",
            solution=[[1, 2, 5], [3, 4]], cost=0, feasible=False, elapsed_sec=1e-07,
            # cost と feasible はわざと嘘にしてある。Java の読み込みは値をそのまま読み、
            # 計算し直しはしないこと (しているなら、ここで食い違いが出る)
            stats={"int": 12, "float": 0.1, "true": True, "null": None, "text": "日本語"},
            history=[Improvement(0.0, 100, 0), Improvement(2.5e-05, 90, None)],
        ),
    ]


HANDMADE = "toy5-handmade"


def build_runs() -> dict[str, tuple[Instance, list[SolveResult]]]:
    runs = {inst.name: (inst, [SOLVERS[s].run(inst) for s in solvers]) for inst, solvers in RUN_SPECS}
    runs[HANDMADE] = (TOY5, handmade_results())
    return runs


# 距離の丸めの境目。どれも Python と Java で同じ値になること
ROUNDING_EDGES = {
    "name": "rounding-edges",
    "coords": [
        [0, 0],
        [0.49999999999999994, 0],   # +0.5 で 1.0 になる。Math.round なら 0
        [1.5, 2],                   # ちょうど 2.5
        [0.1, 0.2],                 # 2進で表せない小数
        [123456.789, 98765.4321],   # 大きい座標
        [-3, -4],                   # 負の座標
    ],
}


def distance_sources() -> list[tuple[str, list]]:
    """距離行列を書き出す座標の一覧。ケース・ランダム・X set・丸めの境目。"""
    from vrp.instances import load_vrp

    out = [(c.inst.name, c.inst.coords) for c in CASES.values()]
    out += [(inst.name, inst.coords) for inst, _ in RUN_SPECS if inst.name not in CASES]
    for p in sorted((TESTDATA / "cvrplib").glob("*.vrp")):
        inst = load_vrp(p)
        out.append((inst.name, inst.coords))
    out.append((ROUNDING_EDGES["name"], ROUNDING_EDGES["coords"]))
    return out


def dist_text(name: str, coords: list) -> str:
    """行列は1行に1行ずつ書く (101x101 を1要素1行にすると1万行になる)。"""
    from vrp.model import build_distance_matrix

    D = build_distance_matrix([tuple(c) for c in coords])
    head = json.dumps({"format": DIST_FORMAT, "name": name,
                       "coords": [list(c) for c in coords]}, ensure_ascii=False, allow_nan=False)
    rows = ",\n".join("    " + json.dumps(r) for r in D)
    return head[:-1] + ',\n  "D": [\n' + rows + "\n  ]\n}\n"


INVALID_DIR = TESTDATA / "runs" / "invalid"


def _base_run() -> dict:
    """壊す前の正しいファイル。INVALID の各項目はこれを1か所だけ壊す。"""
    from vrp.serialize import FORMAT, instance_to_dict, result_to_dict

    res = SolveResult.build(TOY5, "base", [[1, 2, 5], [3, 4]], elapsed_sec=0.5,
                            stats={"nodes": 3}, history=[Improvement(0.25, 100, 1)])
    return {"format": FORMAT, "instance": instance_to_dict(TOY5), "results": [result_to_dict(res)]}


def _edit(f):
    def make() -> str:
        d = _base_run()
        f(d)
        # allow_nan=True (既定) なので NaN / Infinity がそのまま書かれる
        return json.dumps(d, ensure_ascii=False, indent=2) + "\n"
    return make


def _raw(old: str, new: str):
    def make() -> str:
        text = json.dumps(_base_run(), ensure_ascii=False, indent=2) + "\n"
        assert text.count(old) == 1, old
        return text.replace(old, new)
    return make


def _set(path: str, value):
    """'results.0.cost' のような道筋の値を置き換える。"""
    def f(d):
        *head, last = path.split(".")
        for k in head:
            d = d[int(k)] if isinstance(d, list) else d[k]
        if isinstance(d, list):
            d[int(last)] = value
        else:
            d[last] = value
    return _edit(f)


def _del(path: str):
    def f(d):
        *head, last = path.split(".")
        for k in head:
            d = d[int(k)] if isinstance(d, list) else d[k]
        del d[last]
    return _edit(f)


# 両方の言語が拒否しなければならないファイルと、エラーの文言に含まれるべき箇所
# (規則は testdata/README.md)。箇所は両言語で同じ書き方 ("results[0].cost: ...") に
# してあるので、同じ文字列で確かめられる。箇所まで見ないと、別の理由で拒否されたときにも
# 通ってしまう (capacity を true にすると、型の検査が無くても「1台に積めない顧客が
# 存在する」で拒否される)。None は JSON の構文の段階で拒否されるもので、文言は
# 各言語のパーサに任せる。
INVALID = {
    "nan": (_set("results.0.elapsed_sec", float("nan")), None),
    "infinity": (_set("instance.coords.1.0", float("inf")), None),
    "overflow-to-infinity": (_raw('"elapsed_sec": 0.5', '"elapsed_sec": 1e400'), "results[0].elapsed_sec: "),
    "duplicate-key": (_raw('"capacity": 30,', '"capacity": 30,\n    "capacity": 30,'), None),
    "unknown-key-in-instance": (_set("instance.note", "x"), "instance: "),
    "unknown-key-in-result": (_set("results.0.gap", 1.0), "results[0]: "),
    "unknown-key-at-top": (_set("version", 1), "run: "),
    "missing-key": (_del("results.0.history"), "results[0]: "),
    "fractional-int": (_set("results.0.cost", 3.7), "results[0].cost: "),
    "integral-float-for-int": (_set("results.0.cost", 100.0), "results[0].cost: "),
    "bool-for-int": (_set("results.0.cost", True), "results[0].cost: "),
    "string-for-int": (_set("instance.demands.1", "12"), "instance.demands[1]: "),
    "string-for-float": (_set("results.0.elapsed_sec", "0.5"), "results[0].elapsed_sec: "),
    "int-for-bool": (_set("results.0.feasible", 1), "results[0].feasible: "),
    "null-cost": (_set("results.0.cost", None), "results[0].cost: "),
    "null-capacity": (_set("instance.capacity", None), "instance.capacity: "),
    "int32-overflow": (_set("instance.num_vehicles", 2 ** 31), "instance.num_vehicles: "),
    "int64-overflow": (_set("results.0.cost", 2 ** 63), "results[0].cost: "),
    "nested-stat": (_set("results.0.stats.nodes", [1]), "results[0].stats.nodes: "),
    "float-iteration": (_set("results.0.history.0.iteration", 1.5), "results[0].history[0].iteration: "),
    "coord-with-three-values": (_set("instance.coords.1", [1, 2, 3]), "instance.coords[1]: "),
    "route-not-a-list": (_set("results.0.solution.1", 3), "results[0].solution[1]: "),
    "unknown-format": (_set("format", "vrp-run/0"), "未知の形式"),
    "not-an-object": (lambda: "[]\n", "未知の形式"),
    "trailing-garbage": (_raw('  ]\n}\n', '  ]\n}\n{}\n'), None),
}
INVALID_EXPECTED = TESTDATA / "runs" / "invalid.json"


def invalid_expected_text() -> str:
    """Java のテストが読む、ファイル名 -> 文言に含まれるべき箇所 の表。"""
    return json.dumps({name: where for name, (_, where) in sorted(INVALID.items())},
                      ensure_ascii=False, indent=2) + "\n"


def main() -> None:
    for name, (inst, results) in build_runs().items():
        print(save_run(RUNS_DIR / f"{name}.json", inst, results))
    INVALID_DIR.mkdir(parents=True, exist_ok=True)
    for name, (make, _) in INVALID.items():
        p = INVALID_DIR / f"{name}.json"
        p.write_text(make(), encoding="utf-8", newline="\n")
        print(p)
    INVALID_EXPECTED.write_text(invalid_expected_text(), encoding="utf-8", newline="\n")
    print(INVALID_EXPECTED)
    # 正しく読めることの対照。これが読めないなら、上の各ファイルは別の理由で落ちている
    p = INVALID_DIR.parent / "valid-base.json"
    p.write_text(_edit(lambda d: None)(), encoding="utf-8", newline="\n")
    print(p)
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    for name, coords in distance_sources():
        p = DIST_DIR / f"{name}.json"
        p.write_text(dist_text(name, coords), encoding="utf-8", newline="\n")
        print(p)


if __name__ == "__main__":
    sys.exit(main())
