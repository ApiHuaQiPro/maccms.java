package com.maccms.backend.install.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 旧版"初始化数据"（B22，docs/architecture/31-b22-install-wizard.md 第 6.1 节）：仓库 {@code application/install/sql/initdata.sql} 的
 * 18 条 {@code INSERT INTO `mac_type` VALUES (…)}，按 MySQL 字符串字面量规则解码后存成 jar 内的数据文件
 * {@code install/initdata-mac_type.json}（每行 20 个值，按表定义的列顺序）。jar 里不带 SQL 脚本，也不执行 SQL 文本：值经
 * {@code InstallWriteMapper.insertType} 绑定写入。{@code InitDataTest} 锁定这份数据与仓库 initdata.sql 逐值一致，
 * {@code InstallWizardMySqlIT} 锁定写入结果与 PHP 执行 initdata.sql 的结果逐行一致。
 */
public final class InitData {

    static final String RESOURCE = "install/initdata-mac_type.json";
    static final int COLUMNS = 20;

    private static final List<List<String>> ROWS = load();

    private InitData() {
    }

    /** 全部行（文件中的顺序）。 */
    public static List<List<String>> rows() {
        return ROWS;
    }

    private static List<List<String>> load() {
        try (InputStream in = InitData.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("缺少 " + RESOURCE);
            }
            JsonNode root = JsonMapper.builder().build().readTree(in);
            List<List<String>> out = new ArrayList<>();
            for (JsonNode row : root) {
                if (!row.isArray() || row.size() != COLUMNS) {
                    throw new IllegalStateException(RESOURCE + " 每行应有 " + COLUMNS + " 个值");
                }
                List<String> vals = new ArrayList<>();
                for (JsonNode v : row) {
                    if (!v.isString()) {
                        throw new IllegalStateException(RESOURCE + " 的值应为字符串");
                    }
                    vals.add(v.asString());
                }
                out.add(List.copyOf(vals));
            }
            if (out.isEmpty()) {
                throw new IllegalStateException(RESOURCE + " 没有数据");
            }
            return List.copyOf(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
