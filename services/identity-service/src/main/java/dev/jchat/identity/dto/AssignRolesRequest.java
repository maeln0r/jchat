package dev.jchat.identity.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record AssignRolesRequest(
        @NotEmpty List<String> realmRoles,
        List<String> clientRoles,
        String clientId
) {
}