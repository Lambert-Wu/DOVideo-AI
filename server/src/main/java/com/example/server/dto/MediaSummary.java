package com.example.server.dto;

import com.example.server.entity.MediaFile;

import java.time.LocalDateTime;
import java.util.function.UnaryOperator;

public record MediaSummary(
        Long id,
        String filename,
        String status,
        String coverUrl,
        LocalDateTime uploadTime
) {
    public static MediaSummary from(MediaFile mediaFile) {
        return from(mediaFile, UnaryOperator.identity());
    }

    /**
     * @param coverResolver 库里存的是内部 MinIO 地址，返回给客户端前需要换成对外可访问的
     *                      预签名地址（见 MediaService#clientReadableSource）
     */
    public static MediaSummary from(MediaFile mediaFile, UnaryOperator<String> coverResolver) {
        String coverUrl = mediaFile.getCoverUrl();
        return new MediaSummary(
                mediaFile.getId(),
                mediaFile.getFilename(),
                mediaFile.getStatus(),
                coverUrl == null || coverUrl.isBlank() ? null : coverResolver.apply(coverUrl),
                mediaFile.getUploadTime());
    }
}
