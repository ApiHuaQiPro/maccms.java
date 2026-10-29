package com.maccms.backend.install.infrastructure;

import com.maccms.backend.common.write.WriteDatabase;
import java.util.List;

/**
 * 第五步的写库（B22，31 文档第 6 节）：经 B07 可写数据源对目标库执行登记表中 install 的两条 INSERT——可选的初始分类、第一个管理员。
 * 旧表 MyISAM 没有事务，任何一行失败即停止并报告（已写入的行留给部署方处理，31 文档第 6.3 节）。
 */
public class InstallWrites {

    /** 写库结果。 */
    public enum Result {
        OK, INIT_DATA_FAILED, ADMIN_FAILED
    }

    private final InstallConnections connections;

    public InstallWrites(InstallConnections connections) {
        this.connections = connections;
    }

    /**
     * @param types 初始分类（为空表示不导入）
     * @param pwd   已按旧版 md5 的密码
     */
    public Result write(InstallTarget target, boolean allowRoot, List<List<String>> types, String name, String pwd) {
        try (WriteDatabase db = connections.openWrite(target, allowRoot)) {
            InstallWriteMapper w = db.mapper(InstallWriteMapper.class);
            for (List<String> row : types) {
                if (w.insertType(row) != 1) {
                    return Result.INIT_DATA_FAILED;
                }
            }
            return w.insertAdmin(name, pwd, 1, "") == 1 ? Result.OK : Result.ADMIN_FAILED;
        }
    }
}
