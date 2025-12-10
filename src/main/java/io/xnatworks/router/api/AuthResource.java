/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.config.AppConfig;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API for authentication.
 */
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {
    private static final Logger log = LoggerFactory.getLogger(AuthResource.class);

    private final AppConfig config;

    public AuthResource(AppConfig config) {
        this.config = config;
    }

    /**
     * Login endpoint - validates credentials and returns a token.
     */
    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response login(Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        if (username == null || password == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Username and password required"))
                    .build();
        }

        if (config.getAdminUsername().equals(username) &&
                config.getAdminPassword().equals(password)) {

            // Generate token
            String token = Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8)
            );

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("token", token);
            response.put("username", username);

            log.info("User '{}' logged in", username);
            return Response.ok(response).build();
        }

        log.warn("Failed login attempt for user '{}'", username);
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "Invalid credentials"))
                .build();
    }

    /**
     * Check if current token/session is valid.
     */
    @GET
    @Path("/check")
    public Response checkAuth(@HeaderParam("X-Auth-Token") String token) {
        if (!config.isAuthRequired()) {
            return Response.ok(Map.of(
                    "authenticated", true,
                    "authRequired", false,
                    "username", "anonymous"
            )).build();
        }

        if (token != null) {
            String expectedToken = Base64.getEncoder().encodeToString(
                    (config.getAdminUsername() + ":" + config.getAdminPassword())
                            .getBytes(StandardCharsets.UTF_8)
            );
            if (expectedToken.equals(token)) {
                return Response.ok(Map.of(
                        "authenticated", true,
                        "authRequired", true,
                        "username", config.getAdminUsername()
                )).build();
            }
        }

        return Response.ok(Map.of(
                "authenticated", false,
                "authRequired", true
        )).build();
    }

    /**
     * Logout - invalidate token (client-side).
     */
    @POST
    @Path("/logout")
    public Response logout() {
        // Since we're using simple tokens, logout is handled client-side
        // Just return success
        return Response.ok(Map.of("success", true)).build();
    }

    /**
     * Get authentication configuration.
     */
    @GET
    @Path("/config")
    public Response getAuthConfig() {
        return Response.ok(Map.of(
                "authRequired", config.isAuthRequired()
        )).build();
    }
}
