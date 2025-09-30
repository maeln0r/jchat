package dev.jchat.identity.events;

import dev.jchat.identity.kafka.Topic;

import java.time.Instant;
import java.util.UUID;

public record UserDeactivatedEvent(
        String type,
        Instant occurredAt,
        String traceId,
        UUID userId
) implements DomainEvent {
    public static UserDeactivatedEvent of(String traceId, UUID userId) {
        return new UserDeactivatedEvent(Topic.USER_DEACTIVATED.value(), Instant.now(), traceId, userId);
    }
}