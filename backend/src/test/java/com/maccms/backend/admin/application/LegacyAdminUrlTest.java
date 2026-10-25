package com.maccms.backend.admin.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 旧 {@code url()} 在后台入口下的结果（地址都取自 PHP 黄金页面里的实际输出）。 */
class LegacyAdminUrlTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // 当前控制器 | 当前动作 | url() 参数 | 旧版输出
            "Index|index|admin/index/welcome|/_admin.php/admin/index/welcome.html",
            "Index|index|admin/art/data|/_admin.php/admin/art/data.html",
            "Index|index|admin/make/index?ac2=wap|/_admin.php/admin/make/index/ac2/wap.html",
            "Index|index|system/config|/_admin.php/admin/system/config.html",
            "Index|index|vod/data?status=0|/_admin.php/admin/vod/data/status/0.html",
            "Index|index|admin/index|/_admin.php/admin/admin/index.html",
            "Index|index|''|/_admin.php/admin/index/index.html",
            "Index|index|checkcache|/_admin.php/admin/index/checkcache.html",
            "Index|login|index/login|/_admin.php/admin/index/login.html",
            "Index|login|''|/_admin.php/admin/index/login.html",
            "Admin|index|field?col=admin_status&ids=8|/_admin.php/admin/admin/field/col/admin_status/ids/8.html",
            "Admin|index|info?id=8|/_admin.php/admin/admin/info/id/8.html",
            "Admin|index|del?ids=8|/_admin.php/admin/admin/del/ids/8.html",
            "Admin|index|info|/_admin.php/admin/admin/info.html",
            "Admin|del|''|/_admin.php/admin/admin/del.html",
            "Admin|info|''|/_admin.php/admin/admin/info.html",
            "Index|unlocked|''|/_admin.php/admin/index/unlocked.html",
            "VodData|index|x|/_admin.php/admin/vod_data/x.html",
            "Index|index|a/b/c|/_admin.php/a/b/c.html",
            "Index|index|Vod/DATA|/_admin.php/admin/vod/data.html"})
    void buildsLikeTheLegacyUrlHelper(String controller, String action, String url, String expected) {
        String u = url.equals("''") ? "" : url;
        assertThat(new LegacyAdminUrl("/_admin.php", controller, action).build(u)).isEqualTo(expected);
    }

    @Test
    void varsAreAppendedAsPathPairsSkippingBlankValues() {
        // 旧 admin/index.html 的 curUrl：url('admin/index', $param)，page/limit 为 {page}/{limit}
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("wd", "&amp;");
        vars.put("empty", " ");
        vars.put("page", "{page}");
        vars.put("limit", "{limit}");
        assertThat(new LegacyAdminUrl("/_admin.php", "Admin", "index").build("admin/index", vars))
                .isEqualTo("/_admin.php/admin/admin/index/wd/%26amp%3B/page/%7Bpage%7D/limit/%7Blimit%7D.html");
        Map<String, String> star = new LinkedHashMap<>();
        star.put("q", "a b*~");
        assertThat(new LegacyAdminUrl("/sub/_admin.php", "Admin", "index").build("x", star))
                .isEqualTo("/sub/_admin.php/admin/admin/x/q/a+b%2A%7E.html");
    }
}
