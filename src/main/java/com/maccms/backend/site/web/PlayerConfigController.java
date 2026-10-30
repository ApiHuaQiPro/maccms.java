package com.maccms.backend.site.web;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.site.application.PlayerSettings;
import com.maccms.backend.site.config.ConditionalOnSiteEnabled;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 旧路径 {@code /static/js/playerconfig.js}（B05，W1）：内容由启动时读取的旧配置生成（{@link PlayerSettings#playerConfigScript()}），
 * W1 打开时与旧后台生成的文件逐字节相同，关闭时不含外部解析、缓冲与预加载地址。没有旧配置时 404。查询串（旧模板的 {@code ?t=}）忽略。
 * 旧版这是后台写出的静态文件（B15 负责写回）；新版不读写该文件。
 */
@Controller
@ConditionalOnSiteEnabled
public class PlayerConfigController {

    static final MediaType JAVASCRIPT_UTF8 = new MediaType("text", "javascript", StandardCharsets.UTF_8);

    private final PlayerSettings player;

    public PlayerConfigController(PlayerSettings player) {
        this.player = player;
    }

    @GetMapping("/" + SitePages.PLAYER_CONFIG_PATH)
    public ResponseEntity<String> script() {
        if (!player.available()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "页面不存在");
        }
        String body = player.playerConfigScript();
        return ResponseEntity.ok()
                .contentType(JAVASCRIPT_UTF8)
                .contentLength(body.getBytes(StandardCharsets.UTF_8).length)
                .cacheControl(CacheControl.noCache())
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }
}
