package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.model.TimelineEvent;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe buffer between Bukkit/PacketEvents listeners (their threads) and
 * the async IO writer. The IO drain loop pulls batches and writes them.
 */
public final class EventSink {

    private final ConcurrentLinkedQueue<TimelineEvent> queue = new ConcurrentLinkedQueue<>();
    private volatile boolean closed = false;

    public void add(TimelineEvent e) {
        if (!closed) {
            queue.add(e);
        }
    }

    public TimelineEvent poll() {
        return queue.poll();
    }

    public java.util.List<TimelineEvent> drain(int max) {
        java.util.List<TimelineEvent> out = new java.util.ArrayList<>(Math.min(max, 256));
        TimelineEvent e;
        while (out.size() < max && (e = queue.poll()) != null) {
            out.add(e);
        }
        return out;
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int size() {
        return queue.size();
    }

    public void close() {
        closed = true;
    }
}
