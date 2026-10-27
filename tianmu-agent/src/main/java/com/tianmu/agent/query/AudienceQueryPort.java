package com.tianmu.agent.query;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.control.CallControl;
public interface AudienceQueryPort {
    DanmakuTypes.Dataset audience(DanmakuTypes.Query query,AccessScope scope,CallControl control);
}
