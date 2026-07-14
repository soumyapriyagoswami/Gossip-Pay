package com.demo.upimesh.cache;

/**
 * Contract for the duplicate-packet / idempotency cache.
 *
 * Two implementations exist:
 *   - InMemoryIdempotencyStore — default profile, zero setup, single instance only.
 *   - RedisIdempotencyStore    — "event-driven" profile, shared across every
 *     instance of the Settlement Service, which matters once you actually run
 *     more than one replica (the whole point of splitting into services).
 *
 * Both must guarantee: if N callers invoke claim(hash) concurrently for the
 * same hash, exactly one gets true. That atomicity is what kills the
 * "three bridges deliver the same packet at the same instant" problem.
 */
public interface IdempotencyStore {

    /**
     * Try to claim a hash. Returns true if this caller is the first (i.e. the
     * hash was NOT already present); false if someone already claimed it.
     */
    boolean claim(String key);

    /** Test/demo helper. */
    void clear();
}
