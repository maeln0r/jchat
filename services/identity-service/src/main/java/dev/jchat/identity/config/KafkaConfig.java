package dev.jchat.identity.config;

import dev.jchat.identity.kafka.Topic;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaConfig {
    @Bean
    NewTopic userCreated() {
        return new NewTopic(Topic.USER_CREATED.value(), 3, (short) 1);
    }

    @Bean
    NewTopic userUpdated() {
        return new NewTopic(Topic.USER_UPDATED.value(), 3, (short) 1);
    }

    @Bean
    NewTopic userRoles() {
        return new NewTopic(Topic.USER_ROLES_CHANGED.value(), 3, (short) 1);
    }

    @Bean
    NewTopic userDeactivated() {
        return new NewTopic(Topic.USER_DEACTIVATED.value(), 3, (short) 1);
    }
}