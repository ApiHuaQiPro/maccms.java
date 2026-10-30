package com.maccms.backend.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SafeExceptionLogTest {

    private static final String LEAK = "SYNTHETIC-LEAK-MARKER";

    @Test
    void describeKeepsTypesAndFramesButNoMessages() {
        RuntimeException inner = new IllegalArgumentException(LEAK + "-inner");
        inner.addSuppressed(new IllegalStateException(LEAK + "-suppressed", new RuntimeException(LEAK + "-s-cause")));
        RuntimeException outer = new IllegalStateException(LEAK + "-outer", inner);

        String text = SafeExceptionLog.describe(outer);

        assertThat(text).doesNotContain(LEAK)
                .startsWith("java.lang.IllegalStateException")
                .contains("Caused by: java.lang.IllegalArgumentException")
                .contains("Suppressed: java.lang.IllegalStateException <- java.lang.RuntimeException")
                .contains("\tat " + SafeExceptionLogTest.class.getName() + ".describeKeepsTypesAndFramesButNoMessages");
    }

    @Test
    void causeCycleAndDepthAreBounded() {
        CyclicException a = new CyclicException();
        CyclicException b = new CyclicException();
        a.next = b;
        b.next = a;
        assertThat(SafeExceptionLog.typeChain(a).split(" <- ")).hasSize(2);

        RuntimeException deep = new RuntimeException(LEAK);
        for (int i = 0; i < 50; i++) {
            deep = new RuntimeException(LEAK + i, deep);
        }
        assertThat(SafeExceptionLog.typeChain(deep).split(" <- ")).hasSize(SafeExceptionLog.MAX_CAUSE_DEPTH);
        assertThat(SafeExceptionLog.describe(deep)).doesNotContain(LEAK);
    }

    /** getCause() 返回彼此，模拟异常链成环。 */
    private static final class CyclicException extends RuntimeException {
        CyclicException next;

        CyclicException() {
            super(LEAK);
        }

        @Override
        public synchronized Throwable getCause() {
            return next;
        }
    }
}
