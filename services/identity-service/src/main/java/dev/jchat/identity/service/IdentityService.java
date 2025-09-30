package dev.jchat.identity.service;

import dev.jchat.identity.entity.UserEntity;
import dev.jchat.identity.dto.AssignRolesRequest;
import dev.jchat.identity.dto.CreateUserRequest;
import dev.jchat.identity.dto.UpdateUserRequest;
import dev.jchat.identity.repository.UserRepository;
import dev.jchat.identity.events.*;
import dev.jchat.identity.util.Tracing;
import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.representations.idm.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.UUID;

@Service
public class IdentityService {
    private final Keycloak kc;
    private final UserRepository users;
    private final KafkaTemplate<String, Object> kafka;
    private final String realm;

    public IdentityService(Keycloak kc, UserRepository users, KafkaTemplate<String, Object> kafka,
                           @Value("${keycloak.realm}") String realm) {
        this.kc = kc;
        this.users = users;
        this.kafka = kafka;
        this.realm = realm;
    }

    @Transactional
    public UUID createUser(CreateUserRequest req) {
        // 1) KC user
        UserRepresentation u = new UserRepresentation();
        u.setUsername(req.email());
        u.setEmail(req.email());
        u.setEmailVerified(false);
        u.setEnabled(true);
        u.singleAttribute("display_name", req.displayName());

        try (Response resp = kc.realm(realm).users().create(u)) {
            int status = resp.getStatus();
            if (status < 200 || status >= 300) {
                String err = null;
                try {
                    err = resp.readEntity(String.class);
                } catch (Exception ignore) {
                }
                throw new IllegalStateException("KC create failed: " + status + (err != null ? " :: " + err : ""));
            }

            String kcId = CreatedResponseUtil.getCreatedId(resp);
            // 2) send verify email / required action (invite)
            if (req.sendInvite()) {
                kc.realm(realm).users().get(kcId).executeActionsEmail(List.of("VERIFY_EMAIL", "UPDATE_PASSWORD"));
            }

            // 3) our DB
            UUID id = UUID.randomUUID();
            var entity = UserEntity.builder()
                    .id(id)
                    .kcId(UUID.fromString(kcId))
                    .email(req.email())
                    .displayName(req.displayName())
                    .tenantId(req.tenantId())
                    .build();
            users.save(entity);

            // 4) event (базовый DTO с метаданными)
            var event = UserCreatedEvent.of(traceId(), id, UUID.fromString(kcId), req.email());
            sendToKafka(id, event);
            return id;
        }
    }

    @Transactional
    public void updateUser(UUID id, UpdateUserRequest req) {
        var e = users.findById(id).orElseThrow();
        e.setDisplayName(req.displayName());
        if (req.tenantId() != null) e.setTenantId(req.tenantId());
        if (req.active() != null) e.setActive(req.active());
        users.save(e);

        var kcUser = kc.realm(realm).users().get(e.getKcId().toString());
        UserRepresentation rep = kcUser.toRepresentation();
        rep.singleAttribute("display_name", req.displayName());
        if (Boolean.FALSE.equals(req.active())) rep.setEnabled(false);
        kcUser.update(rep);
        var event = UserUpdatedEvent.of(traceId(), id);
        sendToKafka(id, event);
    }

    public void assignRoles(UUID id, AssignRolesRequest req) {
        var e = users.findById(id).orElseThrow();
        var ures = kc.realm(realm).users().get(e.getKcId().toString());

        // realm roles
        var roles = kc.realm(realm).roles();
        List<RoleRepresentation> reps = req.realmRoles().stream().map(roles::get).map(RoleResource::toRepresentation).toList();
        ures.roles().realmLevel().add(reps);

        // client roles
        if (req.clientId() != null && req.clientRoles() != null && !req.clientRoles().isEmpty()) {
            var clients = kc.realm(realm).clients();
            var client = clients.findByClientId(req.clientId()).getFirst();
            var cres = clients.get(client.getId()).roles();
            List<RoleRepresentation> cr = req.clientRoles().stream().map(cres::get).map(RoleResource::toRepresentation).toList();
            ures.roles().clientLevel(client.getId()).add(cr);
        }
        var event = UserRolesChangedEvent.of(traceId(), id);
        sendToKafka(id, event);
    }

    public void deactivate(UUID id) {
        var e = users.findById(id).orElseThrow();
        kc.realm(realm).users().get(e.getKcId().toString()).logout();
        var rep = kc.realm(realm).users().get(e.getKcId().toString()).toRepresentation();
        rep.setEnabled(false);
        kc.realm(realm).users().get(e.getKcId().toString()).update(rep);
        e.setActive(false);
        users.save(e);
        var event = UserDeactivatedEvent.of(traceId(), id);
        sendToKafka(id, event);
    }

    private String traceId() {
        return Tracing.currentTraceId();
    }

    private void sendToKafka(UUID id, DomainEvent event) {
        kafka.send(event.type(), id.toString(), event);
    }
}