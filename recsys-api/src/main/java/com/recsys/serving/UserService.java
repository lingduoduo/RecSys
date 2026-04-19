package com.recsys.serving;

import com.recsys.features.DataManager;
import com.recsys.models.User;
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
            String userIdStr = request.getParameter("userId");
            if (userIdStr == null || userIdStr.isBlank()) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "missing required query parameter: userId");
                return;
            }

            int userId = Integer.parseInt(userIdStr);
            User user = dataManager.getUserById(userId);

            if (user == null) {
                writeError(response, HttpServletResponse.SC_NOT_FOUND, "user not found", "userId", userId);
                return;
            }

            writeJson(response, HttpServletResponse.SC_OK, user);

        } catch (NumberFormatException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid userId format");
        } catch (Exception e) {
            e.printStackTrace();
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal server error");
        }
    }
}
