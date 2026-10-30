package com.maccms.backend.install.infrastructure;

import com.maccms.backend.common.write.WriteMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 安装向导的写入（B22，31 文档第 6 节；登记 WriteRegistry：install / mac_type、mac_admin / INSERT）。SQL 见
 * {@code resources/mapper-write/install/InstallWriteMapper.xml}；只能经 {@code WriteDatabase.mapper()} 取得
 * （{@link InstallConnections#openWrite}）。返回匹配行数。
 */
@WriteMapper
public interface InstallWriteMapper {

    /** 一行初始分类（旧 initdata.sql 的一条 {@code INSERT INTO mac_type VALUES (…)}，20 个值按表定义的列顺序）。 */
    int insertType(@Param("v") List<String> values);

    int insertAdmin(@Param("name") String name, @Param("pwd") String pwd, @Param("status") int status,
                    @Param("auth") String auth);
}
