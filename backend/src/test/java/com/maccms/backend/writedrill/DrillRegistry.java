package com.maccms.backend.writedrill;

import com.maccms.backend.common.write.WriteOperation;
import com.maccms.backend.common.write.WritePermission;
import com.maccms.backend.common.write.WriteRegistry;
import java.util.List;

/**
 * 演练登记表：只在一次性库 {@code maccms_it_*} 上使用的合成写入权限（模块 {@code drill}，批次固定 B07）。
 * 故意不登记：{@code mac_gbook} 的 INSERT（表未登记）、{@code mac_user} 的 DELETE（操作未登记）、模块 {@code drillother}
 * （模块未登记），用来证明静态护栏与运行期拦截器都能抓到。
 */
public final class DrillRegistry {

    public static final String BATCH = "B07";

    private static final List<WritePermission> PERMISSIONS = List.of(
            new WritePermission("drill", "mac_user", WriteOperation.UPDATE, BATCH),
            new WritePermission("drill", "mac_card", WriteOperation.UPDATE, BATCH),
            new WritePermission("drill", "mac_vod", WriteOperation.UPDATE, BATCH),
            new WritePermission("drill", "mac_plog", WriteOperation.INSERT, BATCH),
            new WritePermission("drill", "mac_plog", WriteOperation.DELETE, BATCH),
            new WritePermission("drill", "mac_ulog", WriteOperation.INSERT, BATCH));

    private DrillRegistry() {
    }

    public static WriteRegistry registry() {
        return WriteRegistry.of(PERMISSIONS);
    }
}
