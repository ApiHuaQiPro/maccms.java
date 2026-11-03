package com.maccms.backend.phptemplate.runtime;

import java.util.List;

/**
 * 模板函数分派（技术设计 v15 §6.5 RT-7，C-19）：解释器的函数调用只经本接口。生产实现是 application 层的
 * {@code FunctionTable}/{@code TemplateFunctions}（C-27，S3-11）；runtime 不内置任何业务函数，测试注入桩。
 *
 * <p>只依赖 JDK。{@link #isRegistered} 同时交给 {@link PhpParser} 做解析期白名单判定（BARE-2：名字后接 {@code (}
 * 才是函数调用，不回退为裸名）。{@link #call} 抛出的异常由解释器原样上抛。</p>
 */
public interface FunctionDispatcher {

    /** 函数名原文是否在白名单内。 */
    boolean isRegistered(String name);

    /**
     * @param name 已登记的函数名原文
     * @param args 实参的按值快照（数组已深复制）
     * @return 返回值，不得为 Java {@code null}
     */
    ZValue call(String name, List<ZValue> args);
}
