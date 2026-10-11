package com.maccms.spike.jte;

/** 与正式 common.web.SafeExceptionLog 同一原则：只输出异常类名链，从不输出 message。 */
final class SafeLog {

    private SafeLog() {
    }

    static String typeChain(Throwable e) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (Throwable t = e; t != null && depth < 8; t = t.getCause(), depth++) {
            sb.append(depth == 0 ? "" : " <- ").append(t.getClass().getName());
        }
        return sb.toString();
    }
}
