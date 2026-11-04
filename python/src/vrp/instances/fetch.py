"""
CVRPLib から X set (100 インスタンスと BKS) を取得する。

X set 全体はリポジトリに含めない (テスト用の 2 件だけ tests/data に置いてある)。
ベンチマークの前に一度だけこれで取得する。

公式の配布はセット単位の 7z なので、展開に py7zr を使う。py7zr は optional な
依存 (bench extra) で、ここでしか import しない。探索の core は無依存のまま。

展開は一時ディレクトリで行い、名前が X set のファイル名の形に合うものだけを
こちらで決めたファイル名でコピーする。アーカイブ内の名前をそのまま書き出し先に
使うと、"../" を含む名前で dest の外に書き込まれうる (パストラバーサル)。
"""

from __future__ import annotations

import re
import shutil
import tempfile
import urllib.request
from pathlib import Path

BASE_URL = "https://galgos.inf.puc-rio.br/cvrplib/en/download"
X_SET_ID = 17   # CVRPLib の一覧での X set の番号
URLS = {
    ".vrp": f"{BASE_URL}/instance-set/{X_SET_ID}",
    ".sol": f"{BASE_URL}/instance-set-bks/{X_SET_ID}",
}
_X_NAME = re.compile(r"X-n\d+-k\d+")


def fetch_xset(dest: str | Path) -> list[Path]:
    """X set の .vrp と .sol を dest に置き、置いたファイルの一覧を返す。"""
    dest = Path(dest)
    dest.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    with tempfile.TemporaryDirectory() as tmp:
        for suffix, url in URLS.items():
            archive = Path(tmp) / f"x{suffix}.7z"
            req = urllib.request.Request(url, headers={"User-Agent": "vrp-solver"})
            with urllib.request.urlopen(req, timeout=120) as resp, open(archive, "wb") as f:
                shutil.copyfileobj(resp, f)
            written += extract_7z(archive, dest, suffix)
    return written


def extract_7z(archive: str | Path, dest: str | Path, suffix: str) -> list[Path]:
    """archive から X set の名前の形をした *suffix ファイルだけを dest に取り出す。"""
    import py7zr   # optional な依存なので、使うときにだけ import する

    dest = Path(dest)
    written: list[Path] = []
    with tempfile.TemporaryDirectory() as tmp:
        with py7zr.SevenZipFile(archive) as z:
            z.extractall(path=tmp)
        for p in sorted(Path(tmp).rglob(f"*{suffix}")):
            if p.is_file() and _X_NAME.fullmatch(p.stem):
                target = dest / f"{p.stem}{suffix}"
                shutil.copyfile(p, target)
                written.append(target)
    if not written:
        raise ValueError(f"{Path(archive).name} に X set の {suffix} ファイルが無い")
    return written
