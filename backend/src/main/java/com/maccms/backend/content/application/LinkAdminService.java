package com.maccms.backend.content.application;

import com.maccms.backend.content.application.view.AdminMessage;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import com.maccms.backend.content.infrastructure.ContentAdminMapper;
import com.maccms.backend.content.infrastructure.ContentAdminWrites;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 后台友情链接（B13，22 文档第 7.8 节）：旧 {@code admin/controller/Link.php} → {@code common/model/Link.php}。
 */
public class LinkAdminService {

    private final ContentAdminMapper reader;
    private final ContentAdminWrites writer;
    private final Clock clock;

    public LinkAdminService(ContentAdminMapper reader, ContentAdminWrites writer, Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.clock = clock;
    }

    /**
     * 列表（旧 Link::index）：{@code wd} 非 {@code empty()} 时按 {@code link_name LIKE '%' . htmlspecialchars(urldecode(wd)) . '%'}，
     * {@code link_id} 倒序。
     */
    public AdminRowPage list(byte[] wd, AdminPaging paging) {
        String word = null;
        String like = null;
        if (!PhpForm.phpEmpty(wd)) {
            word = new String(PhpText.htmlspecialcharsCompat(PhpText.urldecode(wd)), StandardCharsets.UTF_8);
            like = PhpText.legacyLike(word);
        }
        long total = reader.countLinks(like);
        List<AdminRow> rows = new ArrayList<>();
        if (!paging.beyond()) {
            for (Map<String, String> r : reader.listLinks(like, paging.offset(), paging.limit())) {
                rows.add(new AdminRow(r));
            }
        }
        return new AdminRowPage(rows, total, paging.page(), paging.limit(), word);
    }

    /** 表单（旧 Link::info 的 GET）：按 {@code input('id')} 读一行，没有时为空表单。 */
    public Optional<AdminRow> info(byte[] id) {
        return AdminIds.lookupId(id).map(reader::selectLink).map(AdminRow::new);
    }

    /** 保存（旧 Link::info 的 POST，{@code input()} 交给 {@code Link::saveData}）。 */
    public AdminResult save(PhpForm param) {
        return AdminWrites.guard(() -> saveData(param.copy()));
    }

    /**
     * 批量修改（旧 Link::batch）：{@code ids[]} 的每一项与同位置的 {@code link_name[] link_sort[] link_url[] link_type[] link_logo[]}
     * 组成一行交给 {@code saveData}，名称为空时用 {@code lang('unknown')}；某一行保存失败时立即返回该提示（之前的行已保存）。
     */
    public AdminResult batch(PhpForm param, String unknownName) {
        PhpForm.Arr ids = param.array("ids");
        if (ids == null) {
            // 旧版 foreach 非数组：PHP 8 报错（500），7.3 不进入循环后以未定义的 $res 输出
            return AdminResult.error("param_err");
        }
        return AdminWrites.guard(() -> {
            AdminResult last = AdminResult.ok("save_ok");
            for (Map.Entry<String, byte[]> e : ids.entries().entrySet()) {
                String k = e.getKey();
                PhpForm data = PhpForm.empty();
                data.put("link_id", Long.toString(PhpIntval.of(latin1(e.getValue()))));
                AdminIds.item(data, "link_name", param, "link_name", k);
                AdminIds.item(data, "link_sort", param, "link_sort", k);
                AdminIds.item(data, "link_url", param, "link_url", k);
                AdminIds.item(data, "link_type", param, "link_type", k);
                // intval(null) 为 0
                data.put("link_type", Long.toString(data.isNull("link_type") ? 0 : PhpIntval.of(latin1(data.scalar("link_type")))));
                AdminIds.item(data, "link_logo", param, "link_logo", k);
                if (data.empty("link_name")) {
                    data.put("link_name", unknownName);
                }
                last = saveData(data);
                if (!last.ok()) {
                    return last;
                }
            }
            return last;
        });
    }

    /** 删除（旧 Link::del）：{@code ids} 为逗号串或数组；为空时"参数错误"。 */
    public AdminResult delete(PhpForm param) {
        return AdminWrites.guard(() -> {
            List<Long> ids = AdminIds.ids(param);
            if (ids == null) {
                return AdminResult.error("param_err");
            }
            writer.delete(AdminTable.LINK.table(), ids);
            return AdminResult.ok("del_ok");
        });
    }

    /**
     * 旧 {@code Link::saveData}（common/model/Link.php:96-118）：验证名称、网址必填 → {@code link_time = time()} → {@code link_id}
     * 非空时按主键整行覆盖，否则另写 {@code link_add_time} 后新增。
     */
    private AdminResult saveData(PhpForm data) {
        if (!data.require("link_name")) {
            return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_name"));
        }
        if (!data.require("link_url")) {
            return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_url"));
        }
        long now = clock.instant().getEpochSecond();
        data.put("link_time", Long.toString(now));
        if (!data.empty("link_id")) {
            long id = AdminIds.primaryKey(data.scalar("link_id"));
            writer.update(AdminTable.LINK.table(), AdminRowValues.of(AdminTable.LINK, data, Set.of("link_id")), id);
        } else {
            data.put("link_add_time", Long.toString(now));
            writer.insert(AdminTable.LINK.table(), AdminRowValues.of(AdminTable.LINK, data, Set.of("link_id")));
        }
        return AdminResult.ok("save_ok");
    }

    private static String latin1(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }
}
