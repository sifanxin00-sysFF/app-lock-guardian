package com.codex.applockguard.util;

public final class ProtectionStatusSnapshot {
    public final String code;
    public final String title;
    public final String detail;
    public final boolean blockReady;

    public ProtectionStatusSnapshot(String code, String title, String detail, boolean blockReady) {
        this.code = code;
        this.title = title;
        this.detail = detail;
        this.blockReady = blockReady;
    }
}
