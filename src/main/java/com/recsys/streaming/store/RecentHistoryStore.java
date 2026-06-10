package com.recsys.streaming.store;

import java.util.List;

public interface RecentHistoryStore {
    List<Integer> getRecentMovieIds(int userId, int limit);
}
