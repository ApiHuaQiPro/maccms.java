-- B06（MACC-30）Provide 与 ajax 的"新版差异"数据：主用例之后由 provide_golden.php 与 ProvideGoldenMySqlIT 在同一个一次性库中再导入。
-- 这些行只用来固定 16 文档第 3.6、4.9、4.10、7 节登记的差异（不可见分类、访问密码、XML 特殊字符、关联视频不可见），
-- 黄金样本记录旧版输出，Java 侧按差异规则断言，不与旧版逐字节比较。全部是合成数据。
INSERT INTO mac_type (type_id,type_name,type_en,type_sort,type_mid,type_pid,type_status,type_extend) VALUES (5,'停用分类','tingyong',9,1,0,0,'');
INSERT INTO mac_type (type_id,type_name,type_en,type_sort,type_mid,type_pid,type_status,type_extend) VALUES (6,'会员分类','huiyuan',9,1,0,1,'');
INSERT INTO mac_type (type_id,type_name,type_en,type_sort,type_mid,type_pid,type_status,type_extend) VALUES (7,'R&B<片>','rnb',9,1,0,1,'');
UPDATE mac_group SET group_type=',1,2,3,4,7,11,21,22,31,41,', group_popedom='{"1":{"1":"1","2":"2","3":"3","4":"4","5":"5"},"2":{"1":"1","2":"2","3":"3","4":"4","5":"5"},"3":{"1":"1","2":"2","3":"3","4":"4","5":"5"},"4":{"1":"1","2":"2","3":"3","4":"4","5":"5"},"7":{"1":"1","2":"2","3":"3","4":"4","5":"5"},"11":{"1":"1","2":"2","3":"3","4":"4","5":"5"},"21":{"1":"1","2":"2","3":"3","4":"4","5":"5"},"22":{"1":"1","2":"2","3":"3","4":"4","5":"5"},"31":{"1":"1","2":"2","3":"3","4":"4","5":"5"},"41":{"1":"1","2":"2","3":"3","4":"4","5":"5"}}' WHERE group_id=1;
INSERT INTO mac_vod (vod_id,type_id,type_id_1,vod_name,vod_en,vod_status,vod_letter,vod_pic_screenshot,vod_time,vod_time_add,vod_pwd,vod_pwd_play,vod_pwd_down,vod_content,vod_play_from,vod_play_server,vod_play_note,vod_play_url,vod_down_from,vod_down_server,vod_down_note,vod_down_url,vod_plot_name,vod_plot_detail,vod_remarks,vod_year) VALUES
(30,6,0,'会员分类视频','huiyuanshipin',1,'H','',1700030000,1690030000,'','','','<p>会员</p>','aaa','no','','x$http://media.example.test/30.mp4','','','','','','','','2015'),
(31,5,0,'停用分类视频','tingyongshipin',1,'T','',1700031000,1690031000,'','','','','aaa','no','','x$http://media.example.test/31.mp4','','','','','','','','2015'),
(32,99,0,'无分类视频','wufenlei',1,'W','',1700032000,1690032000,'','','','','aaa','no','','x$http://media.example.test/32.mp4','','','','','','','','2015'),
(33,2,1,'播放密码视频','bofangmima',1,'B','',1700033000,1690033000,'','p1','','<p>播放密码</p>','aaa','no','','x$http://media.example.test/33.mp4','dl','no','','d$http://media.example.test/33.zip','','','','2015'),
(34,2,1,'详情密码视频','xiangqingmima',1,'X','',1700034000,1690034000,'p2','','','<p>详情密码正文</p>','aaa','no','','x$http://media.example.test/34.mp4','dl','no','','d$http://media.example.test/34.zip','','','','2015'),
(35,2,1,'下载密码视频','xiazaimima',1,'X','',1700035000,1690035000,'','','p3','<p>下载</p>','aaa','no','','x$http://media.example.test/35.mp4','dl','no','','d$http://media.example.test/35.zip','','','','2015'),
(36,7,0,'特殊]]>字符<名>','teshu',1,'T','',1700036000,1690036000,'','','','<p>]]> 正文</p>','a&b','no','','x$http://media.example.test/36.mp4?a=1&b=2','','','','','','','A&B<','2015');
INSERT INTO mac_art (art_id,type_id,type_id_1,art_name,art_en,art_status,art_letter,art_pic_screenshot,art_time,art_time_add,art_pwd,art_title,art_note,art_content) VALUES
(10,21,0,'密码文章','mimawenzhang',1,'M','',1700040000,1690040000,'p4','','','<p>密码文章正文</p>');
INSERT INTO mac_role (role_id,role_rid,role_name,role_en,role_status,role_letter,role_time,role_time_add,role_content) VALUES
(3,30,'不可见视频角色','bukejian',1,'B',1700041000,1690041000,''),
(4,1,'未审核角色','weishenhejuese',0,'W',1700042000,1690042000,'');
