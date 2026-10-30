package com.maccms.backend.admin.infrastructure;

import com.maccms.backend.common.write.WriteMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 管理员的写入（B12，21 文档第 5 节；登记 WriteRegistry：admin / mac_admin / INSERT、UPDATE、DELETE）。SQL 见
 * {@code resources/mapper-write/admin/AdminWriteMapper.xml}；只能经 {@code WriteDatabase.mapper()} 取得
 * （{@link AdminInfrastructureConfiguration}）。每个方法返回<b>匹配</b>行数，调用方与预期比较。
 */
@WriteMapper
public interface AdminWriteMapper {

    int recordLogin(@Param("adminId") long adminId, @Param("pwd") String pwd, @Param("now") long now,
                    @Param("ip") long ip, @Param("random") String random);

    int insert(@Param("name") String name, @Param("pwd") String pwd, @Param("status") int status,
               @Param("auth") String auth);

    /**
     * @param pwd    为 null 时不改密码
     * @param status 为 null 时不改状态
     */
    int update(@Param("adminId") long adminId, @Param("name") String name, @Param("pwd") String pwd,
               @Param("status") Integer status, @Param("auth") String auth);

    int delete(@Param("ids") List<Long> ids);

    int setStatus(@Param("ids") List<Long> ids, @Param("status") int status);
}
