"""
CVRPLib のファイルを読む。.vrp (TSPLIB 形式) から Instance を、.sol から既知最良解 (BKS) を作る。

対応するのは X set が使う範囲だけ:
  TYPE: CVRP / EDGE_WEIGHT_TYPE: EUC_2D / depot は1つで、ノード番号 1。

範囲外のファイルは黙って読まずに ValueError にする。読めてしまうと、
距離の定義や制約が違う問題を解いたまま BKS と比べることになり、しかも気づけない。
知らないヘッダ (例: DISTANCE = ルート長の上限) も同じ理由で拒否する。
読み飛ばしてよいと分かっているのは COMMENT だけ。

距離は model.build_distance_matrix (EUC_2D の四捨五入) をそのまま使う。
ここで別に計算しないのは、丸めの規約を1か所にしか書かないため。

車両数は常に None (上限なし)。X set の規約は台数自由で、名前の k
(例: X-n101-k25 の 25) は上限ではない (⌈総需要 / 容量⌉ で決まる最小台数)。

depot が 1 番でないファイルは、並べ替えて読むのではなくエラーにする。
並べ替えると顧客番号がファイルとずれ、.sol の解と照合するときに対応表が要る。

数は正規表現で形を確かめてから変換する。int() / float() は 1_000 や全角数字、
nan を受け付け、Java の parseInt / parseDouble は全角数字や 0x1p4、NaN を受け付ける。
標準の関数に任せると、Python と Java (Cvrplib.java) で読めるファイルの範囲が食い違う。
"""

from __future__ import annotations

import math
import re
from pathlib import Path

from ..model import Instance, Solution

_SECTIONS = ("NODE_COORD_SECTION", "DEMAND_SECTION", "DEPOT_SECTION")
_KNOWN_KEYS = {"NAME", "COMMENT", "TYPE", "DIMENSION", "EDGE_WEIGHT_TYPE", "CAPACITY"}
# X set の .sol 100 件のうち 3 件は Cost を "20215.0" や "72383.000000" と書いている。
# EUC_2D のコストは整数なので、小数部がすべて 0 のときだけ受け付ける。
# float() で読んで int() にすると、端数のある値が黙って切り捨てられる。
_INTEGRAL = re.compile(r"([0-9]+)(?:\.0+)?")
_INT = re.compile(r"-?[0-9]+")
_REAL = re.compile(r"-?[0-9]+(?:\.[0-9]+)?(?:[eE][-+]?[0-9]+)?")


def load_vrp(path: str | Path) -> Instance:
    p = Path(path)
    try:
        return parse_vrp(p.read_text(encoding="utf-8"))
    except ValueError as e:
        raise ValueError(f"{p.name}: {e}") from e


def parse_vrp(text: str) -> Instance:
    header: dict[str, str] = {}
    rows: dict[str, list[list[str]]] = {s: [] for s in _SECTIONS}
    section: str | None = None

    for line in text.splitlines():      # CRLF のファイルもそのまま読める
        tokens = line.split()
        if not tokens:
            continue
        if tokens[0] == "EOF":
            break
        if tokens[0].endswith("_SECTION"):
            if tokens[0] not in rows:
                raise ValueError(f"未対応のセクション: {tokens[0]}")
            section = tokens[0]
            continue
        if ":" in line:
            # ヘッダはセクションより前にしか来ない。セクション内の ":" は壊れたファイル
            if section is not None:
                raise ValueError(f"セクションの途中にヘッダがある: {line.strip()!r}")
            key, _, value = line.partition(":")
            key = key.strip()
            if key not in _KNOWN_KEYS:
                raise ValueError(f"未対応のヘッダ: {key}")
            header[key] = value.strip()
            continue
        if section is None:
            raise ValueError(f"解釈できない行: {line.strip()!r}")
        rows[section].append(tokens)

    _expect(header, "TYPE", "CVRP")
    _expect(header, "EDGE_WEIGHT_TYPE", "EUC_2D")
    dimension = _int_field(header, "DIMENSION")
    capacity = _int_field(header, "CAPACITY")

    coords_rows = _numbered(rows["NODE_COORD_SECTION"], "NODE_COORD_SECTION", dimension, width=2)
    demand_rows = _numbered(rows["DEMAND_SECTION"], "DEMAND_SECTION", dimension, width=1)

    depots = [_int(t[0], "DEPOT_SECTION") for t in rows["DEPOT_SECTION"]]
    if not depots or depots[-1] != -1:
        raise ValueError("DEPOT_SECTION が -1 で終わっていない")
    if depots[:-1] != [1]:
        raise ValueError(f"depot はノード 1 の1つだけに対応: {depots[:-1]}")

    return Instance(
        name=header.get("NAME", ""),
        coords=[(_real(x, "座標"), _real(y, "座標")) for x, y in coords_rows],
        demands=[_int(d, "需要") for (d,) in demand_rows],
        capacity=capacity,
        num_vehicles=None,
    )


def load_sol(path: str | Path) -> tuple[Solution, int]:
    p = Path(path)
    try:
        return parse_sol(p.read_text(encoding="utf-8"))
    except ValueError as e:
        raise ValueError(f"{p.name}: {e}") from e


def parse_sol(text: str) -> tuple[Solution, int]:
    """
    「Route #i: 顧客...」の行と「Cost N」の行から (解, 記載コスト) を返す。

    顧客番号は depot を除いた 1..n で、Instance の添字とそのまま一致する
    (.vrp の 1 番が depot なので、.vrp の i+1 番 = .sol の i = 添字 i)。
    番号の範囲や重複はここでは見ない。それは validate の仕事で、
    ここで同じ検査を書くと、検査の誤りが二重になって打ち消し合いうる。

    記載コストは返すだけで信用しない。目的関数で計算し直した値と
    比べるのは呼び出し側 (テスト・ベンチマーク)。
    """
    routes: Solution = []
    cost: int | None = None
    for line in text.splitlines():
        tokens = line.split()
        if not tokens:
            continue
        if tokens[0] == "Route":
            head, _, body = line.partition(":")
            if head.split()[1:] != [f"#{len(routes) + 1}"]:
                raise ValueError(f"ルート番号が連続していない: {head.strip()!r}")
            route = [_int(t, "顧客番号") for t in body.split()]
            if not route:
                raise ValueError(f"空のルート: {head.strip()!r}")
            routes.append(route)
        elif tokens[0] == "Cost" and len(tokens) == 2 and cost is None:
            m = _INTEGRAL.fullmatch(tokens[1])
            if m is None:
                raise ValueError(f"Cost が整数でない: {tokens[1]}")
            cost = int(m.group(1))
        else:
            raise ValueError(f"解釈できない行: {line.strip()!r}")
    if not routes:
        raise ValueError("ルートが1本もない")
    if cost is None:
        raise ValueError("Cost の行がない")
    return routes, cost


# ---------------------------------------------------------------- 検査


def _expect(header: dict[str, str], key: str, value: str) -> None:
    if key not in header:
        raise ValueError(f"{key} がない")
    if header[key] != value:
        raise ValueError(f"未対応の {key}: {header[key]} ({value} のみ対応)")


def _int_field(header: dict[str, str], key: str) -> int:
    if key not in header:
        raise ValueError(f"{key} がない")
    return _int(header[key], key)


def _numbered(rows: list[list[str]], section: str, dimension: int, width: int) -> list[list[str]]:
    """
    「番号 値...」の行が 1..DIMENSION の順に過不足なく並んでいることを確かめ、値の部分を返す。

    番号を捨てて行の順番だけで読むと、行が欠けたファイルでも
    後ろのノードが1つずつずれたまま読めてしまう。
    """
    if len(rows) != dimension:
        raise ValueError(f"{section} が {len(rows)} 行 (DIMENSION は {dimension})")
    for i, r in enumerate(rows, start=1):
        if len(r) != width + 1:
            raise ValueError(f"{section} の列数が違う: {' '.join(r)}")
        if _int(r[0], section) != i:
            raise ValueError(f"{section} の番号が連続していない: {i} 番目の行が {r[0]}")
    return [r[1:] for r in rows]


def _int(token: str, what: str) -> int:
    if not _INT.fullmatch(token):
        raise ValueError(f"{what} が整数でない: {token!r}")
    return int(token)


def _real(token: str, what: str) -> float:
    if not _REAL.fullmatch(token):
        raise ValueError(f"{what} が数でない: {token!r}")
    x = float(token)
    # 1e400 は無限大になる。距離の計算まで進むと ValueError ではなく OverflowError になる
    if not math.isfinite(x):
        raise ValueError(f"{what} が有限の数でない: {token!r}")
    return x
