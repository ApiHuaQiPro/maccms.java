package com.maccms.backend.collect.application;

import com.maccms.backend.collect.infrastructure.CollectDataException;
import com.maccms.backend.content.application.AdminRejectedException;
import com.maccms.backend.content.application.view.AdminResult;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/** B17 后台采集配置服务的公共收尾与取值（与 B13、B14 的后台服务相同）。 */
final class CollectAdminSupport {

    private CollectAdminSupport() {
    }

    /** 参数被拒绝、MySQL 拒绝取值时都按旧版的 {@code error()} 返回提示（D-B13-3 的同一规则）。 */
    static AdminResult guard(Supplier<AdminResult> action) {
        try {
            return action.get();
        } catch (AdminRejectedException e) {
            return AdminResult.error(e.reason());
        } catch (CollectDataException e) {
            return AdminResult.error("param_err");
        }
    }

    /** 合法 UTF-8 时的文字，否则"参数错误"。 */
    static String utf8(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            throw AdminRejectedException.paramError();
        }
    }

    static String latin1(byte[] raw) {
        return new String(raw, StandardCharsets.ISO_8859_1);
    }
}
