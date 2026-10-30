package com.maccms.backend.admin.infrastructure;

import com.maccms.backend.common.write.WriteMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 数据库工具"批量替换"的写入（B15，24 文档第 9.4 节；登记 WriteRegistry：admin / 26 张 mac_* 表 / UPDATE / B15）。SQL 见
 * {@code resources/mapper-write/admin/DatabaseReplaceWriteMapper.xml}，按 install.sql 逐表生成：每张表一个方法，表名与列名都是 XML 里的固定文字，
 * {@code column} 只能是 {@link #COLUMNS} 里该表的列（调用方另用 {@code SHOW COLUMNS} 校验，同旧版）。只能经 {@code WriteDatabase.mapper()} 取得。
 * 返回匹配行数（旧版不检查）。
 */
@WriteMapper
public interface DatabaseReplaceWriteMapper {

    /** install.sql 的各表与列（顺序同 install.sql）。 */
    Map<String, List<String>> COLUMNS = Map.ofEntries(
            Map.entry("mac_actor", List.of("actor_id", "type_id", "type_id_1", "actor_name", "actor_en", "actor_alias", "actor_status", "actor_lock", "actor_letter", "actor_sex", "actor_color", "actor_pic", "actor_blurb", "actor_remarks", "actor_area", "actor_height", "actor_weight", "actor_birthday", "actor_birtharea", "actor_blood", "actor_starsign", "actor_school", "actor_works", "actor_tag", "actor_class", "actor_level", "actor_time", "actor_time_add", "actor_time_hits", "actor_time_make", "actor_hits", "actor_hits_day", "actor_hits_week", "actor_hits_month", "actor_score", "actor_score_all", "actor_score_num", "actor_up", "actor_down", "actor_tpl", "actor_jumpurl", "actor_content")),
            Map.entry("mac_admin", List.of("admin_id", "admin_name", "admin_pwd", "admin_random", "admin_status", "admin_auth", "admin_login_time", "admin_login_ip", "admin_login_num", "admin_last_login_time", "admin_last_login_ip")),
            Map.entry("mac_annex", List.of("annex_id", "annex_time", "annex_file", "annex_size", "annex_type")),
            Map.entry("mac_art", List.of("art_id", "type_id", "type_id_1", "group_id", "art_name", "art_sub", "art_en", "art_status", "art_letter", "art_color", "art_from", "art_author", "art_tag", "art_class", "art_pic", "art_pic_thumb", "art_pic_slide", "art_pic_screenshot", "art_blurb", "art_remarks", "art_jumpurl", "art_tpl", "art_level", "art_lock", "art_points", "art_points_detail", "art_up", "art_down", "art_hits", "art_hits_day", "art_hits_week", "art_hits_month", "art_time", "art_time_add", "art_time_hits", "art_time_make", "art_score", "art_score_all", "art_score_num", "art_rel_art", "art_rel_vod", "art_pwd", "art_pwd_url", "art_title", "art_note", "art_content")),
            Map.entry("mac_card", List.of("card_id", "card_no", "card_pwd", "card_money", "card_points", "card_use_status", "card_sale_status", "user_id", "card_add_time", "card_use_time")),
            Map.entry("mac_cash", List.of("cash_id", "user_id", "cash_status", "cash_points", "cash_money", "cash_bank_name", "cash_bank_no", "cash_payee_name", "cash_time", "cash_time_audit")),
            Map.entry("mac_cj_content", List.of("id", "nodeid", "status", "url", "title", "data")),
            Map.entry("mac_cj_history", List.of("md5")),
            Map.entry("mac_cj_node", List.of("nodeid", "name", "lastdate", "sourcecharset", "sourcetype", "urlpage", "pagesize_start", "pagesize_end", "page_base", "par_num", "url_contain", "url_except", "url_start", "url_end", "title_rule", "title_html_rule", "type_rule", "type_html_rule", "content_rule", "content_html_rule", "content_page_start", "content_page_end", "content_page_rule", "content_page", "content_nextpage", "down_attachment", "watermark", "coll_order", "customize_config", "program_config", "mid")),
            Map.entry("mac_collect", List.of("collect_id", "collect_name", "collect_url", "collect_type", "collect_mid", "collect_appid", "collect_appkey", "collect_param", "collect_filter", "collect_filter_from", "collect_opt", "collect_sync_pic_opt")),
            Map.entry("mac_comment", List.of("comment_id", "comment_mid", "comment_rid", "comment_pid", "user_id", "comment_status", "comment_name", "comment_ip", "comment_time", "comment_content", "comment_up", "comment_down", "comment_reply", "comment_report")),
            Map.entry("mac_gbook", List.of("gbook_id", "gbook_rid", "user_id", "gbook_status", "gbook_name", "gbook_ip", "gbook_time", "gbook_reply_time", "gbook_content", "gbook_reply")),
            Map.entry("mac_group", List.of("group_id", "group_name", "group_status", "group_type", "group_popedom", "group_points_day", "group_points_week", "group_points_month", "group_points_year", "group_points_free")),
            Map.entry("mac_link", List.of("link_id", "link_type", "link_name", "link_sort", "link_add_time", "link_time", "link_url", "link_logo")),
            Map.entry("mac_msg", List.of("msg_id", "user_id", "msg_type", "msg_status", "msg_to", "msg_code", "msg_content", "msg_time")),
            Map.entry("mac_order", List.of("order_id", "user_id", "order_status", "order_code", "order_price", "order_time", "order_points", "order_pay_type", "order_pay_time", "order_remarks")),
            Map.entry("mac_plog", List.of("plog_id", "user_id", "user_id_1", "plog_type", "plog_points", "plog_time", "plog_remarks")),
            Map.entry("mac_role", List.of("role_id", "role_rid", "role_name", "role_en", "role_status", "role_lock", "role_letter", "role_color", "role_actor", "role_remarks", "role_pic", "role_sort", "role_level", "role_time", "role_time_add", "role_time_hits", "role_time_make", "role_hits", "role_hits_day", "role_hits_week", "role_hits_month", "role_score", "role_score_all", "role_score_num", "role_up", "role_down", "role_tpl", "role_jumpurl", "role_content")),
            Map.entry("mac_topic", List.of("topic_id", "topic_name", "topic_en", "topic_sub", "topic_status", "topic_sort", "topic_letter", "topic_color", "topic_tpl", "topic_type", "topic_pic", "topic_pic_thumb", "topic_pic_slide", "topic_key", "topic_des", "topic_title", "topic_blurb", "topic_remarks", "topic_level", "topic_up", "topic_down", "topic_score", "topic_score_all", "topic_score_num", "topic_hits", "topic_hits_day", "topic_hits_week", "topic_hits_month", "topic_time", "topic_time_add", "topic_time_hits", "topic_time_make", "topic_tag", "topic_rel_vod", "topic_rel_art", "topic_content", "topic_extend")),
            Map.entry("mac_type", List.of("type_id", "type_name", "type_en", "type_sort", "type_mid", "type_pid", "type_status", "type_tpl", "type_tpl_list", "type_tpl_detail", "type_tpl_play", "type_tpl_down", "type_key", "type_des", "type_title", "type_union", "type_extend", "type_logo", "type_pic", "type_jumpurl")),
            Map.entry("mac_ulog", List.of("ulog_id", "user_id", "ulog_mid", "ulog_type", "ulog_rid", "ulog_sid", "ulog_nid", "ulog_points", "ulog_time")),
            Map.entry("mac_user", List.of("user_id", "group_id", "user_name", "user_pwd", "user_nick_name", "user_qq", "user_email", "user_phone", "user_status", "user_portrait", "user_portrait_thumb", "user_openid_qq", "user_openid_weixin", "user_question", "user_answer", "user_points", "user_points_froze", "user_reg_time", "user_reg_ip", "user_login_time", "user_login_ip", "user_last_login_time", "user_last_login_ip", "user_login_num", "user_extend", "user_random", "user_end_time", "user_pid", "user_pid_2", "user_pid_3")),
            Map.entry("mac_visit", List.of("visit_id", "user_id", "visit_ip", "visit_ly", "visit_time")),
            Map.entry("mac_vod", List.of("vod_id", "type_id", "type_id_1", "group_id", "vod_name", "vod_sub", "vod_en", "vod_status", "vod_letter", "vod_color", "vod_tag", "vod_class", "vod_pic", "vod_pic_thumb", "vod_pic_slide", "vod_pic_screenshot", "vod_actor", "vod_director", "vod_writer", "vod_behind", "vod_blurb", "vod_remarks", "vod_pubdate", "vod_total", "vod_serial", "vod_tv", "vod_weekday", "vod_area", "vod_lang", "vod_year", "vod_version", "vod_state", "vod_author", "vod_jumpurl", "vod_tpl", "vod_tpl_play", "vod_tpl_down", "vod_isend", "vod_lock", "vod_level", "vod_copyright", "vod_points", "vod_points_play", "vod_points_down", "vod_hits", "vod_hits_day", "vod_hits_week", "vod_hits_month", "vod_duration", "vod_up", "vod_down", "vod_score", "vod_score_all", "vod_score_num", "vod_time", "vod_time_add", "vod_time_hits", "vod_time_make", "vod_trysee", "vod_douban_id", "vod_douban_score", "vod_reurl", "vod_rel_vod", "vod_rel_art", "vod_pwd", "vod_pwd_url", "vod_pwd_play", "vod_pwd_play_url", "vod_pwd_down", "vod_pwd_down_url", "vod_content", "vod_play_from", "vod_play_server", "vod_play_note", "vod_play_url", "vod_down_from", "vod_down_server", "vod_down_note", "vod_down_url", "vod_plot", "vod_plot_name", "vod_plot_detail")),
            Map.entry("mac_website", List.of("website_id", "type_id", "type_id_1", "website_name", "website_sub", "website_en", "website_status", "website_letter", "website_color", "website_lock", "website_sort", "website_jumpurl", "website_pic", "website_pic_screenshot", "website_logo", "website_area", "website_lang", "website_level", "website_time", "website_time_add", "website_time_hits", "website_time_make", "website_time_referer", "website_hits", "website_hits_day", "website_hits_week", "website_hits_month", "website_score", "website_score_all", "website_score_num", "website_up", "website_down", "website_referer", "website_referer_day", "website_referer_week", "website_referer_month", "website_tag", "website_class", "website_remarks", "website_tpl", "website_blurb", "website_content")),
            Map.entry("mac_vod_search", List.of("search_key", "search_word", "search_field", "search_hit_count", "search_last_hit_time", "search_update_time", "search_result_count", "search_result_ids")));

    int replaceInMacActor(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacAdmin(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacAnnex(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacArt(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacCard(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacCash(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacCjContent(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacCjHistory(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacCjNode(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacCollect(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacComment(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacGbook(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacGroup(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacLink(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacMsg(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacOrder(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacPlog(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacRole(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacTopic(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacType(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacUlog(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacUser(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacVisit(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacVod(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacWebsite(@Param("column") String column, @Param("find") String find, @Param("to") String to);

    int replaceInMacVodSearch(@Param("column") String column, @Param("find") String find, @Param("to") String to);
}
