/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;

/**
 * CORS filter to allow browser access to the REST API.
 */
@Provider
public class CorsFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext requestContext,
                       ContainerResponseContext responseContext) throws IOException {

        responseContext.getHeaders().add("Access-Control-Allow-Origin", "*");
        responseContext.getHeaders().add("Access-Control-Allow-Methods",
                "GET, POST, PUT, DELETE, OPTIONS, HEAD");
        responseContext.getHeaders().add("Access-Control-Allow-Headers",
                "Origin, Content-Type, Accept, Authorization, X-Requested-With");
        responseContext.getHeaders().add("Access-Control-Allow-Credentials", "true");
        responseContext.getHeaders().add("Access-Control-Max-Age", "86400");
    }
}
