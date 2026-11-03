package com.maccms.backend.phptemplate.runtime;

/**
 * 单次渲染的资源上限（技术设计 v15 §6.5 RT-8，C-19）：循环总次数、输出字节数、语句嵌套（递归）深度。
 *
 * <p>默认 1,000,000 次 / 32 MiB / 64 层（{@link #DEFAULTS}）。配置项 {@code maccms.phptemplate.limits.*} 由
 * application 层读取后构造本记录传入解释器；runtime 只依赖 JDK，不读配置（§6.3）。超限时解释器抛
 * {@link TemplateUnsupportedException}（kind {@code limit}），按“不支持”处理、由 C-24 转 500。</p>
 *
 * @param maxLoopIterations 一次渲染内全部 {@code foreach}/{@code for} 的迭代总次数上限
 * @param maxOutputBytes    一次渲染输出字节数上限
 * @param maxDepth          {@code if}/{@code foreach}/{@code for}/{@code switch} 语句体的嵌套深度上限
 */
public record ExecutionLimits(long maxLoopIterations, long maxOutputBytes, int maxDepth) {

    /** RT-8 默认值。 */
    public static final ExecutionLimits DEFAULTS = new ExecutionLimits(1_000_000L, 32L * 1024 * 1024, 64);

    public ExecutionLimits {
        if (maxLoopIterations < 0 || maxOutputBytes < 0 || maxDepth < 0) {
            throw new IllegalArgumentException("execution limits must be >= 0");
        }
    }
}
