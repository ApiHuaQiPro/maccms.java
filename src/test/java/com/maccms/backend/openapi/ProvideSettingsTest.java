package com.maccms.backend.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maccms.backend.common.clientip.IpAddress;
import com.maccms.backend.content.application.LegacyCondition;
import com.maccms.backend.legacyconfig.application.VarExportParser;
import com.maccms.backend.openapi.application.ProvideModule;
import com.maccms.backend.openapi.application.ProvideSettings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Provide 旧配置的取值规则（B06，16 文档第 3.4 节）：仓库值可用、datafilter 只接受白名单语法、错误信息不回显取值。 */
class ProvideSettingsTest {

    private static ProvideSettings parse(String vod) {
        String php = "<?php\nreturn array ('api' => array ('vod' => array (" + vod + ")));\n";
        return ProvideSettings.from(VarExportParser.parse(php.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void repositoryConfigurationIsAcceptedAndClosed() throws Exception {
        ProvideSettings s = ProvideSettings.from(VarExportParser.parse(
                Files.readAllBytes(Path.of("php", "application", "extra", "maccms.php"))));
        for (ProvideModule m : ProvideModule.values()) {
            assertThat(s.module(m).open()).as(m.key()).isFalse();
            assertThat(s.module(m).dataFilter()).as(m.key()).hasSize(1);
            assertThat(s.module(m).dataFilter().getFirst().column()).isEqualTo(LegacyCondition.Column.STATUS);
            assertThat(s.module(m).pageSizeJson()).isEqualTo("20");
        }
        assertThat(s.uploadProtocol()).isEqualTo("http");
        assertThat(s.module(ProvideModule.VOD).imgUrl()).isEqualTo("http://img.test.com/");
    }

    @Test
    void flagsPageSizeAndAuth() {
        ProvideSettings s = parse("'status' => 1, 'charge' => '1', 'pagesize' => 30, 'cachetime' => '120',"
                + " 'auth' => ' 1.2.3.4 #example.test# 2001:DB8::1 #1.2.3.5:80#', 'detail_inc_hits' => '1'");
        ProvideSettings.ModuleSettings vod = s.module(ProvideModule.VOD);
        assertThat(vod.open()).isTrue();
        assertThat(vod.charge()).isTrue();
        assertThat(vod.detailIncHits()).isTrue();
        assertThat(vod.pageSize()).isEqualTo(30);
        assertThat(vod.pageSizeJson()).as("整数配置在 JSON 中仍是整数").isEqualTo(30L);
        assertThat(vod.cacheTime()).isEqualTo(120);
        assertThat(vod.auth()).containsExactlyInAnyOrder(IpAddress.parse("127.0.0.1").orElseThrow(),
                IpAddress.parse("1.2.3.4").orElseThrow(), IpAddress.parse("2001:db8::1").orElseThrow());
        assertThat(vod.ignoredAuth()).as("域名与带端口的项").isEqualTo(2);
        assertThat(s.module(ProvideModule.ART).open()).as("缺少的模块按关闭").isFalse();
        assertThat(parse("'pagesize' => ''").module(ProvideModule.VOD).pageSize()).isEqualTo(20);
    }

    @Test
    void dataFilterWhitelist() {
        List<LegacyCondition> c = parse("'datafilter' => ' vod_status=1 AND vod_level >= 2 and vod_isend <> 0 '")
                .module(ProvideModule.VOD).dataFilter();
        assertThat(c).containsExactly(new LegacyCondition(LegacyCondition.Column.STATUS, LegacyCondition.Op.EQ, 1),
                new LegacyCondition(LegacyCondition.Column.LEVEL, LegacyCondition.Op.GE, 2),
                new LegacyCondition(LegacyCondition.Column.ISEND, LegacyCondition.Op.NE, 0));
        assertThat(parse("'datafilter' => ''").module(ProvideModule.VOD).dataFilter()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"vod_status=1 OR 1=1", "vod_status=1; DROP TABLE mac_vod", "vod_name='x'", "art_status=1",
            "vod_status IN (1)", "1", "vod_status=1 AND", "vod_status = '1'", "vod_hits>1", "(vod_status=1)"})
    void dataFilterOutsideTheWhitelistRefusesToStart(String filter) {
        assertThatThrownBy(() -> parse("'datafilter' => '" + filter.replace("'", "\\'") + "'"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api.vod.datafilter")
                .hasMessageNotContaining("DROP").hasMessageNotContaining("OR 1=1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"'status' => 'yes'", "'pagesize' => '0'", "'pagesize' => 'abc'", "'imgurl' => 'ftp://x'",
            "'imgurl' => 'http://a b'", "'typefilter' => '1;2'", "'cachetime' => '-1'", "'auth' => array()"})
    void unsupportedValuesRefuseToStartWithoutEchoingThem(String entry) {
        assertThatThrownBy(() -> parse(entry)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api.vod.")
                .hasMessageNotContaining("abc").hasMessageNotContaining("ftp://").hasMessageNotContaining("yes");
    }
}
