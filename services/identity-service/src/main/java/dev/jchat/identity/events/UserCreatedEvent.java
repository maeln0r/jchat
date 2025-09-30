package dev.jchat.identity.events;

import dev.jchat.identity.kafka.Topic;

import java.time.Instant;
import java.util.UUID;

public record UserCreatedEvent(
        String type,
        Instant occurredAt,
        String traceId,
        UUID userId,
        UUID kcId,
        String email
) implements DomainEvent {
    public static UserCreatedEvent of(String traceId, UUID userId, UUID kcId, String email) {
        return new UserCreatedEvent(Topic.USER_CREATED.value(), Instant.now(), traceId, userId, kcId, email);
    }
}