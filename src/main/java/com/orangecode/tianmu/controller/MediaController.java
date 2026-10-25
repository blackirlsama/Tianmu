package com.orangecode.tianmu.controller;

import com.orangecode.tianmu.model.entity.File;
import com.orangecode.tianmu.model.entity.User;
import com.orangecode.tianmu.model.entity.Video;
import com.orangecode.tianmu.service.FileService;
import com.orangecode.tianmu.service.UserService;
import com.orangecode.tianmu.service.VideoService;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Serves registered public image files through the site's HTTPS origin. */
@RestController
@RequestMapping("/media")
public class MediaController {
    private static final Pattern OBJECT_NAME = Pattern.compile("[A-Za-z0-9_-]{8,128}\\.(png|jpe?g|gif|webp|avif)", Pattern.CASE_INSENSITIVE);
    private static final Map<String, MediaType> CONTENT_TYPES = Map.of(
            "png", MediaType.IMAGE_PNG,
            "jpg", MediaType.IMAGE_JPEG,
            "jpeg", MediaType.IMAGE_JPEG,
            "gif", MediaType.IMAGE_GIF,
            "webp", MediaType.parseMediaType("image/webp"),
            "avif", MediaType.parseMediaType("image/avif")
    );

    @Resource private FileService fileService;
    @Resource private UserService userService;
    @Resource private VideoService videoService;
    @Resource private MinioClient minioClient;
    @Value("${minio.bucket-name}") private String bucketName;

    @GetMapping("/{objectName:.+}")
    public ResponseEntity<StreamingResponseBody> image(@PathVariable String objectName) {
        if (!OBJECT_NAME.matcher(objectName).matches()) throw notFound();
        int dot = objectName.lastIndexOf('.');
        String hash = objectName.substring(0, dot);
        String objectPath = "/" + bucketName + "/" + objectName;
        boolean referenced = fileService.lambdaQuery().eq(File::getFileHash, hash).eq(File::getIsDelete, 0)
                .like(File::getFileUrl, objectPath).exists()
                || videoService.lambdaQuery().eq(Video::getIsDelete, 0)
                .and(query -> query.like(Video::getCoverUrl, objectPath).or().like(Video::getFileUrl, objectPath)).exists()
                || userService.lambdaQuery().eq(User::getIsDelete, 0).like(User::getAvatar, objectPath).exists();
        if (!referenced) throw notFound();

        StatObjectResponse stat;
        try {
            stat = minioClient.statObject(StatObjectArgs.builder().bucket(bucketName).object(objectName).build());
        } catch (Exception ignored) {
            throw notFound();
        }

        MediaType type = CONTENT_TYPES.get(objectName.substring(dot + 1).toLowerCase(Locale.ROOT));
        StreamingResponseBody body = output -> {
            try (GetObjectResponse input = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucketName).object(objectName).build())) {
                input.transferTo(output);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Image transfer failed", e);
            }
        };
        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(stat.size())
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
