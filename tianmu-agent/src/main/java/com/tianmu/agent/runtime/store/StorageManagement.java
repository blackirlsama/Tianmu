package com.tianmu.agent.runtime.store;

import com.tianmu.agent.core.AgentTypes.AccessScope;
import java.util.*;

/** Optional persistent-storage administration; controllers never depend on JDBC adapters. */
public interface StorageManagement {
    List<Map<String, Object>> artifacts(UUID id, AccessScope owner);
    byte[] artifact(UUID id, String artifactId, AccessScope owner);
    void deleteSession(UUID id, AccessScope owner);
}
