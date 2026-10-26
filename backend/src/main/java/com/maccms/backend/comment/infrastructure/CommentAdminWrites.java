package com.maccms.backend.comment.infrastructure;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 后台评论与留言写入的执行（B13，22 文档第 6 节）：经 {@link CommentAdminWriteMapper} 执行，把 MySQL 严格模式下的数据错误换成
 * {@link CommentDataException}，其余异常原样抛出（与 content 模块的后台写入相同）。
 */
public final class CommentAdminWrites {

    /** 数据错误：1048 列不能为 NULL、1264 超出范围、1265 截断、1292 值不正确、1364 没有默认值的列未给值、1366 整数/字符串值不正确、1406 过长。 */
    private static final Set<Integer> DATA_ERRORS = Set.of(1048, 1264, 1265, 1292, 1364, 1366, 1406);

    private final CommentAdminWriteMapper mapper;

    public CommentAdminWrites(CommentAdminWriteMapper mapper) {
        this.mapper = mapper;
    }

    /** 新增一行（{@code comment} 为真时 mac_comment，否则 mac_gbook），返回自增主键。 */
    public long insert(boolean comment, Map<String, String> columns) {
        CommentAdminInsert row = new CommentAdminInsert(columns);
        run(() -> comment ? mapper.insertComment(row) : mapper.insertGbook(row));
        return row.getId() == null ? 0 : row.getId();
    }

    /** 按主键整行覆盖（只写给出的列）；没有列时不执行（旧版 ThinkPHP 对空数据返回 0 行）。 */
    public int update(boolean comment, Map<String, String> columns, long id) {
        if (columns.isEmpty()) {
            return 0;
        }
        return run(() -> comment ? mapper.updateComment(columns, id) : mapper.updateGbook(columns, id));
    }

    /** 按主键列表修改；列或主键为空时不执行。 */
    public int updateByIds(boolean comment, Map<String, String> columns, List<Long> ids) {
        if (columns.isEmpty() || ids.isEmpty()) {
            return 0;
        }
        return run(() -> comment ? mapper.updateCommentByIds(columns, ids) : mapper.updateGbookByIds(columns, ids));
    }

    /** 按主键列表删除；列表为空时不执行。 */
    public int delete(boolean comment, List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return run(() -> comment ? mapper.deleteCommentByIds(ids) : mapper.deleteGbookByIds(ids));
    }

    /** 全部删除（旧"清空"）。 */
    public int deleteAll(boolean comment) {
        return run(() -> comment ? mapper.deleteAllComments() : mapper.deleteAllGbooks());
    }

    private static <T> T run(Supplier<T> write) {
        try {
            return write.get();
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException s && DATA_ERRORS.contains(s.getErrorCode())) {
                    throw new CommentDataException();
                }
                if (t.getCause() == t) {
                    break;
                }
            }
            throw e;
        }
    }
}
