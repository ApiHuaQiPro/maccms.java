package com.maccms.backend.legacyconfig.application.view;

/**
 * 旧配置文件（{@code var_export} 格式的 PHP 数组）中的一个值，只读、不可变。
 * 与 PHP 的类型一一对应：字符串、整数（64 位）、浮点、布尔、{@code NULL}、数组（{@link PhpArray}）。
 * 不做类型转换：{@code 'site_status' => '1'} 是字符串，{@code 'part_size' => 20971520} 是整数，由调用方按旧代码的用法解释。
 */
public sealed interface PhpValue permits PhpValue.PhpString, PhpValue.PhpInt, PhpValue.PhpFloat, PhpValue.PhpBool,
        PhpValue.PhpNull, PhpArray {

    /** PHP 字符串（单引号字面量解码后的内容）。 */
    record PhpString(String value) implements PhpValue {
        public PhpString {
            if (value == null) {
                throw new IllegalArgumentException("value 不能为 null");
            }
        }

        @Override
        public String toString() {
            // 不输出内容：配置里可能有密钥
            return "PhpString[length=" + value.length() + "]";
        }
    }

    /** PHP 整数。 */
    record PhpInt(long value) implements PhpValue {
    }

    /** PHP 浮点数（有限值；{@code NAN}、{@code INF} 是常量，解析器不接受）。 */
    record PhpFloat(double value) implements PhpValue {
    }

    /** PHP 布尔值。 */
    record PhpBool(boolean value) implements PhpValue {
    }

    /** PHP {@code NULL}。 */
    enum PhpNull implements PhpValue {
        INSTANCE
    }
}
