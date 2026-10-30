package com.maccms.backend.content.application;

/**
 * 一次购买（{@code mac_ulog} 的一行）除会员以外的六项（B10，19 文档第 5.1 节）：旧 {@code check_user_popedom} 与
 * {@code ajax_buy_popedom} 都按这六项加 {@code user_id} 精确匹配，所以价格改动后旧的购买记录不再匹配（照旧）。
 *
 * @param mid    {@code ulog_mid}：1 视频、2 文章
 * @param type   {@code ulog_type}：1 详情（文章）、4 播放、5 下载
 * @param rid    {@code ulog_rid}
 * @param sid    {@code ulog_sid}（整片/整篇收费时为 0；文章为页码）
 * @param nid    {@code ulog_nid}
 * @param points {@code ulog_points}（判定时的价格）
 */
public record PurchaseKey(int mid, int type, long rid, int sid, int nid, int points) {
}
