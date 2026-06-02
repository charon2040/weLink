package com.epsilon.welink.message.constant;

public final class MessageOutboxConstants {

    private MessageOutboxConstants() {
    }

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_PUBLISHED = 1;
    public static final int STATUS_FAILED = 2;
    public static final int STATUS_PUBLISHING = 3;
}
