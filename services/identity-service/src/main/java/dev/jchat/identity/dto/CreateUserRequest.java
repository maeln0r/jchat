package dev.jchat.identity.dto;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record CreateUserRequest(
        @NotBlank String email,
        @NotBlank String displayName,
        UUID tenantId,
        boolean sendInvite
){}