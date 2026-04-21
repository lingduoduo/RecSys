package com.recsys.serving;

import com.recsys.features.DataManager;
import com.recsys.models.Movie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class MovieService extends BaseApiServlet {

    private final DataManager dataManager;

    public MovieService(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepareJson(response);
        try {
            int movieId = requiredIntParam(request, "id");
            Movie movie = dataManager.getMovieById(movieId);

            if (movie == null) {
                writeError(response, HttpServletResponse.SC_NOT_FOUND, "movie not found", "id", movieId);
                return;
            }

            writeJson(response, HttpServletResponse.SC_OK, movie);

        } catch (BadRequestException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal server error");
        }
    }
}
