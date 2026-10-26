package com.tianmu.agent.model;

import com.tianmu.agent.core.AudienceData.Topic;
import com.tianmu.agent.core.DanmakuTypes.Dataset;
import com.tianmu.agent.core.VideoContent;
import com.tianmu.agent.runtime.control.CallControl;
import java.util.List;

public interface AudienceModelPort {
    String summarize(Dataset data, List<Topic> previous, VideoContent.Facts video, CallControl control);
}
