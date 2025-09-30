package dev.jchat.identity.api;

import dev.jchat.identity.dto.AssignRolesRequest;
import dev.jchat.identity.dto.CreateUserRequest;
import dev.jchat.identity.dto.UpdateUserRequest;
import dev.jchat.identity.service.IdentityService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class IdentityController {
    private final IdentityService service;

    public IdentityController(IdentityService service) {
        this.service = service;
    }

    @PostMapping("/users")
    @PreAuthorize("hasRole('admin') or hasAuthority('SCOPE_manage')")
    public UUID create(@RequestBody @Valid CreateUserRequest req) {
        return service.createUser(req);
    }

    @PatchMapping("/users/{id}")
    @PreAuthorize("hasRole('admin') or hasAuthority('SCOPE_manage')")
    public void update(@PathVariable UUID id, @RequestBody @Valid UpdateUserRequest req) {
        service.updateUser(id, req);
    }

    @PostMapping("/users/{id}/roles")
    @PreAuthorize("hasRole('admin')")
    public void roles(@PathVariable UUID id, @RequestBody @Valid AssignRolesRequest req) {
        service.assignRoles(id, req);
    }

    @PostMapping("/users/{id}/deactivate")
    @PreAuthorize("hasRole('admin')")
    public void deactivate(@PathVariable UUID id) {
        service.deactivate(id);
    }
}