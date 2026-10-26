package com.agentscope.brain.identity.internal.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class AuthResponses {
    private AuthResponses() { }
    public static void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}
