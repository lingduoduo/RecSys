package com.example;

import java.util.List;

public class SimilarMovieResponse {

    private int movieId;
    private List<Movie> similar;

    public SimilarMovieResponse(int movieId, List<Movie> similar) {
        this.movieId = movieId;
        this.similar = similar;
    }

    public int getMovieId() {
        return movieId;
    }

    public List<Movie> getSimilar() {
        return similar;
    }
}
