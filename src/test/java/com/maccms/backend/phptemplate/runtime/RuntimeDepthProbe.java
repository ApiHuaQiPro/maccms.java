package com.maccms.backend.phptemplate.runtime;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * APPK-4 REV-1 证据探针（不是回归测试，不改任何断言口径）：类名不匹配 surefire 默认 include，
 * 不进 {@code verify}；按需 {@code ./mvnw -B -o test -Dtest=RuntimeDepthProbe -Dsurefire.failIfNoSpecifiedTests=false} 复跑。
 *
 * <p>① 深嵌套输入经 {@link PhpParser#parse} 与 {@link Interpreter#execute} 各自的实际结局（含宿主栈溢出）；
 * ② 168 个编译黄金里语句体嵌套、表达式树深度与两者沿同一路径之和的实测最大值，供 RT-8 深度口径决策参考。</p>
 */
class RuntimeDepthProbe {

    static String outcome(String src) {
        Interpreter in = new Interpreter(InterpreterTest.stdStubs(), InterpreterTest.NO_MODELS, ExecutionLimits.DEFAULTS);
        byte[] b = src.getBytes(StandardCharsets.UTF_8);
        String stage = "parse";
        try {
            PhpAst.Program p = PhpParser.parse(b, n -> true);
            stage = "exec";
            in.execute(b, p, new ZArray());
            return "OK";
        } catch (TemplateUnsupportedException e) {
            return stage + ":" + e.kind();
        } catch (Throwable t) {
            return stage + ":" + t.getClass().getSimpleName();
        }
    }

    @Test
    void deepInputs() {
        String[][] shapes = {
                {"parens", "<?php echo %s1%s; ?>", "(", ")"},
                {"not", "<?php echo %s1; ?>", "!", ""},
                {"array", "<?php $a=%s1%s; ?>", "[", "]"},
                {"call", "<?php echo %s1%s; ?>", "f(", ")"},
                {"assign", "<?php %s1; ?>", "$a=", ""},
                {"ifs", "<?php %secho 1;%s ?>", "if(1){", "}"},
                {"ifs-untaken", "<?php if(0){%s%s} ?>", "if(1){", "}"},
        };
        String[][] chains = {
                {"plus", "<?php echo 1%s; ?>", "+1"},
                {"concat", "<?php echo 'a'%s; ?>", ".'a'"},
                {"and", "<?php echo 1%s; ?>", "&&1"},
                {"ternary", "<?php echo 1%s; ?>", "?1:1"},
                {"index", "<?php echo $a%s; ?>", "[0]"},
        };
        for (int n : new int[] {65, 200, 1000, 5000, 100000}) {
            StringBuilder r = new StringBuilder("PROBE N=" + n);
            for (String[] s : shapes) {
                r.append(' ').append(s[0]).append('=')
                        .append(outcome(String.format(s[1], s[2].repeat(n), s[3].repeat(n))));
            }
            for (String[] c : chains) {
                r.append(' ').append(c[0]).append('=').append(outcome(String.format(c[1], c[2].repeat(n))));
            }
            System.out.println(r);
        }
    }

    private int maxStmt;
    private int maxExpr;
    private int maxSum;

    private void visit(Object o, int sd, int ed) {
        if (o instanceof List<?> list) {
            boolean body = !list.isEmpty() && list.get(0) instanceof PhpAst.Stmt;
            for (Object e : list) {
                visit(e, body ? sd + 1 : sd, ed);
            }
            return;
        }
        if (o instanceof PhpAst.Expr) {
            ed++;
        }
        maxStmt = Math.max(maxStmt, sd);
        maxExpr = Math.max(maxExpr, ed);
        maxSum = Math.max(maxSum, sd + ed);
        if (o instanceof Record rec) {
            for (RecordComponent c : rec.getClass().getRecordComponents()) {
                try {
                    visit(c.getAccessor().invoke(rec), sd, ed);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    @Test
    void goldenDepths() throws IOException {
        Path root = Path.of("src", "test", "resources", "phptemplate", "compile-golden");
        int parsed = 0;
        int rejected = 0;
        int[] best = new int[3];
        String[] where = new String[3];
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".html")).sorted().toList()) {
                PhpAst.Program p;
                try {
                    // 与 PhpParserGoldenTest 相同：只把归一化的 instanceof 类名还原成编译器真实输出，其余字节不动
                    p = PhpParser.parse(PhpParserGoldenTest.restoreClassNames(Files.readAllBytes(f)), n -> true);
                } catch (TemplateUnsupportedException e) {
                    rejected++;
                    continue;
                }
                parsed++;
                maxStmt = maxExpr = maxSum = 0;
                // 顶层语句不算一层：Program.statements 视作第 0 层
                for (PhpAst.Stmt s : p.statements()) {
                    visit(s, 0, 0);
                }
                int[] v = {maxStmt, maxExpr, maxSum};
                for (int i = 0; i < 3; i++) {
                    if (v[i] > best[i]) {
                        best[i] = v[i];
                        where[i] = root.relativize(f).toString().replace(java.io.File.separatorChar, '/');
                    }
                }
            }
        }
        System.out.println("GOLDEN parsed=" + parsed + " rejected=" + rejected);
        System.out.println("GOLDEN maxStmtNesting=" + best[0] + " @ " + where[0]);
        System.out.println("GOLDEN maxExprDepth=" + best[1] + " @ " + where[1]);
        System.out.println("GOLDEN maxStmtPlusExpr=" + best[2] + " @ " + where[2]);
    }
}
