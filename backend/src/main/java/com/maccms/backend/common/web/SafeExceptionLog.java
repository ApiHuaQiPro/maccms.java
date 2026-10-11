package com.maccms.backend.common.web;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 生成可写入日志的异常摘要：只含异常类名、cause 链类名与调用栈位置，不含任何异常 message。
 *
 * <p>异常 message（包括嵌套 cause、suppressed）可能带有 SQL 参数、连接串、账号等内部信息，
 * 所以这里从不调用 {@code getMessage()} / {@code toString()}，也不把 Throwable 交给日志框架。
 * 栈帧只有类名、方法名、文件名与行号，足够定位问题。
 *
 * <p>公开给其他模块（如 site 的页面异常处理）复用；只公开两个摘要方法，深度与栈帧上限保持包内可见。
 */
public final class SafeExceptionLog {

    static final int MAX_CAUSE_DEPTH = 8;
    static final int MAX_FRAMES_PER_EXCEPTION = 12;

    private SafeExceptionLog() {
    }

    /** 单行：外层到最内层的异常类名，例如 {@code a.B <- c.D}。 */
    public static String typeChain(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable t = e; t != null && depth < MAX_CAUSE_DEPTH && seen.add(t); t = t.getCause(), depth++) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getName());
        }
        return sb.toString();
    }

    /** 多行：每层异常的类名与前若干栈帧，不含 message。 */
    public static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable t = e; t != null && depth < MAX_CAUSE_DEPTH && seen.add(t); t = t.getCause(), depth++) {
            sb.append(depth == 0 ? "" : "\nCaused by: ").append(t.getClass().getName());
            StackTraceElement[] frames = t.getStackTrace();
            int n = Math.min(frames.length, MAX_FRAMES_PER_EXCEPTION);
            for (int i = 0; i < n; i++) {
                sb.append("\n\tat ").append(frames[i]);
            }
            if (frames.length > n) {
                sb.append("\n\t... ").append(frames.length - n).append(" more");
            }
            for (Throwable s : t.getSuppressed()) {
                sb.append("\n\tSuppressed: ").append(typeChain(s));
            }
        }
        return sb.toString();
    }
}
