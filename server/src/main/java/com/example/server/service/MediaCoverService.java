package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 视频封面（第一帧）生成。
 *
 * <p>资料库卡片原来只显示一个摄影机占位图标，这里用 ffmpeg 抽出视频首帧、压成小尺寸 JPEG
 * 存进 MinIO，并把内部地址写回 {@code media_files.cover_url}，之后列表接口直接带出封面，
 * 不必每次重新抽帧。
 *
 * <p>首帧经常是转场淡入的黑场或白场，拿它当封面等于没有封面（实测 4 个视频里有 2 个如此），
 * 所以抽出来的帧如果是纯色画面，会再往后试着抽几帧，取第一个有内容的画面。
 *
 * <p>抽帧失败不抛异常：封面只是观感优化，不该让资料库列表跟着报错，调用方拿到 null
 * 退回占位图标即可。
 */
@Service
public class MediaCoverService {

    private static final Logger log = LoggerFactory.getLogger(MediaCoverService.class);

    /** 封面对象统一放在这个前缀下，便于识别与随视频一起清理。 */
    static final String COVER_OBJECT_PREFIX = "covers";

    private static final long EXTRACT_TIMEOUT_MINUTES = 3;
    /**
     * 卡片上按 56×56 显示（高清屏也就 ~112px），这里取 320 已经绰绰有余；
     * 同时限制长短边，避免竖屏视频按宽度缩放后高度上千、白白多传几十 KB。
     */
    private static final String SCALE_FILTER = "scale=320:320:force_original_aspect_ratio=decrease";

    /**
     * 依次尝试的时间点（秒）。第一个是需求要的第一帧；后面几个只在拿到纯色画面时才用，
     * 用来躲开淡入黑场/白场这类没有信息量的片头。
     */
    private static final long[] FRAME_OFFSETS_SECONDS = { 0, 3, 8 };

    /**
     * 判定封面帧「不可用」：纯色画面（黑场/白场/纯背景）或整体过黑、过白。
     * 只看纯色是不够的——淡入时的黑场往往还有微弱结构，明暗极差并不小。
     */
    private static final int FLAT_FRAME_RANGE = 10;
    /** 平均亮度低于/高于这两个值就认为画面没有可辨识内容（0-255）。 */
    private static final int MIN_FRAME_LUMINANCE = 28;
    private static final int MAX_FRAME_LUMINANCE = 228;

    private final MediaFileMapper mediaFileMapper;
    private final MinioUtils minioUtils;
    /** 同一个视频并发生成只会白跑一次 ffmpeg，按 mediaId 串行化。 */
    private final ConcurrentHashMap<Long, Object> generateLocks = new ConcurrentHashMap<>();

    public MediaCoverService(MediaFileMapper mediaFileMapper, MinioUtils minioUtils) {
        this.mediaFileMapper = mediaFileMapper;
        this.minioUtils = minioUtils;
    }

    /**
     * @param url       给浏览器用的封面地址，生成失败时为 null
     * @param generated 本次是否真的抽了帧（true 表示列表缓存该失效了）
     */
    public record CoverResult(String url, boolean generated) {
    }

    /**
     * 返回可直接交给前端的封面地址；没有就现抽一帧并落库。
     * 库里存的是内部 MinIO 地址，返回前会换成对外前缀（与 /media/playback 一致）。
     */
    public CoverResult ensureCover(MediaFile mediaFile) {
        if (mediaFile == null) return new CoverResult(null, false);
        if (hasCover(mediaFile)) return new CoverResult(toClientUrl(mediaFile.getCoverUrl()), false);

        Long mediaId = mediaFile.getId();
        Object lock = generateLocks.computeIfAbsent(mediaId, id -> new Object());
        try {
            synchronized (lock) {
                // 等锁期间别的请求可能已经生成好了，重新读一次避免重复抽帧。
                MediaFile latest = mediaFileMapper.selectById(mediaId);
                if (latest == null) return new CoverResult(null, false);
                if (hasCover(latest)) return new CoverResult(toClientUrl(latest.getCoverUrl()), false);

                String generatedUrl = extractCoverFrame(latest);
                if (generatedUrl == null) return new CoverResult(null, false);
                persistCover(mediaId, generatedUrl);
                return new CoverResult(toClientUrl(generatedUrl), true);
            }
        } finally {
            generateLocks.remove(mediaId, lock);
        }
    }

    /** 库里的内部地址 -> 浏览器可访问的预签名地址。 */
    public String toClientUrl(String coverUrl) {
        return minioUtils.publicReadableSource(coverUrl);
    }

    private boolean hasCover(MediaFile mediaFile) {
        return mediaFile.getCoverUrl() != null && !mediaFile.getCoverUrl().isBlank();
    }

    /**
     * 依 {@link #FRAME_OFFSETS_SECONDS} 抽帧，遇到第一个非纯色画面就上传存 MinIO，返回内部对象地址。
     * 全都是纯色（或抽帧失败）时返回第一张能拿到的，实在拿不到返回 null。
     */
    private String extractCoverFrame(MediaFile mediaFile) {
        String videoUrl = minioUtils.readableSource(mediaFile.getFilePath());
        if (videoUrl == null || videoUrl.isBlank()) {
            log.warn("media_cover_source_missing mediaId={}", mediaFile.getId());
            return null;
        }

        List<Path> frameFiles = new ArrayList<>();
        try {
            Path fallback = null;
            for (long offset : FRAME_OFFSETS_SECONDS) {
                Path frame = grabFrame(videoUrl, offset, mediaFile.getId());
                // 这个时间点抽不出来（视频比它短、或超时），再往后也没有意义。
                if (frame == null) break;
                frameFiles.add(frame);
                if (!isUnusableFrame(frame)) return uploadCover(frame);
                if (fallback == null) fallback = frame;
            }
            return fallback == null ? null : uploadCover(fallback);
        } catch (Exception e) {
            log.warn("media_cover_extract_error mediaId={}", mediaFile.getId(), e);
            return null;
        } finally {
            frameFiles.forEach(this::deleteQuietly);
        }
    }

    /**
     * 抽单个时间点的帧到临时文件，返回该文件；失败返回 null。
     * 与 ASR / 音频导出一致，直接把预签名地址喂给 ffmpeg，不额外落一份本地视频。
     */
    private Path grabFrame(String videoUrl, long offsetSeconds, Long mediaId) {
        Path framePath = null;
        Process process = null;
        try {
            framePath = Files.createTempFile("dovideo-cover-", ".jpg");
            List<String> command = new ArrayList<>(List.of("ffmpeg", "-y"));
            // -ss 放在 -i 前是快速定位，避免为了取第一帧之外的画面从 0 解码过来。
            if (offsetSeconds > 0) {
                command.addAll(List.of("-ss", String.valueOf(offsetSeconds)));
            }
            command.addAll(List.of(
                    "-i", videoUrl,
                    "-frames:v", "1",
                    "-vf", SCALE_FILTER,
                    "-q:v", "4",
                    framePath.toString()));

            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(EXTRACT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                log.warn("media_cover_extract_timeout mediaId={} offset={}", mediaId, offsetSeconds);
                deleteQuietly(framePath);
                return null;
            }
            if (process.exitValue() != 0 || Files.size(framePath) == 0) {
                log.warn("media_cover_extract_failed mediaId={} offset={} exitCode={}",
                        mediaId, offsetSeconds, process.exitValue());
                deleteQuietly(framePath);
                return null;
            }
            return framePath;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("media_cover_extract_interrupted mediaId={} offset={}", mediaId, offsetSeconds);
            deleteQuietly(framePath);
            return null;
        } catch (Exception e) {
            log.warn("media_cover_extract_error mediaId={} offset={}", mediaId, offsetSeconds, e);
            deleteQuietly(framePath);
            return null;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    /**
     * 判断这一帧能不能当封面：缩到 32×32 后统计亮度极差与均值，
     * 纯色（极差≈0）以及整体过黑/过白（均值越界）都算没有可辨识内容。
     * 用 sRGB 亮度而不是 TYPE_BYTE_GRAY，是为了和肉眼/外部工具看到的一致。
     *
     * <p>package-private 是为了让测试直接覆盖这套阈值判定。
     */
    static boolean isUnusableFrame(Path framePath) {
        try {
            BufferedImage source = ImageIO.read(framePath.toFile());
            if (source == null) return true;
            BufferedImage scaled = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.drawImage(source, 0, 0, 32, 32, null);
            } finally {
                graphics.dispose();
            }

            int min = 255;
            int max = 0;
            int sum = 0;
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    int luminance = luminanceOf(scaled.getRGB(x, y));
                    if (luminance < min) min = luminance;
                    if (luminance > max) max = luminance;
                    sum += luminance;
                }
            }
            int mean = sum / (32 * 32);
            boolean flat = max - min <= FLAT_FRAME_RANGE;
            boolean extreme = mean < MIN_FRAME_LUMINANCE || mean > MAX_FRAME_LUMINANCE;
            return flat || extreme;
        } catch (Exception e) {
            // 读不出来说明这帧本身有问题，当成不可用，让循环去试下一个时间点。
            log.warn("media_cover_frame_probe_failed frame={}", framePath, e);
            return true;
        }
    }

    /** sRGB 亮度（ITU-R BT.601 权重），和 ffmpeg 缩放后取平均的口径一致。 */
    static int luminanceOf(int argb) {
        int red = (argb >> 16) & 0xFF;
        int green = (argb >> 8) & 0xFF;
        int blue = argb & 0xFF;
        return (red * 299 + green * 587 + blue * 114) / 1000;
    }

    private String uploadCover(Path framePath) throws Exception {
        return minioUtils.uploadLocalFile(framePath.toFile(), "cover.jpg", COVER_OBJECT_PREFIX);
    }

    private void persistCover(Long mediaId, String coverUrl) {
        // 只更新 cover_url：MyBatis-Plus 默认忽略 null 字段，不会碰其它列。
        MediaFile update = new MediaFile();
        update.setId(mediaId);
        update.setCoverUrl(coverUrl);
        mediaFileMapper.updateById(update);
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.warn("media_cover_temp_cleanup_failed path={}", path, e);
        }
    }
}
