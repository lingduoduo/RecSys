package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class UserService extends HttpServlet {

    // Reuse ObjectMapper
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response) throws IOException {

        // Common headers
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Access-Control-Allow-Origin", "*");

        try {
            // 1. Get and validate userId
            String userIdStr = request.getParameter("userId");
            if (userIdStr == null || userIdStr.isBlank()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().println(
                        "{\"error\":\"missing required query parameter: userId\"}"
                );
                return;
            }

            int userId = Integer.parseInt(userIdStr);

            // 2. Fetch user from DataManager
            User user = DataManager.getInstance().getUserById(userId);

            if (user == null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                response.getWriter().println(
                        "{\"error\":\"user not found\",\"userId\":" + userId + "}"
                );
                return;
            }

            // 3. Serialize User -> JSON
            String jsonUser = MAPPER.writeValueAsString(user);

            // 4. Success response
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().println(jsonUser);

        } catch (NumberFormatException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().println(
                    "{\"error\":\"invalid userId format\"}"
            );
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().println(
                    "{\"error\":\"internal server error\"}"
            );
        }
    }
}
