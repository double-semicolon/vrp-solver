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
import math
import sys
from pathlib import Path

from conftest import CASES, TESTDATA

from vrp.instances import TOY5, random_instance
from vrp.model import Instance
from vrp.result import Improvement, SolveResult
from vrp.serialize import load_run, save_run
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
def _stored_random(n: int, seed: int, layout: str) -> Instance:
    """
    ランダムインスタンスは、最初に作ったときの座標をファイルに残し、以後はそれを正とする。

    clustered は random.gauss (中で libm の log・cos を使う) で座標を作るので、OS によって
    末尾の桁が変わる。実際に Windows と Linux (CI) で座標が 1 ulp 違った。作り直すたびに
    座標が変わると、Java と突き合わせる基準が OS ごとに揺れる。
    同じインスタンスであること (末尾の桁以外) だけは、ここで確かめる。
    """
    inst = random_instance(n, seed=seed, layout=layout)
    path = RUNS_DIR / f"{inst.name}.json"
    if not path.exists():
        return inst
    stored, _ = load_run(path)
    same = (
        (stored.name, stored.demands, stored.capacity, stored.num_vehicles)
        == (inst.name, inst.demands, inst.capacity, inst.num_vehicles)
        and len(stored.coords) == len(inst.coords)
        and all(math.isclose(a, b, rel_tol=1e-12, abs_tol=1e-12)
                for p, q in zip(stored.coords, inst.coords) for a, b in zip(p, q))
    )
    if not same:
        raise ValueError(f"{path.name} の座標が random_instance の出力と違う (生成器を変えたなら消して作り直す)")
    return stored


# 2-opt で縮む距離が同じ手が2つある小さい例。最近傍法のルート [3, 1, 2, 4, 5, 6] で
# (i, j) = (3, 5) と (4, 5) がどちらも 1 縮む。i の小さい (3, 5) を選ぶと [3, 1, 2, 6, 5, 4]、
# (4, 5) を選ぶと [3, 1, 2, 4, 6, 5] になる。同点の決め方が言語で違えば解が変わる
TWO_OPT_TIE = Instance(
    name="two-opt-tie",
    coords=[(5, 5), (1, 5), (0, 8), (4, 2), (3, 7), (5, 9), (4, 10)],
    demands=[0, 1, 1, 1, 1, 1, 1],
    capacity=6,
    num_vehicles=1,
)

# ランダムインスタンスのうち 2-opt の突き合わせに使うもの。顧客 50 件の 10 件は Python の
# テスト (conftest.RANDOM_50) と同じ。顧客 200 件の 2 件は 2-opt の同点が多い (8 回と 6 回)
RANDOM_SPECS = [(30, 0, "uniform"), (30, 1, "clustered")]
RANDOM_SPECS += [(50, s, lay) for s in range(5) for lay in ("uniform", "clustered")]
RANDOM_SPECS += [(200, 1, "uniform"), (200, 1, "clustered")]

RUN_SPECS: list[tuple[Instance, list[str]]] = [
    (CASES["toy8"].inst, ALL),
    (CASES["toy5-unlimited"].inst, ALL),
    (CASES["nn-over"].inst, ALL),
    (TWO_OPT_TIE, HEURISTICS),
    *[(_stored_random(n, seed=s, layout=lay), HEURISTICS) for n, s, lay in RANDOM_SPECS],
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
    # ランダムは小数の座標の代表として顧客 30 件の 2 件だけ。全部書くと顧客 200 件で
    # 1 件 200 KB 近くになる。2-opt はほぼ全ての組の距離を見るので、距離がずれていれば
    # 解の突き合わせ (Java の SameAsPythonTest) で落ちる
    out += [(inst.name, inst.coords) for inst, _ in RUN_SPECS
            if inst.name in ("rand30-uniform-s0", "rand30-clustered-s1")]
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
    # 数の配列は許す (1 段だけ)。要素が数でないもの、入れ子、オブジェクトは拒否
    "nested-stat": (_set("results.0.stats.nodes", [[1]]), "results[0].stats.nodes[0]: "),
    "object-stat": (_set("results.0.stats.nodes", {"a": 1}), "results[0].stats.nodes: "),
    "bool-in-stat-array": (_set("results.0.stats.nodes", [1, True]), "results[0].stats.nodes[1]: "),
    "null-in-stat-array": (_set("results.0.stats.nodes", [None]), "results[0].stats.nodes[0]: "),
    "string-in-stat-array": (_set("results.0.stats.nodes", ["1"]), "results[0].stats.nodes[0]: "),
    "int64-overflow-in-stat-array": (_set("results.0.stats.nodes", [2 ** 63]), "results[0].stats.nodes[0]: "),
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


def without_time(results: list[SolveResult]) -> list[dict]:
    """経過時間は実行のたびに変わるので、比較から外した形にする。"""
    out = []
    for r in results:
        d = {k: v for k, v in vars(r).items() if k != "elapsed_sec"}
        d["history"] = [(h.cost, h.iteration) for h in r.history]
        out.append(d)
    return out


def main() -> None:
    for name, (inst, results) in build_runs().items():
        path = RUNS_DIR / f"{name}.json"
        if path.exists():
            old_inst, old = load_run(path)
            # 経過時間しか違わないなら書き直さない (作り直すたびに差分が出ないように)
            if old_inst == inst and without_time(old) == without_time(results):
                continue
        print(save_run(path, inst, results))
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
    for path, text in cvrplib_parse_files().items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8", newline="\n")
        print(path)


# ---------------------------------------------------------------- CVRPLib のローダの異常系
#
# Python (cvrplib.py) と Java (Cvrplib.java) が同じファイルを同じ理由で拒否することを
# 確かめるための、手書きの小さい .vrp / .sol と、それを1か所ずつ壊したもの。

PARSE_DIR = TESTDATA / "cvrplib" / "parse"

TINY_VRP = """\
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

TINY_SOL = "Route #1: 1 2\nRoute #2: 3\nCost 42\n"


def _vrp(old: str, new: str) -> str:
    assert TINY_VRP.count(old) == 1, old
    return TINY_VRP.replace(old, new)


def _sol(old: str, new: str) -> str:
    assert TINY_SOL.count(old) == 1, old
    return TINY_SOL.replace(old, new)


# ファイル名 -> (中身, エラーの文言に含まれるべき文字列)。文言は両言語で同じ
CVRPLIB_INVALID: dict[str, tuple[str, str]] = {
    # 未対応の形式
    "geo.vrp": (_vrp("EUC_2D", "GEO"), "EDGE_WEIGHT_TYPE"),                # 距離の定義が違う
    "tsp.vrp": (_vrp("TYPE : CVRP", "TYPE : TSP"), "TYPE"),
    "unknown-key.vrp": (_vrp("COMMENT : hand written", "DISTANCE : 50"), "DISTANCE"),   # 知らない制約
    "unknown-section.vrp": (_vrp("DEPOT_SECTION", "TIME_WINDOW_SECTION"), "セクション"),
    "depot-not-1.vrp": (_vrp("DEPOT_SECTION\n1\n", "DEPOT_SECTION\n2\n"), "depot"),
    "two-depots.vrp": (_vrp("DEPOT_SECTION\n1\n", "DEPOT_SECTION\n1\n2\n"), "depot"),
    # 壊れたファイル
    "no-terminator.vrp": (_vrp("1\n-1\n", "1\n"), "-1"),
    "missing-row.vrp": (_vrp("3 0 2\n", ""), "NODE_COORD_SECTION"),       # 行が欠けている
    "out-of-order.vrp": (_vrp("2 3 4\n3 0 2\n", "3 0 2\n2 3 4\n"), "番号"),  # 番号が順番どおりでない
    "extra-column.vrp": (_vrp("2 4\n", "2 4 1\n"), "列数"),
    "no-capacity.vrp": (_vrp("CAPACITY : 10\n", ""), "CAPACITY"),
    "header-in-section.vrp": (_vrp("3 0 2\n", "3 0 2\nCAPACITY : 10\n"), "セクションの途中"),
    "line-before-section.vrp": (_vrp("CAPACITY : 10\n", "CAPACITY : 10\n7 7\n"), "解釈できない行"),
    # 数の形。標準の int() / float() / parseInt / parseDouble は言語ごとに緩さが違う
    "underscore-in-int.vrp": (_vrp("CAPACITY : 10", "CAPACITY : 1_0"), "CAPACITY が整数でない"),   # Python の int は読む
    "fullwidth-digit.vrp": (_vrp("3 5\n", "3 ５\n"), "需要 が整数でない"),                # 両方の標準関数が読む
    "nan-coord.vrp": (_vrp("3 0 2\n", "3 nan 2\n"), "座標 が数でない"),                  # 両方の標準関数が読む
    "hex-coord.vrp": (_vrp("3 0 2\n", "3 0x1p1 2\n"), "座標 が数でない"),                # Java の parseDouble は読む
    "suffixed-coord.vrp": (_vrp("3 0 2\n", "3 0d 2\n"), "座標 が数でない"),              # Java の parseDouble は読む
    "overflow-coord.vrp": (_vrp("3 0 2\n", "3 1e400 2\n"), "座標 が有限の数でない"),     # 読むと無限大になる
    # .sol
    "no-cost.sol": (_sol("Cost 42\n", ""), "Cost"),
    "gap-in-numbering.sol": (_sol("Route #2", "Route #3"), "連続"),
    "empty-route.sol": (_sol("Route #2: 3", "Route #2:"), "空"),
    "unknown-line.sol": (TINY_SOL + "Time 1.5\n", "解釈できない"),
    "two-costs.sol": (TINY_SOL + "Cost 43\n", "解釈できない"),
    "no-routes.sol": ("Cost 42\n", "ルート"),
    "fractional-cost.sol": (_sol("Cost 42", "Cost 42.5"), "整数"),     # 切り捨てて 42 にしない
    "exponent-cost.sol": (_sol("Cost 42", "Cost 4e1"), "整数"),
    "fullwidth-customer.sol": (_sol("Route #2: 3", "Route #2: ３"), "顧客番号 が整数でない"),
}


def cvrplib_parse_files() -> dict[Path, str]:
    files = {PARSE_DIR / "valid.vrp": TINY_VRP, PARSE_DIR / "valid.sol": TINY_SOL}
    for name, (text, _) in CVRPLIB_INVALID.items():
        files[PARSE_DIR / "invalid" / name] = text
    # Java の core は JSON を読めないので、理由の表はタブ区切りにする
    files[PARSE_DIR / "invalid.tsv"] = "".join(
        f"{name}\t{why}\n" for name, (_, why) in sorted(CVRPLIB_INVALID.items()))
    return files


if __name__ == "__main__":
    sys.exit(main())
