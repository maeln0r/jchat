package dev.jchat.identity.events;

import dev.jchat.identity.kafka.Topic;

import java.time.Instant;
import java.util.UUID;

public record UserUpdatedEvent(
        String type,
        Instant occurredAt,
        String traceId,
        UUID userId
) implements DomainEvent {
    public static UserUpdatedEvent of(String traceId, UUID userId) {
        return new UserUpdatedEvent(Topic.USER_UPDATED.value(), Instant.now(), traceId, userId);
    }
}