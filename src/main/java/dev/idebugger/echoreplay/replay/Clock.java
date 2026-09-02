package dev.idebugger.echoreplay.replay;

/**
 * Media clock: converts wall-clock ticks into media time given a speed, with
 * pause support. Media time is monotonic non-decreasing while playing.
 */
public final class Clock {

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

    public void setSpeed(double speed) {
        this.speed = speed;
    }

    public double speed() {
        return speed;
    }

    public void pause() { paused = true; }
    public void resume() { paused = false; }
    public boolean paused() { return paused; }

    public double mediaTime() { return mediaTimeMs; }

    public void seekTo(double ms) {
        mediaTimeMs = Math.max(0, ms);
        remainder = 0;
    }
}
