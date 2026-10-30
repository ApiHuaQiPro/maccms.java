package com.maccms.backend.admin.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只读。SQL 见 resources/mapper/admin/AdminMapper.xml。写入在 {@link AdminWriteMapper}（B12，可写数据源）。 */
@Mapper
public interface AdminMapper {

    /** 按主键读当前行（D2 ⑤：每个后台请求都按会话里的 admin_id 重读）。 */
    AdminRow selectById(@Param("adminId") long adminId);

    /**
     * 旧 {@code Admin::login} 的查询（{@code common/model/Admin.php:123-128}）：{@code admin_name}、{@code admin_pwd} 与
     * {@code admin_status=1}。{@code admin_name} 按列的排序规则比较（不分大小写、忽略尾部空格），与旧版相同。旧版 {@code find()} 不排序，
     * 这里按 {@code admin_id} 取第一行，同名同密码多行时结果确定。
     */
    AdminRow selectForLogin(@Param("name") String name, @Param("pwd") String pwd);

    /**
     * 管理员列表的总数（{@code admin/controller/Admin.php:16-24}，{@code common/model/Admin.php:23-28}）。
     *
     * @param like {@code null} 表示不按名称筛选；否则是已转义的 LIKE 模式（{@code !} 为转义符）
     */
    long count(@Param("like") String like);

    /** 管理员列表的一页，按 {@code admin_id} 倒序。 */
    List<AdminRow> list(@Param("like") String like, @Param("offset") long offset, @Param("limit") int limit);
}
