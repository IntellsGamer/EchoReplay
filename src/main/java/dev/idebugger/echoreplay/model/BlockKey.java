package dev.idebugger.echoreplay.model;

/**
 * A namespaced block state key, e.g. "minecraft:oak_stairs[half=bottom,facing=north]".
 * The string form comes from {@code BlockData.getAsString(true)} and round-trips 1:1.
 */
public record BlockKey(String stateString) {

    @Override
    public String toString() {
        return stateString;
    }
}
