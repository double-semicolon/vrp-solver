# テスト用の CVRPLib インスタンス

ローダ (`vrp.instances.load_vrp`) のテストに使う小さいインスタンスだけを置く。
X set 全体はリポジトリに含めず、ベンチマーク時にダウンロードする。

- 出典: CVRPLib (https://galgos.inf.puc-rio.br/cvrplib/)
- X set: Uchoa, Pecin, Pessoa, Poggi, Subramanian, Vidal (2017),
  "New benchmark instances for the Capacitated Vehicle Routing Problem",
  European Journal of Operational Research 257(3), 845–858.

| ファイル | 顧客数 | 容量 | 総需要 |
|---|---|---|---|
| X-n101-k25.vrp | 100 | 206 | 5147 |
| X-n106-k14.vrp | 105 | 600 | 7864 |

総需要はファイルに書かれていないので、DEMAND_SECTION を awk で合計して求めた
(ローダとは別の方法で出した値をテストの期待値にするため)。
