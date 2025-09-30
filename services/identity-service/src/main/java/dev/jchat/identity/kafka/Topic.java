package dev.jchat.identity.kafka;

public enum Topic {
    USER_CREATED("user.created"),
    USER_UPDATED("user.updated"),
    USER_ROLES_CHANGED("user.roles.changed"),
    USER_DEACTIVATED("user.deactivated");

    private final String value;

    Topic(String v) {
        this.value = v;
    }

    public String value() {
        return value;
    }
}
