# テスト用の CVRPLib インスタンス

ローダ (Python の `vrp.instances.load_vrp` / `load_sol` と、その Java 版) のテストに使う小さいインスタンスと、その既知最良解だけを置く。
X set 全体はリポジトリに含めず、ベンチマーク時にダウンロードする。

- 出典: CVRPLib (https://galgos.inf.puc-rio.br/cvrplib/)
- X set: Uchoa, Pecin, Pessoa, Poggi, Subramanian, Vidal (2017),
  "New benchmark instances for the Capacitated Vehicle Routing Problem",
  European Journal of Operational Research 257(3), 845–858.

| インスタンス | 顧客数 | 容量 | 総需要 | BKS のコスト | BKS のルート数 |
|---|---|---|---|---|---|
| X-n101-k25 | 100 | 206 | 5147 | 27591 | 26 |
| X-n106-k14 | 105 | 600 | 7864 | 26362 | 14 |

`.vrp` がインスタンス、`.sol` が既知最良解 (BKS)。BKS のコストは CVRPLib の一覧表の値。

総需要はファイルに書かれていないので、DEMAND_SECTION を awk で合計して求めた
(ローダとは別の方法で出した値をテストの期待値にするため)。
