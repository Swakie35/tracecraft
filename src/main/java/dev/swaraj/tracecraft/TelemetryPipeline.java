package dev.swaraj.tracecraft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, lossy-on-overload async telemetry pipeline. */
public final class TelemetryPipeline implements AutoCloseable {
    public record Event(String service, long latencyMillis, boolean success) {
        public Event {
            if (service == null || !service.matches("[a-zA-Z0-9_-]{1,50}"))
                throw new IllegalArgumentException("service must be 1-50 letters/digits/_/-");
            if (latencyMillis < 0 || latencyMillis > 600_000)
                throw new IllegalArgumentException("latencyMillis out of bounds");
        }
    }
    public record Stats(long total, long failures, long p50Millis, long p95Millis, long p99Millis, int sampleCount) {}
    public record Snapshot(long accepted, long dropped, long processed, int pending, Map<String, Stats> services) {}

    private static final class RollingStats {
        long total,failures;
        final ArrayDeque<Long> latest = new ArrayDeque<>();
        synchronized void add(Event x, int window) {
            total++;
            if (!x.success()) failures++;
            latest.addLast(x.latencyMillis());
            while (latest.size() > window) latest.removeFirst();
        }
        synchronized Stats snapshot() {
            long[] sorted = latest.stream().mapToLong(Long::longValue).sorted().toArray();
            return new Stats(total,failures,percentile(sorted,.5),percentile(sorted,.95),percentile(sorted,.99),sorted.length);
        }
    }
    private static long percentile(long[] sorted, double p) {
        if(sorted.length==0) return 0;
        return sorted[Math.max(0, (int)Math.ceil(sorted.length*p)-1)];
    }

    private final ArrayBlockingQueue<Event> queue;
    private final Map<String,RollingStats> services = new ConcurrentHashMap<>();
    private final AtomicLong accepted = new AtomicLong(), dropped = new AtomicLong(),processed = new AtomicLong();
    private final Thread worker;
    private final int sampleWindow;
    private volatile boolean running=true;

    public TelemetryPipeline(int capacity, int sampleWindow) {
        if(capacity<1 || sampleWindow<1)throw new IllegalArgumentException("positive capacity and sampleWindow required");
        this.queue=new ArrayBlockingQueue<>(capacity);
        this.sampleWindow=sampleWindow;
        this.worker=Thread.ofVirtual().name("telemetry-consumer").start(this::consume);
    }
    public boolean submit(Event event) {
        if (!running) throw new IllegalStateException("closed");
        boolean ok=queue.offer(event);
        if(ok)accepted.incrementAndGet();else dropped.incrementAndGet();
        return ok;
    }
    private void consume() {
        try {
            while(running || !queue.isEmpty()) {
                Event event=queue.poll(100,java.util.concurrent.TimeUnit.MILLISECONDS);
                if(event==null)continue;
                services.computeIfAbsent(event.service(), k->new RollingStats()).add(event,sampleWindow);
                processed.incrementAndGet();
            }
        } catch (InterruptedException ex) {Thread.currentThread().interrupt();}
    }
    public Snapshot snapshot() {
        Map<String,Stats> report=new TreeMap<>();
        services.forEach((name,stats)->report.put(name,stats.snapshot()));
        return new Snapshot(accepted.get(),dropped.get(),processed.get(),queue.size(),report);
    }
    @Override public void close() {
        running=false;
        try {worker.join(1500);} catch(InterruptedException ex) {Thread.currentThread().interrupt();}
    }
}
