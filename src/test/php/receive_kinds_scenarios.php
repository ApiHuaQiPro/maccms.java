<?php
/*
 * B19 增量 2（MACC-43）的黄金场景：文章、演员、角色、网址、评论的 Receive 入口与旧 Collect::{种类}_data（契约见
 * docs/architecture/27-b19-collect-ingest.md 第 7 节）。由 receive_golden.php 在 GOLDEN_SET=kinds 时引入，不单独运行。
 */

/** 增量 2 另加的分类（与 ReceiveGoldenMySqlIT 相同）：文章、明星、网址各一个顶级分类和一个子分类。 */
const KINDS_TYPES_SQL = "INSERT INTO mac_type (type_id, type_name, type_en, type_sort, type_mid, type_pid, type_status) VALUES "
    . "(2, '文章', 'wenzhang', 4, 2, 0, 1), (8, '头条', 'toutiao', 5, 2, 2, 1), (3, '明星', 'mingxing', 6, 8, 0, 1), "
    . "(9, '内地明星', 'neidi', 7, 8, 3, 1), (4, '网址', 'wangzhi', 8, 11, 0, 1), (10, '导航', 'daohang', 9, 11, 4, 1)";

/** 统一的入库配置：随机项（点击、顶踩、评分）关闭，随机场景单独打开；图片同步关闭。 */
function kinds_config()
{
    $off = ['hits_start' => '0', 'hits_end' => '0', 'updown_start' => '0', 'updown_end' => '0', 'score' => '0', 'pic' => '0',
        'psernd' => '0', 'psesyn' => '0', 'thesaurus' => '', 'words' => '', 'filter' => '禁词,屏蔽'];
    return [
        'interface' => ['arttype' => "头条=快讯\r\n不存在=孤儿", 'actortype' => "内地明星=内地", 'websitetype' => "导航=站点导航"],
        'collect' => [
            'art' => array_merge($off, ['status' => '1', 'tag' => '0', 'inrule' => ',b', 'uprule' => ',a,b,c,d,e,f']),
            'actor' => array_merge($off, ['status' => '1', 'inrule' => ',b,c', 'uprule' => ',a,b,c,d,e']),
            'role' => array_merge($off, ['status' => '1', 'inrule' => ',a', 'uprule' => ',a,b,c']),
            'website' => array_merge($off, ['status' => '0', 'inrule' => ',b', 'uprule' => ',a,b,c,d,e']),
            'comment' => array_merge($off, ['status' => '1', 'inrule' => ',b', 'uprule' => ',a']),
        ],
    ];
}

function kp($fields)
{
    return array_merge(['pass' => PASS], $fields);
}

function seed_art($id, $over)
{
    return array_merge(['art_id' => $id, 'type_id' => 8, 'type_id_1' => 2, 'art_name' => '旧文', 'art_title' => '', 'art_note' => '',
        'art_content' => '旧内容', 'art_author' => '旧作者', 'art_from' => '旧来源', 'art_pic' => 'http://old.example.test/a.jpg',
        'art_tag' => '旧标签', 'art_blurb' => '旧简介', 'art_lock' => 0, 'art_status' => 1, 'art_time' => 1600000000,
        'art_time_add' => 1600000000, 'art_pic_screenshot' => ''], $over);
}

function seed_actor($id, $over)
{
    return array_merge(['actor_id' => $id, 'type_id' => 9, 'type_id_1' => 3, 'actor_name' => '演员乙', 'actor_sex' => '女',
        'actor_content' => '旧介绍', 'actor_blurb' => '旧简介', 'actor_remarks' => '旧备注', 'actor_works' => '旧作品',
        'actor_pic' => 'http://old.example.test/p.jpg', 'actor_lock' => 0, 'actor_status' => 1, 'actor_time' => 1600000000,
        'actor_time_add' => 1600000000], $over);
}

function seed_role($id, $over)
{
    return array_merge(['role_id' => $id, 'role_rid' => 1, 'role_name' => '旧角色', 'role_actor' => '演员甲', 'role_content' => '旧内容',
        'role_remarks' => '旧备注', 'role_pic' => 'http://old.example.test/r.jpg', 'role_lock' => 0, 'role_status' => 1,
        'role_time' => 1600000000, 'role_time_add' => 1600000000], $over);
}

function seed_website($id, $over)
{
    return array_merge(['website_id' => $id, 'type_id' => 10, 'type_id_1' => 4, 'website_name' => '旧网址', 'website_content' => '旧内容',
        'website_blurb' => '旧简介', 'website_remarks' => '旧备注', 'website_jumpurl' => 'http://old.example.test/',
        'website_pic' => 'http://old.example.test/w.jpg', 'website_lock' => 0, 'website_status' => 1, 'website_time' => 1600000000,
        'website_time_add' => 1600000000, 'website_pic_screenshot' => ''], $over);
}

function seed_comment($id, $over)
{
    return array_merge(['comment_id' => $id, 'comment_mid' => 1, 'comment_rid' => 1, 'comment_name' => '老用户',
        'comment_content' => '老评论', 'comment_status' => 1, 'comment_time' => 1600000000], $over);
}

/** 种子视频（角色、评论的关联对象）。 */
function seed_rel_vod($id, $over)
{
    return array_merge(['vod_id' => $id, 'type_id' => 6, 'type_id_1' => 1, 'vod_name' => '关联剧', 'vod_actor' => '演员甲,演员乙',
        'vod_director' => '导演甲', 'vod_content' => '', 'vod_play_from' => '', 'vod_play_url' => '', 'vod_play_server' => '',
        'vod_play_note' => '', 'vod_down_from' => '', 'vod_down_url' => '', 'vod_down_server' => '', 'vod_down_note' => '',
        'vod_pic_screenshot' => '', 'vod_plot_detail' => '', 'vod_time' => 1600000000, 'vod_time_add' => 1600000000], $over);
}

/** 场景表：[标签, 配置覆盖, 种子, 步骤[[标签, 方法, 路径, 查询串, POST 字段（null 为 GET）]], 选项]（同 scenarios()）。 */
function kinds_scenarios()
{
    $A = '/receive/art/';
    $C = '/receive/actor/';
    $R = '/receive/role/';
    $W = '/receive/website/';
    $M = '/receive/comment/';
    $artAll = ['art_content' => '新内容', 'art_author' => '新作者', 'art_from' => '新来源', 'art_pic' => 'http://new.example.test/a.jpg',
        'art_tag' => '新标签', 'art_blurb' => '新简介'];
    $actorAll = ['actor_content' => '新介绍', 'actor_blurb' => '新简介', 'actor_remarks' => '新备注', 'actor_works' => '新作品',
        'actor_pic' => 'http://new.example.test/p.jpg'];
    $webAll = ['website_content' => '新内容', 'website_blurb' => '新简介', 'website_remarks' => '新备注',
        'website_jumpurl' => 'http://new.example.test/', 'website_pic' => 'http://new.example.test/w.jpg'];
    $relSeed = [
        'mac_vod' => [seed_rel_vod(1, []), seed_rel_vod(2, ['vod_name' => '豆瓣剧', 'vod_douban_id' => 456])],
        'mac_art' => [seed_art(1, ['art_name' => '评论文'])],
        'mac_topic' => [['topic_id' => 1, 'topic_name' => '评论专题', 'topic_content' => '', 'topic_extend' => '', 'topic_status' => 1]],
        'mac_actor' => [seed_actor(1, ['actor_name' => '评论演员'])],
        'mac_role' => [seed_role(1, ['role_name' => '评论角色'])],
        'mac_website' => [seed_website(1, ['website_name' => '评论网址'])],
    ];
    return [
        // ---------------------------------------------------------------- 文章
        ['art-require', [], [], [
            ['no-name', 'POST', $A, '', kp(['type_id' => '8', 'art_content' => 'c'])],
            ['no-type', 'POST', $A, '', kp(['art_name' => '无分类', 'art_content' => 'c'])],
            ['type-mapped', 'POST', $A, '', kp(['art_name' => '映射文', 'type_name' => '快讯', 'art_content' => 'c'])],
            ['type-unmapped', 'POST', $A, '', kp(['art_name' => '未映射', 'type_name' => '没有', 'art_content' => 'c'])],
            ['type-orphan', 'POST', $A, '', kp(['art_name' => '孤儿文', 'type_name' => '孤儿', 'art_content' => 'c'])],
            ['type-zero', 'POST', $A, '', kp(['art_name' => '零分类', 'type_id' => '0', 'type_name' => '快讯', 'art_content' => 'c'])],
            ['wrong-pass', 'POST', $A, '', ['pass' => 'nope', 'art_name' => 'x', 'type_id' => '8']]]],
        ['art-insert', [], [], [
            ['full', 'POST', $A, '', kp(['art_id' => '999', 'type_id' => '8', 'type_name' => '头条', 'art_name' => '文章<b>一</b>',
                'art_sub' => '副<i>标</i>', 'art_letter' => 'Z', 'art_en' => 'zzz', 'art_status' => '0', 'art_lock' => '0', 'art_hits' => '5',
                'art_score' => '7.5', 'art_tag' => '<i>t</i>', 'art_pic' => 'http://img.example.test/a.jpg', 'art_title' => 't1$$$t2$$$t3',
                'art_note' => 'n1', 'art_content' => '<p>第一段</p>$$$第二段', 'art_blurb' => '', 'art_author' => '作者',
                'art_from' => '来源', 'art_time_add' => '1700000000', 'art_level' => '2', 'extra_field' => 'ignored'])],
            ['ascii-blurb', 'POST', $A, '', kp(['type_id' => '8', 'art_name' => 'English Article',
                'art_content' => '<p>Hello world, this is a plain ASCII body.</p>'])],
            ['no-content', 'POST', $A, '', kp(['type_id' => '2', 'art_name' => '无正文'])],
            ['parent-type', 'POST', $A, '', kp(['type_id' => '2', 'art_name' => '顶级分类文', 'art_content' => '正文',
                'art_blurb' => '自带简介'])]]],
        ['art-filter', [], [], [
            ['filtered', 'POST', $A, '', kp(['type_id' => '8', 'art_name' => '含禁词的文章', 'art_content' => 'c'])]]],
        ['art-update', [], ['mac_art' => [seed_art(1, []), seed_art(2, ['art_name' => '本地图文', 'art_pic' => 'upload/art/x.jpg']),
            seed_art(3, ['art_name' => '锁定文', 'art_lock' => 1])]], [
            ['update-all', 'POST', $A, '', kp(array_merge(['type_id' => '8', 'art_name' => '旧文'], $artAll))],
            ['same-again', 'POST', $A, '', kp(array_merge(['type_id' => '8', 'art_name' => '旧文'], $artAll))],
            ['local-pic', 'POST', $A, '', kp(['type_id' => '8', 'art_name' => '本地图文', 'art_pic' => 'http://new.example.test/b.jpg',
                'art_content' => '旧内容', 'art_blurb' => '旧简介'])],
            ['empty-fields', 'POST', $A, '', kp(['type_id' => '8', 'art_name' => '本地图文', 'art_content' => '旧内容'])],
            ['locked', 'POST', $A, '', kp(['type_id' => '8', 'art_name' => '锁定文', 'art_content' => '新'])],
            ['clear-pic', 'POST', $A, '', kp(array_merge(['type_id' => '8', 'art_name' => '旧文'], $artAll, ['art_pic' => '']))],
            ['other-type', 'POST', $A, '', kp(['type_id' => '2', 'art_name' => '旧文', 'art_content' => '别的分类'])]]],
        ['art-uprule-empty', ['collect' => ['art' => ['uprule' => '']]], ['mac_art' => [seed_art(1, [])]], [
            ['uprule-empty', 'POST', $A, '', kp(['type_id' => '8', 'art_name' => '旧文', 'art_content' => '新'])]]],
        ['art-inrule-none', ['collect' => ['art' => ['inrule' => ',', 'uprule' => ',a']]], ['mac_art' => [seed_art(1, [])]], [
            ['any-type', 'POST', $A, '', kp(['type_id' => '2', 'art_name' => '旧文', 'art_content' => '换了'])]]],
        ['art-psesyn', ['collect' => ['art' => ['psesyn' => '1', 'thesaurus' => "好=佳\r\n电影=影片"]]], [], [
            ['psesyn', 'POST', $A, '', kp(['type_id' => '8', 'art_name' => '同义词', 'art_content' => '好电影，真好'])]]],
        ['art-random', ['collect' => ['art' => ['hits_start' => '1', 'hits_end' => '1000', 'updown_start' => '1', 'updown_end' => '1000',
            'score' => '1']]], [], [['random', 'POST', $A, '', kp(['type_id' => '8', 'art_name' => '随机文', 'art_content' => 'c'])]],
            ['random' => true]],

        // ---------------------------------------------------------------- 演员
        ['actor-require', [], [], [
            ['no-name', 'POST', $C, '', kp(['actor_sex' => '男', 'type_id' => '9'])],
            ['no-sex', 'POST', $C, '', kp(['actor_name' => '无性别', 'type_id' => '9'])],
            ['sex-zero', 'POST', $C, '', kp(['actor_name' => '零', 'actor_sex' => '0', 'type_id' => '9'])],
            ['no-type', 'POST', $C, '', kp(['actor_name' => '无分类', 'actor_sex' => '男'])],
            ['type-mapped', 'POST', $C, '', kp(['actor_name' => '映射演员', 'actor_sex' => '男', 'type_name' => '内地',
                'actor_content' => 'c', 'actor_pic' => 'p'])],
            ['type-unmapped', 'POST', $C, '', kp(['actor_name' => '未映射', 'actor_sex' => '男', 'type_name' => '港台',
                'actor_content' => 'c'])]]],
        ['actor-insert', [], [], [
            ['full', 'POST', $C, '', kp(['actor_id' => '99', 'type_id' => '9', 'actor_name' => '演员<b>甲</b>', 'actor_sex' => '男',
                'actor_alias' => '别名', 'actor_area' => '大陆', 'actor_content' => '<p>简介内容</p>', 'actor_blurb' => '',
                'actor_remarks' => '备注', 'actor_works' => '作品甲,作品乙', 'actor_pic' => 'http://img.example.test/p.jpg',
                'actor_status' => '0', 'actor_hits' => '3', 'actor_letter' => 'Q', 'actor_score' => '9.1', 'extra_field' => 'x'])],
            ['no-pic', 'POST', $C, '', kp(['type_id' => '9', 'actor_name' => '无图', 'actor_sex' => '女', 'actor_content' => 'c'])],
            ['no-content', 'POST', $C, '', kp(['type_id' => '9', 'actor_name' => '无介绍', 'actor_sex' => '女', 'actor_pic' => 'p'])]]],
        ['actor-filter', [], [], [
            ['filtered', 'POST', $C, '', kp(['type_id' => '9', 'actor_name' => '屏蔽某人', 'actor_sex' => '男', 'actor_content' => 'c'])]]],
        ['actor-update', [], ['mac_actor' => [seed_actor(1, []), seed_actor(2, ['actor_name' => '本地图演员', 'actor_pic' => 'upload/actor/x.jpg']),
            seed_actor(3, ['actor_name' => '锁定演员', 'actor_lock' => 1])]], [
            ['update-all', 'POST', $C, '', kp(array_merge(['type_id' => '9', 'actor_name' => '演员乙', 'actor_sex' => '女'], $actorAll))],
            ['same-again', 'POST', $C, '', kp(array_merge(['type_id' => '9', 'actor_name' => '演员乙', 'actor_sex' => '女'], $actorAll))],
            ['sex-differs', 'POST', $C, '', kp(['type_id' => '9', 'actor_name' => '演员乙', 'actor_sex' => '男', 'actor_content' => '男版',
                'actor_pic' => 'p'])],
            ['type-differs', 'POST', $C, '', kp(['type_id' => '3', 'actor_name' => '演员乙', 'actor_sex' => '女', 'actor_content' => '顶级',
                'actor_pic' => 'p'])],
            ['local-pic', 'POST', $C, '', kp(['type_id' => '9', 'actor_name' => '本地图演员', 'actor_sex' => '女',
                'actor_pic' => 'http://new.example.test/q.jpg', 'actor_content' => '旧介绍', 'actor_blurb' => '旧简介'])],
            ['locked', 'POST', $C, '', kp(['type_id' => '9', 'actor_name' => '锁定演员', 'actor_sex' => '女', 'actor_content' => '新'])],
            ['update-no-pic', 'POST', $C, '', kp(['type_id' => '9', 'actor_name' => '演员乙', 'actor_sex' => '女', 'actor_content' => '再改'])]]],
        ['actor-uprule-empty', ['collect' => ['actor' => ['uprule' => '']]], ['mac_actor' => [seed_actor(1, [])]], [
            ['uprule-empty', 'POST', $C, '', kp(['type_id' => '9', 'actor_name' => '演员乙', 'actor_sex' => '女', 'actor_content' => '新'])]]],
        ['actor-random', ['collect' => ['actor' => ['hits_start' => '1', 'hits_end' => '1000', 'updown_start' => '1',
            'updown_end' => '1000', 'score' => '1']]], [], [['random', 'POST', $C, '', kp(['type_id' => '9', 'actor_name' => '随机演员',
            'actor_sex' => '男', 'actor_content' => 'c', 'actor_pic' => 'p'])]], ['random' => true]],

        // ---------------------------------------------------------------- 角色
        ['role-require', [], [], [
            ['no-name', 'POST', $R, '', kp(['role_actor' => '演员甲', 'vod_name' => '关联剧'])],
            ['no-actor', 'POST', $R, '', kp(['role_name' => '角色', 'vod_name' => '关联剧'])],
            ['no-vod', 'POST', $R, '', kp(['role_name' => '角色', 'role_actor' => '演员甲'])],
            ['douban-only', 'POST', $R, '', kp(['role_name' => '角色', 'role_actor' => '演员甲', 'douban_id' => '456'])]]],
        ['role-insert', [], ['mac_vod' => [seed_rel_vod(1, []), seed_rel_vod(2, ['vod_name' => '豆瓣剧', 'vod_douban_id' => 456])]], [
            ['vod-missing', 'POST', $R, '', kp(['role_name' => '角色一', 'role_actor' => '演员甲', 'vod_name' => '无此剧',
                'role_content' => 'c'])],
            ['insert', 'POST', $R, '', kp(['role_id' => '9', 'role_name' => '角色<b>一</b>', 'role_actor' => '演员甲',
                'vod_name' => '关联剧', 'role_content' => '<p>角色介绍</p>', 'role_remarks' => '主角', 'role_pic' => 'http://img.example.test/r.jpg',
                'role_status' => '0', 'role_sort' => '3', 'role_letter' => 'X', 'extra_field' => 'x'])],
            ['douban', 'POST', $R, '', kp(['role_name' => '角色二', 'role_actor' => '演员乙', 'vod_name' => '名字不对',
                'douban_id' => '456', 'role_content' => 'c2', 'role_pic' => 'p'])],
            ['same-again', 'POST', $R, '', kp(['role_name' => '角色一', 'role_actor' => '演员甲', 'vod_name' => '关联剧',
                'role_content' => '<p>角色介绍</p>', 'role_remarks' => '主角', 'role_pic' => 'http://img.example.test/r.jpg'])],
            ['update', 'POST', $R, '', kp(['role_name' => '角色一', 'role_actor' => '演员甲', 'vod_name' => '关联剧',
                'role_content' => '新介绍', 'role_remarks' => '配角', 'role_pic' => 'http://img.example.test/r2.jpg'])],
            ['no-pic', 'POST', $R, '', kp(['role_name' => '无图角色', 'role_actor' => '演员甲', 'vod_name' => '关联剧', 'role_content' => 'c'])],
            ['no-content', 'POST', $R, '', kp(['role_name' => '无介绍角色', 'role_actor' => '演员甲', 'vod_name' => '关联剧',
                'role_pic' => 'p'])],
            ['update-no-pic', 'POST', $R, '', kp(['role_name' => '角色一', 'role_actor' => '演员甲', 'vod_name' => '关联剧',
                'role_content' => '再改'])]]],
        ['role-inrule', ['collect' => ['role' => ['inrule' => ',c,d']]], ['mac_vod' => [
            seed_rel_vod(1, ['vod_name' => '同名剧', 'vod_actor' => '演员甲', 'vod_director' => '导演X']),
            seed_rel_vod(2, ['vod_name' => '同名剧', 'vod_actor' => '别人', 'vod_director' => '演员乙'])]], [
            ['actor-match', 'POST', $R, '', kp(['role_name' => 'r1', 'role_actor' => '演员甲', 'vod_name' => '同名剧', 'role_content' => 'c', 'role_pic' => 'p'])],
            ['director-match', 'POST', $R, '', kp(['role_name' => 'r2', 'role_actor' => '演员乙', 'vod_name' => '同名剧', 'role_content' => 'c', 'role_pic' => 'p'])],
            ['multi', 'POST', $R, '', kp(['role_name' => 'r3', 'role_actor' => '路人,演员乙', 'vod_name' => '同名剧', 'role_content' => 'c', 'role_pic' => 'p'])],
            ['none', 'POST', $R, '', kp(['role_name' => 'r4', 'role_actor' => '路人', 'vod_name' => '同名剧', 'role_content' => 'c', 'role_pic' => 'p'])]]],
        ['role-inrule-c', ['collect' => ['role' => ['inrule' => ',c']]], ['mac_vod' => [
            seed_rel_vod(1, ['vod_name' => '同名剧', 'vod_actor' => '演员甲', 'vod_director' => '导演X']),
            seed_rel_vod(2, ['vod_name' => '同名剧', 'vod_actor' => '别人', 'vod_director' => '演员乙'])]], [
            ['director-only', 'POST', $R, '', kp(['role_name' => 'r1', 'role_actor' => '演员乙', 'vod_name' => '同名剧', 'role_content' => 'c', 'role_pic' => 'p'])],
            ['actor', 'POST', $R, '', kp(['role_name' => 'r2', 'role_actor' => '演员甲', 'vod_name' => '同名剧', 'role_content' => 'c', 'role_pic' => 'p'])]]],
        ['role-locked', [], ['mac_vod' => [seed_rel_vod(1, [])], 'mac_role' => [seed_role(1, ['role_lock' => 1])]], [
            ['locked', 'POST', $R, '', kp(['role_name' => '旧角色', 'role_actor' => '演员甲', 'vod_name' => '关联剧', 'role_content' => '新'])]]],
        ['role-uprule-empty', ['collect' => ['role' => ['uprule' => '']]], ['mac_vod' => [seed_rel_vod(1, [])],
            'mac_role' => [seed_role(1, [])]], [
            ['uprule-empty', 'POST', $R, '', kp(['role_name' => '旧角色', 'role_actor' => '演员甲', 'vod_name' => '关联剧',
                'role_content' => '新'])]]],
        ['role-filter', [], ['mac_vod' => [seed_rel_vod(1, [])]], [
            ['filtered', 'POST', $R, '', kp(['role_name' => '禁词角色', 'role_actor' => '演员甲', 'vod_name' => '关联剧', 'role_content' => 'c'])]]],

        // ---------------------------------------------------------------- 网址
        ['website-require', [], [], [
            ['no-name', 'POST', $W, '', kp(['type_id' => '10'])],
            ['no-type', 'POST', $W, '', kp(['website_name' => '无分类'])],
            ['type-mapped', 'POST', $W, '', kp(['website_name' => '映射网址', 'type_name' => '站点导航', 'website_content' => 'c',
                'website_pic' => 'p'])]]],
        ['website-insert', [], [], [
            ['full', 'POST', $W, '', kp(['website_id' => '5', 'type_id' => '10', 'website_name' => ' 网址<b>一</b> ',
                'website_sub' => '副名', 'website_jumpurl' => 'http://jump.example.test/', 'website_status' => '1',
                'website_content' => '<p>网址介绍，比较长的一段文字。</p>', 'website_blurb' => '', 'website_remarks' => '备注',
                'website_pic' => 'http://img.example.test/w.jpg', 'website_area' => '大陆', 'website_lang' => '中文', 'extra_field' => 'x'])],
            ['no-pic', 'POST', $W, '', kp(['type_id' => '10', 'website_name' => '无图', 'website_content' => 'c'])],
            ['no-content', 'POST', $W, '', kp(['type_id' => '10', 'website_name' => '无介绍', 'website_pic' => 'p'])]]],
        ['website-filter', [], [], [
            ['filtered', 'POST', $W, '', kp(['type_id' => '10', 'website_name' => '屏蔽站', 'website_content' => 'c'])]]],
        ['website-update', [], ['mac_website' => [seed_website(1, []),
            seed_website(2, ['website_name' => '本地图网址', 'website_pic' => 'upload/website/x.jpg']),
            seed_website(3, ['website_name' => '锁定网址', 'website_lock' => 1])]], [
            ['update-all', 'POST', $W, '', kp(array_merge(['type_id' => '10', 'website_name' => '旧网址'], $webAll))],
            ['same-again', 'POST', $W, '', kp(array_merge(['type_id' => '10', 'website_name' => '旧网址'], $webAll))],
            ['type-differs', 'POST', $W, '', kp(['type_id' => '4', 'website_name' => '旧网址', 'website_content' => '顶级',
                'website_pic' => 'p'])],
            ['local-pic', 'POST', $W, '', kp(['type_id' => '10', 'website_name' => '本地图网址', 'website_pic' => 'http://new.example.test/y.jpg',
                'website_content' => '旧内容', 'website_blurb' => '旧简介'])],
            ['locked', 'POST', $W, '', kp(['type_id' => '10', 'website_name' => '锁定网址', 'website_content' => '新'])]]],
        ['website-uprule-empty', ['collect' => ['website' => ['uprule' => '']]], ['mac_website' => [seed_website(1, [])]], [
            ['uprule-empty', 'POST', $W, '', kp(['type_id' => '10', 'website_name' => '旧网址', 'website_content' => '新'])]]],

        // ---------------------------------------------------------------- 评论
        ['comment-require', [], [], [
            ['no-name', 'POST', $M, '', kp(['comment_content' => '内容', 'comment_mid' => '1', 'rel_name' => '关联剧'])],
            ['no-content', 'POST', $M, '', kp(['comment_name' => '网友', 'comment_mid' => '1', 'rel_name' => '关联剧'])],
            ['no-mid', 'POST', $M, '', kp(['comment_name' => '网友', 'comment_content' => '内容', 'rel_name' => '关联剧'])],
            ['mid-zero', 'POST', $M, '', kp(['comment_name' => '网友', 'comment_content' => '内容', 'comment_mid' => '0',
                'rel_name' => '关联剧'])],
            ['no-rel', 'POST', $M, '', kp(['comment_name' => '网友', 'comment_content' => '内容', 'comment_mid' => '1'])],
            ['douban-only', 'POST', $M, '', kp(['comment_name' => '网友', 'comment_content' => '内容', 'comment_mid' => '1',
                'douban_id' => '456'])]]],
        ['comment-rel', ['collect' => ['comment' => ['inrule' => ',']]], $relSeed, [
            ['vod', 'POST', $M, '', kp(['comment_id' => '50', 'comment_name' => '网友<b>甲</b>', 'comment_content' => '<p>好看</p>',
                'comment_mid' => '1', 'rel_name' => '关联剧', 'comment_status' => '0', 'comment_up' => '5', 'comment_down' => '2',
                'comment_ip' => '1.2.3.4', 'comment_pid' => '0', 'extra_field' => 'x'])],
            ['vod-douban', 'POST', $M, '', kp(['comment_name' => '网友乙', 'comment_content' => '豆瓣', 'comment_mid' => '1',
                'rel_name' => '名字不对', 'douban_id' => '456', 'comment_ip' => 'abc'])],
            ['art', 'POST', $M, '', kp(['comment_name' => '网友丙', 'comment_content' => '文章评论', 'comment_mid' => '2',
                'rel_name' => '评论文', 'comment_ip' => '3232235777'])],
            ['topic', 'POST', $M, '', kp(['comment_name' => 'u', 'comment_content' => '专题评论', 'comment_mid' => '3', 'rel_name' => '评论专题'])],
            ['actor', 'POST', $M, '', kp(['comment_name' => 'u', 'comment_content' => '演员评论', 'comment_mid' => '8', 'rel_name' => '评论演员'])],
            ['role', 'POST', $M, '', kp(['comment_name' => 'u', 'comment_content' => '角色评论', 'comment_mid' => '9', 'rel_name' => '评论角色'])],
            ['website', 'POST', $M, '', kp(['comment_name' => 'u', 'comment_content' => '网址评论', 'comment_mid' => '11',
                'rel_name' => '评论网址'])],
            ['unknown-mid', 'POST', $M, '', kp(['comment_name' => 'u', 'comment_content' => '留言', 'comment_mid' => '5', 'rel_name' => '关联剧'])],
            ['not-found', 'POST', $M, '', kp(['comment_name' => 'u', 'comment_content' => '找不到', 'comment_mid' => '1', 'rel_name' => '无此剧'])],
            ['rel-id', 'POST', $M, '', kp(['comment_name' => 'u', 'comment_content' => '直接编号', 'comment_mid' => '2', 'rel_name' => 'x',
                'rel_id' => '77'])],
            ['mid-text', 'POST', $M, '', kp(['comment_name' => 'u', 'comment_content' => '文字模块', 'comment_mid' => '1abc',
                'rel_name' => '关联剧'])]]],
        ['comment-dedupe', ['collect' => ['comment' => ['inrule' => ',b,c']]], ['mac_vod' => [seed_rel_vod(1, [])],
            'mac_comment' => [seed_comment(1, [])]], [
            ['dup', 'POST', $M, '', kp(['comment_name' => '老用户', 'comment_content' => '老评论', 'comment_mid' => '1', 'rel_name' => '关联剧'])],
            ['other-name', 'POST', $M, '', kp(['comment_name' => '新用户', 'comment_content' => '老评论', 'comment_mid' => '1',
                'rel_name' => '关联剧'])]]],
        ['comment-uprule-empty', ['collect' => ['comment' => ['uprule' => '']]], ['mac_vod' => [seed_rel_vod(1, [])],
            'mac_comment' => [seed_comment(1, [])]], [
            ['uprule-empty', 'POST', $M, '', kp(['comment_name' => '谁', 'comment_content' => '老评论', 'comment_mid' => '1',
                'rel_name' => '关联剧'])]]],
        ['comment-filter', [], ['mac_vod' => [seed_rel_vod(1, [])]], [
            ['filtered', 'POST', $M, '', kp(['comment_name' => 'u', 'comment_content' => '这是禁词评论', 'comment_mid' => '1',
                'rel_name' => '关联剧'])]]],
        ['comment-random', ['collect' => ['comment' => ['updown_start' => '1', 'updown_end' => '1000']]], ['mac_vod' => [seed_rel_vod(1, [])]],
            [['random', 'POST', $M, '', kp(['comment_name' => 'u', 'comment_content' => '随机', 'comment_mid' => '1', 'rel_name' => '关联剧'])]],
            ['random' => true]],
    ];
}
