package dev.jchat.identity.events;

import dev.jchat.identity.kafka.Topic;

import java.time.Instant;
import java.util.UUID;

public record UserRolesChangedEvent(
        String type,
        Instant occurredAt,
        String traceId,
        UUID userId
) implements DomainEvent {
    public static UserRolesChangedEvent of(String traceId, UUID userId) {
        return new UserRolesChangedEvent(Topic.USER_ROLES_CHANGED.value(), Instant.now(), traceId, userId);
    }
}