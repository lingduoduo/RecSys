package com.recsys.infrastructure.store;

import java.util.List;

public interface RecentHistoryStore {
    List<Integer> getRecentMovieIds(int userId, int limit);

    /** Primary/no-cache read used only after a consistency token is observed as applied. */
    default List<Integer> getRecentMovieIdsPrimary(int userId, int limit) {
        throw new UnsupportedOperationException("Primary recent-history reads are not supported");
    }
}
