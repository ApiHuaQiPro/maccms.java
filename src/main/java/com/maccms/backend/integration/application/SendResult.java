package com.maccms.backend.integration.application;

/**
 * 短信/邮件发送的结果（B25，32 文档第 3 节），取值与旧 {@code mac_send_sms}/{@code mac_send_mail} 及各通道 {@code submit()} 的返回数组相同：
 * 1 成功；9005 未配置；999 号码/地址格式不正确；998、997 参数为空；991 找不到发送方式；101、102 通道返回的失败。
 *
 * @param msg 旧版的提示文字（含通道返回的错误信息原文，未转义；不含号码、地址、验证码与密钥）
 */
public record SendResult(int code, String msg) {

    public boolean ok() {
        return code == 1;
    }
}
