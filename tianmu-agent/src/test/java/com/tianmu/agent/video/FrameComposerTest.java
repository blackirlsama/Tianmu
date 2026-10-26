package com.tianmu.agent.video;

import com.tianmu.agent.core.VideoContent;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.StopReason;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class FrameComposerTest {
    private String image(int width, int height) throws IOException {
        var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    @Test void createsBoundedTimestampedSheet() throws Exception {
        var sheet = new FrameComposer().compose(List.of(new VideoContent.Frame(100, image(32, 32)), new VideoContent.Frame(500, image(64, 32))), VideoAnalysisFlightsTest.control());
        var decoded = ImageIO.read(new ByteArrayInputStream(sheet));
        assertEquals(1920, decoded.getWidth()); assertEquals(400, decoded.getHeight());
    }
    @Test void rejectsInvalidOrOversizedImageBeforeModelRequest() throws Exception {
        for (String value : List.of("not-base64", Base64.getEncoder().encodeToString(new byte[]{1, 2}), image(1281, 1))) {
            assertEquals("INVALID_VIDEO_ANALYSIS", assertThrows(AgentException.class, () -> new FrameComposer().compose(
                    List.of(new VideoContent.Frame(0, value)), VideoAnalysisFlightsTest.control())).code());
        }
    }
    @Test void cancelledCompositionStopsBeforeImageDecoding() {
        var control = VideoAnalysisFlightsTest.control(); control.cancellation().cancel(StopReason.USER_CANCELLED);
        assertEquals("CANCELLED", assertThrows(AgentException.class, () -> new FrameComposer().compose(List.of(new VideoContent.Frame(0, "invalid")), control)).code());
    }
}
