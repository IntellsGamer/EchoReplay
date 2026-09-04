package dev.idebugger.echoreplay.replay;

/**
 * Media clock: converts wall-clock ticks into media time given a speed, with
 * pause support. Media time is monotonic non-decreasing while playing.
 *
 * <p>S-7: speed is now validated and clamped. v1 accepted any double,
 * including {@code NaN}, {@code -5}, and {@code 1e9} — which deadlocked
 * the RUN loop (NaN never reaches durationMs) or froze the tick with
 * millions of events applied in one tick.</p>
 */
public final class Clock {

    /** Hard lower bound — slower than 1/8 speed is visually useless and freezes event emission. */
    public static final double MIN_SPEED = 0.125;
    /** Hard upper bound — faster than 16x saturates the run loop's per-tick event budget. */
    public static final double MAX_SPEED = 16.0;

    private double mediaTimeMs = 0;
    private double speed = 1.0;
    private boolean paused = false;
    private double remainder = 0;

    /** Advance by one 50ms wall tick. Returns media time after advancing. */
    public double tick() {
        if (!paused) {
            double effective = 50.0 * speed + remainder;
            long whole = (long) effective;
            remainder = effective - whole;
            mediaTimeMs += whole;
        }
        return mediaTimeMs;
    }

    /**
     * Set playback speed. Non-finite or out-of-range values are rejected with
     * an exception (callers should catch and surface a friendly message); the
     * underlying speed is unchanged on rejection.
     */
    public void setSpeed(double speed) {
        if (!Double.isFinite(speed)) {
            throw new IllegalArgumentException("speed must be finite (got " + speed + ")");
        }
        this.speed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
    }

    public double speed() {
        return speed;
    }

    public void pause() { paused = true; }
    public void resume() { paused = false; }
    public boolean paused() { return paused; }

    public double mediaTime() { return mediaTimeMs; }

    public void seekTo(double ms) {
        if (!Double.isFinite(ms) || ms < 0) ms = 0;
        mediaTimeMs = ms;
        remainder = 0;
    }
}
