package com.maccms.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.maccms.backend.integration.application.MailSender;
import com.maccms.backend.integration.application.SendResult;
import com.maccms.backend.integration.application.SmsSender;
import com.maccms.backend.member.application.view.AccountReply;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.infrastructure.MemberMapper;
import com.maccms.backend.member.infrastructure.MemberWriteMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** 验证码发送与校验的边界（B25，32 文档第 5 节）；与旧版的逐条对照在 {@code SmsMailGoldenMySqlIT}。 */
class MemberVerificationTest {

    private static final long NOW = 1_793_251_578L;
    private final MemberMapper mapper = mock(MemberMapper.class);
    private final MemberWriteMapper writer = mock(MemberWriteMapper.class);
    private final SmsSender sms = mock(SmsSender.class);
    private final MailSender mail = mock(MailSender.class);
    private final VerificationSettings settings = new VerificationSettings("[用户]的[类型]码[验证码]，[时长]分钟", "10",
            Map.of("user_bind_title", "T{$code}", "user_bind_body", "&lt;b&gt;{$code}&lt;/b&gt;{$time}"), Map.of());
    private final MemberVerification v = new MemberVerification(mapper, writer, sms, mail, settings,
            Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC), new Random(7));
    private final MemberState member = new MemberState(true, 5, "member05", 2, 2, null, 0, false);

    {
        // Mockito 对 Long 返回 0；查不到时 Mapper 返回 null
        when(mapper.selectRecentMessage(anyLong(), anyLong(), anyInt(), anyString())).thenReturn(null);
        when(mapper.selectValidMessage(anyLong(), anyLong(), anyInt(), anyString(), anyString())).thenReturn(null);
    }

    @Test
    void windowsFollowTheLegacyRule() {
        assertThat(settings.windowSeconds(false)).as("短信固定 5 分钟").isEqualTo(300);
        assertThat(settings.windowSeconds(true)).isEqualTo(600);
        assertThat(new VerificationSettings("", "0", Map.of(), Map.of()).windowSeconds(true)).isEqualTo(300);
        assertThat(new VerificationSettings("", "abc", Map.of(), Map.of()).windowSeconds(true)).isEqualTo(300);
    }

    @Test
    void sendsRecordsAndRateLimits() {
        when(sms.send(anyString(), anyString(), anyString())).thenReturn(new SendResult(1, "ok"));
        AccountReply r = v.send(MemberState.guest(null), "phone", "13800000001", 3);
        assertThat(r).isEqualTo(AccountReply.of(1, "验证码发送成功，请前往查看"));
        verify(mapper).selectRecentMessage(0, NOW - 300, 3, "13800000001");
        verify(writer).insertMessage(eq(0L), eq(3), eq("13800000001"), anyString(),
                org.mockito.ArgumentMatchers.matches("游客的注册码[0-9]{6}，5分钟"), eq(NOW));
        when(mapper.selectRecentMessage(5, NOW - 600, 1, "a@example.com")).thenReturn(9L);
        assertThat(v.send(member, "email", "a@example.com", 1)).isEqualTo(AccountReply.of(9002, "请不要频繁发送"));
        verify(mail, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void emailBodyIsRenderedAndDecoded() {
        when(mail.send(anyString(), anyString(), anyString())).thenReturn(new SendResult(102, "发生错误：SMTP Error: Could not authenticate."));
        assertThat(v.send(member, "email", "a@example.com", 1))
                .isEqualTo(AccountReply.of(9009, "验证码发送失败：发生错误：SMTP Error: Could not authenticate."));
        verify(mail).send(eq("a@example.com"), org.mockito.ArgumentMatchers.matches("T[0-9]{6}"),
                org.mockito.ArgumentMatchers.matches("<b>[0-9]{6}</b>10"));
        verify(writer, never()).insertMessage(anyLong(), anyInt(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void addressesThatDoNotFitMsgToAreNotSent() {
        String longEmail = "a".repeat(20) + "@example12345.com";
        assertThat(v.send(member, "email", longEmail, 1)).isEqualTo(AccountReply.of(9009, "验证码发送失败：邮箱格式不正确"));
        verify(mail, never()).send(anyString(), anyString(), anyString());
        verify(mapper, never()).selectRecentMessage(anyLong(), anyLong(), anyInt(), anyString());
    }

    @Test
    void recordFailureStillReportsSuccessLikeTheLegacyCode() {
        when(sms.send(anyString(), anyString(), anyString())).thenReturn(new SendResult(1, "ok"));
        when(writer.insertMessage(anyLong(), anyInt(), anyString(), anyString(), anyString(), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("down"));
        assertThat(v.send(member, "phone", "13800000001", 1).ok()).isTrue();
    }

    @Test
    void validChecksUserTypeCodeAndAddress() {
        when(mapper.selectValidMessage(5, NOW - 300, 2, "123456", "13800000001")).thenReturn(1L);
        assertThat(v.valid(member, "phone", "123456", 2, "13800000001")).isTrue();
        assertThat(v.valid(member, "phone", "123456", 2, "13800000002")).isFalse();
        assertThat(v.valid(MemberState.guest(null), "phone", "123456", 2, "13800000001")).isFalse();
    }

    @Test
    void contentFitsTheColumn() {
        assertThat(MemberVerification.storable("a".repeat(300))).hasSize(255);
        assertThat(MemberVerification.storable("码😀")).isEqualTo("码?");
    }
}
