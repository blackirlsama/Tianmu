package com.tianmu.agent.video;

import com.tianmu.agent.core.VideoContent;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.control.CallControl;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;

/** A timestamped contact sheet for providers accepting one image per call. */
final class FrameComposer {
    byte[] compose(List<VideoContent.Frame> frames, CallControl control) {
        if (frames == null || frames.isEmpty() || frames.size() > 12) throw invalid();
        control.checkpoint();
        var sheet = new BufferedImage(1920, ((frames.size() + 2) / 3) * 400, BufferedImage.TYPE_INT_RGB);
        var graphics = sheet.createGraphics();
        try {
            graphics.setColor(Color.BLACK); graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
            for (int i = 0; i < frames.size(); i++) {
                control.checkpoint();
                var frame = frames.get(i);
                var image = decode(frame.jpegBase64());
                int x = i % 3 * 640, y = i / 3 * 400;
                double scale = Math.min(640.0 / image.getWidth(), 360.0 / image.getHeight());
                int width = (int) (image.getWidth() * scale), height = (int) (image.getHeight() * scale);
                graphics.drawImage(image, x + (640 - width) / 2, y, width, height, null);
                graphics.setColor(Color.WHITE); graphics.drawString("atMs=" + frame.atMs(), x + 10, y + 385);
            }
            var bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(sheet, "jpeg", bytes)) throw invalid();
            control.checkpoint();
            return bytes.toByteArray();
        } catch (AgentException e) { throw e; }
        catch (Exception e) { throw invalid(); }
        finally { graphics.dispose(); }
    }

    private BufferedImage decode(String base64) throws IOException {
        if (base64 == null || base64.length() > 140_000) throw invalid();
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(base64)))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                // Check dimensions before allocating the decoded raster.
                if (reader.getWidth(0) < 1 || reader.getHeight(0) < 1 || reader.getWidth(0) > 1280 || reader.getHeight(0) > 1280) throw invalid();
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }

    private AgentException invalid() { return new AgentException("INVALID_VIDEO_ANALYSIS", "Invalid sampled frame image"); }
}
