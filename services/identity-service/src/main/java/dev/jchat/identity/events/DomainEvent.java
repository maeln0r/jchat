package dev.jchat.identity.events;

import java.time.Instant;

/**
 * Базовый контракт доменных событий для Kafka.
 */
public sealed interface DomainEvent permits UserCreatedEvent, UserUpdatedEvent, UserRolesChangedEvent, UserDeactivatedEvent {

    String type();

    Instant occurredAt();

    String traceId();

}