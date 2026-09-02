package com.echoreplay.util;

/**
 * Helpers for Minecraft tick math. Paper mostly runs at 20 TPS but we keep the
 * math in one place: 50 ms per tick.
 */
public final class Tick {

    private Tick() {}

    public static final long MILLIS_PER_TICK = 50L;
}
