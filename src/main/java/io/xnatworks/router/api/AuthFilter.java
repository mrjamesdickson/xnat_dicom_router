/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.config.AppConfig;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Base64;

/**
 * Basic HTTP authentication filter for the admin API.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class AuthFilter implements ContainerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    private final AppConfig config;

    public AuthFilter(AppConfig config) {
        this.config = config;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        // Skip authentication if not required
        if (!config.isAuthRequired()) {
            return;
        }

        // Allow /api/auth/login without authentication
        String path = requestContext.getUriInfo().getPath();
        if (path.equals("auth/login") || path.equals("auth/check")) {
            return;
        }

        String authHeader = requestContext.getHeaderString("Authorization");

        // Check for Basic auth
        if (authHeader != null && authHeader.toLowerCase().startsWith("basic ")) {
            String credentials = authHeader.substring(6);
            try {
                String decoded = new String(Base64.getDecoder().decode(credentials), StandardCharsets.UTF_8);
                String[] parts = decoded.split(":", 2);
                if (parts.length == 2) {
                    String username = parts[0];
                    String password = parts[1];

                    if (isValidCredentials(username, password)) {
                        // Set security context
                        final String authenticatedUser = username;
                        requestContext.setSecurityContext(new SecurityContext() {
                            @Override
                            public Principal getUserPrincipal() {
                                return () -> authenticatedUser;
                            }

                            @Override
                            public boolean isUserInRole(String role) {
                                return "admin".equals(role);
                            }

                            @Override
                            public boolean isSecure() {
                                return requestContext.getSecurityContext().isSecure();
                            }

                            @Override
                            public String getAuthenticationScheme() {
                                return SecurityContext.BASIC_AUTH;
                            }
                        });
                        return;
                    }
                }
            } catch (IllegalArgumentException e) {
                log.debug("Invalid Base64 in Authorization header");
            }
        }

        // Check for session token (for browser-based UI)
        String sessionToken = requestContext.getHeaderString("X-Auth-Token");
        if (sessionToken != null && isValidToken(sessionToken)) {
            return;
        }

        // Authentication failed
        requestContext.abortWith(Response
                .status(Response.Status.UNAUTHORIZED)
                .header("WWW-Authenticate", "Basic realm=\"XNAT DICOM Router Admin\"")
                .entity("{\"error\": \"Authentication required\"}")
                .build());
    }

    private boolean isValidCredentials(String username, String password) {
        return config.getAdminUsername().equals(username) &&
                config.getAdminPassword().equals(password);
    }

    private boolean isValidToken(String token) {
        // For simplicity, use a hash of username:password as the token
        // In production, you'd want proper session management
        String expectedToken = Base64.getEncoder().encodeToString(
                (config.getAdminUsername() + ":" + config.getAdminPassword()).getBytes(StandardCharsets.UTF_8)
        );
        return expectedToken.equals(token);
    }

    /**
     * Generate a session token for the UI.
     */
    public String generateToken() {
        return Base64.getEncoder().encodeToString(
                (config.getAdminUsername() + ":" + config.getAdminPassword()).getBytes(StandardCharsets.UTF_8)
        );
    }
}
