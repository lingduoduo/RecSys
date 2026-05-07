package com.recsys.streaming;

import java.util.List;

public interface RecentHistoryStore {
    List<Integer> getRecentMovieIds(int userId, int limit);
}
