package io.github.doublesemicolon.vrp;

import java.util.Arrays;
import java.util.List;

/**
 * 解の表現。ルート = 顧客の添字の列。depot (0) は前後に暗黙で付くので含めない。
 * ルートの数が使用車両数。
 *
 * <p>実行可能かどうかはここでは問わない (検査は {@link Objective#validate})。
 * 壊れた解も表現できないと、壊れていることを報告できない。
 */
public record Solution(List<List<Integer>> routes) {
    public Solution {
        // 渡されたリストを後で書き換えられても影響を受けないよう、変更不可のコピーにする
        routes = routes.stream().map(List::copyOf).toList();
    }

    public static Solution of(int[]... routes) {
        return new Solution(Arrays.stream(routes).map(r -> Arrays.stream(r).boxed().toList()).toList());
    }

    public int numRoutes() {
        return routes.size();
    }
}
