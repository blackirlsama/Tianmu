package com.tianmu.agent.video;

import com.tianmu.agent.runtime.control.CallControl;

public interface TranscriptionPort {
    String transcribe(String audioBase64, CallControl control);
}
