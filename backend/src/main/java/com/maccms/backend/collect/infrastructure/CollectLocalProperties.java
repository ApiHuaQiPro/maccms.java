package com.maccms.backend.collect.infrastructure;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 采集本地文件源的部署参数（B18，26 文档第 4 节；{@code maccms.collect.local.*}，只经环境变量提供）。
 *
 * @param sourceDir    放 Provide 文件的本地目录，留空表示不使用（只能上传）
 * @param cacheDir     预览缓存目录，留空时为系统临时目录下的 {@code maccms-collect-preview}；不要指向旧站目录
 * @param cacheTtl     预览缓存的有效期，默认 24 小时（{@code PT24H}）
 * @param maxFileBytes 每个文件的大小上限，默认 10,240,000 字节
 * @param maxEntries   预览缓存最多保留的项数，默认 200
 */
@ConfigurationProperties(prefix = "maccms.collect.local")
public record CollectLocalProperties(String sourceDir, String cacheDir, Duration cacheTtl, Long maxFileBytes, Integer maxEntries) {

    public static final long DEFAULT_MAX_FILE_BYTES = 10_240_000L;

    public CollectLocalProperties {
        if (cacheTtl == null || cacheTtl.isNegative() || cacheTtl.isZero()) {
            cacheTtl = Duration.ofHours(24);
        }
        if (maxFileBytes == null || maxFileBytes < 1 || maxFileBytes > 64L * 1024 * 1024) {
            maxFileBytes = DEFAULT_MAX_FILE_BYTES;
        }
        if (maxEntries == null || maxEntries < 1) {
            maxEntries = 200;
        }
    }

    Path sourcePath() {
        return sourceDir == null || sourceDir.isBlank() ? null : Path.of(sourceDir.strip());
    }

    Path cachePath() {
        return cacheDir == null || cacheDir.isBlank() ? Path.of(System.getProperty("java.io.tmpdir"), "maccms-collect-preview")
                : Path.of(cacheDir.strip());
    }
}
