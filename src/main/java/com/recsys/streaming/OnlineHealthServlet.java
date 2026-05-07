package com.recsys.streaming;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

public final class OnlineHealthServlet extends ApiServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepareJson(response);
        writeJson(response, HttpServletResponse.SC_OK, Map.of("ok", true, "service", "online-serving"));
    }
}
