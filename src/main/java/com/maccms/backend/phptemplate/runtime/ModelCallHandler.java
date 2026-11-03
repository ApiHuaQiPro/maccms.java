package com.maccms.backend.phptemplate.runtime;

import java.util.List;

/**
 * 模型调用分派（技术设计 v15 §6.3、§6.21 MC-1～MC-6，C-19）：解释器遇到 {@link PhpAst.ModelCall} 时只经构造时注入的
 * 本接口分派，不用反射、不查宿主类。唯一生产实现是 application 层的 {@code TagModels}（MC-3 白名单在那里判定）。
 *
 * <p>只依赖 JDK。实现抛出的任何异常（含 {@link TemplateUnsupportedException}、{@code ApiException}）由解释器原样上抛，
 * 不包装、不补行号（MC-6、QG-5）。</p>
 */
@FunctionalInterface
public interface ModelCallHandler {

    /**
     * @param model  按 MC-2 规范化后的模型名（{@code art} → {@code Art}）
     * @param method 方法名原文（区分大小写，MC-2）
     * @param args   实参的按值快照（MC-4）：数组已深复制，实现改动它不影响模板变量，模板之后改变量也不影响它
     * @return 结果值（MC-5，原样交给模板），不得为 Java {@code null}
     */
    ZValue call(String model, String method, List<ZValue> args);
}
