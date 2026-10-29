package com.maccms.backend.install;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * 不连数据库能复放的 B22 黄金场景（{@code ./mvnw verify}；31 文档第 9 节）：页面（首页、语言切换、环境检测、数据库配置页、未知步骤、
 * 第四步的 GET、未测试连接就执行安装）与第四步的全部字段校验（含繁体下的英文提示）。连接参数里的端口换成本机一个没有监听的端口，
 * "密码错误"一步同样得到"数据库连接失败"。需要真实目标库的场景在 {@code InstallWizardMySqlIT}。
 */
class InstallWizardGoldenTest {

    @Test
    void pagesAndValidationMatchPhp73And80() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        Map<String, String> vars = Map.of("{host}", "127.0.0.1", "{port}", String.valueOf(closedPort),
                "{db}", "maccms_it_00000000000000_00000000", "{user}", "test_only_user", "{password}", "test-only-password");
        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (String resource : List.of(InstallGolden.PHP73, InstallGolden.PHP80)) {
            JsonNode g = InstallGolden.load(resource);
            for (JsonNode sc : g.get("scenarios")) {
                String label = sc.get("label").asString();
                if (!label.equals("pages") && !label.equals("step4-validation")) {
                    continue;
                }
                InstallGolden.Site site = InstallGolden.Site.create();
                try (ConfigurableApplicationContext app = InstallGolden.start(site, false, null, null)) {
                    MockMvc mvc = InstallGolden.mvc(app);
                    MockHttpSession session = new MockHttpSession();
                    for (JsonNode st : sc.get("steps")) {
                        MockHttpServletResponse r = InstallGolden.send(mvc, session, st.get("request"), vars);
                        String p = InstallGolden.compare(st, r);
                        compared++;
                        if (p != null) {
                            problems.add(resource + " " + label + "/" + st.get("label").asString() + "：" + p);
                        }
                    }
                    // 这些场景都不写任何东西
                    assertThat(Files.exists(site.deployConfig())).as(label + " 部署配置").isFalse();
                    assertThat(Files.exists(site.lock())).as(label + " 安装锁").isFalse();
                } finally {
                    site.delete();
                }
            }
        }
        assertThat(problems).isEmpty();
        assertThat(compared).isEqualTo(2 * (12 + 17));
    }

    /** 比较规则自检：规范化只去掉脚本、meta、令牌与空白，页面正文仍在；改动一个字就能发现。 */
    @Test
    void htmlNormalizationKeepsContent() {
        JsonNode index = InstallGolden.load(InstallGolden.PHP80).get("scenarios").get(0).get("steps").get(0);
        String n = InstallGolden.normalizeHtml(InstallGolden.body(index), false);
        assertThat(n).contains("<div class=\"install-box\">", "苹果CMS用户协议", "<option value=\"zh-cn\" selected>zh-cn</option>",
                "<a href=\"?step=2\" class=\"layui-btn layui-btn-big layui-btn-normal\">同意协议并安装系统</a>")
                .doesNotContain("<script", "ROOT_PATH", "http-equiv");
        assertThat(InstallGolden.normalizeHtml(InstallGolden.body(index).replace("同意协议", "同意協議"), false)).isNotEqualTo(n);
        JsonNode step2 = InstallGolden.load(InstallGolden.PHP80).get("scenarios").get(0).get("steps").get(2);
        assertThat(InstallGolden.normalizeHtml(InstallGolden.body(step2), true))
                .contains("<th>環境名稱</th>", "<tbody></tbody>").doesNotContain("<td>");
    }
}
