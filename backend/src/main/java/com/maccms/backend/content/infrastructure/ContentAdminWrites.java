package com.maccms.backend.content.infrastructure;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 后台内容管理写入的执行（B13，22 文档第 6 节）：经 {@link ContentAdminWriteMapper} 执行，把 MySQL 严格模式下的数据错误换成
 * {@link AdminDataException}，其余异常原样抛出。
 */
public final class ContentAdminWrites {

    /** 数据错误：1048 列不能为 NULL、1264 超出范围、1265 截断、1292 值不正确、1364 没有默认值的列未给值、1366 整数/字符串值不正确、1406 过长。 */
    private static final Set<Integer> DATA_ERRORS = Set.of(1048, 1264, 1265, 1292, 1364, 1366, 1406);

    private final ContentAdminWriteMapper mapper;

    public ContentAdminWrites(ContentAdminWriteMapper mapper) {
        this.mapper = mapper;
    }

    public ContentAdminWriteMapper mapper() {
        return mapper;
    }

    /** 执行一条写入；数据错误换成 {@link AdminDataException}。 */
    public static <T> T run(Supplier<T> write) {
        try {
            return write.get();
        } catch (RuntimeException e) {
            if (dataError(e)) {
                throw new AdminDataException();
            }
            throw e;
        }
    }

    /** 新增一行，返回自增主键。 */
    public long insert(String table, Map<String, String> columns) {
        AdminInsert row = new AdminInsert(columns);
        run(() -> switch (table) {
            case "mac_vod" -> mapper.insertVod(row);
            case "mac_art" -> mapper.insertArt(row);
            case "mac_topic" -> mapper.insertTopic(row);
            case "mac_actor" -> mapper.insertActor(row);
            case "mac_role" -> mapper.insertRole(row);
            case "mac_website" -> mapper.insertWebsite(row);
            case "mac_link" -> mapper.insertLink(row);
            case "mac_type" -> mapper.insertType(row);
            case "mac_annex" -> mapper.insertAnnex(row);
            default -> throw new IllegalArgumentException(table);
        });
        return row.getId() == null ? 0 : row.getId();
    }

    /** 按主键整行覆盖（只写给出的列），返回匹配行数；没有列时不执行（旧版 ThinkPHP 对空数据返回 0 行）。 */
    public int update(String table, Map<String, String> columns, long id) {
        if (columns.isEmpty()) {
            return 0;
        }
        return run(() -> switch (table) {
            case "mac_vod" -> mapper.updateVod(columns, id);
            case "mac_art" -> mapper.updateArt(columns, id);
            case "mac_topic" -> mapper.updateTopic(columns, id);
            case "mac_actor" -> mapper.updateActor(columns, id);
            case "mac_role" -> mapper.updateRole(columns, id);
            case "mac_website" -> mapper.updateWebsite(columns, id);
            case "mac_link" -> mapper.updateLink(columns, id);
            case "mac_type" -> mapper.updateType(columns, id);
            case "mac_annex" -> mapper.updateAnnex(columns, id);
            default -> throw new IllegalArgumentException(table);
        });
    }

    /** 按主键列表修改（只写给出的列），返回匹配行数；列或主键为空时不执行。 */
    public int updateByIds(String table, Map<String, String> columns, List<Long> ids) {
        if (columns.isEmpty() || ids.isEmpty()) {
            return 0;
        }
        return run(() -> switch (table) {
            case "mac_vod" -> mapper.updateVodByIds(columns, ids);
            case "mac_art" -> mapper.updateArtByIds(columns, ids);
            case "mac_topic" -> mapper.updateTopicByIds(columns, ids);
            case "mac_actor" -> mapper.updateActorByIds(columns, ids);
            case "mac_role" -> mapper.updateRoleByIds(columns, ids);
            case "mac_website" -> mapper.updateWebsiteByIds(columns, ids);
            case "mac_link" -> mapper.updateLinkByIds(columns, ids);
            case "mac_type" -> mapper.updateTypeByIds(columns, ids);
            case "mac_annex" -> mapper.updateAnnexByIds(columns, ids);
            default -> throw new IllegalArgumentException(table);
        });
    }

    /** 按主键列表删除，返回删除行数；列表为空时不执行。 */
    public int delete(String table, List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return run(() -> switch (table) {
            case "mac_vod" -> mapper.deleteVodByIds(ids);
            case "mac_art" -> mapper.deleteArtByIds(ids);
            case "mac_topic" -> mapper.deleteTopicByIds(ids);
            case "mac_actor" -> mapper.deleteActorByIds(ids);
            case "mac_role" -> mapper.deleteRoleByIds(ids);
            case "mac_website" -> mapper.deleteWebsiteByIds(ids);
            case "mac_link" -> mapper.deleteLinkByIds(ids);
            case "mac_type" -> mapper.deleteTypeByIds(ids);
            case "mac_annex" -> mapper.deleteAnnexByIds(ids);
            default -> throw new IllegalArgumentException(table);
        });
    }

    static boolean dataError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && DATA_ERRORS.contains(s.getErrorCode())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
