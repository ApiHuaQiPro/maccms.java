package com.maccms.backend.member.application;

import static com.maccms.backend.member.application.FinanceSupport.nz;
import static com.maccms.backend.member.application.FinanceSupport.str;

import com.maccms.backend.common.web.PhpInput;
import com.maccms.backend.common.web.SafeExceptionLog;
import com.maccms.backend.common.write.CompensationFailedException;
import com.maccms.backend.common.write.WriteConflictException;
import com.maccms.backend.common.write.WriteSequence;
import com.maccms.backend.member.application.view.FinanceRecords;
import com.maccms.backend.member.application.view.JsonReply;
import com.maccms.backend.member.application.view.MemberState;
import com.maccms.backend.member.application.view.RecordPage;
import com.maccms.backend.member.infrastructure.CardRow;
import com.maccms.backend.member.infrastructure.CashRow;
import com.maccms.backend.member.infrastructure.GroupPriceRow;
import com.maccms.backend.member.infrastructure.InviteeRow;
import com.maccms.backend.member.infrastructure.MemberFinanceMapper;
import com.maccms.backend.member.infrastructure.MemberFinanceWriteMapper;
import com.maccms.backend.member.infrastructure.OrderRow;
import com.maccms.backend.member.infrastructure.WalletRow;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * 会员的积分账户（B10，docs/architecture/19-b10-member-finance.md 第 4.4、4.5、4.7、4.9、4.10 节）：卡密充值、本地建单、提现申请与撤销、
 * 积分升级会员组，以及订单、充值卡、提现、推广会员列表与会员组价格。返回码与提示逐条按旧 {@code index/controller/User.php} 与
 * {@code common/model/{Card,Order,Cash,User}.php}，由 {@code member/finance-php{80,73}-golden.json} 锁定。
 *
 * <p>多表写入按 B07 {@code WriteSequence}（第 6.3 节）；数值变化都是带前置条件的原子表达式，检查匹配行数。不发起任何支付请求。
 */
public class MemberWalletService {

    private static final Logger log = LoggerFactory.getLogger(MemberWalletService.class);
    /** 升级时长（common/model/User.php:543）。 */
    static final Map<String, Long> SPANS = Map.of("day", 86_400L, "week", 604_800L, "month", 2_592_000L,
            "year", 31_536_000L);
    /** 升级时到期时间被并发改动后的重试上限（每次失败都意味着另一个写入已生效）。 */
    static final int UPGRADE_ATTEMPTS = 64;
    private static final int ORDER_CODE_ATTEMPTS = 5;
    private static final int DELETE_BATCH = 200;
    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(MemberAccountService.ZONE);

    private final MemberFinanceMapper reader;
    private final MemberFinanceWriteMapper writer;
    private final MemberFinanceSettings settings;
    private final MemberRewards rewards;
    private final Clock clock;
    private final RandomGenerator random;

    /**
     * @param writer 可写数据源关闭时为 null（只读的列表照常可用）
     * @param random 订单号的随机部分（生产为 {@code SecureRandom}）
     */
    public MemberWalletService(MemberFinanceMapper reader, MemberFinanceWriteMapper writer,
                               MemberFinanceSettings settings, Clock clock, RandomGenerator random) {
        this.reader = reader;
        this.writer = writer;
        this.settings = settings;
        this.rewards = new MemberRewards(writer, settings);
        this.clock = clock;
        this.random = random;
    }

    public boolean available() {
        return writer != null;
    }

    public MemberFinanceSettings settings() {
        return settings;
    }

    /** 写库前重读的积分账户；会员不存在时为空。 */
    public Optional<FinanceRecords.Wallet> wallet(long userId) {
        WalletRow w = reader.selectWallet(userId);
        return w == null ? Optional.empty() : Optional.of(new FinanceRecords.Wallet(nz(w.userId()), str(w.userName()),
                nz(w.groupId()), nz(w.points()), nz(w.froze()), nz(w.endTime())));
    }

    // ------------------------------------------------------------------ buy：卡密

    /** {@code $flag == 'card'}（{@code input('param.flag')}，按字节相等）。 */
    public static boolean isCard(Map<String, PhpInput.Value> params) {
        return FinanceSupport.text(params, "flag").equals("card");
    }

    /** 卡密充值（{@code common/model/Card.php:123-157}）。 */
    public JsonReply useCard(MemberState member, Map<String, PhpInput.Value> params) {
        requireWriter();
        String no = LegacyText.clean(FinanceSupport.raw(params, "card_no"));
        String pwd = LegacyText.clean(FinanceSupport.raw(params, "card_pwd"));
        if (LegacyText.empty(no) || LegacyText.empty(pwd)) {
            return JsonReply.of(1001, "参数错误");
        }
        if (MemberSessionService.hasSupplementary(no + pwd)) {
            // utf8 列存不下 4 字节字符，不可能相等
            return notFound();
        }
        long userId = member.userId();
        // 旧版按卡号卡密整体更新（同号同密的多张未用卡一起作废）；新版只认领一张（D-B10-14），被并发抢先时试下一张
        for (CardRow card : reader.selectUnusedCards(no, pwd, 3)) {
            long now = FinanceSupport.now(clock);
            long points = nz(card.points());
            try {
                WriteSequence.named("member.card")
                        .step(WriteSequence.Role.CLAIM, "card", () -> writer.claimCard(card.cardId(), userId, now), 1,
                                () -> writer.releaseCard(card.cardId(), userId, now, nz(card.saleStatus()),
                                        nz(card.userId()), nz(card.useTime())))
                        .step(WriteSequence.Role.TRANSFER, "points", () -> writer.addPoints(userId, points), 1, null)
                        .build().run();
                return JsonReply.of(1, "充值成功，增加积分【" + points + "】");
            } catch (WriteConflictException e) {
                if (!e.step().equals("card")) {
                    return JsonReply.of(1003, "更新用户点数失败，请重试");
                }
            } catch (CompensationFailedException e) {
                return JsonReply.of(1003, "更新用户点数失败，请重试");
            } catch (DataAccessException e) {
                // 积分超出 int unsigned 等：卡已按条件释放
                log.warn("member card rejected by database: {}", SafeExceptionLog.typeChain(e));
                return JsonReply.of(1003, "更新用户点数失败，请重试");
            }
        }
        return notFound();
    }

    private static JsonReply notFound() {
        return JsonReply.of(1002, "充值卡信息有误，请重试");
    }

    // ------------------------------------------------------------------ buy：本地建单

    /** 在线充值建单（User.php:444-461）：只建单，不发起任何支付请求（B24 前，19 文档第 4.5、4.6 节）。 */
    public JsonReply createOrder(MemberState member, Map<String, PhpInput.Value> params) {
        requireWriter();
        String price = FinanceSupport.utf8(params, "price");
        if (LegacyText.empty(price) || !PhpNumbers.isNumeric(price)) {
            // 非数字串：旧版按字符串比较后写库报错（500），新版按参数错误（D-B10-1）
            return JsonReply.of(1001, "参数错误");
        }
        double value = Double.parseDouble(price.strip());
        if (value < settings.payMin()) {
            return JsonReply.of(1002, "最小充值金额不能低于" + settings.payMinText() + "元");
        }
        long points = PhpNumbers.intval(settings.payScale() * value);
        long userId = member.userId();
        for (int attempt = 0; attempt < ORDER_CODE_ATTEMPTS; attempt++) {
            long now = FinanceSupport.now(clock);
            String code = "PAY" + ORDER_DATE.format(Instant.ofEpochSecond(now)) + random.nextInt(100_000, 1_000_000);
            int inserted;
            try {
                inserted = writer.insertOrder(userId, code, price, now, points);
            } catch (DataAccessException e) {
                // order_price 超出 decimal(12,2)、order_points 超出 mediumint：旧版 500（D-B10-3）
                log.warn("member order rejected by database: {}", e.getClass().getSimpleName());
                return JsonReply.of(1002, "保存失败!：");
            }
            if (inserted == 1) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("user_id", userId);
                data.put("order_code", code);
                data.put("order_price", price);
                data.put("order_time", now);
                data.put("order_points", points);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", 1);
                body.put("msg", "保存成功!");
                body.put("data", data);
                return JsonReply.of(body);
            }
        }
        return JsonReply.of(1002, "保存失败!：");
    }

    /**
     * {@code gopay}（User.php:491-505）的提示：{@code order_code}（clean）、{@code order_id}（intval）、{@code payment}（strtolower(clean)）
     * 都为空 → "参数错误"；否则 B24 前一律"该支付选项未开启！"——与旧版 {@code pay.<payment>.appid} 为空时相同。不生成任何网关地址、
     * 不跳转、不发请求（19 文档第 4.6 节）。
     */
    public static String gopayMessage(Map<String, PhpInput.Value> params) {
        String code = LegacyText.clean(FinanceSupport.raw(params, "order_code"));
        long id = FinanceSupport.intval(params, "order_id");
        String payment = LegacyText.clean(FinanceSupport.raw(params, "payment")).toLowerCase(java.util.Locale.ROOT);
        if (LegacyText.empty(code) && id == 0 && LegacyText.empty(payment)) {
            return "参数错误";
        }
        return "该支付选项未开启！";
    }

    // ------------------------------------------------------------------ 订单、充值卡

    public RecordPage<FinanceRecords.Order> orders(MemberState member, Map<String, PhpInput.Value> params) {
        int page = FinanceSupport.page(params);
        int limit = FinanceSupport.limit(params, 20, 20);
        long total = reader.countOrders(member.userId());
        long offset = (long) (page - 1) * limit;
        List<FinanceRecords.Order> rows = offset >= total ? List.of()
                : reader.selectOrders(member.userId(), offset, limit).stream().map(MemberWalletService::toRecord)
                        .toList();
        return new RecordPage<>(rows, total, page, limit);
    }

    /** {@code pay}（User.php:472-479）：按单号（{@code clean}）与会员。 */
    public Optional<FinanceRecords.Order> orderByCode(MemberState member, Map<String, PhpInput.Value> params) {
        String code = LegacyText.clean(FinanceSupport.raw(params, "order_code"));
        if (MemberSessionService.hasSupplementary(code)) {
            return Optional.empty();
        }
        return Optional.ofNullable(reader.selectOrderByCode(member.userId(), code)).map(MemberWalletService::toRecord);
    }

    /** {@code order_info}（User.php:858-869）：{@code intval(order_id)}，只查本会员（D-B10-11）。 */
    public Optional<FinanceRecords.Order> orderById(MemberState member, Map<String, PhpInput.Value> params) {
        long id = FinanceSupport.intval(params, "order_id");
        return Optional.ofNullable(reader.selectOrderById(member.userId(), id)).map(MemberWalletService::toRecord);
    }

    /** 订单全部列（旧 JSON 的 info，列顺序同 install.sql）。 */
    public static Map<String, Object> orderJson(FinanceRecords.Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order_id", o.id());
        m.put("user_id", o.userId());
        m.put("order_status", o.status());
        m.put("order_code", o.code());
        m.put("order_price", o.price());
        m.put("order_time", o.time());
        m.put("order_points", o.points());
        m.put("order_pay_type", o.payType());
        m.put("order_pay_time", o.payTime());
        m.put("order_remarks", o.remarks());
        return m;
    }

    static FinanceRecords.Order toRecord(OrderRow r) {
        return new FinanceRecords.Order(nz(r.orderId()), nz(r.userId()), nz(r.status()), str(r.code()),
                r.price() == null ? "0.00" : r.price().toPlainString(), nz(r.time()), nz(r.points()),
                str(r.payType()), nz(r.payTime()), str(r.remarks()));
    }

    public RecordPage<FinanceRecords.Card> cards(MemberState member, Map<String, PhpInput.Value> params) {
        int page = FinanceSupport.page(params);
        int limit = FinanceSupport.limit(params, 20, 20);
        long total = reader.countUsedCards(member.userId());
        long offset = (long) (page - 1) * limit;
        List<FinanceRecords.Card> rows = offset >= total ? List.of()
                : reader.selectUsedCards(member.userId(), offset, limit).stream()
                        .map(c -> new FinanceRecords.Card(nz(c.cardId()), str(c.cardNo()), nz(c.money()),
                                nz(c.points()), nz(c.useTime()))).toList();
        return new RecordPage<>(rows, total, page, limit);
    }

    // ------------------------------------------------------------------ 提现

    /** 提现申请（{@code common/model/Cash.php:66-115}）。 */
    public JsonReply applyCash(MemberState member, Map<String, PhpInput.Value> params) {
        requireWriter();
        double money = PhpNumbers.floatval(FinanceSupport.utf8(params, "cash_money"));
        if (!settings.cashOn()) {
            return JsonReply.of(1005, "提现功能未开启！");
        }
        if (money < settings.cashMin()) {
            return JsonReply.of(1006, "最低提现金额！：" + settings.cashMinText());
        }
        long points = PhpNumbers.intval(money * settings.cashRatio());
        long userId = member.userId();
        WalletRow wallet = reader.selectWallet(userId);
        if (wallet == null || points > nz(wallet.points())) {
            return JsonReply.of(1007, "提现太多了,没有这么多积分哦！");
        }
        String bankName = LegacyText.clean(FinanceSupport.raw(params, "cash_bank_name"));
        String bankNo = LegacyText.clean(FinanceSupport.raw(params, "cash_bank_no"));
        String payee = LegacyText.clean(FinanceSupport.raw(params, "cash_payee_name"));
        // ThinkPHP require（common/validate/Cash.php：规则顺序 bank_name、payee_name、bank_no；bank_no 的提示键写错，用默认提示）
        if (bankName.isEmpty()) {
            return JsonReply.of(1001, "参数错误：银行名称必须");
        }
        if (payee.isEmpty()) {
            return JsonReply.of(1001, "参数错误：收款人姓名必须");
        }
        if (bankNo.isEmpty()) {
            return JsonReply.of(1001, "参数错误：cash_bank_no不能为空");
        }
        long now = FinanceSupport.now(clock);
        String moneyText = PhpNumbers.phpString(money);
        try {
            WriteSequence.named("member.cash")
                    .step(WriteSequence.Role.TRANSFER, "freeze", () -> writer.freezePoints(userId, points), 1,
                            () -> writer.unfreezePoints(userId, points))
                    .step(WriteSequence.Role.RECORD, "cash", () -> writer.insertCash(userId, points, moneyText, bankName,
                            bankNo, payee, now), 1, null)
                    .build().run();
        } catch (WriteConflictException e) {
            return JsonReply.of(1007, "提现太多了,没有这么多积分哦！");
        } catch (CompensationFailedException e) {
            return JsonReply.of(1004, "保存失败!：");
        } catch (DataAccessException e) {
            // cash_points 超出 smallint、字段超长等：冻结已撤销（旧版 500，D-B10-3）
            log.warn("member cash rejected by database: {}", SafeExceptionLog.typeChain(e));
            return JsonReply.of(1004, "保存失败!：");
        }
        return JsonReply.of(1, "保存成功!");
    }

    public RecordPage<FinanceRecords.Cash> cashes(MemberState member, Map<String, PhpInput.Value> params) {
        int page = FinanceSupport.page(params);
        int limit = FinanceSupport.limit(params, 20, 20);
        long total = reader.countCashes(member.userId());
        long offset = (long) (page - 1) * limit;
        List<FinanceRecords.Cash> rows = offset >= total ? List.of()
                : reader.selectCashes(member.userId(), offset, limit).stream().map(MemberWalletService::toRecord)
                        .toList();
        return new RecordPage<>(rows, total, page, limit);
    }

    private static FinanceRecords.Cash toRecord(CashRow r) {
        return new FinanceRecords.Cash(nz(r.cashId()), nz(r.status()), nz(r.points()),
                r.money() == null ? "0.00" : r.money().toPlainString(), str(r.bankName()), str(r.bankNo()),
                str(r.payeeName()), nz(r.time()), nz(r.timeAudit()));
    }

    /**
     * 撤销提现（{@code cash_del}，User.php:784-809 → {@code Cash::delData}，common/model/Cash.php:117-146）：逐行删除，未审核的同时退回
     * 冻结积分。每行是"按读到的状态条件删除（认领）→ 退回冻结"；退回匹配 0 行时把该行原样插回并返回 1005。
     */
    public JsonReply deleteCash(MemberState member, Map<String, PhpInput.Value> params) {
        requireWriter();
        String ids = LegacyText.clean(FinanceSupport.raw(params, "ids"));
        byte[] all = FinanceSupport.raw(params, "all");
        if (LegacyText.empty(ids) && LegacyText.empty(all)) {
            return JsonReply.of(1001, "参数错误");
        }
        Set<Long> list = FinanceSupport.ids(ids);
        if (list == null) {
            return JsonReply.of(1001, "参数错误");
        }
        long userId = member.userId();
        boolean everything = MemberRecordService.isAll(all);
        long after = 0;
        while (true) {
            List<CashRow> rows = everything ? reader.selectCashesFor(userId, null, after, DELETE_BATCH)
                    : reader.selectCashesFor(userId, list, 0, FinanceSupport.MAX_IDS);
            for (CashRow row : rows) {
                JsonReply failed = deleteOne(userId, row);
                if (failed != null) {
                    return failed;
                }
                after = Math.max(after, nz(row.cashId()));
            }
            if (!everything || rows.size() < DELETE_BATCH) {
                break;
            }
        }
        return JsonReply.of(1, "删除成功");
    }

    /** @return 失败时的回复；成功（含已被别人删除）时为 null */
    private JsonReply deleteOne(long userId, CashRow row) {
        long cashId = nz(row.cashId());
        int status = nz(row.status());
        long points = nz(row.points());
        try {
            if (status != 0) {
                writer.deleteCash(cashId, userId, status);
                return null;
            }
            WriteSequence.named("member.cash_del")
                    .step(WriteSequence.Role.CLAIM, "cash", () -> writer.deleteCash(cashId, userId, 0), 1,
                            () -> writer.restoreCash(row))
                    .step(WriteSequence.Role.TRANSFER, "unfreeze", () -> writer.unfreezePoints(userId, points), 1, null)
                    .build().run();
            return null;
        } catch (WriteConflictException e) {
            if (e.step().equals("cash")) {
                // 已被并发撤销（行不在了），或期间被审核（状态变了）：已审核的按新状态只删除，不退冻结
                List<CashRow> again = reader.selectCashesFor(userId, List.of(cashId), 0, 1);
                if (!again.isEmpty() && nz(again.get(0).status()) != 0) {
                    writer.deleteCash(cashId, userId, nz(again.get(0).status()));
                }
                return null;
            }
            return JsonReply.of(1005, "更新用户积分失败：");
        } catch (CompensationFailedException e) {
            return JsonReply.of(1005, "更新用户积分失败：");
        } catch (DataAccessException e) {
            log.warn("member cash delete rejected by database: {}", SafeExceptionLog.typeChain(e));
            return JsonReply.of(1001, "删除失败：");
        }
    }

    // ------------------------------------------------------------------ 升级会员组

    /** 全部会员组与价格（旧 {@code Group::getCache}）。 */
    public List<FinanceRecords.GroupOffer> groups() {
        return reader.selectGroups().stream().map(g -> new FinanceRecords.GroupOffer(nz(g.id()), str(g.name()),
                nz(g.status()), nz(g.day()), nz(g.week()), nz(g.month()), nz(g.year()))).toList();
    }

    /** 积分升级会员组（{@code common/model/User.php:539-600}）；成功时带 {@code group_id}/{@code group_name} 两个 cookie。 */
    public JsonReply upgrade(MemberState member, Map<String, PhpInput.Value> params) {
        requireWriter();
        long groupId = FinanceSupport.intval(params, "group_id");
        String longText = FinanceSupport.text(params, "long");
        Long span = SPANS.get(longText);
        if (span == null) {
            return JsonReply.of(1001, "非法操作");
        }
        if (groupId < 3) {
            return JsonReply.of(1002, "请选择自定义收费会员组");
        }
        GroupPriceRow group = groupId > 65_535 ? null
                : reader.selectGroups().stream().filter(g -> nz(g.id()) == groupId).findFirst().orElse(null);
        if (group == null) {
            return JsonReply.of(1003, "获取会员组信息失败");
        }
        if (nz(group.status()) == 0) {
            return JsonReply.of(1004, "会员组已经关闭，无法升级");
        }
        int price = switch (longText) {
            case "day" -> nz(group.day());
            case "week" -> nz(group.week());
            case "month" -> nz(group.month());
            default -> nz(group.year());
        };
        long userId = member.userId();
        for (int attempt = 0; attempt < UPGRADE_ATTEMPTS; attempt++) {
            WalletRow wallet = reader.selectWallet(userId);
            if (wallet == null || nz(wallet.points()) < price) {
                return JsonReply.of(1005, "积分不够，无法升级");
            }
            long now = FinanceSupport.now(clock);
            long oldEnd = nz(wallet.endTime());
            int oldGroup = nz(wallet.groupId());
            long newEnd = (oldEnd > now ? oldEnd : now) + span;
            int target = (int) groupId;
            try {
                WriteSequence.named("member.upgrade")
                        .step(WriteSequence.Role.TRANSFER, "upgrade", () -> writer.upgradeGroup(userId, price, newEnd,
                                target, oldEnd, oldGroup), 1, () -> writer.revertUpgrade(userId, price, newEnd, target,
                                oldEnd, oldGroup))
                        .step(WriteSequence.Role.RECORD, "plog", () -> writer.insertPlog(userId, 7, price, now, ""), 1,
                                null)
                        .build().run();
            } catch (WriteConflictException e) {
                // 积分不够，或到期时间/组被并发改过：重读后再判定
                continue;
            } catch (CompensationFailedException e) {
                return JsonReply.of(1009, "升级会员组失败");
            } catch (DataAccessException e) {
                log.warn("member upgrade rejected by database: {}", SafeExceptionLog.typeChain(e));
                return JsonReply.of(1009, "升级会员组失败");
            }
            rewards.pay(wallet, price, now);
            return JsonReply.of(1, "升级会员组成功").withCookies(
                    MemberCookies.groupRefresh(target, str(group.name()), now));
        }
        return JsonReply.of(1009, "升级会员组失败");
    }

    // ------------------------------------------------------------------ 推广会员

    /** {@code reward}（User.php:811-837）：{@code level} 为 '2'/'3'（按字节）时按 user_pid_2/3，否则 user_pid。 */
    public RecordPage<FinanceRecords.Invitee> invitees(MemberState member, Map<String, PhpInput.Value> params) {
        int level = invitationLevel(params);
        int page = FinanceSupport.page(params);
        int limit = FinanceSupport.limit(params, 20, 20);
        long total = reader.countInvitees(level, member.userId());
        long offset = (long) (page - 1) * limit;
        List<FinanceRecords.Invitee> rows = offset >= total ? List.of()
                : reader.selectInvitees(level, member.userId(), offset, limit).stream().map(MemberWalletService::toRecord)
                        .toList();
        return new RecordPage<>(rows, total, page, limit);
    }

    /** 推广层级 1～3。 */
    public static int invitationLevel(Map<String, PhpInput.Value> params) {
        String level = FinanceSupport.text(params, "level");
        return level.equals("2") ? 2 : level.equals("3") ? 3 : 1;
    }

    private static FinanceRecords.Invitee toRecord(InviteeRow r) {
        return new FinanceRecords.Invitee(nz(r.userId()), str(r.userName()), str(r.nickName()), nz(r.regTime()),
                nz(r.loginTime()));
    }

    private void requireWriter() {
        if (writer == null) {
            throw new IllegalStateException("会员账户功能需要可写数据源");
        }
    }
}
