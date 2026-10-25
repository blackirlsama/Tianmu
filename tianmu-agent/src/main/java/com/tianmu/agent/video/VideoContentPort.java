package com.tianmu.agent.video;

import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.control.CallControl;

public interface VideoContentPort {
    VideoContent.Source source(long videoId, AccessScope scope, CallControl control);
    VideoContent.Media media(long videoId, String version, long startMs, long endMs, AccessScope scope, CallControl control);
}
