package com.maccms.backend.phptemplate.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.legacyconfig.application.view.PhpArray;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * C-17 黄金之外的补充单测：键规则与 {@code PhpArray.normalizeKey} 一致（RT-2 原文要求）、数组快照与自动下标、
 * 64 位边界、字符串下标边界，以及 {@code phptemplate.runtime} 只依赖 JDK（§6.3）。
 */
class PhpSemanticsTest {

    private static final Path RUNTIME_SOURCES = Path.of("src", "main", "java", "com", "maccms", "backend", "phptemplate", "runtime");

    @Test
    void stringKeysNormalizeExactlyLikePhpArray() {
        List<String> keys = List.of("0", "1", "-1", "01", "-0", "00", "", " 1", "1 ", "+1", "1.0", "1e2", "abc", "-",
                "9223372036854775807", "9223372036854775808", "-9223372036854775808", "-9223372036854775809",
                "12345678901234567890", "123456789012345678901", "0x1A", "１");
        for (String k : keys) {
            Object php = PhpArray.normalizeKey(k);
            Object z = ZArray.normalizeKey(ZValue.of(k));
            if (php instanceof Long l) {
                assertThat(z).as(k).isEqualTo(l);
            } else {
                assertThat(z).as(k).isEqualTo(ZValue.of(k));
            }
        }
    }

    @Test
    void nonStringKeysFollowPhp73() {
        assertThat(ZArray.normalizeKey(ZValue.of(-1.9))).isEqualTo(-1L);
        assertThat(ZArray.normalizeKey(ZValue.of(Double.NaN))).isEqualTo(0L);
        assertThat(ZArray.normalizeKey(ZValue.FALSE)).isEqualTo(0L);
        assertThat(ZArray.normalizeKey(ZValue.NULL)).isEqualTo(ZValue.EMPTY_STRING);
        assertThat(ZArray.normalizeKey(new ZArray())).as("数组作下标非法").isNull();
        ZArray a = new ZArray();
        assertThat(a.put(new ZArray(), ZValue.TRUE)).isFalse();
        assertThat(a.size()).isZero();
    }

    @Test
    void overwriteKeepsPositionAndAutoIndexNeverShrinks() {
        ZArray a = new ZArray();
        a.put(ZValue.of(-5L), ZValue.of("neg"));
        a.append(ZValue.of("x")); // PHP 7.3：负数键之后自动下标仍从 0 开始
        a.put("k", ZValue.of("k1"));
        a.put(ZValue.of(5L), ZValue.of("five"));
        a.put("k", ZValue.of("k2"));
        a.remove(ZValue.of(5L));
        a.append(ZValue.of("y"));
        assertThat(a.keys()).containsExactly(-5L, 0L, ZValue.of("k"), 6L);
        assertThat(a.get("k")).isEqualTo(ZValue.of("k2"));

        ZArray full = new ZArray();
        full.put(Long.MAX_VALUE, ZValue.TRUE);
        assertThat(full.append(ZValue.FALSE)).as("下一个元素已被占用").isFalse();
        assertThat(full.size()).isEqualTo(1);
    }

    @Test
    void copyIsAnIndependentSnapshot() {
        ZArray inner = ZArray.list(ZValue.of(1L));
        ZArray outer = new ZArray();
        outer.put("in", inner);
        outer.append(ZValue.of(2L));
        ZArray snap = outer.copy();
        inner.append(ZValue.of(9L));
        outer.put("in2", ZValue.TRUE);
        snap.append(ZValue.of(3L));
        assertThat(((ZArray) snap.get("in")).size()).isEqualTo(1);
        assertThat(snap.keys()).containsExactly(ZValue.of("in"), 0L, 1L);
        assertThat(outer.keys()).containsExactly(ZValue.of("in"), 0L, ZValue.of("in2"));
    }

    @Test
    void sixtyFourBitAndOverflowBoundaries() {
        assertThat(PhpSemantics.doubleToLong(0x1p64 + 0x1p12)).as("按 2^64 取模").isEqualTo(4096L);
        assertThat(PhpSemantics.doubleToLong(-0x1p63)).isEqualTo(Long.MIN_VALUE);
        assertThat(PhpSemantics.toLong(ZValue.of("99999999999999999999"))).as("数字串溢出截到最大值").isEqualTo(Long.MAX_VALUE);
        assertThat(PhpSemantics.toLong(ZValue.of(" 12abc"))).isEqualTo(12L);
        assertThat(PhpSemantics.toLong(ZValue.of("1e3"))).isEqualTo(1000L);
        // 两个同向溢出的整数串退回按字节比较
        assertThat(PhpSemantics.looseEquals(ZValue.of("9223372036854775808"), ZValue.of("9223372036854775809"))).isFalse();
        assertThat(PhpSemantics.looseEquals(ZValue.of(Long.MAX_VALUE - 1), ZValue.of(Long.MAX_VALUE))).isFalse();
        assertThat(PhpSemantics.isSmaller(ZValue.of(Long.MAX_VALUE - 1), ZValue.of(Long.MAX_VALUE))).isTrue();
        assertThat(PhpSemantics.looseEquals(ZValue.of(Double.NaN), ZValue.of(Double.NaN))).isFalse();
        assertThat(PhpSemantics.isSmallerOrEqual(ZValue.of(Double.NaN), ZValue.of(1L))).isFalse();
    }

    @Test
    void numericStringParsingMatchesZendRules() {
        PhpSemantics.Numeric n = PhpSemantics.parseNumeric(ZValue.of(" \t-12"));
        assertThat(n.isDouble()).isFalse();
        assertThat(n.longValue()).isEqualTo(-12L);
        assertThat(n.wellFormed()).isTrue();
        assertThat(PhpSemantics.parseNumeric(ZValue.of("12 ")).wellFormed()).as("7.3 不允许尾随空白").isFalse();
        assertThat(PhpSemantics.parseNumeric(ZValue.of(".5")).doubleValue()).isEqualTo(0.5);
        assertThat(PhpSemantics.parseNumeric(ZValue.of("1e")).isDouble()).isFalse();
        assertThat(PhpSemantics.parseNumeric(ZValue.of("0x1A")).wellFormed()).as("不认十六进制").isFalse();
        assertThat(PhpSemantics.parseNumeric(ZValue.of("abc"))).isNull();
        assertThat(PhpSemantics.parseNumeric(ZValue.of("-9223372036854775808")).isDouble()).isFalse();
        PhpSemantics.Numeric big = PhpSemantics.parseNumeric(ZValue.of("9223372036854775808"));
        assertThat(big.isDouble()).isTrue();
        assertThat(big.overflow()).isEqualTo(1);
    }

    @Test
    void stringOffsetBoundariesNeverThrow() {
        ZValue s = ZValue.of("abc");
        assertThat(PhpSemantics.fetchDim(s, ZValue.of(Long.MIN_VALUE))).isEqualTo(ZValue.EMPTY_STRING);
        assertThat(PhpSemantics.fetchDim(s, ZValue.of(Long.MAX_VALUE))).isEqualTo(ZValue.EMPTY_STRING);
        assertThat(PhpSemantics.fetchDim(s, ZValue.of(-4L))).isEqualTo(ZValue.EMPTY_STRING);
        assertThat(PhpSemantics.fetchDim(s, ZValue.of(-3L))).isEqualTo(ZValue.of("a"));
        assertThat(PhpSemantics.fetchDimQuiet(s, ZValue.of(3L))).as("isset 上下文越界为 null").isEqualTo(ZValue.NULL);
        assertThat(PhpSemantics.fetchDimQuiet(s, ZValue.of("1x"))).isEqualTo(ZValue.NULL);
        assertThat(PhpSemantics.fetchDim(ZValue.NULL, ZValue.of("k"))).isEqualTo(ZValue.NULL);
        assertThat(PhpSemantics.fetchDim(ZValue.of(5L), ZValue.of(0L))).isEqualTo(ZValue.NULL);
    }

    @Test
    void stringsAreByteStrings() {
        ZValue.Str s = ZValue.of("你");
        assertThat(s.length()).isEqualTo(3);
        byte[] raw = {(byte) 0xff, 0x00, 0x41};
        ZValue.Str b = ZValue.bytes(raw);
        raw[2] = 0x42;
        assertThat(PhpSemantics.toBytes(b)).containsExactly(0xff, 0x00, 0x41);
        assertThat(PhpSemantics.compare(ZValue.bytes(new byte[] {(byte) 0xff}), ZValue.of("a"))).as("无符号字节比较").isPositive();
        assertThat(b.toString()).doesNotContain("A");
    }

    @Test
    void runtimeDependsOnlyOnJdk() throws IOException {
        Pattern imp = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);
        Pattern fq = Pattern.compile("\\b(com\\.maccms\\.backend\\.[\\w.]+|org\\.springframework\\.|tools\\.jackson\\.|org\\.apache\\.)");
        List<String> bad = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(RUNTIME_SOURCES)) {
            files = s.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(files).extracting(p -> p.getFileName().toString())
                .contains("ZValue.java", "ZArray.java", "PhpSemantics.java");
        for (Path p : files) {
            String src = Files.readString(p, StandardCharsets.UTF_8);
            Matcher m = imp.matcher(src);
            while (m.find()) {
                if (!m.group(1).startsWith("java.")) {
                    bad.add(p.getFileName() + ": import " + m.group(1));
                }
            }
            Matcher f = fq.matcher(src.replaceAll("(?m)^package .*$", ""));
            while (f.find()) {
                if (!f.group(1).startsWith("com.maccms.backend.phptemplate.runtime")) {
                    bad.add(p.getFileName() + ": " + f.group(1));
                }
            }
        }
        assertThat(bad).as("phptemplate.runtime 只依赖 JDK").isEmpty();
    }
}
