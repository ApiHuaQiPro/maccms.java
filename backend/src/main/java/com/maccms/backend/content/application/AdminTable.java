package com.maccms.backend.content.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台内容管理（B13，docs/architecture/22-b13-admin-content.md 第 3 节）写入的旧表与各列的类型，逐列照抄
 * {@code application/install/sql/install.sql}（{@code AdminTableSchemaTest} 与该文件逐列比对：列名、顺序、类型、长度、有无符号）。
 * B14（23 文档第 3 节）另加后台会员编辑写的 {@code mac_user}、{@code mac_group}，取值规则相同，由 member 模块使用。
 *
 * <p>旧版保存时用 ThinkPHP {@code allowField(true)}：提交的字段里凡是表中有的列都写进去（整行覆盖，含计数列），其余忽略。新版按这里的
 * 列清单做同样的过滤，并在写库前按列类型检查取值（{@link AdminRowValues}）。
 */
public enum AdminTable {
    VOD("mac_vod", "vod_id", List.of(
            c("vod_id", Kind.INT, 0, 0L, 4294967295L),
            c("type_id", Kind.INT, 0, -32768L, 32767L),
            c("type_id_1", Kind.INT, 0, 0L, 65535L),
            c("group_id", Kind.INT, 0, 0L, 65535L),
            c("vod_name", Kind.CHAR, 255, 0, 0),
            c("vod_sub", Kind.CHAR, 255, 0, 0),
            c("vod_en", Kind.CHAR, 255, 0, 0),
            c("vod_status", Kind.INT, 0, 0L, 255L),
            c("vod_letter", Kind.CHAR, 1, 0, 0),
            c("vod_color", Kind.CHAR, 6, 0, 0),
            c("vod_tag", Kind.CHAR, 100, 0, 0),
            c("vod_class", Kind.CHAR, 255, 0, 0),
            c("vod_pic", Kind.CHAR, 1024, 0, 0),
            c("vod_pic_thumb", Kind.CHAR, 1024, 0, 0),
            c("vod_pic_slide", Kind.CHAR, 1024, 0, 0),
            c("vod_pic_screenshot", Kind.TEXT, 65535, 0, 0),
            c("vod_actor", Kind.CHAR, 255, 0, 0),
            c("vod_director", Kind.CHAR, 255, 0, 0),
            c("vod_writer", Kind.CHAR, 100, 0, 0),
            c("vod_behind", Kind.CHAR, 100, 0, 0),
            c("vod_blurb", Kind.CHAR, 255, 0, 0),
            c("vod_remarks", Kind.CHAR, 100, 0, 0),
            c("vod_pubdate", Kind.CHAR, 100, 0, 0),
            c("vod_total", Kind.INT, 0, 0L, 16777215L),
            c("vod_serial", Kind.CHAR, 20, 0, 0),
            c("vod_tv", Kind.CHAR, 30, 0, 0),
            c("vod_weekday", Kind.CHAR, 30, 0, 0),
            c("vod_area", Kind.CHAR, 20, 0, 0),
            c("vod_lang", Kind.CHAR, 10, 0, 0),
            c("vod_year", Kind.CHAR, 10, 0, 0),
            c("vod_version", Kind.CHAR, 30, 0, 0),
            c("vod_state", Kind.CHAR, 30, 0, 0),
            c("vod_author", Kind.CHAR, 60, 0, 0),
            c("vod_jumpurl", Kind.CHAR, 150, 0, 0),
            c("vod_tpl", Kind.CHAR, 30, 0, 0),
            c("vod_tpl_play", Kind.CHAR, 30, 0, 0),
            c("vod_tpl_down", Kind.CHAR, 30, 0, 0),
            c("vod_isend", Kind.INT, 0, 0L, 255L),
            c("vod_lock", Kind.INT, 0, 0L, 255L),
            c("vod_level", Kind.INT, 0, 0L, 255L),
            c("vod_copyright", Kind.INT, 0, 0L, 255L),
            c("vod_points", Kind.INT, 0, 0L, 65535L),
            c("vod_points_play", Kind.INT, 0, 0L, 65535L),
            c("vod_points_down", Kind.INT, 0, 0L, 65535L),
            c("vod_hits", Kind.INT, 0, 0L, 16777215L),
            c("vod_hits_day", Kind.INT, 0, 0L, 16777215L),
            c("vod_hits_week", Kind.INT, 0, 0L, 16777215L),
            c("vod_hits_month", Kind.INT, 0, 0L, 16777215L),
            c("vod_duration", Kind.CHAR, 10, 0, 0),
            c("vod_up", Kind.INT, 0, 0L, 16777215L),
            c("vod_down", Kind.INT, 0, 0L, 16777215L),
            d("vod_score", 3, 1, true),
            c("vod_score_all", Kind.INT, 0, 0L, 16777215L),
            c("vod_score_num", Kind.INT, 0, 0L, 16777215L),
            c("vod_time", Kind.INT, 0, 0L, 4294967295L),
            c("vod_time_add", Kind.INT, 0, 0L, 4294967295L),
            c("vod_time_hits", Kind.INT, 0, 0L, 4294967295L),
            c("vod_time_make", Kind.INT, 0, 0L, 4294967295L),
            c("vod_trysee", Kind.INT, 0, 0L, 65535L),
            c("vod_douban_id", Kind.INT, 0, 0L, 4294967295L),
            d("vod_douban_score", 3, 1, true),
            c("vod_reurl", Kind.CHAR, 255, 0, 0),
            c("vod_rel_vod", Kind.CHAR, 255, 0, 0),
            c("vod_rel_art", Kind.CHAR, 255, 0, 0),
            c("vod_pwd", Kind.CHAR, 10, 0, 0),
            c("vod_pwd_url", Kind.CHAR, 255, 0, 0),
            c("vod_pwd_play", Kind.CHAR, 10, 0, 0),
            c("vod_pwd_play_url", Kind.CHAR, 255, 0, 0),
            c("vod_pwd_down", Kind.CHAR, 10, 0, 0),
            c("vod_pwd_down_url", Kind.CHAR, 255, 0, 0),
            c("vod_content", Kind.TEXT, 16777215, 0, 0),
            c("vod_play_from", Kind.CHAR, 255, 0, 0),
            c("vod_play_server", Kind.CHAR, 255, 0, 0),
            c("vod_play_note", Kind.CHAR, 255, 0, 0),
            c("vod_play_url", Kind.TEXT, 16777215, 0, 0),
            c("vod_down_from", Kind.CHAR, 255, 0, 0),
            c("vod_down_server", Kind.CHAR, 255, 0, 0),
            c("vod_down_note", Kind.CHAR, 255, 0, 0),
            c("vod_down_url", Kind.TEXT, 16777215, 0, 0),
            c("vod_plot", Kind.INT, 0, 0L, 255L),
            c("vod_plot_name", Kind.TEXT, 16777215, 0, 0),
            c("vod_plot_detail", Kind.TEXT, 16777215, 0, 0))),
    ART("mac_art", "art_id", List.of(
            c("art_id", Kind.INT, 0, 0L, 4294967295L),
            c("type_id", Kind.INT, 0, 0L, 65535L),
            c("type_id_1", Kind.INT, 0, 0L, 65535L),
            c("group_id", Kind.INT, 0, 0L, 65535L),
            c("art_name", Kind.CHAR, 255, 0, 0),
            c("art_sub", Kind.CHAR, 255, 0, 0),
            c("art_en", Kind.CHAR, 255, 0, 0),
            c("art_status", Kind.INT, 0, 0L, 255L),
            c("art_letter", Kind.CHAR, 1, 0, 0),
            c("art_color", Kind.CHAR, 6, 0, 0),
            c("art_from", Kind.CHAR, 30, 0, 0),
            c("art_author", Kind.CHAR, 30, 0, 0),
            c("art_tag", Kind.CHAR, 100, 0, 0),
            c("art_class", Kind.CHAR, 255, 0, 0),
            c("art_pic", Kind.CHAR, 1024, 0, 0),
            c("art_pic_thumb", Kind.CHAR, 1024, 0, 0),
            c("art_pic_slide", Kind.CHAR, 1024, 0, 0),
            c("art_pic_screenshot", Kind.TEXT, 65535, 0, 0),
            c("art_blurb", Kind.CHAR, 255, 0, 0),
            c("art_remarks", Kind.CHAR, 100, 0, 0),
            c("art_jumpurl", Kind.CHAR, 150, 0, 0),
            c("art_tpl", Kind.CHAR, 30, 0, 0),
            c("art_level", Kind.INT, 0, 0L, 255L),
            c("art_lock", Kind.INT, 0, 0L, 255L),
            c("art_points", Kind.INT, 0, 0L, 65535L),
            c("art_points_detail", Kind.INT, 0, 0L, 65535L),
            c("art_up", Kind.INT, 0, 0L, 16777215L),
            c("art_down", Kind.INT, 0, 0L, 16777215L),
            c("art_hits", Kind.INT, 0, 0L, 16777215L),
            c("art_hits_day", Kind.INT, 0, 0L, 16777215L),
            c("art_hits_week", Kind.INT, 0, 0L, 16777215L),
            c("art_hits_month", Kind.INT, 0, 0L, 16777215L),
            c("art_time", Kind.INT, 0, 0L, 4294967295L),
            c("art_time_add", Kind.INT, 0, 0L, 4294967295L),
            c("art_time_hits", Kind.INT, 0, 0L, 4294967295L),
            c("art_time_make", Kind.INT, 0, 0L, 4294967295L),
            d("art_score", 3, 1, true),
            c("art_score_all", Kind.INT, 0, 0L, 16777215L),
            c("art_score_num", Kind.INT, 0, 0L, 16777215L),
            c("art_rel_art", Kind.CHAR, 255, 0, 0),
            c("art_rel_vod", Kind.CHAR, 255, 0, 0),
            c("art_pwd", Kind.CHAR, 10, 0, 0),
            c("art_pwd_url", Kind.CHAR, 255, 0, 0),
            c("art_title", Kind.TEXT, 16777215, 0, 0),
            c("art_note", Kind.TEXT, 16777215, 0, 0),
            c("art_content", Kind.TEXT, 16777215, 0, 0))),
    TOPIC("mac_topic", "topic_id", List.of(
            c("topic_id", Kind.INT, 0, 0L, 65535L),
            c("topic_name", Kind.CHAR, 255, 0, 0),
            c("topic_en", Kind.CHAR, 255, 0, 0),
            c("topic_sub", Kind.CHAR, 255, 0, 0),
            c("topic_status", Kind.INT, 0, 0L, 255L),
            c("topic_sort", Kind.INT, 0, 0L, 65535L),
            c("topic_letter", Kind.CHAR, 1, 0, 0),
            c("topic_color", Kind.CHAR, 6, 0, 0),
            c("topic_tpl", Kind.CHAR, 30, 0, 0),
            c("topic_type", Kind.CHAR, 255, 0, 0),
            c("topic_pic", Kind.CHAR, 1024, 0, 0),
            c("topic_pic_thumb", Kind.CHAR, 1024, 0, 0),
            c("topic_pic_slide", Kind.CHAR, 1024, 0, 0),
            c("topic_key", Kind.CHAR, 255, 0, 0),
            c("topic_des", Kind.CHAR, 255, 0, 0),
            c("topic_title", Kind.CHAR, 255, 0, 0),
            c("topic_blurb", Kind.CHAR, 255, 0, 0),
            c("topic_remarks", Kind.CHAR, 100, 0, 0),
            c("topic_level", Kind.INT, 0, 0L, 255L),
            c("topic_up", Kind.INT, 0, 0L, 16777215L),
            c("topic_down", Kind.INT, 0, 0L, 16777215L),
            d("topic_score", 3, 1, true),
            c("topic_score_all", Kind.INT, 0, 0L, 16777215L),
            c("topic_score_num", Kind.INT, 0, 0L, 16777215L),
            c("topic_hits", Kind.INT, 0, 0L, 16777215L),
            c("topic_hits_day", Kind.INT, 0, 0L, 16777215L),
            c("topic_hits_week", Kind.INT, 0, 0L, 16777215L),
            c("topic_hits_month", Kind.INT, 0, 0L, 16777215L),
            c("topic_time", Kind.INT, 0, 0L, 4294967295L),
            c("topic_time_add", Kind.INT, 0, 0L, 4294967295L),
            c("topic_time_hits", Kind.INT, 0, 0L, 4294967295L),
            c("topic_time_make", Kind.INT, 0, 0L, 4294967295L),
            c("topic_tag", Kind.CHAR, 255, 0, 0),
            c("topic_rel_vod", Kind.TEXT, 65535, 0, 0),
            c("topic_rel_art", Kind.TEXT, 65535, 0, 0),
            c("topic_content", Kind.TEXT, 65535, 0, 0),
            c("topic_extend", Kind.TEXT, 65535, 0, 0))),
    ACTOR("mac_actor", "actor_id", List.of(
            c("actor_id", Kind.INT, 0, 0L, 4294967295L),
            c("type_id", Kind.INT, 0, 0L, 65535L),
            c("type_id_1", Kind.INT, 0, 0L, 65535L),
            c("actor_name", Kind.CHAR, 255, 0, 0),
            c("actor_en", Kind.CHAR, 255, 0, 0),
            c("actor_alias", Kind.CHAR, 255, 0, 0),
            c("actor_status", Kind.INT, 0, 0L, 255L),
            c("actor_lock", Kind.INT, 0, 0L, 255L),
            c("actor_letter", Kind.CHAR, 1, 0, 0),
            c("actor_sex", Kind.CHAR, 1, 0, 0),
            c("actor_color", Kind.CHAR, 6, 0, 0),
            c("actor_pic", Kind.CHAR, 1024, 0, 0),
            c("actor_blurb", Kind.CHAR, 255, 0, 0),
            c("actor_remarks", Kind.CHAR, 100, 0, 0),
            c("actor_area", Kind.CHAR, 20, 0, 0),
            c("actor_height", Kind.CHAR, 10, 0, 0),
            c("actor_weight", Kind.CHAR, 10, 0, 0),
            c("actor_birthday", Kind.CHAR, 10, 0, 0),
            c("actor_birtharea", Kind.CHAR, 20, 0, 0),
            c("actor_blood", Kind.CHAR, 10, 0, 0),
            c("actor_starsign", Kind.CHAR, 10, 0, 0),
            c("actor_school", Kind.CHAR, 20, 0, 0),
            c("actor_works", Kind.CHAR, 255, 0, 0),
            c("actor_tag", Kind.CHAR, 255, 0, 0),
            c("actor_class", Kind.CHAR, 255, 0, 0),
            c("actor_level", Kind.INT, 0, 0L, 255L),
            c("actor_time", Kind.INT, 0, 0L, 4294967295L),
            c("actor_time_add", Kind.INT, 0, 0L, 4294967295L),
            c("actor_time_hits", Kind.INT, 0, 0L, 4294967295L),
            c("actor_time_make", Kind.INT, 0, 0L, 4294967295L),
            c("actor_hits", Kind.INT, 0, 0L, 16777215L),
            c("actor_hits_day", Kind.INT, 0, 0L, 16777215L),
            c("actor_hits_week", Kind.INT, 0, 0L, 16777215L),
            c("actor_hits_month", Kind.INT, 0, 0L, 16777215L),
            d("actor_score", 3, 1, true),
            c("actor_score_all", Kind.INT, 0, 0L, 16777215L),
            c("actor_score_num", Kind.INT, 0, 0L, 16777215L),
            c("actor_up", Kind.INT, 0, 0L, 16777215L),
            c("actor_down", Kind.INT, 0, 0L, 16777215L),
            c("actor_tpl", Kind.CHAR, 30, 0, 0),
            c("actor_jumpurl", Kind.CHAR, 150, 0, 0),
            c("actor_content", Kind.TEXT, 65535, 0, 0))),
    ROLE("mac_role", "role_id", List.of(
            c("role_id", Kind.INT, 0, 0L, 4294967295L),
            c("role_rid", Kind.INT, 0, 0L, 4294967295L),
            c("role_name", Kind.CHAR, 255, 0, 0),
            c("role_en", Kind.CHAR, 255, 0, 0),
            c("role_status", Kind.INT, 0, 0L, 255L),
            c("role_lock", Kind.INT, 0, 0L, 255L),
            c("role_letter", Kind.CHAR, 1, 0, 0),
            c("role_color", Kind.CHAR, 6, 0, 0),
            c("role_actor", Kind.CHAR, 255, 0, 0),
            c("role_remarks", Kind.CHAR, 100, 0, 0),
            c("role_pic", Kind.CHAR, 1024, 0, 0),
            c("role_sort", Kind.INT, 0, 0L, 65535L),
            c("role_level", Kind.INT, 0, 0L, 255L),
            c("role_time", Kind.INT, 0, 0L, 4294967295L),
            c("role_time_add", Kind.INT, 0, 0L, 4294967295L),
            c("role_time_hits", Kind.INT, 0, 0L, 4294967295L),
            c("role_time_make", Kind.INT, 0, 0L, 4294967295L),
            c("role_hits", Kind.INT, 0, 0L, 16777215L),
            c("role_hits_day", Kind.INT, 0, 0L, 16777215L),
            c("role_hits_week", Kind.INT, 0, 0L, 16777215L),
            c("role_hits_month", Kind.INT, 0, 0L, 16777215L),
            d("role_score", 3, 1, true),
            c("role_score_all", Kind.INT, 0, 0L, 16777215L),
            c("role_score_num", Kind.INT, 0, 0L, 16777215L),
            c("role_up", Kind.INT, 0, 0L, 16777215L),
            c("role_down", Kind.INT, 0, 0L, 16777215L),
            c("role_tpl", Kind.CHAR, 30, 0, 0),
            c("role_jumpurl", Kind.CHAR, 150, 0, 0),
            c("role_content", Kind.TEXT, 65535, 0, 0))),
    WEBSITE("mac_website", "website_id", List.of(
            c("website_id", Kind.INT, 0, 0L, 4294967295L),
            c("type_id", Kind.INT, 0, 0L, 65535L),
            c("type_id_1", Kind.INT, 0, 0L, 65535L),
            c("website_name", Kind.CHAR, 60, 0, 0),
            c("website_sub", Kind.CHAR, 255, 0, 0),
            c("website_en", Kind.CHAR, 255, 0, 0),
            c("website_status", Kind.INT, 0, 0L, 255L),
            c("website_letter", Kind.CHAR, 1, 0, 0),
            c("website_color", Kind.CHAR, 6, 0, 0),
            c("website_lock", Kind.INT, 0, 0L, 255L),
            c("website_sort", Kind.INT, 0, -2147483648L, 2147483647L),
            c("website_jumpurl", Kind.CHAR, 255, 0, 0),
            c("website_pic", Kind.CHAR, 1024, 0, 0),
            c("website_pic_screenshot", Kind.TEXT, 65535, 0, 0),
            c("website_logo", Kind.CHAR, 255, 0, 0),
            c("website_area", Kind.CHAR, 20, 0, 0),
            c("website_lang", Kind.CHAR, 10, 0, 0),
            c("website_level", Kind.INT, 0, 0L, 255L),
            c("website_time", Kind.INT, 0, 0L, 4294967295L),
            c("website_time_add", Kind.INT, 0, 0L, 4294967295L),
            c("website_time_hits", Kind.INT, 0, 0L, 4294967295L),
            c("website_time_make", Kind.INT, 0, 0L, 4294967295L),
            c("website_time_referer", Kind.INT, 0, 0L, 4294967295L),
            c("website_hits", Kind.INT, 0, 0L, 16777215L),
            c("website_hits_day", Kind.INT, 0, 0L, 16777215L),
            c("website_hits_week", Kind.INT, 0, 0L, 16777215L),
            c("website_hits_month", Kind.INT, 0, 0L, 16777215L),
            d("website_score", 3, 1, true),
            c("website_score_all", Kind.INT, 0, 0L, 16777215L),
            c("website_score_num", Kind.INT, 0, 0L, 16777215L),
            c("website_up", Kind.INT, 0, 0L, 16777215L),
            c("website_down", Kind.INT, 0, 0L, 16777215L),
            c("website_referer", Kind.INT, 0, 0L, 16777215L),
            c("website_referer_day", Kind.INT, 0, 0L, 16777215L),
            c("website_referer_week", Kind.INT, 0, 0L, 16777215L),
            c("website_referer_month", Kind.INT, 0, 0L, 16777215L),
            c("website_tag", Kind.CHAR, 100, 0, 0),
            c("website_class", Kind.CHAR, 255, 0, 0),
            c("website_remarks", Kind.CHAR, 100, 0, 0),
            c("website_tpl", Kind.CHAR, 30, 0, 0),
            c("website_blurb", Kind.CHAR, 255, 0, 0),
            c("website_content", Kind.TEXT, 16777215, 0, 0))),
    LINK("mac_link", "link_id", List.of(
            c("link_id", Kind.INT, 0, 0L, 65535L),
            c("link_type", Kind.INT, 0, 0L, 255L),
            c("link_name", Kind.CHAR, 60, 0, 0),
            c("link_sort", Kind.INT, 0, -32768L, 32767L),
            c("link_add_time", Kind.INT, 0, 0L, 4294967295L),
            c("link_time", Kind.INT, 0, 0L, 4294967295L),
            c("link_url", Kind.CHAR, 255, 0, 0),
            c("link_logo", Kind.CHAR, 255, 0, 0))),
    TYPE("mac_type", "type_id", List.of(
            c("type_id", Kind.INT, 0, 0L, 65535L),
            c("type_name", Kind.CHAR, 60, 0, 0),
            c("type_en", Kind.CHAR, 60, 0, 0),
            c("type_sort", Kind.INT, 0, 0L, 65535L),
            c("type_mid", Kind.INT, 0, 0L, 65535L),
            c("type_pid", Kind.INT, 0, 0L, 65535L),
            c("type_status", Kind.INT, 0, 0L, 255L),
            c("type_tpl", Kind.CHAR, 30, 0, 0),
            c("type_tpl_list", Kind.CHAR, 30, 0, 0),
            c("type_tpl_detail", Kind.CHAR, 30, 0, 0),
            c("type_tpl_play", Kind.CHAR, 30, 0, 0),
            c("type_tpl_down", Kind.CHAR, 30, 0, 0),
            c("type_key", Kind.CHAR, 255, 0, 0),
            c("type_des", Kind.CHAR, 255, 0, 0),
            c("type_title", Kind.CHAR, 255, 0, 0),
            c("type_union", Kind.CHAR, 255, 0, 0),
            c("type_extend", Kind.TEXT, 65535, 0, 0),
            c("type_logo", Kind.CHAR, 255, 0, 0),
            c("type_pic", Kind.CHAR, 1024, 0, 0),
            c("type_jumpurl", Kind.CHAR, 150, 0, 0))),
    COMMENT("mac_comment", "comment_id", List.of(
            c("comment_id", Kind.INT, 0, 0L, 4294967295L),
            c("comment_mid", Kind.INT, 0, 0L, 255L),
            c("comment_rid", Kind.INT, 0, 0L, 4294967295L),
            c("comment_pid", Kind.INT, 0, 0L, 4294967295L),
            c("user_id", Kind.INT, 0, 0L, 4294967295L),
            c("comment_status", Kind.INT, 0, 0L, 255L),
            c("comment_name", Kind.CHAR, 60, 0, 0),
            c("comment_ip", Kind.INT, 0, 0L, 4294967295L),
            c("comment_time", Kind.INT, 0, 0L, 4294967295L),
            c("comment_content", Kind.CHAR, 255, 0, 0),
            c("comment_up", Kind.INT, 0, 0L, 16777215L),
            c("comment_down", Kind.INT, 0, 0L, 16777215L),
            c("comment_reply", Kind.INT, 0, 0L, 16777215L),
            c("comment_report", Kind.INT, 0, 0L, 16777215L))),
    GBOOK("mac_gbook", "gbook_id", List.of(
            c("gbook_id", Kind.INT, 0, 0L, 4294967295L),
            c("gbook_rid", Kind.INT, 0, 0L, 4294967295L),
            c("user_id", Kind.INT, 0, 0L, 4294967295L),
            c("gbook_status", Kind.INT, 0, 0L, 255L),
            c("gbook_name", Kind.CHAR, 60, 0, 0),
            c("gbook_ip", Kind.INT, 0, 0L, 4294967295L),
            c("gbook_time", Kind.INT, 0, 0L, 4294967295L),
            c("gbook_reply_time", Kind.INT, 0, 0L, 4294967295L),
            c("gbook_content", Kind.CHAR, 255, 0, 0),
            c("gbook_reply", Kind.CHAR, 255, 0, 0))),
    ANNEX("mac_annex", "annex_id", List.of(
            c("annex_id", Kind.INT, 0, 0L, 4294967295L),
            c("annex_time", Kind.INT, 0, 0L, 4294967295L),
            c("annex_file", Kind.CHAR, 255, 0, 0),
            c("annex_size", Kind.INT, 0, 0L, 4294967295L),
            c("annex_type", Kind.CHAR, 8, 0, 0))),
    /** B14 后台会员编辑（旧 {@code User::saveData} 把整个 POST 交给 {@code update/insert}，表里有的列都写）。 */
    USER("mac_user", "user_id", List.of(
            c("user_id", Kind.INT, 0, 0L, 4294967295L),
            c("group_id", Kind.INT, 0, 0L, 65535L),
            c("user_name", Kind.CHAR, 30, 0, 0),
            c("user_pwd", Kind.CHAR, 32, 0, 0),
            c("user_nick_name", Kind.CHAR, 30, 0, 0),
            c("user_qq", Kind.CHAR, 16, 0, 0),
            c("user_email", Kind.CHAR, 30, 0, 0),
            c("user_phone", Kind.CHAR, 16, 0, 0),
            c("user_status", Kind.INT, 0, 0L, 255L),
            c("user_portrait", Kind.CHAR, 100, 0, 0),
            c("user_portrait_thumb", Kind.CHAR, 100, 0, 0),
            c("user_openid_qq", Kind.CHAR, 40, 0, 0),
            c("user_openid_weixin", Kind.CHAR, 40, 0, 0),
            c("user_question", Kind.CHAR, 255, 0, 0),
            c("user_answer", Kind.CHAR, 255, 0, 0),
            c("user_points", Kind.INT, 0, 0L, 4294967295L),
            c("user_points_froze", Kind.INT, 0, 0L, 4294967295L),
            c("user_reg_time", Kind.INT, 0, 0L, 4294967295L),
            c("user_reg_ip", Kind.INT, 0, 0L, 4294967295L),
            c("user_login_time", Kind.INT, 0, 0L, 4294967295L),
            c("user_login_ip", Kind.INT, 0, 0L, 4294967295L),
            c("user_last_login_time", Kind.INT, 0, 0L, 4294967295L),
            c("user_last_login_ip", Kind.INT, 0, 0L, 4294967295L),
            c("user_login_num", Kind.INT, 0, 0L, 65535L),
            c("user_extend", Kind.INT, 0, 0L, 65535L),
            c("user_random", Kind.CHAR, 32, 0, 0),
            c("user_end_time", Kind.INT, 0, 0L, 4294967295L),
            c("user_pid", Kind.INT, 0, 0L, 4294967295L),
            c("user_pid_2", Kind.INT, 0, 0L, 4294967295L),
            c("user_pid_3", Kind.INT, 0, 0L, 4294967295L))),
    /** B14 后台会员组编辑（旧 {@code Group::saveData}，{@code allowField(true)}）。 */
    GROUP("mac_group", "group_id", List.of(
            c("group_id", Kind.INT, 0, -32768L, 32767L),
            c("group_name", Kind.CHAR, 30, 0, 0),
            c("group_status", Kind.INT, 0, 0L, 255L),
            c("group_type", Kind.TEXT, 65535, 0, 0),
            c("group_popedom", Kind.TEXT, 65535, 0, 0),
            c("group_points_day", Kind.INT, 0, 0L, 65535L),
            c("group_points_week", Kind.INT, 0, -32768L, 32767L),
            c("group_points_month", Kind.INT, 0, 0L, 65535L),
            c("group_points_year", Kind.INT, 0, 0L, 65535L),
            c("group_points_free", Kind.INT, 0, 0L, 255L)));

    private final String table;
    private final String pk;
    private final Map<String, Column> columns;

    AdminTable(String table, String pk, List<Column> columns) {
        this.table = table;
        this.pk = pk;
        Map<String, Column> m = new LinkedHashMap<>();
        for (Column c : columns) {
            m.put(c.name(), c);
        }
        this.columns = java.util.Collections.unmodifiableMap(m);
    }

    /** 表名（如 {@code mac_vod}）。 */
    public String table() {
        return table;
    }

    /** 主键列（自增）。 */
    public String pk() {
        return pk;
    }

    /** 全部列（install.sql 的顺序）。 */
    public Map<String, Column> columns() {
        return columns;
    }

    public Column column(String name) {
        return columns.get(name);
    }

    public boolean has(String name) {
        return columns.containsKey(name);
    }

    /** 列的类别：整数、定点小数、定长/变长字符串（长度按字符）、TEXT/MEDIUMTEXT（长度按字节）。 */
    public enum Kind {
        INT, DECIMAL, CHAR, TEXT
    }

    /**
     * 一列。
     *
     * @param length CHAR 为最多字符数，TEXT 为最多字节数，DECIMAL 为总位数（precision）
     * @param min    整数列的下限；DECIMAL 为小数位数（scale）
     * @param max    整数列的上限；DECIMAL 为 1 表示无符号
     */
    public record Column(String name, Kind kind, int length, long min, long max) {

        public boolean numeric() {
            return kind == Kind.INT || kind == Kind.DECIMAL;
        }
    }

    private static Column c(String name, Kind kind, int length, long min, long max) {
        return new Column(name, kind, length, min, max);
    }

    private static Column d(String name, int precision, int scale, boolean unsigned) {
        return new Column(name, Kind.DECIMAL, precision, scale, unsigned ? 1 : 0);
    }
}
