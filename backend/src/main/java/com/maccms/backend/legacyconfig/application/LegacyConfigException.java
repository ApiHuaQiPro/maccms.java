package com.maccms.backend.legacyconfig.application;

/**
 * 旧配置读取或写回（B15）失败。信息只含原因、固定说明与出错位置（行、列），不含文件内容、路径或配置取值（配置里可能有密钥）。
 */
public class LegacyConfigException extends RuntimeException {

    /** 失败原因。 */
    public enum Reason {
        /** 未设置 {@code maccms.legacy-config.dir}。 */
        NOT_CONFIGURED,
        /** 文件名不符合规则（含 {@code ..}、绝对路径、反斜线等）。 */
        INVALID_NAME,
        /** 解析后的真实路径不在配置目录内（路径穿越、符号链接指向目录外）。 */
        OUTSIDE_ROOT,
        NOT_FOUND,
        /** 不是普通文件（目录、设备等）。 */
        NOT_REGULAR_FILE,
        /** 超过 {@link VarExportParser#MAX_BYTES}。 */
        TOO_LARGE,
        /** 不是合法 UTF-8，或以 BOM 开头。 */
        INVALID_ENCODING,
        /** 语法错误（未闭合、缺分隔符等）。 */
        SYNTAX,
        /** 合法 PHP 但不是 var_export 数据结构（常量、函数调用、变量、对象、双引号字符串、拼接等），一律拒绝、不执行。 */
        UNSUPPORTED_CONSTRUCT,
        /** 超过嵌套深度或元素总数上限，或数值超出范围。 */
        LIMIT_EXCEEDED,
        /** 读取文件时的 I/O 错误。 */
        IO,
        /** 写回（B15）：文件在读出之后已被他人（另一位管理员或仍在运行的旧 PHP）修改，本次不写。 */
        CONFLICT,
        /** 写回（B15）：要写的值不能写成新旧两边都能读回的 var_export 数据（含 NUL 字节、非法 UTF-8 等）。 */
        INVALID_VALUE,
        /** 写回（B15）：写临时文件或原子改名失败；目标文件保持原样。 */
        WRITE_FAILED
    }

    private final Reason reason;

    public LegacyConfigException(Reason reason, String detail) {
        super(reason + "：" + detail);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
