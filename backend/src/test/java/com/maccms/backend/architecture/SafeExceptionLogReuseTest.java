package com.maccms.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.common.web.SafeExceptionLog;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 从 common.web 之外的包使用 SafeExceptionLog（模拟 site 的页面异常处理）：
 * 日志只有类型链与栈帧，不出现任何 message；公开范围只有两个摘要方法。标记是合成值。
 */
@ExtendWith(OutputCaptureExtension.class)
class SafeExceptionLogReuseTest {

    private static final Logger log = LoggerFactory.getLogger(SafeExceptionLogReuseTest.class);
    private static final String LEAK = "SYNTHETIC-LEAK-MARKER";

    @Test
    void crossModuleLoggingKeepsTypeChainAndDropsMessages(CapturedOutput output) {
        java.sql.SQLException sql = new java.sql.SQLException(LEAK + " user=synthetic_user password=" + LEAK);
        RuntimeException e = new IllegalStateException("Cookie: SESSION=" + LEAK,
                new IllegalArgumentException("jdbc:mysql://" + LEAK + "-host/db", sql));
        e.addSuppressed(new UnsupportedOperationException(LEAK + "-suppressed"));

        log.error("page render failed: {}", SafeExceptionLog.typeChain(e));
        log.error("page render failed: {}", SafeExceptionLog.describe(e));

        assertThat(SafeExceptionLog.typeChain(e)).isEqualTo(
                "java.lang.IllegalStateException <- java.lang.IllegalArgumentException <- java.sql.SQLException");
        assertThat(output.getAll())
                .contains("page render failed: java.lang.IllegalStateException <- java.lang.IllegalArgumentException"
                        + " <- java.sql.SQLException")
                .contains("Caused by: java.sql.SQLException")
                .contains("Suppressed: java.lang.UnsupportedOperationException")
                .contains("\tat " + SafeExceptionLogReuseTest.class.getName())
                .doesNotContain(LEAK)
                .doesNotContain("synthetic_user")
                .doesNotContain("jdbc:")
                .doesNotContain("SESSION=");
    }

    @Test
    void publicSurfaceIsOnlyTheTwoSummaryMethods() {
        Class<SafeExceptionLog> c = SafeExceptionLog.class;
        assertThat(Modifier.isPublic(c.getModifiers()) && Modifier.isFinal(c.getModifiers())).isTrue();
        assertThat(Arrays.stream(c.getDeclaredMethods()).filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)).containsExactlyInAnyOrder("typeChain", "describe");
        assertThat(Arrays.stream(c.getDeclaredFields()).filter(f -> Modifier.isPublic(f.getModifiers()))
                .map(Field::getName)).isEmpty();
        assertThat(Arrays.stream(c.getDeclaredConstructors()).map(Constructor::getModifiers))
                .allMatch(Modifier::isPrivate);
    }
}
