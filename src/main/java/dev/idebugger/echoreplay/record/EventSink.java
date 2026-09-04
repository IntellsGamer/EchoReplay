package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.model.TimelineEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe buffer between Bukkit/PacketEvents listeners (their threads) and
 * the async IO writer. Events are buffered in memory; the periodic checkpoint
 * flush and the final save pull them out via {@link #drainAll()}.
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

    /** Take ownership of every buffered event (checkpoint flush / final save). */
    public List<TimelineEvent> drainAll() {
        List<TimelineEvent> out = new ArrayList<>();
        TimelineEvent e;
        while ((e = queue.poll()) != null) {
            out.add(e);
        }
        return out;
    }

    public void close() {
        closed = true;
    }
}
