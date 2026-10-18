package com.maccms.backend.content.application;

import java.util.List;
import java.util.Optional;

/**
 * 旧协议整行读取涉及的内容表（B06，docs/architecture/16-b06-misc-pages-provide.md 第 3.5、4.10 节）。模型编号与旧
 * {@code mac_get_mid_code}（application/common.php:1055-1071）相同。
 *
 * <p>{@link #fullColumns()} 是旧 {@code SELECT *} 的列顺序（install.sql，去掉访问密码列），{@link #listColumns()} 是 Provide
 * 列表的字段顺序（api/controller/Provide.php:125、:396、:507、:619、:719，含旧版的 {@code "" as type_name}）。两份清单与
 * LegacyRowMapper.xml 由同一份列清单生成，LegacyRowMapperMySqlIT 核对它们与一次性库的 information_schema 一致。
 */
public enum LegacyTable {
    VOD("vod", 1, true,
            List.of(
                    "vod_id", "type_id", "type_id_1", "group_id", "vod_name", "vod_sub", "vod_en", "vod_status",
                    "vod_letter", "vod_color", "vod_tag", "vod_class", "vod_pic", "vod_pic_thumb", "vod_pic_slide",
                    "vod_pic_screenshot", "vod_actor", "vod_director", "vod_writer", "vod_behind", "vod_blurb",
                    "vod_remarks", "vod_pubdate", "vod_total", "vod_serial", "vod_tv", "vod_weekday", "vod_area",
                    "vod_lang", "vod_year", "vod_version", "vod_state", "vod_author", "vod_jumpurl", "vod_tpl",
                    "vod_tpl_play", "vod_tpl_down", "vod_isend", "vod_lock", "vod_level", "vod_copyright",
                    "vod_points", "vod_points_play", "vod_points_down", "vod_hits", "vod_hits_day", "vod_hits_week",
                    "vod_hits_month", "vod_duration", "vod_up", "vod_down", "vod_score", "vod_score_all",
                    "vod_score_num", "vod_time", "vod_time_add", "vod_time_hits", "vod_time_make", "vod_trysee",
                    "vod_douban_id", "vod_douban_score", "vod_reurl", "vod_rel_vod", "vod_rel_art", "vod_content",
                    "vod_play_from", "vod_play_server", "vod_play_note", "vod_play_url", "vod_down_from",
                    "vod_down_server", "vod_down_note", "vod_down_url", "vod_plot", "vod_plot_name", "vod_plot_detail"),
            List.of(
                    "vod_id", "vod_name", "type_id", "type_name", "vod_en", "vod_time", "vod_remarks", "vod_play_from")),
    ART("art", 2, true,
            List.of(
                    "art_id", "type_id", "type_id_1", "group_id", "art_name", "art_sub", "art_en", "art_status",
                    "art_letter", "art_color", "art_from", "art_author", "art_tag", "art_class", "art_pic",
                    "art_pic_thumb", "art_pic_slide", "art_pic_screenshot", "art_blurb", "art_remarks",
                    "art_jumpurl", "art_tpl", "art_level", "art_lock", "art_points", "art_points_detail", "art_up",
                    "art_down", "art_hits", "art_hits_day", "art_hits_week", "art_hits_month", "art_time",
                    "art_time_add", "art_time_hits", "art_time_make", "art_score", "art_score_all", "art_score_num",
                    "art_rel_art", "art_rel_vod", "art_title", "art_note", "art_content"),
            List.of(
                    "art_id", "art_name", "type_id", "type_name", "art_en", "art_time", "art_author", "art_from",
                    "art_remarks", "art_pic")),
    TOPIC("topic", 3, false,
            List.of(
                    "topic_id", "topic_name", "topic_en", "topic_sub", "topic_status", "topic_sort", "topic_letter",
                    "topic_color", "topic_tpl", "topic_type", "topic_pic", "topic_pic_thumb", "topic_pic_slide",
                    "topic_key", "topic_des", "topic_title", "topic_blurb", "topic_remarks", "topic_level",
                    "topic_up", "topic_down", "topic_score", "topic_score_all", "topic_score_num", "topic_hits",
                    "topic_hits_day", "topic_hits_week", "topic_hits_month", "topic_time", "topic_time_add",
                    "topic_time_hits", "topic_time_make", "topic_tag", "topic_rel_vod", "topic_rel_art",
                    "topic_content", "topic_extend"),
            List.of(
                    "topic_id", "topic_name", "topic_en", "topic_time", "topic_pic")),
    ACTOR("actor", 8, true,
            List.of(
                    "actor_id", "type_id", "type_id_1", "actor_name", "actor_en", "actor_alias", "actor_status",
                    "actor_lock", "actor_letter", "actor_sex", "actor_color", "actor_pic", "actor_blurb",
                    "actor_remarks", "actor_area", "actor_height", "actor_weight", "actor_birthday",
                    "actor_birtharea", "actor_blood", "actor_starsign", "actor_school", "actor_works", "actor_tag",
                    "actor_class", "actor_level", "actor_time", "actor_time_add", "actor_time_hits",
                    "actor_time_make", "actor_hits", "actor_hits_day", "actor_hits_week", "actor_hits_month",
                    "actor_score", "actor_score_all", "actor_score_num", "actor_up", "actor_down", "actor_tpl",
                    "actor_jumpurl", "actor_content"),
            List.of(
                    "actor_id", "actor_name", "type_id", "type_name", "actor_en", "actor_area", "actor_time",
                    "actor_alias", "actor_sex", "actor_pic")),
    ROLE("role", 9, false,
            List.of(
                    "role_id", "role_rid", "role_name", "role_en", "role_status", "role_lock", "role_letter",
                    "role_color", "role_actor", "role_remarks", "role_pic", "role_sort", "role_level", "role_time",
                    "role_time_add", "role_time_hits", "role_time_make", "role_hits", "role_hits_day",
                    "role_hits_week", "role_hits_month", "role_score", "role_score_all", "role_score_num", "role_up",
                    "role_down", "role_tpl", "role_jumpurl", "role_content"),
            List.of(
                    "role_id", "role_name", "role_rid", "role_en", "role_actor", "role_time", "role_pic")),
    WEBSITE("website", 11, true,
            List.of(
                    "website_id", "type_id", "type_id_1", "website_name", "website_sub", "website_en",
                    "website_status", "website_letter", "website_color", "website_lock", "website_sort",
                    "website_jumpurl", "website_pic", "website_pic_screenshot", "website_logo", "website_area",
                    "website_lang", "website_level", "website_time", "website_time_add", "website_time_hits",
                    "website_time_make", "website_time_referer", "website_hits", "website_hits_day",
                    "website_hits_week", "website_hits_month", "website_score", "website_score_all",
                    "website_score_num", "website_up", "website_down", "website_referer", "website_referer_day",
                    "website_referer_week", "website_referer_month", "website_tag", "website_class",
                    "website_remarks", "website_tpl", "website_blurb", "website_content"),
            List.of(
                    "website_id", "website_name", "type_id", "type_name", "website_en", "website_time",
                    "website_area", "website_lang", "website_pic"));

    private final String prefix;
    private final int mid;
    private final boolean typed;
    private final List<String> fullColumns;
    private final List<String> listColumns;

    LegacyTable(String prefix, int mid, boolean typed, List<String> fullColumns, List<String> listColumns) {
        this.prefix = prefix;
        this.mid = mid;
        this.typed = typed;
        this.fullColumns = fullColumns;
        this.listColumns = listColumns;
    }

    /** 列名前缀（{@code vod}、{@code art} 等）。 */
    public String prefix() {
        return prefix;
    }

    /** 旧模型编号（1 视频、2 文章、3 专题、8 演员、9 角色、11 网址）。 */
    public int mid() {
        return mid;
    }

    /** 是否有 {@code type_id}（专题、角色没有分类）。 */
    public boolean typed() {
        return typed;
    }

    public List<String> fullColumns() {
        return fullColumns;
    }

    public List<String> listColumns() {
        return listColumns;
    }

    public static Optional<LegacyTable> byMid(int mid) {
        for (LegacyTable t : values()) {
            if (t.mid == mid) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
