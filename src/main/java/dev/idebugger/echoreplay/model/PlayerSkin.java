package dev.idebugger.echoreplay.model;

/**
 * A player's skin (Mojang textures property). Both value and signature may be
 * stored; signature may be absent for legacy/offline profiles.
 */
public record PlayerSkin(String value, String signature) {
    public boolean hasValue() {
        return value != null && !value.isEmpty();
    }
}
