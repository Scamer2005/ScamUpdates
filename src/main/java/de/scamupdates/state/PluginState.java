package de.scamupdates.state;

public enum PluginState {
    STARTING,
    ACTIVE,
    ACTIVE_OFFLINE,
    DISABLED_MISSING_CONFIG,
    DISABLED_NO_INTERNET,
    DISABLED_CRAFTY_UNAVAILABLE,
    DISABLED_CRAFTY_AUTH_FAILED,
    DISABLED_CONFIGURATION_ERROR;

    public boolean isOperational() {
        return this == ACTIVE || this == ACTIVE_OFFLINE;
    }

    public boolean isOnline() {
        return this == ACTIVE;
    }

    public boolean isDisabled() {
        return this != STARTING && !isOperational();
    }
}
