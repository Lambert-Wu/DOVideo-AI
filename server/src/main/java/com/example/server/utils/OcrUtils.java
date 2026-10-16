package com.example.server.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Component
public class OcrUtils {

    private static final Logger log = LoggerFactory.getLogger(OcrUtils.class);

    private final String ocrCommand;

    public OcrUtils(@Value("${tool.ocr.command:tesseract}") String ocrCommand) {
        this.ocrCommand = ocrCommand;
    }

    public String recognize(File image) {
        if (image == null || !image.isFile()) throw new IllegalArgumentException("OCR image does not exist");
        Process process = null;
        Path output = null;
        Path error = null;
        try {
            output = Files.createTempFile("dovideo-ocr-", ".txt");
            error = Files.createTempFile("dovideo-ocr-err-", ".txt");
            // tesseract 会把「缺少语言包 / 估算分辨率」等告警写到 stderr。
            // 早先用 redirectErrorStream(true) 把它并进 stdout，告警被当成识别文本写进了 VideoContext；
            // 这里让 stderr 单独落盘，只把真正的识别结果作为 OCR 文本返回。
            process = new ProcessBuilder(
                    ocrCommand, image.getAbsolutePath(), "stdout", "-l", "chi_sim+eng")
                    .redirectOutput(output.toFile())
                    .redirectError(error.toFile())
                    .start();
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("OCR execution timed out");
            }
            String errorText = readTrimmed(error);
            // 语言包缺失时 tesseract 仍然返回 0，只是把中文识别退化成乱码。
            // 这种情况必须显式失败，否则错误文本会静默污染上下文与后续检索。
            if (errorText.contains("Failed loading language") || errorText.contains("Error opening data file")) {
                throw new IllegalStateException("OCR language data unavailable: " + firstLine(errorText));
            }
            if (process.exitValue() != 0) {
                String detail = firstLine(errorText);
                throw new IllegalStateException("OCR process failed with exit code " + process.exitValue()
                        + (detail.isEmpty() ? "" : ": " + detail));
            }
            return readTrimmed(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OCR execution interrupted", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("OCR failed for " + image.getName(), e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            deleteQuietly(output);
            deleteQuietly(error);
        }
    }

    private static String readTrimmed(Path path) throws IOException {
        if (path == null || !Files.exists(path)) return "";
        return Files.readString(path, StandardCharsets.UTF_8).trim();
    }

    private static String firstLine(String text) {
        int index = text.indexOf('\n');
        return index < 0 ? text : text.substring(0, index);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.warn("ocr_temp_cleanup_failed path={}", path, e);
        }
    }
}
