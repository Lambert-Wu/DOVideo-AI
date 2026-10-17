package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 只覆盖不需要真的跑 ffmpeg 的部分：首帧可用性判定，以及已有封面 / 缺源文件时的短路行为。
 * 抽帧本身依赖外部进程，交给真实视频手工验证。
 */
class MediaCoverServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void blackAndWhiteOpeningFramesAreRejectedWhileRealScenesAreKept() throws Exception {
        assertTrue(MediaCoverService.isUnusableFrame(paint("black.jpg", Color.BLACK, null)),
                "纯黑场不该被当成封面");
        assertTrue(MediaCoverService.isUnusableFrame(paint("white.jpg", Color.WHITE, null)),
                "纯白场不该被当成封面");
        assertTrue(MediaCoverService.isUnusableFrame(paint("flat.jpg", new Color(120, 120, 120), null)),
                "纯色画面没有可辨识内容");
        // 场景起始（有些画面偏暗）只要还有明暗结构、均值不离谱就该保留，
        // 否则会把正常的视频封面也一路往后推。
        assertFalse(MediaCoverService.isUnusableFrame(paint("darkScene.jpg",
                new Color(40, 36, 44), new Color(150, 140, 120))));
    }

    @Test
    void luminanceMatchesPerceivedBrightness() {
        assertEquals(0, MediaCoverService.luminanceOf(0x000000));
        assertEquals(255, MediaCoverService.luminanceOf(0xFFFFFF));
        assertEquals(149, MediaCoverService.luminanceOf(0x00FF00), "绿色分量权重最高");
        assertEquals(76, MediaCoverService.luminanceOf(0xFF0000));
        assertEquals(29, MediaCoverService.luminanceOf(0x0000FF), "蓝色分量权重最低");
    }

    @Test
    void existingCoverIsReturnedWithoutRegenerating() {
        MediaFileMapper mapper = mock(MediaFileMapper.class);
        MinioUtils minioUtils = mock(MinioUtils.class);
        MediaFile media = new MediaFile();
        media.setId(9L);
        media.setCoverUrl("http://localhost:9000/media/covers/a.jpg");
        when(minioUtils.publicReadableSource(media.getCoverUrl()))
                .thenReturn("/minio/media/covers/a.jpg");

        MediaCoverService.CoverResult result = new MediaCoverService(mapper, minioUtils).ensureCover(media);

        assertEquals("/minio/media/covers/a.jpg", result.url());
        assertFalse(result.generated());
        verifyNoInteractions(mapper);
    }

    @Test
    void mediaWithoutPlayableSourceYieldsNoCoverInsteadOfThrowing() {
        MediaFileMapper mapper = mock(MediaFileMapper.class);
        MinioUtils minioUtils = mock(MinioUtils.class);
        MediaFile media = new MediaFile();
        media.setId(9L);
        media.setFilePath(null);
        when(mapper.selectById(9L)).thenReturn(media);
        when(minioUtils.readableSource(null)).thenReturn(null);

        MediaCoverService.CoverResult result = new MediaCoverService(mapper, minioUtils).ensureCover(media);

        assertNull(result.url());
        assertFalse(result.generated());
    }

    /** 生成一张 JPEG：background 铺满，foreground 非空时铺左半边以制造明暗差。 */
    private Path paint(String name, Color background, Color foreground) throws Exception {
        BufferedImage image = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(background);
            graphics.fillRect(0, 0, 64, 36);
            if (foreground != null) {
                graphics.setColor(foreground);
                graphics.fillRect(0, 0, 32, 36);
            }
        } finally {
            graphics.dispose();
        }
        Path path = tempDir.resolve(name);
        ImageIO.write(image, "jpg", path.toFile());
        return path;
    }
}
