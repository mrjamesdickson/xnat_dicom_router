/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Servlet that serves index.html for all SPA routes.
 * This enables client-side routing to work with browser refresh/direct navigation.
 */
public class SpaFallbackServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        String path = req.getRequestURI();

        // Skip API routes and static assets (but serve index.html for root and SPA routes)
        if (path.startsWith("/api/") ||
            (path.contains(".") && !path.equals("/"))) {  // Has file extension (js, css, png, etc.)
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // Serve index.html for SPA routes
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("META-INF/resources/admin/index.html")) {
            if (is == null) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, "index.html not found");
                return;
            }

            resp.setContentType("text/html");
            resp.setCharacterEncoding("UTF-8");

            try (OutputStream os = resp.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }
        }
    }
}
