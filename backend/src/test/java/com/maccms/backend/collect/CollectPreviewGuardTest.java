package com.maccms.backend.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * B18 的"不写库、不联网"（26 文档第 8 节）：解析、预览服务与本地文件、缓存的实现不引用任何数据访问或网络类型。只读的分类名与绑定经已有的
 * {@code TypeNames}（content 的分类缓存）与 {@code CollectBindService}（旧配置文件）取得；写库只在 B19。运行期的证明见
 * {@code AdminCollectPreviewMySqlIT}（请求前后全库逐行相同、没有出站连接）。
 */
class CollectPreviewGuardTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "maccms", "backend", "collect");
    private static final List<String> FILES = List.of(
            "application/ProvideValues.java", "application/ProvideJson.java", "application/ProvideXml.java", "application/ProvideKind.java",
            "application/ProvideDocument.java", "application/ProvideParser.java", "application/CollectPreviewService.java",
            "infrastructure/FileProvidePreviewStore.java", "infrastructure/DirectoryProvideSourceFiles.java",
            "infrastructure/CollectLocalProperties.java");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "Mapper|WriteDatabase|CollectAdminWrites|java\\.sql|javax\\.sql|jdbc|java\\.net\\.(?!URI\\b)|HttpClient|RestClient|WebClient"
                    + "|Socket|URLConnection|openStream|ProcessBuilder|Runtime\\.getRuntime");

    @Test
    void previewSourcesHaveNoDatabaseOrNetworkAccess() throws Exception {
        for (String f : FILES) {
            String code = Files.readString(MAIN.resolve(f), StandardCharsets.UTF_8)
                    .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
            assertThat(FORBIDDEN.matcher(code).find()).as(f).isFalse();
        }
    }
}
