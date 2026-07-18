package com.recsys.infrastructure.store;

import java.util.List;

public interface TrendingStore {
    List<String> getTopKIds(String window, int k);

    default List<String> getTopKIdsPrimary(String window, int k) {
        return getTopKIds(window, k);
    }
}
