package com.maccms.backend.integration.application;

/**
 * 短信验证码发送（E06，B25；32 文档第 3 节）：旧 {@code mac_send_sms($to, $code, $type_flag, $type_des, $msg)}
 * （{@code application/common.php:379-403}）与 {@code common/extend/sms/Aliyun.php}、{@code Qcloud.php}。实现在已登记子包
 * {@code integration.sms}，经出站客户端发请求；其他模块只经本接口使用。
 */
public interface SmsSender {

    /**
     * 判定顺序与旧版相同：{@code sms.type} 为空 → 9005；号码不符合 {@code /^1[345789][0-9]{9}$/} → 999；验证码为空 → 998；
     * 模板标识为空 → 997；{@code sms.type} 不是 {@code aliyun}/{@code qcloud}（不分大小写的首字母）→ 991；否则交给通道。
     *
     * @param to       手机号
     * @param code     验证码（只放进发给通道的请求）
     * @param typeFlag {@code reg}、{@code bind}、{@code findpass}（模板 {@code sms.tpl_code_<flag>}）
     */
    SendResult send(String to, String code, String typeFlag);
}
