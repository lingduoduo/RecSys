package com.recsys.online.store;

import java.util.List;

public interface TrendingStore {
    List<String> getTopKIds(String window, int k);
}
