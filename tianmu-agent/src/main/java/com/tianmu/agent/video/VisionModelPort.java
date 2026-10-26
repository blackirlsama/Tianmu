package com.tianmu.agent.video;

import com.tianmu.agent.core.VideoContent;

import com.fasterxml.jackson.databind.JsonNode;
import com.tianmu.agent.runtime.control.CallControl;

public interface VisionModelPort {
    JsonNode understand(VideoContent.Media media, String focus, String transcript, CallControl control);
}
