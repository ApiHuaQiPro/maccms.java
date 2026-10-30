-- B14 浏览器走查的合成数据（b14_admin_member_check.py）：只导入一次性库 maccms_it_*，在 install.sql 之后执行。
-- 管理员 admin / admin888（只用于本机一次性库）。冻结积分与未审核提现一致（会员 2：冻结 30 = 提现 1 的 20 + 提现 3 的 10）。
DELETE FROM mac_admin;
INSERT INTO mac_admin (admin_id, admin_name, admin_pwd, admin_status, admin_auth) VALUES (1, 'admin', MD5('admin888'), 1, '');
DELETE FROM mac_group;
INSERT INTO mac_group (group_id, group_name, group_status, group_type, group_popedom, group_points_day, group_points_week,
                       group_points_month, group_points_year) VALUES
  (1, '游客', 1, ',1,2,', '{"1":{"1":"1","2":"2"},"2":{"1":"1","2":"2"}}', 0, 0, 0, 0),
  (2, '默认会员', 1, ',1,2,', '{"1":{"1":"1","2":"2","3":"3"},"2":{"1":"1","2":"2"}}', 0, 0, 0, 0),
  (3, 'VIP会员', 1, ',1,2,', '{"1":{"1":"1","2":"2","3":"3","4":"4","5":"5"}}', 10, 70, 300, 3600),
  (4, '空组', 1, '', '', 1, 2, 3, 4);
DELETE FROM mac_type;
INSERT INTO mac_type (type_id, type_name, type_en, type_sort, type_mid, type_pid, type_status, type_tpl, type_tpl_list,
                      type_tpl_detail, type_tpl_play, type_tpl_down, type_extend) VALUES
  (1, '电影', 'dianying', 1, 1, 0, 1, 'type.html', 'show.html', 'detail.html', 'play.html', 'down.html', ''),
  (2, '动作片', 'dongzuo', 1, 1, 1, 1, 'type.html', 'show.html', 'detail.html', 'play.html', 'down.html', ''),
  (5, '资讯', 'zixun', 2, 2, 0, 1, 'type.html', 'show.html', 'detail.html', 'play.html', 'down.html', '');
INSERT INTO mac_vod (vod_id, vod_name, type_id, type_id_1, vod_status, vod_content, vod_play_from, vod_play_server, vod_play_note,
                     vod_play_url, vod_down_from, vod_down_server, vod_down_note, vod_down_url, vod_plot_name, vod_plot_detail,
                     vod_time, vod_time_add) VALUES
  (1, '星际穿越', 2, 1, 1, '', '', '', '', '', '', '', '', '', '', '', 1700000000, 1690000000);
INSERT INTO mac_user (user_id, group_id, user_name, user_pwd, user_status, user_points, user_points_froze, user_reg_time,
                      user_login_time, user_login_ip, user_login_num, user_end_time, user_pid, user_pid_2) VALUES
  (1, 2, 'alice01', MD5('pw1'), 1, 100, 0, 1690000001, UNIX_TIMESTAMP(), 2130706433, 3, 0, 0, 0),
  (2, 3, 'bobvip', MD5('pw2'), 1, 50, 30, 1690000002, 1700000000, 0, 1, 4000000000, 1, 0),
  (3, 3, 'carolvip', MD5('pw3'), 1, 7, 0, 1690000003, 1700000000, 0, 1, 1600000000, 2, 1),
  (4, 2, 'daveuser', MD5('pw4'), 0, 0, 0, 1690000004, 0, 0, 0, 0, 1, 0);
INSERT INTO mac_card (card_id, card_no, card_pwd, card_money, card_points, card_use_status, card_sale_status, user_id, card_add_time,
                      card_use_time) VALUES
  (1, 'AAAA2222BBBB3333', 'PWD12345', 10, 100, 1, 1, 1, 1600000000, 1650000000),
  (2, 'CCCC4444DDDD5555', 'QWERTYUI', 20, 200, 0, 0, 0, UNIX_TIMESTAMP(), 0);
INSERT INTO mac_order (order_id, user_id, order_status, order_code, order_price, order_time, order_points) VALUES
  (1, 1, 0, 'PAY20260101120000123456', '10.00', 1700000100, 100),
  (2, 2, 1, 'PAY20260102120000654321', '5.50', 1700000200, 55);
INSERT INTO mac_cash (cash_id, user_id, cash_status, cash_points, cash_money, cash_bank_name, cash_bank_no, cash_payee_name, cash_time,
                      cash_time_audit) VALUES
  (1, 2, 0, 20, '0.20', '银行A', '6222000011112222', '鲍勃', 1700000100, 0),
  (2, 2, 1, 10, '0.10', '银行B', '6222000033334444', '鲍勃', 1700000200, 1700000300),
  (3, 2, 0, 10, '0.10', '银行C', '6222000055556666', '鲍勃', 1700000300, 0);
INSERT INTO mac_plog (plog_id, user_id, plog_type, plog_points, plog_time, plog_remarks) VALUES
  (1, 1, 4, 5, 1700000100, '用户【2、bobvip】消费50积分，获得奖励5积分'),
  (2, 2, 8, 10, 1700000200, '');
INSERT INTO mac_ulog (ulog_id, user_id, ulog_mid, ulog_type, ulog_rid, ulog_sid, ulog_nid, ulog_points, ulog_time) VALUES
  (1, 1, 1, 4, 1, 1, 2, 5, 1700000100),
  (2, 2, 1, 2, 1, 0, 0, 0, 1700000200);
INSERT INTO mac_visit (visit_id, user_id, visit_ip, visit_ly, visit_time) VALUES
  (1, 1, 2130706433, 'http://a.test/page', UNIX_TIMESTAMP()),
  (2, 0, 0, 'https://b.test/', 1700000000);
