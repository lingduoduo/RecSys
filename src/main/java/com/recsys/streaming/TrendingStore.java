package com.recsys.streaming;

import java.util.List;

public interface TrendingStore {
    List<String> getTopKIds(String window, int k);
}
