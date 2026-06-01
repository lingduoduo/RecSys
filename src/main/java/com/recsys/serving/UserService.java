package com.recsys.serving;

import com.recsys.infrastructure.DataManager;
import com.recsys.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class UserService extends BaseApiServlet {

    private final DataManager dataManager;

    public UserService(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepareJson(response);
        try {
            int userId = requiredIntParam(request, "userId");
            User user = dataManager.getUserById(userId);

            if (user == null) {
                writeError(response, HttpServletResponse.SC_NOT_FOUND, "user not found", "userId", userId);
                return;
            }

            writeJson(response, HttpServletResponse.SC_OK, user);

        } catch (BadRequestException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in UserService", e);
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal server error");
        }
    }
}
