package com.maccms.spike.jte.model;

/**
 * 视频卡片视图模型（合成）。name 为 {@link #BOOM} 时访问器抛异常，用来制造"渲染到一半失败"。
 * 异常 message 带合成标记 {@link #LEAK_MARKER}，测试据此检查它是否出现在响应或日志里。
 */
public record VideoCard(String name, String url, String pic, String remarks) {

    public static final String BOOM = "__SYNTHETIC_BOOM__";
    public static final String LEAK_MARKER = "SYNTHETIC-LEAK-7f3a";

    @Override
    public String name() {
        if (BOOM.equals(name)) {
            throw new IllegalStateException(LEAK_MARKER + " jdbc:mysql://synthetic-host/synthetic_db");
        }
        return name;
    }
}
