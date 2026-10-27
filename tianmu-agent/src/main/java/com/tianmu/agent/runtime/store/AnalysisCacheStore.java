package com.tianmu.agent.runtime.store;

import java.time.Instant;

/** Optional durable backing for derived report checkpoints, never an authorization source. */
public interface AnalysisCacheStore {
    record Entry(long rank,String json,Instant expiresAt) {}
    Entry get(String key);
    void put(String key,Entry entry);
    default boolean acquire(String key,java.util.UUID runId){return true;}
    default void release(java.util.UUID runId){}
}
