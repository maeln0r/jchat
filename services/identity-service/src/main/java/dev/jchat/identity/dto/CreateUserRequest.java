package dev.jchat.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record CreateUserRequest(
        @NotBlank(message = "error.validation.email_required")
        @Email(message = "error.validation.email_invalid")
        String email,
        @NotBlank String displayName,
        UUID tenantId,
        boolean sendInvite
) {
}