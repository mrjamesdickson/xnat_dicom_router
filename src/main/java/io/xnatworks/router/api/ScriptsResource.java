/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.anon.ScriptLibrary;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.*;

/**
 * REST API for anonymization script management.
 */
@Path("/scripts")
@Produces(MediaType.APPLICATION_JSON)
public class ScriptsResource {

    private final ScriptLibrary scriptLibrary;

    public ScriptsResource(ScriptLibrary scriptLibrary) {
        this.scriptLibrary = scriptLibrary;
    }

    @GET
    public Response listScripts() {
        List<Map<String, Object>> scripts = new ArrayList<>();

        for (ScriptLibrary.ScriptEntry entry : scriptLibrary.listScripts()) {
            scripts.add(scriptToMap(entry, false));
        }

        return Response.ok(scripts).build();
    }

    @GET
    @Path("/{name}")
    public Response getScript(@PathParam("name") String name) {
        ScriptLibrary.ScriptEntry entry = scriptLibrary.getScript(name);
        if (entry == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Script not found: " + name))
                    .build();
        }

        return Response.ok(scriptToMap(entry, true)).build();
    }

    @GET
    @Path("/{name}/content")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getScriptContent(@PathParam("name") String name) {
        try {
            String content = scriptLibrary.getScriptContent(name);
            return Response.ok(content).build();
        } catch (Exception e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Script not found: " + name)
                    .build();
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createScript(Map<String, Object> request) {
        String name = (String) request.get("name");
        String content = (String) request.get("content");
        String description = (String) request.get("description");

        if (name == null || name.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Script name is required"))
                    .build();
        }

        if (content == null || content.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Script content is required"))
                    .build();
        }

        // Check if already exists
        if (scriptLibrary.getScript(name) != null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Script already exists: " + name))
                    .build();
        }

        try {
            ScriptLibrary.ScriptEntry entry = scriptLibrary.addCustomScript(name, description, content);
            return Response.status(Response.Status.CREATED)
                    .entity(scriptToMap(entry, false))
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to create script: " + e.getMessage()))
                    .build();
        }
    }

    @PUT
    @Path("/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateScript(@PathParam("name") String name, Map<String, Object> request) {
        ScriptLibrary.ScriptEntry existing = scriptLibrary.getScript(name);
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Script not found: " + name))
                    .build();
        }

        if (existing.isBuiltIn()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Cannot modify built-in script: " + name))
                    .build();
        }

        String content = (String) request.get("content");
        String description = (String) request.get("description");

        if (content == null || content.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Script content is required"))
                    .build();
        }

        try {
            ScriptLibrary.ScriptEntry entry = scriptLibrary.updateScript(name, description, content);
            return Response.ok(scriptToMap(entry, false)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to update script: " + e.getMessage()))
                    .build();
        }
    }

    @DELETE
    @Path("/{name}")
    public Response deleteScript(@PathParam("name") String name) {
        ScriptLibrary.ScriptEntry existing = scriptLibrary.getScript(name);
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Script not found: " + name))
                    .build();
        }

        if (existing.isBuiltIn()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Cannot delete built-in script: " + name))
                    .build();
        }

        try {
            scriptLibrary.deleteScript(name);
            return Response.noContent().build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to delete script: " + e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/builtin")
    public Response getBuiltInScripts() {
        List<Map<String, Object>> scripts = new ArrayList<>();

        for (ScriptLibrary.ScriptEntry entry : scriptLibrary.listScripts()) {
            if (entry.isBuiltIn()) {
                scripts.add(scriptToMap(entry, false));
            }
        }

        return Response.ok(scripts).build();
    }

    @GET
    @Path("/custom")
    public Response getCustomScripts() {
        List<Map<String, Object>> scripts = new ArrayList<>();

        for (ScriptLibrary.ScriptEntry entry : scriptLibrary.listScripts()) {
            if (!entry.isBuiltIn()) {
                scripts.add(scriptToMap(entry, false));
            }
        }

        return Response.ok(scripts).build();
    }

    @POST
    @Path("/validate")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response validateScript(String content) {
        try {
            ScriptLibrary.ValidationResult result = scriptLibrary.validateScript(content);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("valid", result.isValid());
            response.put("errors", result.getErrors());
            response.put("warnings", result.getWarnings());
            response.put("tagCount", result.getTagCount());

            return Response.ok(response).build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("valid", false, "errors", List.of(e.getMessage())))
                    .build();
        }
    }

    private Map<String, Object> scriptToMap(ScriptLibrary.ScriptEntry entry, boolean includeContent) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", entry.getName());
        map.put("description", entry.getDescription());
        map.put("builtIn", entry.isBuiltIn());
        map.put("source", entry.getSource());
        map.put("createdAt", entry.getCreatedAt() != null ? entry.getCreatedAt().toString() : null);
        map.put("modifiedAt", entry.getModifiedAt() != null ? entry.getModifiedAt().toString() : null);

        if (includeContent) {
            try {
                map.put("content", scriptLibrary.getScriptContent(entry.getName()));
            } catch (Exception e) {
                map.put("content", null);
                map.put("contentError", e.getMessage());
            }
        }

        return map;
    }
}
