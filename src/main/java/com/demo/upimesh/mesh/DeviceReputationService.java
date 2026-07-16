package com.demo.upimesh.mesh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Basic reputation/trust scoring per mesh device, derived purely from
 * observed forwarding behavior — no central authority vouches for a device,
 * its score is earned (or lost) by what the relay-receipt evidence shows it
 * actually did.
 *
 *   - Every time a device forwards a packet (has a valid outbound
 *     RelayReceipt), its forward count goes up.
 *   - Every time RelayReceiptLedger's black-hole sweep flags a device as
 *     having swallowed a packet it should have forwarded, its "suspected
 *     drop" count goes up.
 *
 * The score is a simple ratio, deliberately NOT a fancy Bayesian/EigenTrust
 * model — this is meant to demonstrate the concept (bad behavior measurably
 * lowers trust, good behavior measurably raises it) rather than be a
 * production-grade reputation algorithm. Dropped/suspected-black-hole
 * events are weighted more heavily than successful forwards, on the theory
 * that in a payment network, a handful of confirmed drops should tank trust
 * much faster than a long streak of good behavior can rebuild it — the same
 * asymmetry real-world trust/fraud systems use.
 */
@Service
public class DeviceReputationService {

    private static final Logger log = LoggerFactory.getLogger(DeviceReputationService.class);

    /** How much more a suspected drop counts against a device than a
     *  successful forward counts in its favor. */
    private static final double DROP_PENALTY_WEIGHT = 4.0;

    private static final class Stats {
        final AtomicLong forwarded = new AtomicLong();
        final AtomicLong suspectedDrops = new AtomicLong();
    }

    private final Map<String, Stats> statsByDevice = new ConcurrentHashMap<>();

    public void recordForward(String deviceId) {
        statsByDevice.computeIfAbsent(deviceId, id -> new Stats()).forwarded.incrementAndGet();
    }

    public void recordSuspectedDrop(String deviceId) {
        Stats stats = statsByDevice.computeIfAbsent(deviceId, id -> new Stats());
        long total = stats.suspectedDrops.incrementAndGet();
        log.warn("Device {} flagged for a suspected black-hole drop (total suspected drops: {})",
                deviceId, total);
    }

    /**
     * Trust score in [0.0, 1.0]. Starts at a neutral 1.0 for a device with no
     * history (innocent until proven otherwise), then converges toward
     * forwarded / (forwarded + weighted drops) as evidence accumulates.
     */
    public double getScore(String deviceId) {
        Stats stats = statsByDevice.get(deviceId);
        if (stats == null) return 1.0;
        double forwarded = stats.forwarded.get();
        double weightedDrops = stats.suspectedDrops.get() * DROP_PENALTY_WEIGHT;
        double total = forwarded + weightedDrops;
        if (total == 0) return 1.0;
        return forwarded / total;
    }

    public Reputation getReputation(String deviceId) {
        Stats stats = statsByDevice.getOrDefault(deviceId, new Stats());
        return new Reputation(deviceId, stats.forwarded.get(), stats.suspectedDrops.get(), getScore(deviceId));
    }

    public Collection<Reputation> getAllReputations() {
        return statsByDevice.keySet().stream()
                .map(this::getReputation)
                .toList();
    }

    public void clear() {
        statsByDevice.clear();
    }

    public record Reputation(String deviceId, long packetsForwarded, long suspectedDrops, double trustScore) {}
}
