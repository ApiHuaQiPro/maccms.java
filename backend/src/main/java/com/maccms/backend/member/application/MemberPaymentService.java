package com.maccms.backend.member.application;

import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.common.write.CompensationFailedException;
import com.maccms.backend.common.write.WriteConflictException;
import com.maccms.backend.common.write.WriteSequence;
import com.maccms.backend.integration.application.PayNotify;
import com.maccms.backend.integration.application.PayOrder;
import com.maccms.backend.integration.application.PayRequest;
import com.maccms.backend.integration.application.PayStart;
import com.maccms.backend.integration.application.PaymentGateways;
import com.maccms.backend.member.application.view.FinanceRecords;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.PayCallback;
import com.maccms.backend.member.application.view.PaymentContext;
import com.maccms.backend.member.application.view.PaymentStep;
import com.maccms.backend.member.infrastructure.MemberFinanceMapper;
import com.maccms.backend.member.infrastructure.MemberFinanceWriteMapper;
import com.maccms.backend.member.infrastructure.OrderRow;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * 在线支付（B24，docs/architecture/33-b24-payment.md）：{@code user/gopay} 的检查与下单（旧 {@code index/controller/User.php:491-533}），
 * 支付回调 {@code payment/notify} 的分派（旧 {@code index/controller/Payment.php}）与到账（旧 {@code common/model/Order.php:104-155}
 * 的 {@code notify}）。参数、签名与网关地址由 {@link PaymentGateways}（{@code integration.pay}）按旧版生成与校验。
 *
 * <p>到账按 B07 {@code WriteSequence}：认领 {@code UPDATE mac_order … WHERE order_id=? AND order_status=0} → 加积分 → 写积分流水
 * （{@code plog_type = 1}）。认领匹配 0 行（重复或并发的回调）时什么都不改、按"订单已支付完毕"返回；后两步失败时按相反顺序补偿。
 */
public class MemberPaymentService {

    /** 旧 {@code mac_plog.plog_points} 是 {@code smallint unsigned}：更大的积分在旧版写流水时报错（订单与积分已改，见第 5.3 节）。 */
    static final long PLOG_POINTS_MAX = 65_535;
    private static final Logger log = LoggerFactory.getLogger(MemberPaymentService.class);

    private final MemberFinanceMapper reader;
    private final MemberFinanceWriteMapper writer;
    private final PaymentGateways gateways;
    private final Clock clock;

    /** @param writer 可写数据源关闭时为 null（此时 {@link #available()} 为 false） */
    public MemberPaymentService(MemberFinanceMapper reader, MemberFinanceWriteMapper writer, PaymentGateways gateways,
                                Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.gateways = gateways;
        this.clock = clock;
    }

    public boolean available() {
        return writer != null;
    }

    // ------------------------------------------------------------------ gopay

    /**
     * {@code gopay}：{@code order_code = clean}、{@code order_id = intval(trim)}、{@code payment = strtolower(clean)}；三者都为空 → "参数错误"；
     * {@code pay.<payment>.appid} 为空（或 W3 关闭时的三种跳转方式）→ "该支付选项未开启！"；按 id、单号与会员查不到 → "获取单据失败"；
     * 已支付 → "该单据已支付完成"；之后按支付方式下单。
     */
    public PaymentStep gopay(MemberState member, Map<String, PhpInput.Value> params, PaymentContext context) {
        String code = LegacyText.clean(FinanceSupport.raw(params, "order_code"));
        long id = FinanceSupport.intval(params, "order_id");
        String payment = LegacyText.clean(FinanceSupport.raw(params, "payment")).toLowerCase(Locale.ROOT);
        if (LegacyText.empty(code) && id == 0 && LegacyText.empty(payment)) {
            return new PaymentStep.Message("参数错误");
        }
        PaymentGateways.Availability availability = gateways.availability(payment);
        if (availability == PaymentGateways.Availability.NOT_OPEN
                || availability == PaymentGateways.Availability.AVAILABLE && !context.w3()
                && gateways.browserRedirect(payment)) {
            return new PaymentStep.Message("该支付选项未开启！");
        }
        OrderRow row = MemberSessionService.hasSupplementary(code) ? null
                : reader.selectOrderForPay(member.userId(), id, code);
        if (row == null) {
            return new PaymentStep.Message("获取单据失败");
        }
        FinanceRecords.Order order = MemberWalletService.toRecord(row);
        if (order.status() == 1) {
            return new PaymentStep.Message("该单据已支付完成");
        }
        if (availability == PaymentGateways.Availability.NO_METHOD) {
            return new PaymentStep.Blank();
        }
        byte[] paytype = PhpInput.scalar(params, "paytype");
        PayStart start = gateways.start(payment, new PayOrder(order.code(), order.price()),
                new PayRequest(paytype == null ? null : new String(paytype, StandardCharsets.UTF_8), context.clientIp()),
                context.notifyUrl());
        return switch (start) {
            case PayStart.Redirect r -> new PaymentStep.Redirect(r.url());
            case PayStart.AutoPost p -> new PaymentStep.AutoPost(p.action(),
                    p.fields().stream().map(f -> new PaymentStep.Field(f.name(), f.value())).toList());
            case PayStart.Weixin w -> new PaymentStep.Weixin(order, w.codeUrl());
        };
    }

    // ------------------------------------------------------------------ payment/notify

    /**
     * {@code payment/notify} 的 POST（{@code Payment.php:15-33}）：{@code pay_type} 取自 {@code input()}；未开启 → "该支付选项未开启！"；
     * 没有对应的类 → "未找到支付选项！"；否则交给该支付方式验签与到账，返回旧版的回复正文。
     */
    public String notify(PayCallback callback) {
        PayNotify notify = new PayNotify(callback.input(), callback.post(), callback.raw());
        PhpInput.Value v = notify.input().get("pay_type");
        String payType = v == null || v.array() ? "" : new String(v.bytes(), StandardCharsets.UTF_8);
        return switch (gateways.availability(payType)) {
            case NOT_OPEN -> "该支付选项未开启！";
            case NO_METHOD -> "未找到支付选项！";
            case AVAILABLE -> gateways.notify(payType, notify, this::credit);
        };
    }

    /**
     * 到账（旧 {@code Order::notify}），返回旧版的 {@code code}：单号或方式为空 1001；订单不存在 1002；已支付 1（"订单已支付完毕"）；
     * 会员不存在 1002；成功 1。旧版的"先查状态再更新"改为认领式条件更新（第 5 节），重复与并发的回调只有一个生效。
     */
    int credit(String orderCode, String payType) {
        requireWriter();
        if (LegacyText.empty(orderCode) || LegacyText.empty(payType)) {
            return 1001;
        }
        OrderRow row = MemberSessionService.hasSupplementary(orderCode) ? null : reader.selectOrderForNotify(orderCode);
        if (row == null) {
            return 1002;
        }
        FinanceRecords.Order order = MemberWalletService.toRecord(row);
        if (order.status() == 1) {
            return 1;
        }
        if (reader.selectWallet(order.userId()) == null) {
            return 1002;
        }
        long now = FinanceSupport.now(clock);
        long userId = order.userId();
        long points = order.points();
        WriteSequence.Builder seq = WriteSequence.named("member.pay_notify")
                .step(WriteSequence.Role.CLAIM, "order", () -> writer.claimOrderPaid(order.id(), now, payType), 1,
                        () -> writer.releaseOrderPaid(order.id(), now, payType, order.payTime(), order.payType()))
                .step(WriteSequence.Role.TRANSFER, "points", () -> writer.addPoints(userId, points), 1,
                        () -> writer.deductPoints(userId, points));
        if (points <= PLOG_POINTS_MAX) {
            seq.step(WriteSequence.Role.RECORD, "plog", () -> writer.insertPlog(userId, 1, points, now, ""), 1, null);
        } else {
            // 旧版写流水时报"超出范围"，订单与积分已经改了；新版照样到账、不写流水，并记一条日志供对账
            log.warn("pay notify: points exceed mac_plog.plog_points, plog skipped (order_id={})", order.id());
        }
        try {
            seq.build().run();
        } catch (WriteConflictException e) {
            // 认领 0 行：另一个回调已经到账；加积分 0 行：会员在检查之后被删除（订单已按条件恢复为未支付）
            return e.step().equals("order") ? 1 : 1002;
        } catch (CompensationFailedException e) {
            return 2003;
        } catch (DataAccessException e) {
            // 积分超出 int unsigned 等服务端拒绝：前序步骤已补偿（旧版此时已改订单、再报 500）
            log.warn("pay notify rejected by database: {}", SafeExceptionLog.typeChain(e));
            return 2003;
        }
        return 1;
    }

    private void requireWriter() {
        if (writer == null) {
            throw new IllegalStateException("支付到账需要可写数据源");
        }
    }
}
