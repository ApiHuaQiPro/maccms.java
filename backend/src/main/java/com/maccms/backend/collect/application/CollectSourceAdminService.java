package com.maccms.backend.collect.application;

import static com.maccms.backend.collect.application.CollectAdminSupport.guard;

import com.maccms.backend.collect.infrastructure.CollectAdminMapper;
import com.maccms.backend.collect.infrastructure.CollectAdminWrites;
import com.maccms.backend.content.application.AdminIds;
import com.maccms.backend.content.application.AdminPaging;
import com.maccms.backend.content.application.AdminRowValues;
import com.maccms.backend.content.application.AdminTable;
import com.maccms.backend.content.application.PhpForm;
import com.maccms.backend.content.application.view.AdminMessage;
import com.maccms.backend.content.application.view.AdminResult;
import com.maccms.backend.content.application.view.AdminRow;
import com.maccms.backend.content.application.view.AdminRowPage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 资源站采集源（B17，25 文档第 3 节）：旧 {@code admin/controller/Collect.php} 的 {@code index}、{@code info}、{@code del} →
 * {@code common/model/Collect.php:23-77}（{@code listData}、{@code infoData}、{@code saveData}、{@code delData}）。
 */
public class CollectSourceAdminService {

    /** 旧 {@code Collect::index}：{@code intval($param['limit']) < 1 ? 100 : $param['limit']}（不用后台每页条数）。 */
    public static final int DEFAULT_LIMIT = 100;

    private final CollectAdminMapper reader;
    private final CollectAdminWrites writer;

    public CollectSourceAdminService(CollectAdminMapper reader, CollectAdminWrites writer) {
        this.reader = reader;
        this.writer = writer;
    }

    /** 列表（旧 Collect::index）：无条件，{@code collect_id desc}。 */
    public AdminRowPage list(byte[] page, byte[] limit) {
        AdminPaging paging = AdminPaging.of(page, limit, DEFAULT_LIMIT);
        long total = reader.countCollects();
        List<AdminRow> rows = new ArrayList<>();
        if (!paging.beyond()) {
            for (Map<String, String> r : reader.listCollects(paging.offset(), paging.limit())) {
                rows.add(new AdminRow(r));
            }
        }
        return new AdminRowPage(rows, total, paging.page(), paging.limit(), null);
    }

    /** 表单（旧 Collect::info 的 GET）：{@code where collect_id = input('id')}，没有时为空表单。 */
    public Optional<AdminRow> info(byte[] id) {
        return AdminIds.lookupId(id).map(reader::selectCollect).map(AdminRow::new);
    }

    /**
     * 保存（旧 Collect::info 的 POST，令牌已由调用方校验）：{@code input('post.')} 交给 {@code saveData}——验证器的 {@code edit} 场景
     * （名称、地址必填，新增与编辑都用它），{@code collect_id} 非空时按主键整行覆盖（只写表里有的列，含主键本身），否则新增。
     */
    public AdminResult save(PhpForm post) {
        return guard(() -> {
            PhpForm data = post.copy();
            if (!data.require("collect_name")) {
                return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_name"));
            }
            if (!data.require("collect_url")) {
                return AdminResult.error(AdminMessage.lang("param_err").thenText("：").thenLang("validate/require_url"));
            }
            if (!data.empty("collect_id")) {
                long id = AdminIds.primaryKey(data.scalar("collect_id"));
                writer.updateCollect(AdminRowValues.of(AdminTable.COLLECT, data, Set.of()), id);
            } else {
                writer.insertCollect(AdminRowValues.of(AdminTable.COLLECT, data, Set.of("collect_id")));
            }
            return AdminResult.ok("save_ok");
        });
    }

    /** 删除（旧 Collect::del）：{@code ids} 为逗号串或数组；为空时"参数错误"。 */
    public AdminResult delete(PhpForm param) {
        return guard(() -> {
            List<Long> ids = AdminIds.ids(param);
            if (ids == null) {
                return AdminResult.error("param_err");
            }
            writer.deleteCollects(ids);
            return AdminResult.ok("del_ok");
        });
    }
}
