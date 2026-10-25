package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;
import java.util.Optional;

/**
 * 播放器内页（旧 vod/player，B05，D-B05-1）：唯一允许脚本的页面，专用 CSP，每个响应一个 nonce。播放器数据只以 JSON 放在
 * {@code div#player-stage} 的 {@code data-player} 属性中（由 jte 按属性转义），由同源脚本读出后设置旧 player.js 需要的
 * {@code maccms}、{@code player_aaaa} 全局变量；页面中没有内联脚本。
 *
 * @param frame        精简外壳
 * @param nonce        本响应的 CSP nonce
 * @param playerData   {@code {"maccms":{...},"player":{...}}} 的 JSON 文本
 * @param trialMinutes 试看计时（分钟，旧 application/lang/zh-cn.php:1229）；0 表示不计时
 * @param scripts      依次加载的同源脚本：player-boot.js、jquery.js、playerconfig.js、player.js
 * @param trialScript  试看脚本 site/trial.js（只在 trialMinutes &gt; 0 时）
 * @param trialEndMessage 试看结束时的文字（B10：已登录会员为旧版 5001/5002 的提示，19 文档第 5.3 节）；空串时 trial.js 用默认文字
 */
public record PlayerFramePage(PlayerFrame frame, String nonce, String playerData, int trialMinutes,
                              List<SafeUrl> scripts, Optional<SafeUrl> trialScript, String trialEndMessage) {

    public PlayerFramePage(PlayerFrame frame, String nonce, String playerData, int trialMinutes,
                           List<SafeUrl> scripts, Optional<SafeUrl> trialScript) {
        this(frame, nonce, playerData, trialMinutes, scripts, trialScript, "");
    }
}
