package dev.jchat.error;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String codeOrMessage) {
        super(codeOrMessage);
    }
}
