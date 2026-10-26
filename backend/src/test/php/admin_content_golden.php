<?php
/*
 * B13（MACC-37）后台内容管理的 PHP 黄金样本生成脚本（契约见 docs/architecture/22-b13-admin-content.md）。
 *
 * 每个场景是一串真实的后台请求：经 admin_content_child.php 跑完整的 ThinkPHP 路由、admin/controller/Base 的构造函数与 B13 范围内的
 * 控制器（Vod、Art、Topic、Actor、Role、Website、Type、Link、Comment、Gbook、Annex、Images、Upload 与 Index::select）；同一场景内按
 * 浏览器的方式保存并回送 cookie（PHP 会话）。每个请求记录状态码、Location、Content-Type、全部 Set-Cookie 与正文字节（base64），
 * 以及请求后发生变化的表的全部行（只记变化的表；场景开始时各表的行在 rows0）与临时站点根目录 upload/ 下的文件清单（变化时）。
 * 文本列一律十六进制。请求前后的秒级时间（started/after）用于比较写入的时间列。
 *
 * 场景之间互不依赖：每个场景先清空跟踪的各表并把自增计数复位（一次性库上的测试夹具：DELETE 后 ALTER TABLE … AUTO_INCREMENT = 1，
 * 新版的复放测试做同样的事，使新增行的主键相同），写入自己的行与 upload/ 文件，使用新的 cookie、会话与配置目录。
 *
 * 流程：外部管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入 install.sql → 逐个场景执行 → 输出 JSON → 删除一次性库与临时目录。
 * 不修改任何旧 PHP 文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，不访问外部网络（图片同步的地址指向本机
 * 未监听的端口 127.0.0.1:9，旧代码的下载立即失败）。旧代码的文件操作只落在临时站点根目录（见 admin_content_child.php）。
 *
 * 管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；另需 PHP_CGI。
 * 用法（仓库根目录，PHP 8.0 与 7.3 各一份）：
 *   PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe backend/src/test/php/admin_content_golden.php \
 *       > backend/src/test/resources/admin/content/php80-golden.json
 * 只跑部分场景：GOLDEN_ONLY=<标签前缀>（逗号分隔多个）。
 */

error_reporting(E_ERROR | E_PARSE);
date_default_timezone_set('PRC');
$repo = realpath(__DIR__ . '/../../../..');

function env_required($name)
{
    $v = getenv($name);
    if ($v === false || $v === '') {
        fwrite(STDERR, "缺少环境变量 $name\n");
        exit(2);
    }
    return $v;
}

$adminUrl = env_required('MACCMS_IT_ADMIN_URL');
$adminUser = env_required('MACCMS_IT_ADMIN_USERNAME');
$adminPassword = env_required('MACCMS_IT_ADMIN_PASSWORD');
$phpCgi = env_required('PHP_CGI');
if (!preg_match('#^jdbc:mysql://([^:/?]+)(?::(\d+))?#', $adminUrl, $m)) {
    fwrite(STDERR, "MACCMS_IT_ADMIN_URL 不是 jdbc:mysql://host[:port]/ 形式\n");
    exit(2);
}
$host = $m[1];
$port = isset($m[2]) && $m[2] !== '' ? $m[2] : '3306';
$dbName = 'maccms_it_' . date('YmdHis') . '_' . bin2hex(random_bytes(4));
if (!preg_match('/^maccms_it_[0-9]{14}_[0-9a-f]{8}$/', $dbName)) {
    fwrite(STDERR, "一次性库名不合法\n");
    exit(2);
}

const ENTRY = '/_admin.php';
/** 跟踪的表与主键（顺序即输出顺序）。 */
const TABLES = ['mac_type' => 'type_id', 'mac_vod' => 'vod_id', 'mac_art' => 'art_id', 'mac_topic' => 'topic_id',
    'mac_actor' => 'actor_id', 'mac_role' => 'role_id', 'mac_website' => 'website_id', 'mac_link' => 'link_id',
    'mac_comment' => 'comment_id', 'mac_gbook' => 'gbook_id', 'mac_annex' => 'annex_id', 'mac_vod_search' => 'search_key'];

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$work = sys_get_temp_dir() . '/maccms-b13-golden-' . bin2hex(random_bytes(4));
$exit = 0;
try {
    $pdo->exec("USE `$dbName`");
    foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
        $pdo->exec($sql);
    }
    echo generate($repo, $pdo, $phpCgi, $work, $host, $port, $dbName, $adminUser, $adminPassword);
} catch (\Throwable $e) {
    fwrite(STDERR, 'failed: ' . get_class($e) . ' at ' . $e->getFile() . ':' . $e->getLine() . "\n");
    if (getenv('GOLDEN_DEBUG') === '1') {
        fwrite(STDERR, $e->getMessage() . "\n");
    }
    $exit = 1;
} finally {
    $pdo->exec("DROP DATABASE `$dbName`");
    rm_tree($work);
}
exit($exit);

// ====================================================================== 场景数据

/** 每个场景都带上的配置（仓库值之上）：后台登录不要验证码，语言简体，每页 20。 */
function base_config()
{
    return ['app' => ['admin_login_verify' => '0', 'lang' => 'zh-cn', 'pagesize' => '20',
        'vod_extend_area' => '大陆,香港,美国', 'vod_extend_lang' => '国语,英语', 'vod_extend_weekday' => '一,二,三',
        'vod_area' => '大陆,香港', 'vod_lang' => '国语,粤语'],
        'upload' => ['protocol' => 'http', 'mode' => 'local', 'watermark' => '0', 'thumb' => '0'],
        'view' => ['vod_detail' => '0', 'art_detail' => '0', 'topic_detail' => '0', 'actor_detail' => '0',
            'role_detail' => '0', 'website_detail' => '0']];
}

function admins()
{
    return [
        ['admin_id' => 1, 'admin_name' => 'admin', 'admin_pwd' => md5('admin888'), 'admin_status' => 1, 'admin_auth' => ''],
        ['admin_id' => 2, 'admin_name' => 'editor', 'admin_pwd' => md5('ed pass'), 'admin_status' => 1,
            'admin_auth' => ',vod/data,vod/info,link/index,link/info,'],
    ];
}

/** 分类：视频 1 电影（2 动作、3 喜剧）、4 电视剧；文章 5 资讯（6 头条）；演员 7；网址 8（9 导航）。 */
function types()
{
    $ext = json_encode(['class' => '动作,喜剧', 'area' => '', 'lang' => '', 'year' => '', 'star' => '', 'director' => '',
        'state' => '正片,预告片', 'version' => '']);
    return [
        t(1, '电影', 'dianying', 1, 1, 0, ['type_extend' => $ext]),
        t(2, '动作片', 'dongzuopian', 1, 1, 1),
        t(3, '喜剧片', 'xijupian', 2, 1, 1, ['type_status' => 0]),
        t(4, '电视剧', 'dianshiju', 2, 1, 0),
        t(5, '资讯', 'zixun', 3, 2, 0),
        t(6, '头条', 'toutiao', 1, 2, 5),
        t(7, '明星', 'mingxing', 4, 8, 0),
        t(8, '网址', 'wangzhi', 5, 11, 0),
        t(9, '导航', 'daohang', 1, 11, 8),
    ];
}

function t($id, $name, $en, $sort, $mid, $pid, array $extra = [])
{
    return array_merge(['type_id' => $id, 'type_name' => $name, 'type_en' => $en, 'type_sort' => $sort, 'type_mid' => $mid,
        'type_pid' => $pid, 'type_status' => 1, 'type_tpl' => 'type.html', 'type_tpl_list' => 'show.html',
        'type_tpl_detail' => 'detail.html', 'type_tpl_play' => 'play.html', 'type_tpl_down' => 'down.html',
        'type_extend' => ''], $extra);
}

function vods()
{
    return [
        v(1, '星际穿越', 2, ['vod_hits' => 100, 'vod_hits_week' => 7, 'vod_level' => 9, 'vod_score' => '9.3',
            'vod_play_from' => 'dplayer$$$m3u8$$$videojs', 'vod_play_server' => 'no$$$no$$$server1',
            'vod_play_note' => '$$$$$$备注',
            'vod_play_url' => '第1集$http://a.test/1.m3u8#第2集$http://a.test/2.m3u8$$$x$http://m.test/1$$$正片$http://b.test/1',
            'vod_down_from' => 'xunlei', 'vod_down_server' => 'no', 'vod_down_note' => '', 'vod_down_url' => '下载$http://c.test/1.zip',
            'vod_actor' => '马修', 'vod_remarks' => 'HD', 'vod_pic' => 'upload/vod/20260101-1/a.jpg', 'vod_time' => 1700000300,
            'vod_plot' => 1, 'vod_plot_name' => '第一集$$$第二集', 'vod_plot_detail' => '剧情一$$$剧情二']),
        v(2, '星际穿越', 3, ['vod_status' => 0, 'vod_time' => 1700000200, 'vod_pic' => 'http://img.test/b.jpg']),
        v(3, '盗梦空间', 2, ['vod_lock' => 1, 'vod_copyright' => 1, 'vod_isend' => 0, 'vod_serial' => '12',
            'vod_time' => 1700000100, 'vod_pic' => 'http://img.test/c.jpg#err2026-01-01', 'vod_area' => '美国', 'vod_lang' => '英语',
            'vod_weekday' => '一,三', 'vod_points_play' => 5]),
        v(4, '星际穿越 ', 4, ['vod_time' => 1700000400, 'vod_sub' => '重名带空格', 'vod_play_from' => 'no']),
        v(5, '甄嬛传', 4, ['vod_time' => 1700000500, 'vod_hits_day' => 3, 'vod_hits_month' => 9]),
    ];
}

function v($id, $name, $type, array $extra = [])
{
    $pid = [2 => 1, 3 => 1, 4 => 0][$type];
    return array_merge(['vod_id' => $id, 'vod_name' => $name, 'vod_en' => '', 'type_id' => $type, 'type_id_1' => $pid,
        'vod_status' => 1, 'vod_letter' => 'X', 'vod_isend' => 1, 'vod_content' => '<p>简介</p>', 'vod_play_from' => '',
        'vod_play_server' => '', 'vod_play_note' => '', 'vod_play_url' => '', 'vod_down_from' => '', 'vod_down_server' => '',
        'vod_down_note' => '', 'vod_down_url' => '', 'vod_plot_name' => '', 'vod_plot_detail' => '', 'vod_time_add' => 1690000000,
        'vod_time' => 1700000000], $extra);
}

function arts()
{
    return [
        ['art_id' => 1, 'art_name' => '新闻一', 'type_id' => 6, 'type_id_1' => 5, 'art_status' => 1, 'art_time' => 1700000100,
            'art_title' => '第一页$$$第二页', 'art_note' => '注一$$$注二', 'art_content' => '<p>内容一</p>$$$<p>内容二</p>',
            'art_hits' => 3, 'art_pic' => 'upload/art/20260101-1/a.jpg', 'art_letter' => 'X'],
        ['art_id' => 2, 'art_name' => '新闻一', 'type_id' => 5, 'type_id_1' => 0, 'art_status' => 0, 'art_time' => 1700000200,
            'art_title' => '', 'art_note' => '', 'art_content' => '<p>重名</p>', 'art_letter' => 'X'],
        ['art_id' => 3, 'art_name' => '新闻三', 'type_id' => 6, 'type_id_1' => 5, 'art_status' => 1, 'art_lock' => 1,
            'art_time' => 1700000300, 'art_title' => '', 'art_note' => '', 'art_content' => '', 'art_pic' => 'http://img.test/a.png',
            'art_letter' => 'X'],
    ];
}

function topics()
{
    return [
        ['topic_id' => 1, 'topic_name' => '专题一', 'topic_en' => 'zhuantiyi', 'topic_status' => 1, 'topic_tpl' => 'detail.html',
            'topic_time' => 1700000100, 'topic_rel_vod' => '1,3', 'topic_rel_art' => '1', 'topic_content' => '<p>专题内容</p>',
            'topic_extend' => '', 'topic_pic' => 'upload/topic/20260101-1/t.jpg', 'topic_level' => 2],
        ['topic_id' => 2, 'topic_name' => '专题二', 'topic_en' => 'zhuantier', 'topic_status' => 0, 'topic_tpl' => 'detail.html',
            'topic_time' => 1700000200, 'topic_rel_vod' => '', 'topic_rel_art' => '', 'topic_content' => '', 'topic_extend' => ''],
    ];
}

function actors()
{
    return [
        ['actor_id' => 1, 'actor_name' => '马修', 'actor_en' => 'maxiu', 'type_id' => 7, 'type_id_1' => 0, 'actor_status' => 1,
            'actor_time' => 1700000100, 'actor_content' => '<p>演员</p>', 'actor_sex' => '男', 'actor_pic' => 'upload/actor/a.jpg'],
        ['actor_id' => 2, 'actor_name' => '安妮', 'actor_en' => 'anni', 'type_id' => 7, 'type_id_1' => 0, 'actor_status' => 0,
            'actor_time' => 1700000200, 'actor_content' => '', 'actor_level' => 3],
    ];
}

function roles()
{
    return [
        ['role_id' => 1, 'role_rid' => 1, 'role_name' => '库珀', 'role_en' => 'kupo', 'role_actor' => '马修', 'role_status' => 1,
            'role_time' => 1700000100, 'role_content' => '<p>角色</p>'],
        ['role_id' => 2, 'role_rid' => 3, 'role_name' => '柯布', 'role_en' => 'kebu', 'role_actor' => '李奥', 'role_status' => 0,
            'role_time' => 1700000200, 'role_content' => ''],
    ];
}

function websites()
{
    return [
        ['website_id' => 1, 'website_name' => '示例站', 'type_id' => 9, 'type_id_1' => 8, 'website_status' => 1,
            'website_time' => 1700000100, 'website_content' => '<p>网址</p>', 'website_jumpurl' => 'http://site.test/'],
        ['website_id' => 2, 'website_name' => '示例站', 'type_id' => 8, 'type_id_1' => 0, 'website_status' => 0,
            'website_time' => 1700000200, 'website_content' => ''],
    ];
}

function links()
{
    return [
        ['link_id' => 1, 'link_type' => 0, 'link_name' => '友链一', 'link_sort' => 1, 'link_add_time' => 1690000000,
            'link_time' => 1700000000, 'link_url' => 'http://l1.test/', 'link_logo' => ''],
        ['link_id' => 2, 'link_type' => 1, 'link_name' => '图片友链', 'link_sort' => 2, 'link_add_time' => 1690000000,
            'link_time' => 1700000000, 'link_url' => 'http://l2.test/', 'link_logo' => 'http://l2.test/logo.png'],
    ];
}

function comments()
{
    return [
        ['comment_id' => 1, 'comment_mid' => 1, 'comment_rid' => 1, 'comment_pid' => 0, 'user_id' => 0, 'comment_status' => 1,
            'comment_name' => '游客', 'comment_ip' => 2130706433, 'comment_time' => 1700000100, 'comment_content' => '好看&amp;推荐',
            'comment_report' => 0],
        ['comment_id' => 2, 'comment_mid' => 2, 'comment_rid' => 1, 'comment_pid' => 0, 'user_id' => 5, 'comment_status' => 0,
            'comment_name' => '会员', 'comment_ip' => 0, 'comment_time' => 1700000200, 'comment_content' => '文章评论',
            'comment_report' => 2],
        ['comment_id' => 3, 'comment_mid' => 1, 'comment_rid' => 1, 'comment_pid' => 1, 'user_id' => 0, 'comment_status' => 1,
            'comment_name' => '回复者', 'comment_ip' => 0, 'comment_time' => 1700000300, 'comment_content' => '回复内容',
            'comment_report' => 0],
    ];
}

function gbooks()
{
    return [
        ['gbook_id' => 1, 'gbook_rid' => 0, 'user_id' => 0, 'gbook_status' => 1, 'gbook_name' => '访客', 'gbook_ip' => 2130706433,
            'gbook_time' => 1700000100, 'gbook_reply_time' => 0, 'gbook_content' => '求片&amp;谢谢', 'gbook_reply' => ''],
        ['gbook_id' => 2, 'gbook_rid' => 3, 'user_id' => 7, 'gbook_status' => 0, 'gbook_name' => '报错', 'gbook_ip' => 0,
            'gbook_time' => 1700000200, 'gbook_reply_time' => 1700000300, 'gbook_content' => '播放不了', 'gbook_reply' => '已修复'],
    ];
}

function annexes()
{
    return [
        ['annex_id' => 1, 'annex_time' => 1700000100, 'annex_file' => 'upload/vod/20260101-1/a.jpg', 'annex_size' => 3, 'annex_type' => 'image'],
        ['annex_id' => 2, 'annex_time' => 1700000200, 'annex_file' => 'upload/art/20260101-1/missing.jpg', 'annex_size' => 5, 'annex_type' => 'image'],
        ['annex_id' => 3, 'annex_time' => 1700000300, 'annex_file' => 'upload/file/doc.txt', 'annex_size' => 1, 'annex_type' => 'file'],
    ];
}

/** 临时站点根目录 upload/ 下预置的文件。 */
function base_files()
{
    return ['upload/vod/20260101-1/a.jpg' => 'jpg', 'upload/art/20260101-1/a.jpg' => 'art', 'upload/topic/20260101-1/t.jpg' => 'tpc',
        'upload/actor/a.jpg' => 'act', 'upload/file/doc.txt' => 'd', 'upload/vod/20260101-1/b.jpg' => 'bbb'];
}

function base_seed()
{
    return ['mac_type' => types(), 'mac_vod' => vods(), 'mac_art' => arts(), 'mac_topic' => topics(), 'mac_actor' => actors(),
        'mac_role' => roles(), 'mac_website' => websites(), 'mac_link' => links(), 'mac_comment' => comments(),
        'mac_gbook' => gbooks(), 'mac_annex' => annexes()];
}

// ====================================================================== 请求构造

/** 一个请求步骤。$post 为数组时按表单编码，为字符串时原样作为正文。 */
function req($label, $method, $path, $query = '', $post = '', array $opt = [])
{
    return ['label' => $label, 'method' => $method, 'path' => $path, 'query' => $query, 'post' => $post,
        'ajax' => !isset($opt['ajax']) || $opt['ajax'], 'headers' => isset($opt['headers']) ? $opt['headers'] : [],
        'body' => !isset($opt['body']) || $opt['body'], 'multipart' => isset($opt['multipart']) ? $opt['multipart'] : null];
}

function get($label, $path, $query = '', array $opt = [])
{
    return req($label, 'GET', $path, $query, '', $opt + ['ajax' => false]);
}

function post($label, $path, $post, array $opt = [])
{
    return req($label, 'POST', $path, '', $post, $opt);
}

/** 原始正文（不经 http_build_query，用于数组名、重复字段与特殊字节）。 */
function raw($label, $path, $raw, array $opt = [])
{
    return req($label, 'POST', $path, '', ['__raw__' => $raw], $opt);
}

function login($name = 'admin', $pwd = 'admin888')
{
    return req('login', 'POST', '/admin/index/login', '', ['admin_name' => $name, 'admin_pwd' => $pwd], ['body' => false]);
}

function sc($label, array $steps, array $opt = [])
{
    return array_merge(['label' => $label, 'seed' => base_seed(), 'files' => base_files(), 'config' => [],
        'steps' => array_merge([login()], $steps)], $opt);
}

// ====================================================================== 场景

function scenarios($repo)
{
    return array_merge(scenarios_link(), scenarios_type(), scenarios_topic(), scenarios_select(), scenarios_actor(),
        scenarios_role(), scenarios_website(), scenarios_art(), scenarios_vod());
}

function scenarios_link()
{
    $s = [];
    $s[] = sc('link-list', [
        get('index', '/admin/link/index'),
        get('wd', '/admin/link/index', 'wd=' . rawurlencode('图片')),
        get('wd-entity', '/admin/link/index', 'wd=' . rawurlencode('a&b<"x">')),
        get('page2', '/admin/link/index', 'page=2&limit=1'),
        get('limit-bad', '/admin/link/index', 'page=abc&limit=0'),
        get('ajax', '/admin/link/index', '', ['ajax' => true]),
    ]);
    $s[] = sc('link-info', [
        get('new', '/admin/link/info'),
        get('edit', '/admin/link/info', 'id=2'),
        get('missing', '/admin/link/info', 'id=99'),
        get('path-id', '/admin/link/info/id/1'),
    ]);
    $s[] = sc('link-save', [
        post('new', '/admin/link/info', ['link_id' => '', 'link_name' => '新友链', 'link_url' => 'http://n.test/',
            'link_logo' => '', 'link_sort' => '5', 'link_type' => '1']),
        post('edit', '/admin/link/info', ['link_id' => '1', 'link_name' => '<b>改名</b> "q" \'s\'', 'link_url' => 'http://e.test/?a=1&b=2',
            'link_logo' => 'x', 'link_sort' => '', 'link_type' => '0']),
        post('no-name', '/admin/link/info', ['link_id' => '1', 'link_name' => '', 'link_url' => 'http://e.test/']),
        post('no-url', '/admin/link/info', ['link_id' => '1', 'link_name' => 'x']),
        post('zero-name', '/admin/link/info', ['link_id' => '2', 'link_name' => '0', 'link_url' => '0']),
        post('extra-columns', '/admin/link/info', ['link_id' => '2', 'link_name' => 'n', 'link_url' => 'u', 'link_add_time' => '5',
            'nosuch' => 'x']),
        post('sort-negative', '/admin/link/info', ['link_id' => '2', 'link_name' => 'n', 'link_url' => 'u', 'link_sort' => '-3']),
        post('sort-decimal', '/admin/link/info', ['link_id' => '2', 'link_name' => 'n', 'link_url' => 'u', 'link_sort' => '1.5']),
        post('sort-bad', '/admin/link/info', ['link_id' => '2', 'link_name' => 'n', 'link_url' => 'u', 'link_sort' => '12abc']),
        post('name-too-long', '/admin/link/info', ['link_id' => '2', 'link_name' => str_repeat('名', 61), 'link_url' => 'u']),
        post('name-emoji', '/admin/link/info', ['link_id' => '2', 'link_name' => "a\xF0\x9F\x98\x80", 'link_url' => 'u']),
        post('html', '/admin/link/info', ['link_id' => '1', 'link_name' => 'h', 'link_url' => 'u'], ['ajax' => false]),
        post('query-merge', '/admin/link/info', ['link_name' => 'q', 'link_url' => 'u'], []),
        req('query-id', 'POST', '/admin/link/info', 'link_id=2', ['link_name' => 'via-query', 'link_url' => 'u']),
    ]);
    $s[] = sc('link-del', [
        get('one', '/admin/link/del', 'ids=1', ['ajax' => true]),
        raw('array', '/admin/link/del', 'ids%5B%5D=2&ids%5B%5D=99'),
        get('empty', '/admin/link/del', '', ['ajax' => true]),
        get('bad', '/admin/link/del', 'ids=abc', ['ajax' => true]),
    ]);
    $s[] = sc('link-batch', [
        raw('batch', '/admin/link/batch', 'ids%5B%5D=1&ids%5B%5D=2&link_name%5B%5D=A&link_name%5B%5D=&link_sort%5B%5D=9'
            . '&link_sort%5B%5D=8&link_url%5B%5D=http%3A%2F%2Fa&link_url%5B%5D=http%3A%2F%2Fb&link_type%5B%5D=1&link_type%5B%5D=x'
            . '&link_logo%5B%5D=&link_logo%5B%5D=L'),
        raw('batch-no-url', '/admin/link/batch', 'ids%5B%5D=1&link_name%5B%5D=A&link_url%5B%5D='),
        get('batch-empty', '/admin/link/batch', '', ['ajax' => true]),
    ]);
    $s[] = sc('link-denied', [
        login('editor', 'ed pass'),
        get('index', '/admin/link/index'),
        get('del', '/admin/link/del', 'ids=1', ['ajax' => true]),
        post('save', '/admin/link/info', ['link_id' => '1', 'link_name' => 'e', 'link_url' => 'u']),
    ]);
    return $s;
}

function scenarios_type()
{
    $s = [];
    $s[] = sc('type-index', [get('index', '/admin/type/index')]);
    $s[] = sc('type-info', [
        get('new', '/admin/type/info'),
        get('new-pid', '/admin/type/info', 'pid=1'),
        get('edit', '/admin/type/info', 'id=1'),
        get('edit-child', '/admin/type/info', 'id=2'),
        get('missing', '/admin/type/info', 'id=99'),
    ]);
    $s[] = sc('type-save', [
        post('no-token', '/admin/type/info', ['type_id' => '', 'type_name' => 'x']),
        get('form', '/admin/type/info'),
        post('bad-token', '/admin/type/info', ['__token__' => 'nope', 'type_id' => '', 'type_name' => 'x']),
        get('form2', '/admin/type/info'),
        post('new', '/admin/type/info', ['__token__' => '{token}', 'type_id' => '', 'type_name' => '纪录片', 'type_en' => '',
            'type_sort' => '6', 'type_mid' => '1', 'type_pid' => '0', 'type_status' => '1', 'type_tpl' => 'type.html',
            'type_tpl_list' => 'show.html', 'type_tpl_detail' => 'detail.html', 'type_tpl_play' => 'play.html',
            'type_tpl_down' => 'down.html', 'type_title' => '<b>t</b>', 'type_key' => 'k', 'type_des' => 'd', 'type_logo' => '',
            'type_pic' => '', 'type_jumpurl' => '', 'type_union' => '',
            'type_extend' => ['class' => '纪实,自然', 'area' => '', 'lang' => '', 'year' => '', 'star' => '', 'director' => '',
                'state' => '', 'version' => '']]),
        post('replay-token', '/admin/type/info', ['__token__' => '{token}', 'type_id' => '', 'type_name' => '重复']),
        get('form3', '/admin/type/info', 'id=2'),
        post('edit', '/admin/type/info', ['__token__' => '{token}', 'type_id' => '2', 'type_name' => '动作片2', 'type_en' => 'dz',
            'type_pid' => '1', 'type_mid' => '1']),
        get('form4', '/admin/type/info'),
        post('no-name', '/admin/type/info', ['__token__' => '{token}', 'type_id' => '2', 'type_name' => '']),
    ]);
    $s[] = sc('type-del', [
        get('has-data', '/admin/type/del', 'ids=1', ['ajax' => true]),
        get('art-has-data', '/admin/type/del', 'ids=6', ['ajax' => true]),
        get('empty-type', '/admin/type/del', 'ids=9', ['ajax' => true]),
        raw('multi', '/admin/type/del', 'ids=7,8'),
        get('none', '/admin/type/del', '', ['ajax' => true]),
    ], ['seed' => array_merge(base_seed(), ['mac_website' => []])]);
    $s[] = sc('type-field', [
        get('off', '/admin/type/field', 'ids=1,2&col=type_status&val=0', ['ajax' => true]),
        get('on', '/admin/type/field', 'ids=3&col=type_status&val=1', ['ajax' => true]),
        get('bad-val', '/admin/type/field', 'ids=3&col=type_status&val=2', ['ajax' => true]),
        get('bad-col', '/admin/type/field', 'ids=3&col=type_sort&val=1', ['ajax' => true]),
    ]);
    $s[] = sc('type-batch', [
        raw('batch', '/admin/type/batch', 'ids%5B%5D=1&ids%5B%5D=2&type_name_1=%E5%BD%B1&type_sort_1=9&type_en_1=ying'
            . '&type_tpl_1=a.html&type_tpl_list_1=b.html&type_tpl_detail_1=c.html&type_name_2=&type_sort_2=3'),
    ]);
    $s[] = sc('type-extend', [
        get('parent', '/admin/type/extend', 'id=1', ['ajax' => true]),
        get('child-inherits', '/admin/type/extend', 'id=2', ['ajax' => true]),
        get('art', '/admin/type/extend', 'id=6', ['ajax' => true]),
        get('config-default', '/admin/type/extend', 'id=4', ['ajax' => true]),
        get('missing', '/admin/type/extend', 'id=99', ['ajax' => true]),
        get('no-id', '/admin/type/extend', '', ['ajax' => true]),
    ], ['config' => ['app' => ['vod_extend_class' => '剧情,爱情', 'vod_extend_state' => '', 'vod_extend_version' => '高清',
        'art_extend_class' => '新闻,公告']]]);
    $s[] = sc('type-move', [
        get('vod', '/admin/type/move', 'ids=2&val=3', ['ajax' => true]),
        get('art', '/admin/type/move', 'ids=6&val=5', ['ajax' => true]),
        get('to-missing', '/admin/type/move', 'ids=4&val=99', ['ajax' => true]),
        get('no-val', '/admin/type/move', 'ids=4', ['ajax' => true]),
    ]);
    return $s;
}

function scenarios_topic()
{
    $s = [];
    $s[] = sc('topic-list', [
        get('data', '/admin/topic/data'),
        get('status0', '/admin/topic/data', 'status=0'),
        get('wd', '/admin/topic/data', 'wd=' . rawurlencode('专题一')),
    ]);
    $s[] = sc('topic-info', [get('new', '/admin/topic/info'), get('edit', '/admin/topic/info', 'id=1')]);
    $s[] = sc('topic-save', [
        post('new', '/admin/topic/info', ['topic_id' => '', 'topic_name' => '新专题', 'topic_en' => '', 'topic_tpl' => 'detail.html',
            'topic_content' => '<p>正文<img src="http://x.test/1.jpg"></p>', 'topic_blurb' => '', 'topic_status' => '1',
            'topic_rel_vod' => '1,2', 'topic_tag' => '<i>tag</i>', 'topic_hits' => '50', 'topic_score' => '8.75']),
        post('edit-uptime', '/admin/topic/info', ['topic_id' => '1', 'topic_name' => '专题一改', 'topic_tpl' => 'detail.html',
            'uptime' => '1', 'topic_blurb' => 'b', 'topic_content' => '', 'topic_extend' => '']),
        post('no-tpl', '/admin/topic/info', ['topic_id' => '1', 'topic_name' => 'x', 'topic_tpl' => '']),
    ]);
    $s[] = sc('topic-del-field', [
        get('field-level', '/admin/topic/field', 'ids=1,2&col=topic_level&val=5', ['ajax' => true]),
        get('field-bad', '/admin/topic/field', 'ids=1&col=topic_hits&val=5', ['ajax' => true]),
        get('del', '/admin/topic/del', 'ids=1', ['ajax' => true]),
    ]);
    return $s;
}

function scenarios_select()
{
    $s = [];
    $s[] = sc('select', [
        get('type', '/admin/index/select', 'tab=vod&col=type_id&tpl=select_type&url=vod/field&ids=1,2', ['ajax' => true]),
        get('type-art', '/admin/index/select', 'tab=art&col=type_id&tpl=select_type&url=art/field&ids=1&val=6', ['ajax' => true]),
        get('level', '/admin/index/select', 'tab=vod&col=vod_level&tpl=select_level&url=vod/field&ids=1', ['ajax' => true]),
        get('hits', '/admin/index/select', 'tab=vod&col=vod_hits&tpl=select_hits&url=vod/field&ids=1&refresh=no'),
        get('status', '/admin/index/select', 'tab=vod&col=vod_status&tpl=select_status&url=vod/field&ids=1'),
        get('lock', '/admin/index/select', 'tab=vod&col=vod_lock&tpl=select_lock&url=vod/field&ids=1'),
        get('copyright', '/admin/index/select', 'tab=vod&col=vod_copyright&tpl=select_copyright&url=vod/field&ids=1'),
        get('state', '/admin/index/select', 'tab=vod&col=vod_state&tpl=select_state&url=vod/field&ids=1'),
        raw('ids-array', '/admin/index/select', 'tab=vod&col=vod_level&tpl=select_level&url=vod%2Ffield&ids%5B%5D=1&ids%5B%5D=3'),
        get('missing-param', '/admin/index/select', 'tab=vod&col=vod_level&tpl=select_level&url=vod/field', ['ajax' => true]),
    ]);
    return $s;
}

/** 演员表单的完整字段（旧 actor/info.html 提交的全部字段）。 */
function actor_form(array $over = [])
{
    return array_merge(['actor_id' => '', 'type_id' => '7', 'actor_status' => '1', 'actor_level' => '0', 'actor_lock' => '0',
        'uptime' => '1', 'uptag' => '0', 'actor_name' => '新演员', 'actor_en' => '', 'actor_alias' => '', 'actor_letter' => '',
        'actor_color' => '', 'actor_sex' => '女', 'actor_area' => '香港', 'actor_height' => '170', 'actor_weight' => '50',
        'actor_birthday' => '1990-01-01', 'actor_birtharea' => '', 'actor_blood' => 'A', 'actor_starsign' => '', 'actor_school' => '',
        'actor_works' => '', 'actor_tag' => '', 'actor_class' => '', 'actor_remarks' => '', 'actor_pic' => '', 'actor_blurb' => '',
        'actor_content' => '<p>简介<img src="http://p.test/1.jpg"></p>', 'actor_tpl' => 'detail.html', 'actor_jumpurl' => '',
        'actor_up' => '1', 'actor_down' => '2', 'actor_hits' => '3', 'actor_hits_month' => '4', 'actor_hits_week' => '5',
        'actor_hits_day' => '6', 'actor_score' => '7.5', 'actor_score_all' => '8', 'actor_score_num' => '9'], $over);
}

function scenarios_actor()
{
    $s = [];
    $s[] = sc('actor-list', [
        get('data', '/admin/actor/data'),
        get('filters', '/admin/actor/data', 'type=7&status=0&level=3&pic=1'),
        get('pic-remote', '/admin/actor/data', 'pic=2'),
        get('wd', '/admin/actor/data', 'wd=' . rawurlencode('马')),
        get('select', '/admin/actor/data', 'select=1&input=x'),
    ]);
    $s[] = sc('actor-info', [get('new', '/admin/actor/info'), get('edit', '/admin/actor/info', 'id=1')]);
    $s[] = sc('actor-save', [
        post('new', '/admin/actor/info', actor_form()),
        post('edit', '/admin/actor/info', actor_form(['actor_id' => '1', 'actor_name' => '马修<b>改</b>', 'actor_en' => 'maxiu2',
            'uptime' => '0', 'actor_blurb' => '自定义', 'actor_hits' => '99'])),
        post('edit-uptag', '/admin/actor/info', actor_form(['actor_id' => '2', 'actor_name' => '安妮', 'uptag' => '1',
            'actor_tag' => 'x'])),
        post('no-type', '/admin/actor/info', actor_form(['actor_id' => '1', 'type_id' => ''])),
        post('type-missing', '/admin/actor/info', actor_form(['actor_id' => '1', 'type_id' => '99'])),
        post('no-name', '/admin/actor/info', actor_form(['actor_id' => '1', 'actor_name' => ''])),
    ]);
    $s[] = sc('actor-del-field', [
        get('field-type', '/admin/actor/field', 'ids=1,2&col=type_id&val=7', ['ajax' => true]),
        get('field-level', '/admin/actor/field', 'ids=1&col=actor_level&val=4', ['ajax' => true]),
        get('field-hits-rnd', '/admin/actor/field', 'ids=2&col=actor_hits&start=5&end=5', ['ajax' => true]),
        get('field-bad', '/admin/actor/field', 'ids=1&col=actor_name&val=x', ['ajax' => true]),
        get('del', '/admin/actor/del', 'ids=1', ['ajax' => true]),
    ]);
    return $s;
}

function role_form(array $over = [])
{
    return array_merge(['role_id' => '', 'role_rid' => '1', 'role_status' => '1', 'role_level' => '0', 'role_lock' => '0',
        'uptime' => '1', 'role_name' => '新角色', 'role_en' => '', 'role_letter' => '', 'role_color' => '', 'role_actor' => '演员甲',
        'role_remarks' => '', 'role_pic' => '', 'role_sort' => '0', 'role_tpl' => 'detail.html', 'role_jumpurl' => '',
        'role_content' => '<p>角色介绍</p>', 'role_up' => '0', 'role_down' => '0', 'role_hits' => '0', 'role_hits_month' => '0',
        'role_hits_week' => '0', 'role_hits_day' => '0', 'role_score' => '0', 'role_score_all' => '0', 'role_score_num' => '0'], $over);
}

function scenarios_role()
{
    $s = [];
    $s[] = sc('role-list', [
        get('data', '/admin/role/data'),
        get('rid', '/admin/role/data', 'rid=1&status=1'),
        get('select', '/admin/role/data', 'select=1&tab=vod&rid=1'),
    ]);
    $s[] = sc('role-info', [
        get('new', '/admin/role/info'),
        get('new-rid', '/admin/role/info', 'rid=3'),
        get('edit', '/admin/role/info', 'id=1'),
    ]);
    $s[] = sc('role-save', [
        post('new', '/admin/role/info', role_form()),
        post('edit', '/admin/role/info', role_form(['role_id' => '1', 'role_name' => '库珀2', 'uptime' => '0'])),
        post('no-actor', '/admin/role/info', role_form(['role_id' => '1', 'role_actor' => ''])),
    ]);
    $s[] = sc('role-del-field', [
        get('field-status', '/admin/role/field', 'ids=1,2&col=role_status&val=0', ['ajax' => true]),
        get('field-hits-rnd', '/admin/role/field', 'ids=1&col=role_hits&start=7&end=7', ['ajax' => true]),
        get('field-type', '/admin/role/field', 'ids=1&col=type_id&val=7', ['ajax' => true]),
        get('del', '/admin/role/del', 'ids=2', ['ajax' => true]),
    ]);
    return $s;
}

function website_form(array $over = [])
{
    return array_merge(['website_id' => '', 'type_id' => '9', 'website_status' => '1', 'website_level' => '0', 'website_lock' => '0',
        'uptime' => '1', 'uptag' => '0', 'website_name' => '新网址', 'website_sub' => '', 'website_en' => '', 'website_letter' => '',
        'website_color' => '', 'website_jumpurl' => 'http://n.test/', 'website_pic' => '', 'website_logo' => '', 'website_area' => '',
        'website_lang' => '', 'website_tag' => '', 'website_class' => '', 'website_remarks' => '', 'website_tpl' => 'detail.html',
        'website_blurb' => '', 'website_pic_screenshot' => "http://s.test/1.jpg\r\nhttp://s.test/2.jpg",
        'website_content' => '<p>网址<img src="http://p.test/w.jpg"></p>', 'website_up' => '0', 'website_down' => '0',
        'website_hits' => '0', 'website_hits_month' => '0', 'website_hits_week' => '0', 'website_hits_day' => '0',
        'website_score' => '0', 'website_score_all' => '0', 'website_score_num' => '0', 'website_referer' => '0',
        'website_referer_month' => '0', 'website_referer_week' => '0', 'website_referer_day' => '0'], $over);
}

function scenarios_website()
{
    $s = [];
    $s[] = sc('website-list', [
        get('data', '/admin/website/data'),
        get('filters', '/admin/website/data', 'type=8&status=1&lock=0'),
        get('repeat', '/admin/website/data', 'repeat=1'),
        get('repeat-p2', '/admin/website/data', 'repeat=1&page=2&limit=1'),
    ]);
    $s[] = sc('website-info', [get('new', '/admin/website/info'), get('edit', '/admin/website/info', 'id=1')]);
    $s[] = sc('website-save', [
        post('new', '/admin/website/info', website_form()),
        post('edit', '/admin/website/info', website_form(['website_id' => '1', 'website_name' => '示例站改', 'website_blurb' => 'b',
            'uptime' => '0', 'website_hits' => '50'])),
    ]);
    $s[] = sc('website-repeat-del', [
        get('repeat', '/admin/website/data', 'repeat=1'),
        get('del-keep-min', '/admin/website/del', 'repeat=1&retain=min', ['ajax' => true]),
    ]);
    $s[] = sc('website-repeat-del-max', [
        get('repeat', '/admin/website/data', 'repeat=1'),
        get('del-keep-max', '/admin/website/del', 'repeat=1&retain=max', ['ajax' => true]),
    ], ['seed' => array_merge(base_seed(), ['mac_website' => array_merge(websites(), [
        ['website_id' => 3, 'website_name' => '示例站', 'type_id' => 9, 'type_id_1' => 8, 'website_status' => 1,
            'website_time' => 1700000300, 'website_content' => '']])])]);
    $s[] = sc('website-batch', [
        get('page', '/admin/website/batch'),
        get('level', '/admin/website/batch', 'ck_level=1&val_level=5'),
        get('level-p2', '/admin/website/batch', 'ck_level=1&val_level=5&page=2&limit=100&total=2&page_count=1'),
        get('status-filtered', '/admin/website/batch', 'type=9&ck_status=1&val_status=0'),
        get('none', '/admin/website/batch', 'type=9', ['ajax' => true]),
        get('del', '/admin/website/batch', 'type=8&ck_del=1'),
    ]);
    $today = websites();
    $today[0]['website_time'] = time();
    $s[] = sc('website-field-today', [
        get('field-hits', '/admin/website/field', 'ids=1&col=website_hits&val=12', ['ajax' => true]),
        get('field-type', '/admin/website/field', 'ids=2&col=type_id&val=9', ['ajax' => true]),
        get('today', '/admin/website/updateToday', '', ['ajax' => true]),
        get('today-type', '/admin/website/updateToday', 'flag=type', ['ajax' => true]),
    ], ['seed' => array_merge(base_seed(), ['mac_website' => $today])]);
    return $s;
}

function art_form(array $over = [])
{
    return array_merge(['art_id' => '', 'type_id' => '6', 'art_status' => '1', 'art_level' => '0', 'art_lock' => '0',
        'uptime' => '1', 'uptag' => '0', 'art_name' => '新文章', 'art_sub' => '', 'art_en' => '', 'art_letter' => '', 'art_color' => '',
        'art_from' => '', 'art_author' => '', 'art_tag' => '', 'art_class' => '', 'art_pic' => '', 'art_pic_thumb' => '',
        'art_pic_slide' => '', 'art_pic_screenshot' => '', 'art_blurb' => '', 'art_remarks' => '', 'art_jumpurl' => '',
        'art_tpl' => 'detail.html', 'art_rel_art' => '', 'art_rel_vod' => '', 'art_pwd' => '', 'art_pwd_url' => '',
        'art_points' => '0', 'art_points_detail' => '0', 'art_up' => '0', 'art_down' => '0', 'art_hits' => '0',
        'art_hits_month' => '0', 'art_hits_week' => '0', 'art_hits_day' => '0', 'art_score' => '0', 'art_score_all' => '0',
        'art_score_num' => '0'], $over);
}

/** 文章分页字段：art_title[] art_note[] art_content[]（原始正文，含重复的数组名）。 */
function art_pages_raw(array $form, array $pages)
{
    $q = http_build_query($form);
    foreach ($pages as $p) {
        $q .= '&art_title%5B%5D=' . rawurlencode($p[0]) . '&art_note%5B%5D=' . rawurlencode($p[1])
            . '&art_content%5B%5D=' . rawurlencode($p[2]);
    }
    return $q;
}

function scenarios_art()
{
    $s = [];
    $s[] = sc('art-list', [
        get('data', '/admin/art/data'),
        get('filters', '/admin/art/data', 'type=5&status=1&lock=1&pic=2'),
        get('wd', '/admin/art/data', 'wd=' . rawurlencode('新闻')),
        get('repeat', '/admin/art/data', 'repeat=1'),
    ]);
    $s[] = sc('art-info', [get('new', '/admin/art/info'), get('edit', '/admin/art/info', 'id=1')]);
    $s[] = sc('art-save', [
        raw('new', '/admin/art/info', art_pages_raw(art_form(), [['第一页', '注一', '<p>内容一<img src="http://p.test/a.jpg"></p>'],
            ['第二页', '', '<p>内容二</p>']])),
        raw('edit', '/admin/art/info', art_pages_raw(art_form(['art_id' => '1', 'art_name' => '新闻一改', 'uptime' => '0',
            'art_hits' => '77', 'art_pic' => 'upload/x.jpg']), [['页', '注', '<b>正文</b>']])),
        post('no-pages', '/admin/art/info', art_form(['art_id' => '3', 'art_name' => '新闻三'])),
        raw('no-type', '/admin/art/info', art_pages_raw(art_form(['art_id' => '1', 'type_id' => '']), [['a', 'b', 'c']])),
    ]);
    $s[] = sc('art-del-field', [
        get('field-status', '/admin/art/field', 'ids=1,2&col=art_status&val=1', ['ajax' => true]),
        get('field-type', '/admin/art/field', 'ids=2&col=type_id&val=6', ['ajax' => true]),
        get('field-hits-rnd', '/admin/art/field', 'ids=1&col=art_hits&start=3&end=3', ['ajax' => true]),
        get('repeat', '/admin/art/data', 'repeat=1'),
        get('del-repeat-min', '/admin/art/del', 'repeat=1&retain=min', ['ajax' => true]),
        get('del', '/admin/art/del', 'ids=1', ['ajax' => true]),
        get('today', '/admin/art/updateToday', '', ['ajax' => true]),
    ]);
    $s[] = sc('art-batch', [
        get('page', '/admin/art/batch'),
        get('lock', '/admin/art/batch', 'ck_lock=1&val_lock=1'),
        get('hits', '/admin/art/batch', 'ck_hits=1&val_hits_min=9&val_hits_max=9&type=6'),
    ]);
    return $s;
}

function vod_form(array $over = [])
{
    return array_merge(['vod_id' => '', 'type_id' => '2', 'vod_level' => '0', 'vod_status' => '1', 'vod_lock' => '0',
        'vod_isend' => '1', 'vod_copyright' => '0', 'uptime' => '1', 'vod_name' => '新视频', 'vod_sub' => '', 'vod_en' => '',
        'vod_letter' => '', 'vod_color' => '', 'vod_tag' => '', 'uptag' => '0', 'vod_remarks' => '', 'vod_behind' => '',
        'vod_total' => '0', 'vod_serial' => '0', 'vod_pubdate' => '', 'vod_actor' => '', 'vod_director' => '', 'vod_writer' => '',
        'vod_tv' => '', 'vod_weekday' => '', 'vod_duration' => '', 'vod_douban_score' => '0', 'vod_douban_id' => '0',
        'vod_rel_vod' => '', 'vod_rel_art' => '', 'vod_class' => '', 'vod_year' => '', 'vod_area' => '', 'vod_lang' => '',
        'vod_version' => '', 'vod_state' => '', 'vod_pic' => '', 'vod_pic_thumb' => '', 'vod_pic_slide' => '',
        'vod_pic_screenshot' => '', 'vod_blurb' => '', 'vod_content' => '', 'vod_up' => '0', 'vod_down' => '0', 'vod_hits' => '0',
        'vod_hits_month' => '0', 'vod_author' => '', 'vod_hits_week' => '0', 'vod_hits_day' => '0', 'vod_jumpurl' => '',
        'vod_score' => '0', 'vod_score_all' => '0', 'vod_score_num' => '0', 'vod_points' => '0', 'vod_points_play' => '0',
        'vod_points_down' => '0', 'vod_tpl' => '', 'vod_tpl_play' => '', 'vod_tpl_down' => '', 'vod_pwd' => '', 'vod_pwd_url' => '',
        'vod_pwd_play' => '', 'vod_pwd_play_url' => '', 'vod_pwd_down' => '', 'vod_pwd_down_url' => ''], $over);
}

function vod_search_rows()
{
    $now = time();
    $r = function ($word, $field, $hits, $ids) use ($now) {
        return ['search_key' => md5($word . '@' . $field), 'search_word' => $word, 'search_field' => $field,
            'search_hit_count' => $hits, 'search_last_hit_time' => $now - $hits, 'search_update_time' => $now,
            'search_result_count' => count(array_filter(explode(',', $ids))), 'search_result_ids' => $ids];
    };
    return [$r('星际', 'vod_name', 9, '1,2,4'), $r('ABC', 'vod_name', 8, ''), $r('马', 'vod_actor|vod_director', 7, '1'),
        $r('甄嬛', 'vod_name', 6, '5'), $r('新', 'vod_name|vod_sub', 5, '')];
}

function scenarios_vod()
{
    $s = [];
    $s[] = sc('vod-list', [
        get('data', '/admin/vod/data'),
        get('filters', '/admin/vod/data', 'type=1&lock=1&copyright=1&isend=0'),
        get('filters2', '/admin/vod/data', 'area=' . rawurlencode('美国') . '&lang=' . rawurlencode('英语') . '&weekday='
            . rawurlencode('三') . '&points=1&pic=3'),
        get('player', '/admin/vod/data', 'player=dplayer'),
        get('player-no', '/admin/vod/data', 'player=no&downer=no'),
        get('downer', '/admin/vod/data', 'downer=xunlei&server=server1'),
        get('url-plot', '/admin/vod/data', 'url=1&plot=0&status=1&level=0'),
        get('order', '/admin/vod/data', 'order=vod_hits_month'),
        get('order-bad', '/admin/vod/data', 'order=vod_name'),
        get('wd-actor', '/admin/vod/data', 'wd=' . rawurlencode('马修')),
        get('wd-sub', '/admin/vod/data', 'wd=' . rawurlencode('重名')),
        get('repeat', '/admin/vod/data', 'repeat=1'),
        get('select', '/admin/vod/data', 'select=1&input=vod_rel_vod&page=2&limit=2'),
    ]);
    $s[] = sc('vod-info', [get('new', '/admin/vod/info'), get('edit', '/admin/vod/info', 'id=1'),
        get('edit-3', '/admin/vod/info', 'id=3')]);
    $s[] = sc('vod-save', [
        post('new', '/admin/vod/info', vod_form(['vod_name' => '新<script>x</script>片', 'vod_remarks' => str_repeat('长', 120),
            'vod_content' => '<p>正文<img src="http://p.test/1.jpg"></p>', 'vod_pic_screenshot' => "a.jpg\r\nb.jpg",
            'vod_play_from' => ['dplayer', 'videojs'], 'vod_play_server' => ['no', 'server1'], 'vod_play_note' => ['', '高清'],
            'vod_play_url' => ["第1集\$http://n.test/1\r\n第2集\$http://n.test/2", 'http://n.test/x'],
            'vod_down_from' => ['http'], 'vod_down_server' => ['no'], 'vod_down_note' => [''], 'vod_down_url' => ['http://d.test/1']])),
        post('edit', '/admin/vod/info', vod_form(['vod_id' => '1', 'vod_name' => '星际穿越改', 'uptime' => '0', 'vod_hits' => '55',
            'vod_en' => 'xingji', 'vod_letter' => 'Q', 'vod_blurb' => '自写简介', 'vod_play_from' => ['videojs'],
            'vod_play_server' => ['no'], 'vod_play_note' => [''], 'vod_play_url' => ['http://e.test/1']])),
        post('no-groups', '/admin/vod/info', vod_form(['vod_id' => '3', 'vod_name' => '盗梦空间', 'type_id' => '4'])),
        post('no-name', '/admin/vod/info', vod_form(['vod_id' => '3', 'vod_name' => ''])),
        post('no-type', '/admin/vod/info', vod_form(['vod_id' => '3', 'type_id' => ''])),
        post('type-missing', '/admin/vod/info', vod_form(['vod_id' => '3', 'type_id' => '99'])),
        post('from-no-url', '/admin/vod/info', vod_form(['vod_id' => '3', 'vod_play_from' => ['dplayer'],
            'vod_play_server' => ['no'], 'vod_play_note' => ['']])),
    ]);
    $s[] = sc('vod-search', [
        post('new', '/admin/vod/info', vod_form(['vod_name' => '星际xAbCx', 'vod_actor' => '马克', 'vod_sub' => ''])),
    ], ['config' => ['app' => ['vod_search_optimise' => 'frontend|collect']],
        'seed' => array_merge(base_seed(), ['mac_vod_search' => vod_search_rows()])]);
    $s[] = sc('vod-search-off', [
        post('new', '/admin/vod/info', vod_form(['vod_name' => '星际二'])),
    ], ['seed' => array_merge(base_seed(), ['mac_vod_search' => vod_search_rows()])]);
    $s[] = sc('vod-iplot', [
        get('view', '/admin/vod/iplot', 'id=1'),
        get('view-new', '/admin/vod/iplot', '', ['ajax' => true]),
        post('save', '/admin/vod/iplot', ['vod_id' => '3', 'vod_name' => '盗梦空间改', 'vod_en' => 'dmkj', 'type_id' => '2',
            'vod_plot_name' => ['一', '二'], 'vod_plot_detail' => ['<p>d1</p>', 'd2']]),
        post('save-empty', '/admin/vod/iplot', ['vod_id' => '1', 'vod_name' => '星际穿越', 'vod_en' => '', 'type_id' => '2']),
        post('save-no-id', '/admin/vod/iplot', ['vod_id' => '', 'vod_name' => 'x', 'type_id' => '2',
            'vod_plot_name' => ['一'], 'vod_plot_detail' => ['d']]),
        post('save-no-name', '/admin/vod/iplot', ['vod_id' => '1', 'vod_name' => '', 'type_id' => '2']),
    ]);
    $today = vods();
    $today[4]['vod_time'] = time();
    $s[] = sc('vod-del-field', [
        get('field-status', '/admin/vod/field', 'ids=1,2&col=vod_status&val=0', ['ajax' => true]),
        get('field-type', '/admin/vod/field', 'ids=2&col=type_id&val=4', ['ajax' => true]),
        get('field-copyright', '/admin/vod/field', 'ids=5&col=vod_copyright&val=1', ['ajax' => true]),
        get('field-hits-rnd', '/admin/vod/field', 'ids=1,3&col=vod_hits&start=5&end=5', ['ajax' => true]),
        get('field-bad', '/admin/vod/field', 'ids=1&col=vod_name&val=x', ['ajax' => true]),
        get('today', '/admin/vod/updateToday', '', ['ajax' => true]),
        get('today-type', '/admin/vod/updateToday', 'flag=type', ['ajax' => true]),
        get('repeat', '/admin/vod/data', 'repeat=1'),
        get('del-repeat-max', '/admin/vod/del', 'repeat=1&retain=max', ['ajax' => true]),
        get('del', '/admin/vod/del', 'ids=1,5', ['ajax' => true]),
        get('del-none', '/admin/vod/del', '', ['ajax' => true]),
    ], ['seed' => array_merge(base_seed(), ['mac_vod' => $today])]);
    $s[] = sc('vod-batch', [
        get('page', '/admin/vod/batch'),
        get('level', '/admin/vod/batch', 'ck_level=1&val_level=3'),
        get('copyright-points', '/admin/vod/batch', 'type=2&ck_copyright=1&val_copyright=0&ck_points=1&val_points_play=7&val_points_down='),
        get('hits-zero', '/admin/vod/batch', 'ck_hits=1&val_hits_min=0&val_hits_max=0&type=4'),
        get('del-player', '/admin/vod/batch', 'ck_del=2&player=videojs'),
        get('del-player-whole', '/admin/vod/batch', 'ck_del=2&player=no'),
        get('del-player-missing', '/admin/vod/batch', 'ck_del=2'),
        get('del-downer', '/admin/vod/batch', 'ck_del=3&downer=xunlei&ck_status=1&val_status=0'),
        get('wd-lock', '/admin/vod/batch', 'wd=' . rawurlencode('星际') . '&ck_lock=1&val_lock=1'),
        get('none', '/admin/vod/batch', 'type=4', ['ajax' => true]),
        get('del-all', '/admin/vod/batch', 'type=4&ck_del=1'),
    ]);
    return $s;
}

// ====================================================================== 引擎

function generate($repo, $pdo, $phpCgi, $work, $host, $port, $db, $user, $password)
{
    $out = [];
    $only = getenv('GOLDEN_ONLY');
    $prefixes = $only !== false && $only !== '' ? explode(',', $only) : null;
    $base = deep_merge(include $repo . '/application/extra/maccms.php', base_config());
    $types = column_types($pdo);
    foreach (scenarios($repo) as $sc) {
        if ($prefixes !== null) {
            $hit = false;
            foreach ($prefixes as $p) {
                $hit = $hit || strpos($sc['label'], $p) === 0;
            }
            if (!$hit) {
                continue;
            }
        }
        $conf = $work . '/conf';
        $runtime = $work . '/runtime';
        $site = $work . '/site';
        rm_tree($conf);
        rm_tree($runtime);
        rm_tree($site);
        @mkdir($runtime, 0777, true);
        @mkdir($site . '/static/data', 0777, true);
        @mkdir($site . '/static/ueditor', 0777, true);
        copy($repo . '/static/data/pinyin.dat', $site . '/static/data/pinyin.dat');
        copy($repo . '/static/ueditor/config.json', $site . '/static/ueditor/config.json');
        @mkdir($site . '/upload', 0777, true);
        foreach ($sc['files'] as $rel => $content) {
            @mkdir(dirname($site . '/' . $rel), 0777, true);
            file_put_contents($site . '/' . $rel, $content);
        }
        $over = $sc['config'];
        $maccms = deep_merge($base, $over);
        write_conf($repo, $conf, $maccms, $host, $port, $db, $user, $password);
        seed($pdo, $sc['seed']);
        $rows0 = read_all($pdo, $types);
        $files0 = list_files($site . '/upload');
        $last = $rows0;
        $lastFiles = $files0;
        $jar = [];
        $token = null;
        $steps = [];
        foreach ($sc['steps'] as $st) {
            if (isset($st['sql'])) {
                $pdo->exec($st['sql']);
                $now = read_all($pdo, $types);
                $steps[] = ['label' => $st['label'], 'sql' => $st['sql'], 'tables' => (object)changed($last, $now)];
                $last = $now;
                continue;
            }
            $headers = $st['headers'];
            if ($st['ajax']) {
                $headers['X-Requested-With'] = 'XMLHttpRequest';
            }
            if ($jar) {
                $pairs = [];
                foreach ($jar as $k => $v) {
                    $pairs[] = $k . '=' . $v;
                }
                $headers['Cookie'] = implode('; ', $pairs);
            }
            $contentType = 'application/x-www-form-urlencoded';
            if ($st['multipart'] !== null) {
                list($post, $contentType) = multipart($st['multipart']);
            } elseif (is_array($st['post'])) {
                $post = isset($st['post']['__raw__']) ? $st['post']['__raw__'] : http_build_query($st['post']);
            } else {
                $post = $st['post'];
            }
            $post = str_replace(['{token}', '%7Btoken%7D'], rawurlencode((string)$token), $post);
            $req = ['path' => $st['path'], 'query' => $st['query'], 'method' => $st['method'], 'post' => $post,
                'contentType' => $contentType, 'headers' => $headers, 'remote' => '127.0.0.1'];
            $started = time();
            list($status, $respHeaders, $setCookies, $body) = run_request($repo, $phpCgi, $conf, $runtime, $site, $req);
            $after = time();
            $now = read_all($pdo, $types);
            $files = list_files($site . '/upload');
            $tables = changed($last, $now);
            $last = $now;
            foreach ($setCookies as $c) {
                $pair = explode(';', $c, 2)[0];
                list($k, $v) = array_pad(explode('=', $pair, 2), 2, '');
                if ($v === 'deleted' || stripos($c, 'Max-Age=0') !== false) {
                    unset($jar[$k]);
                } else {
                    $jar[$k] = $v;
                }
            }
            if (preg_match('/name="__token__"(?: type="hidden")? value="([0-9a-f]+)"/', $body, $tm)) {
                $token = $tm[1];
            }
            $steps[] = ['label' => $st['label'], 'request' => ['method' => $st['method'], 'path' => $st['path'],
                'query' => $st['query'], 'post' => $st['multipart'] !== null ? null : $post,
                'multipart' => $st['multipart'] !== null ? multipart_describe($st['multipart']) : null,
                'ajax' => $st['ajax'], 'headers' => (object)$st['headers']],
                'started' => $started, 'after' => $after, 'status' => $status,
                'contentType' => isset($respHeaders['content-type']) ? $respHeaders['content-type'] : null,
                'location' => isset($respHeaders['location']) ? $respHeaders['location'] : null,
                'setCookies' => $setCookies, 'body' => !$st['body'] ? null : base64_encode($body),
                'tables' => (object)$tables, 'files' => $files === $lastFiles ? null : (object)$files];
            $lastFiles = $files;
        }
        $out[] = ['label' => $sc['label'], 'config' => (object)$over,
            'maccmsPhp' => '<?php return ' . var_export($maccms, true) . ';', 'seed' => encode_seed($sc['seed'], $types),
            'filesSeed' => (object)array_map('base64_encode', $sc['files']), 'files0' => (object)$files0, 'rows0' => $rows0,
            'steps' => $steps];
    }
    return json_encode(['generator' => 'backend/src/test/php/admin_content_golden.php', 'php' => PHP_VERSION,
            'timezone' => 'PRC', 'entry' => ENTRY, 'host' => 'www.example.test', 'remote' => '127.0.0.1',
            'baseConfig' => base_config(), 'admins' => admins(), 'scenarios' => $out],
            JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT | JSON_INVALID_UTF8_SUBSTITUTE) . "\n";
}

/** 每张表的列名 → 是否数值列（information_schema，一次性库）。 */
function column_types($pdo)
{
    $out = [];
    $rows = $pdo->query("SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
        . "ORDER BY TABLE_NAME, ORDINAL_POSITION")->fetchAll(PDO::FETCH_ASSOC);
    foreach ($rows as $r) {
        $out[$r['TABLE_NAME']][$r['COLUMN_NAME']] = in_array($r['DATA_TYPE'], ['tinyint', 'smallint', 'mediumint', 'int', 'bigint'], true)
            ? 'int' : ($r['DATA_TYPE'] === 'decimal' ? 'decimal' : 'text');
    }
    return $out;
}

/** 清空跟踪的表并复位自增计数（一次性库上的测试夹具），写入场景的行与管理员。 */
function seed($pdo, array $seed)
{
    foreach (array_merge(array_keys(TABLES), ['mac_admin']) as $t) {
        $pdo->exec("DELETE FROM $t");
        $pdo->exec("ALTER TABLE $t AUTO_INCREMENT = 1");
    }
    // 旧版查重留下的临时表（一次性库）：每个场景从没有临时表开始，场景之间不串数据
    foreach (['mac_tmpvod', 'mac_tmpart', 'mac_tmpwebsite'] as $t) {
        $pdo->exec("DROP TABLE IF EXISTS $t");
    }
    $all = $seed;
    $all['mac_admin'] = admins();
    foreach ($all as $table => $rows) {
        foreach ($rows as $row) {
            $cols = array_keys($row);
            $stmt = $pdo->prepare("INSERT INTO $table (" . implode(', ', $cols) . ') VALUES ('
                . implode(', ', array_fill(0, count($cols), '?')) . ')');
            $stmt->execute(array_values($row));
        }
    }
}

function encode_seed(array $seed, $types)
{
    $out = [];
    foreach ($seed as $table => $rows) {
        $out[$table] = [];
        foreach ($rows as $row) {
            $out[$table][] = encode_row($table, $row, $types);
        }
    }
    return (object)$out;
}

function encode_row($table, array $row, $types)
{
    $r = [];
    foreach ($row as $c => $v) {
        $kind = $types[$table][$c];
        $r[$c] = $kind === 'int' ? (int)$v : ($kind === 'decimal' ? (string)$v : bin2hex((string)$v));
    }
    return $r;
}

/** 跟踪的各表的全部行（主键顺序；整数列为整数，decimal 为字符串，其余十六进制）。 */
function read_all($pdo, $types)
{
    $out = [];
    foreach (TABLES as $t => $pk) {
        $rows = [];
        foreach ($pdo->query("SELECT * FROM $t ORDER BY $pk")->fetchAll(PDO::FETCH_ASSOC) as $row) {
            $rows[] = encode_row($t, $row, $types);
        }
        $out[$t] = $rows;
    }
    return $out;
}

function changed($before, $after)
{
    $out = [];
    foreach ($after as $t => $rows) {
        if ($before[$t] !== $rows) {
            $out[$t] = $rows;
        }
    }
    return $out;
}

/** upload/ 下的文件（相对站点根的路径 → 大小与 sha256）。 */
function list_files($dir)
{
    $out = [];
    if (!is_dir($dir)) {
        return $out;
    }
    $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($dir, FilesystemIterator::SKIP_DOTS));
    $base = dirname($dir);
    foreach ($it as $f) {
        if ($f->isFile()) {
            $rel = str_replace('\\', '/', substr($f->getPathname(), strlen($base) + 1));
            $out[$rel] = ['size' => $f->getSize(), 'sha256' => hash_file('sha256', $f->getPathname())];
        }
    }
    ksort($out);
    return $out;
}

/** multipart/form-data 正文：$parts = [[name, value] 或 [name, filename, content, type]]。 */
function multipart(array $parts)
{
    $boundary = '----goldenboundary7d3a';
    $body = '';
    foreach ($parts as $p) {
        $body .= "--$boundary\r\n";
        if (count($p) === 2) {
            $body .= "Content-Disposition: form-data; name=\"{$p[0]}\"\r\n\r\n{$p[1]}\r\n";
        } else {
            $body .= "Content-Disposition: form-data; name=\"{$p[0]}\"; filename=\"{$p[1]}\"\r\nContent-Type: {$p[3]}\r\n\r\n{$p[2]}\r\n";
        }
    }
    $body .= "--$boundary--\r\n";
    return [$body, 'multipart/form-data; boundary=' . $boundary];
}

function multipart_describe(array $parts)
{
    $out = [];
    foreach ($parts as $p) {
        $out[] = count($p) === 2 ? ['name' => $p[0], 'value' => $p[1]]
            : ['name' => $p[0], 'filename' => $p[1], 'content' => base64_encode($p[2]), 'type' => $p[3]];
    }
    return $out;
}

/** 递归合并（后者覆盖前者；列表整体替换）。 */
function deep_merge($base, $over)
{
    foreach ($over as $k => $v) {
        if (is_array($v) && isset($base[$k]) && is_array($base[$k])) {
            $base[$k] = deep_merge($base[$k], $v);
        } else {
            $base[$k] = $v;
        }
    }
    return $base;
}

function write_conf($repo, $conf, $maccms, $host, $port, $db, $user, $password)
{
    @mkdir($conf . '/extra', 0777, true);
    foreach (['config.php', 'route.php', 'tags.php', 'command.php'] as $f) {
        copy($repo . '/application/' . $f, $conf . '/' . $f);
    }
    foreach (glob($repo . '/application/extra/*.php') as $f) {
        copy($f, $conf . '/extra/' . basename($f));
    }
    $database = include $repo . '/application/database.php';
    $database = array_merge($database, ['hostname' => $host, 'hostport' => $port, 'database' => $db,
        'username' => $user, 'password' => $password, 'debug' => false]);
    file_put_contents($conf . '/database.php', '<?php return ' . var_export($database, true) . ';');
    file_put_contents($conf . '/extra/maccms.php', '<?php return ' . var_export($maccms, true) . ';');
}

/** 以 php-cgi 执行一个后台请求。返回 [status, headers(小写名 => 值), setCookies, body]。 */
function run_request($repo, $phpCgi, $conf, $runtime, $site, $req)
{
    $query = $req['query'];
    $method = $req['method'];
    $post = $req['post'];
    $entryDir = dirname($runtime) . DIRECTORY_SEPARATOR . 'entry';
    $entryFile = $entryDir . DIRECTORY_SEPARATOR . '_admin.php';
    if (!is_file($entryFile)) {
        @mkdir($entryDir, 0777, true);
        file_put_contents($entryFile, '<?php require ' . var_export(__DIR__ . DIRECTORY_SEPARATOR . 'admin_content_child.php', true) . ';');
    }
    $env = [
        'GOLDEN_CONF_PATH' => $conf,
        'GOLDEN_RUNTIME_PATH' => $runtime,
        'GOLDEN_SESSION_PATH' => $runtime,
        'GOLDEN_SITE_ROOT' => $site,
        'REDIRECT_STATUS' => '200',
        'GATEWAY_INTERFACE' => 'CGI/1.1',
        'SERVER_PROTOCOL' => 'HTTP/1.1',
        'SERVER_SOFTWARE' => 'golden',
        'SERVER_NAME' => 'www.example.test',
        'SERVER_PORT' => '80',
        'HTTP_HOST' => 'www.example.test',
        'REQUEST_METHOD' => $method,
        'SCRIPT_FILENAME' => $entryFile,
        'SCRIPT_NAME' => ENTRY,
        'PHP_SELF' => ENTRY . $req['path'],
        'REQUEST_URI' => ENTRY . $req['path'] . ($query === '' ? '' : '?' . $query),
        'QUERY_STRING' => $query,
        'REMOTE_ADDR' => $req['remote'],
        'REMOTE_PORT' => '50000',
        'DOCUMENT_ROOT' => $entryDir,
        'SystemRoot' => getenv('SystemRoot') ?: 'C:\\Windows',
        'TEMP' => $runtime,
        'TMP' => $runtime,
    ];
    if ($req['path'] !== '') {
        $env['PATH_INFO'] = $req['path'];
    }
    if ($method === 'POST') {
        $env['CONTENT_TYPE'] = $req['contentType'];
        $env['CONTENT_LENGTH'] = (string)strlen($post);
    }
    foreach ($req['headers'] as $name => $value) {
        $env['HTTP_' . strtoupper(str_replace('-', '_', $name))] = $value;
    }
    $cmd = escapeshellarg($phpCgi) . ' -d session.save_path=' . escapeshellarg($runtime)
        . ' -d upload_tmp_dir=' . escapeshellarg($runtime)
        . ' -d display_errors=0 -d error_reporting=0 -d session.gc_probability=0';
    $proc = proc_open($cmd, [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['pipe', 'w']], $pipes, $site, $env,
        ['bypass_shell' => true]);
    if (!is_resource($proc)) {
        throw new RuntimeException('php-cgi');
    }
    fwrite($pipes[0], $post);
    fclose($pipes[0]);
    $out = stream_get_contents($pipes[1]);
    stream_get_contents($pipes[2]);
    fclose($pipes[1]);
    fclose($pipes[2]);
    proc_close($proc);
    $sep = strpos($out, "\r\n\r\n");
    if ($sep === false) {
        throw new RuntimeException('php-cgi 输出没有头部');
    }
    $headers = [];
    $setCookies = [];
    $status = 200;
    foreach (explode("\r\n", substr($out, 0, $sep)) as $line) {
        $p = strpos($line, ':');
        if ($p === false) {
            continue;
        }
        $name = strtolower(trim(substr($line, 0, $p)));
        $value = trim(substr($line, $p + 1));
        if ($name === 'status') {
            $status = (int)$value;
            continue;
        }
        if ($name === 'set-cookie') {
            $setCookies[] = $value;
            continue;
        }
        $headers[$name] = $value;
    }
    return [$status, $headers, $setCookies, substr($out, $sep + 4)];
}

function sql_statements($file)
{
    $text = file_get_contents($file);
    $kept = [];
    foreach (preg_split('/\r?\n/', $text) as $line) {
        if (strpos($line, '--') !== 0) {
            $kept[] = $line;
        }
    }
    $out = [];
    foreach (preg_split('/;\s*\n/', implode("\n", $kept) . "\n") as $stmt) {
        if (trim($stmt) !== '') {
            $out[] = trim($stmt);
        }
    }
    return $out;
}

function rm_tree($dir)
{
    if (!is_dir($dir)) {
        return;
    }
    $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($dir, FilesystemIterator::SKIP_DOTS),
        RecursiveIteratorIterator::CHILD_FIRST);
    foreach ($it as $f) {
        $f->isDir() ? rmdir($f->getPathname()) : unlink($f->getPathname());
    }
    rmdir($dir);
}
