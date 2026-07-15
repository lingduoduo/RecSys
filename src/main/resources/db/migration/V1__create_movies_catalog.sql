CREATE TABLE movies (
    id BIGINT NOT NULL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    year INT NULL,
    genre VARCHAR(64) NULL,
    popularity_score DECIMAL(12,6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_movies_genre_popularity_id
        (genre, popularity_score DESC, id DESC)
);

CREATE INDEX idx_movies_popularity_id
    ON movies (popularity_score DESC, id DESC);
