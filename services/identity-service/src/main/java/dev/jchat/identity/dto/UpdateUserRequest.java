package dev.jchat.identity.dto;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record UpdateUserRequest(
        @NotBlank String displayName,
        UUID tenantId,
        Boolean active
) {
}