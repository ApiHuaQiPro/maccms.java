package com.maccms.backend.content.application;

import tools.jackson.databind.JsonNode;

/** 复刻 PHP 语义的小工具，只放有旧代码依据的规则。 */
final class PhpValues {

    private PhpValues() {
    }

    /** PHP !empty()：null、false、0、0.0、""、"0"、空数组都算空。 */
    static boolean notEmpty(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return false;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isNumber()) {
            return v.doubleValue() != 0.0;
        }
        if (v.isString()) {
            String s = v.stringValue();
            return !s.isEmpty() && !"0".equals(s);
        }
        if (v.isContainer()) {
            return !v.isEmpty();
        }
        return true;
    }
}
