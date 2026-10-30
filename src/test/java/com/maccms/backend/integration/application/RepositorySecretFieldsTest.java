package com.maccms.backend.integration.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.legacyconfig.application.VarExportParser;
import com.maccms.backend.legacyconfig.application.view.PhpArray;
import com.maccms.backend.legacyconfig.application.view.PhpValue;
import com.maccms.backend.legacyconfig.application.view.PhpValues;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 静态检查（B23 验收，路线图第 4.3 节 ①）：仓库 {@code application/extra/*.php} 中所有密钥字段（键名属于
 * {@link ThirdPartyConfig#SECRET_KEYS}，任意层级）都为空。失败信息只列字段路径，不输出值。
 */
class RepositorySecretFieldsTest {

    private static final Path EXTRA = Path.of("php", "application", "extra");

    /** 第三方接口与其他凭证字段：必须能在仓库配置里找到（证明扫描真的走到了它们），且为空。 */
    private static final List<String> EXPECTED = List.of(
            "maccms.php:db.pass", "maccms.php:app.cache_password", "maccms.php:upload.img_key",
            "maccms.php:upload.api.ftp.pwd", "maccms.php:upload.api.qiniu.accesskey", "maccms.php:upload.api.qiniu.secretkey",
            "maccms.php:upload.api.uomg.key", "maccms.php:upload.api.upyun.pwd", "maccms.php:upload.api.weibo.pwd",
            "maccms.php:upload.api.weibo.cookie", "maccms.php:interface.pass",
            "maccms.php:pay.alipay.appkey", "maccms.php:pay.codepay.appkey", "maccms.php:pay.weixin.mchid",
            "maccms.php:pay.weixin.appkey", "maccms.php:pay.zhapay.appkey",
            "maccms.php:connect.qq.key", "maccms.php:connect.qq.secret", "maccms.php:connect.weixin.key",
            "maccms.php:connect.weixin.secret", "maccms.php:weixin.token", "maccms.php:rewrite.encode_key",
            "maccms.php:email.phpmailer.password", "maccms.php:sms.aliyun.appkey", "maccms.php:sms.qcloud.appkey",
            "maccms.php:urlsend.baidu.token", "maccms.php:urlsend.baidufast.token");

    @Test
    void secretFieldsInRepositoryConfigAreEmpty() throws IOException {
        List<String> found = new ArrayList<>();
        List<String> nonEmpty = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(EXTRA)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".php")).sorted().toList();
        }
        assertThat(files).isNotEmpty();
        for (Path f : files) {
            PhpArray root = VarExportParser.parse(Files.readAllBytes(f));
            walk(f.getFileName() + ":", root, found, nonEmpty);
        }
        assertThat(found).containsAll(EXPECTED);
        assertThat(nonEmpty).as("仓库 application/extra/*.php 中不为空的密钥字段").isEmpty();
    }

    private static void walk(String prefix, PhpArray a, List<String> found, List<String> nonEmpty) {
        for (Map.Entry<Object, PhpValue> e : a.entries().entrySet()) {
            String path = prefix + (prefix.endsWith(":") ? "" : ".") + e.getKey();
            if (e.getValue() instanceof PhpArray child) {
                walk(path, child, found, nonEmpty);
            } else if (ThirdPartyConfig.isSecretKey(String.valueOf(e.getKey()))) {
                found.add(path);
                if (!PhpValues.text(e.getValue()).isEmpty()) {
                    nonEmpty.add(path);
                }
            }
        }
    }
}
