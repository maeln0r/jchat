package dev.jchat.identity.repository;

import dev.jchat.identity.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByKcId(UUID kcId);

    Optional<UserEntity> findByEmailIgnoreCase(String email);
}