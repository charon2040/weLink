package com.epsilon.welink.common;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Getter
public class DegradationManager {

    @Value("${welink.degradation.level:0}")
    private int currentLevel;

    @Value("${welink.degradation.read-receipt-enabled:true}")
    private boolean readReceiptEnabled;

    @Value("${welink.degradation.presence-broadcast-enabled:true}")
    private boolean presenceBroadcastEnabled;

    public boolean isDegraded(int level) {
        return currentLevel >= level;
    }

    public boolean isReadReceiptEnabled() {
        return readReceiptEnabled && !isDegraded(2);
    }

    public boolean isPresenceBroadcastEnabled() {
        return presenceBroadcastEnabled && !isDegraded(1);
    }

    public boolean isLargeGroupPushEnabled() {
        return !isDegraded(3);
    }

    public boolean isFileUploadEnabled() {
        return !isDegraded(4);
    }

    public boolean isTextMessageOnly() {
        return isDegraded(5);
    }

    public void setLevel(int level) {
        int oldLevel = this.currentLevel;
        this.currentLevel = Math.max(0, Math.min(5, level));
        log.warn("Degradation level changed: {} -> {}", oldLevel, this.currentLevel);
    }

    public String getLevelDescription() {
        return switch (currentLevel) {
            case 0 -> "NORMAL - All features enabled";
            case 1 -> "L1 - Presence broadcast disabled";
            case 2 -> "L2 - Read receipts disabled";
            case 3 -> "L3 - Large group push disabled (read-diffusion only)";
            case 4 -> "L4 - File/image upload disabled";
            case 5 -> "L5 - Text messages only, all auxiliary features disabled";
            default -> "UNKNOWN";
        };
    }
}
