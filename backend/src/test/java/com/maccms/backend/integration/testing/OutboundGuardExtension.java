package com.maccms.backend.integration.testing;

import java.util.List;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 测试期出站护栏的 JUnit 扩展（B23）：经 {@code META-INF/services/org.junit.jupiter.api.extension.Extension} 与
 * {@code junit-platform.properties} 的自动注册对全部测试生效（surefire 与 failsafe 相同）。类加载时安装 {@link TestOutboundGuard}；
 * 每个测试之后、每个测试类之后检查违规，有则失败。
 */
public final class OutboundGuardExtension implements BeforeAllCallback, AfterEachCallback, AfterAllCallback {

    static {
        TestOutboundGuard.install();
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        TestOutboundGuard.install();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        check();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        check();
    }

    /** 有违规时抛出 {@link AssertionError}（并清空），列出每条违规的目标。 */
    static void check() {
        List<String> v = TestOutboundGuard.drainViolations();
        if (!v.isEmpty()) {
            throw new AssertionError("测试期出站护栏：测试试图访问外部主机 " + v);
        }
    }
}
