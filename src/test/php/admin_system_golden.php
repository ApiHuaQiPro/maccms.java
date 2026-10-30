<?php
/*
 * B15（MACC-39）后台系统配置、播放器/服务器/下载器、站群、定时任务、快捷菜单、推送配置、数据库工具与本地插件的 PHP 黄金样本
 * （契约见 docs/architecture/24-b15-admin-system.md 第 10 节）。
 *
 * 每个场景是一串真实的后台请求：经 admin_system_child.php 以 php-cgi 跑完整的 ThinkPHP 路由、admin/controller/Base 与各控制器；站点是
 * 本次运行在临时目录里复制出来的副本（application/、thinkphp/、vendor/、static/js、static/player、template/、addons/），每个场景开始前把
 * 会被写的区域（application/extra/、route.php、static/js 的三个文件、static/player/、static/addons/、template/、addons/、
 * application/data/backup/、runtime/）还原成仓库里的样子，再按场景写入预置文件。每一步记录状态码、Content-Type、Location、
 * Content-Disposition、正文（base64）与"这一步之后变化了的被跟踪文件"（base64；被删为 null）。被跟踪的文件见 tracked_files()。
 *
 * 表单步骤（form）先 GET 页面，再像浏览器里的 jQuery serialize() 一样从页面的第一个表单取出要提交的字段（文档顺序；忽略 disabled、
 * 按钮与未勾选的复选框/单选框；同名单选框以最后一个 checked 为准；下拉框取最后一个 selected，没有时取第一项；textarea 去掉开头的换行、
 * 换行写成 \r\n），按场景改几个字段后 POST。记录的 POST 正文里表单令牌写成 {token}，复放时换成新版页面里的令牌。
 *
 * 流程：外部管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入 install.sql → 写入管理员与会员组 → 逐个场景执行 → 输出 JSON →
 * 删除一次性库与临时目录。不修改任何仓库文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置。
 * 不调用会访问网络的动作（addon/install、addon/upgrade 会下载插件市场的包，system/test_email 发邮件，system/test_cache 连接缓存服务，
 * urlsend/push 推送），也不调用会改表结构或做表维护的动作（database/import、optimize、repair 与 sql 的 POST）。
 *
 * 管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；另需 PHP_CGI 与 GOLDEN_VENDOR_DIR
 * （备份 zip 里解出的 vendor/ 目录，git 不收录，见根目录 AGENTS.md "本地运行与测试"第 1 步）。
 * 用法（仓库根目录，PHP 8.0 与 7.3 各一份）：
 *   PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe src/test/php/admin_system_golden.php \
 *       > src/test/resources/admin/system/php80-golden.json
 */

error_reporting(E_ERROR | E_PARSE);
date_default_timezone_set('PRC');
$repo = realpath(__DIR__ . '/../../../php');

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
$vendor = env_required('GOLDEN_VENDOR_DIR');
if (!is_file($vendor . '/autoload.php') || !is_dir($vendor . '/karsonzhang/fastadmin-addons')) {
    fwrite(STDERR, "GOLDEN_VENDOR_DIR 不是完整的 vendor 目录\n");
    exit(2);
}
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
const HOST = 'www.example.test';

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$work = sys_get_temp_dir() . DIRECTORY_SEPARATOR . 'maccms-b15-golden-' . bin2hex(random_bytes(4));
$exit = 0;
try {
    $pdo->exec("USE `$dbName`");
    foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
        $pdo->exec($sql);
    }
    seed($pdo);
    echo generate($repo, $vendor, $pdo, $phpCgi, $work, $host, $port, $dbName, $adminUser, $adminPassword);
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

// ====================================================================== 数据

/** 管理员（1 超级管理员、2 只有部分权限）、会员组（系统配置页的"会员组"下拉）、几行分类与视频（数据库工具的备份与批量替换）。 */
function seed($pdo)
{
    // 只在写种子数据的这个连接上放宽严格模式：省略的 text NOT NULL 列取隐式默认值 ''（vod_pic_screenshot 等可为 NULL 的列保持 NULL）
    $pdo->exec("SET SESSION sql_mode = ''");
    $pdo->exec("INSERT INTO mac_admin (admin_id, admin_name, admin_pwd, admin_status, admin_auth) VALUES "
        . "(1, 'admin', '" . md5('admin888') . "', 1, ''), (2, 'editor', '" . md5('ed pass') . "', 1, ',system/config,vodplayer/index,')");
    $pdo->exec("DELETE FROM mac_group");
    $pdo->exec("INSERT INTO mac_group (group_id, group_name, group_status, group_type, group_popedom, group_points_day, "
        . "group_points_week, group_points_month, group_points_year, group_points_free) VALUES "
        . "(1, '游客', 1, ',1,', '', 0, 0, 0, 0, 0), (2, '默认会员', 1, ',1,', '', 0, 0, 0, 0, 0), "
        . "(3, 'VIP<b>会员</b>', 1, ',1,', '', 10, 20, 30, 40, 0)");
    $pdo->exec("INSERT INTO mac_type (type_id, type_name, type_en, type_sort, type_mid, type_pid, type_status, type_tpl, "
        . "type_tpl_list, type_tpl_detail, type_tpl_play, type_tpl_down, type_key, type_des, type_title, type_union, "
        . "type_extend, type_logo, type_pic, type_jumpurl) VALUES "
        . "(1, '电影', 'dianying', 1, 1, 0, 1, 'type.html', 'show.html', 'detail.html', 'play.html', 'down.html', '', "
        . "'it''s \\\\ \"q\"\nline', '', '', '', '', '', ''), "
        . "(2, '连续剧', 'lianxuju', 2, 1, 0, 1, 'type.html', 'show.html', 'detail.html', 'play.html', 'down.html', '', '', '', '', '', '', '', '')");
    $pdo->exec("INSERT INTO mac_vod (vod_id, type_id, vod_name, vod_en, vod_status, vod_play_from, vod_play_url, vod_content, "
        . "vod_remarks, vod_pic) VALUES "
        . "(1, 1, '测试视频一', 'ceshi1', 1, 'dplayer', '第1集\$http://old.example.test/1.m3u8', '<p>http://old.example.test/a</p>', 'HD', ''), "
        . "(2, 2, '测试视频二', 'ceshi2', 1, 'dplayer', '第1集\$http://old.example.test/2.m3u8', '', 'OLD old Old', '')");
}

// ====================================================================== 场景

function req($label, $method, $path, $query = '', $post = '', array $opt = [])
{
    return ['label' => $label, 'method' => $method, 'path' => $path, 'query' => $query, 'post' => $post,
        'ajax' => !isset($opt['ajax']) || $opt['ajax'], 'headers' => isset($opt['headers']) ? $opt['headers'] : []];
}

function get_page($label, $path, $query = '')
{
    return req($label, 'GET', $path, $query, '', ['ajax' => false]);
}

/** 打开 $path 的页面，取第一个表单要提交的字段，按 $mods 修改后 POST 到 $path（或 $opt['action']）。 */
function form_step($label, $path, array $mods = [], array $opt = [])
{
    return ['label' => $label, 'form' => $path, 'query' => isset($opt['query']) ? $opt['query'] : '', 'mods' => $mods,
        'action' => isset($opt['action']) ? $opt['action'] : $path, 'formIndex' => isset($opt['formIndex']) ? $opt['formIndex'] : 0];
}

function raw_post($label, $path, $raw, array $opt = [])
{
    return req($label, 'POST', $path, isset($opt['query']) ? $opt['query'] : '', ['__raw__' => $raw], $opt);
}

/** multipart 上传（导入）。旧页面把表单令牌放在查询串里（?__token__=）。 */
function upload_step($label, $path, $field, $filename, $content, array $fields = [], $query = '')
{
    return ['label' => $label, 'upload' => $path, 'field' => $field, 'filename' => $filename, 'content' => $content,
        'fields' => $fields, 'query' => $query];
}

/** 直接改临时站点里的文件（null 为删除），用于制造并发修改等前置状态。 */
function file_step($label, $rel, $content)
{
    return ['label' => $label, 'file' => $rel, 'content' => $content];
}

function login_steps()
{
    return [req('login', 'POST', '/admin/index/login', '', ['admin_name' => 'admin', 'admin_pwd' => 'admin888'])];
}

function set($name, $value)
{
    return ['set', $name, $value];
}

function remove($name)
{
    return ['remove', $name];
}

function add($name, $value)
{
    return ['add', $name, $value];
}

function scenarios($repo)
{
    $s = [];
    $L = login_steps();
    $special = "A&B <i>\"双引号\"</i> 'single' \\ 中文 © €";

    // ---------------- 系统配置各组（System.php）：GET 页面 → 改几个字段 → 保存 → 再 GET（页面显示保存后的值）
    $s[] = ['label' => 'config-base', 'steps' => array_merge($L, [
        form_step('save', '/admin/system/config', [set('site[site_name]', $special), set('site[site_keywords]', '关键字,keywords'),
            set('site[site_tj]', "<script>var tj='统计';</script>"), set('app[cache_flag]', 'abcdef1234'),
            set('app[extra_var]', "k1$$\$v1\r\nk2$$\$v2 带空格\r\nbad-line\r\nk3$$\$a$$\$b")]),
        get_page('after', '/admin/system/config'),
        form_step('save-again-document-write', '/admin/system/config',
            [set('site[site_tj]', "document.write('<a href=\"x\">tj</a>');"), set('app[cache_flag]', 'abcdef1234')]),
    ])];
    $s[] = ['label' => 'config-search-rules', 'steps' => array_merge($L, [
        form_step('save', '/admin/system/config', [remove('app[search_vod_rule][]'), add('app[search_vod_rule][]', 'vod_en'),
            add('app[search_vod_rule][]', 'vod_actor'), remove('app[vod_search_optimise][]'), set('app[cache_flag]', 'abcdef1234')]),
        form_step('save-no-optimise', '/admin/system/config', [remove('app[vod_search_optimise][]'),
            set('app[vod_search_optimise_cache_minutes]', '15.9abc'), set('app[cache_flag]', 'abcdef1234')]),
    ])];
    $s[] = ['label' => 'config-search-rules-none', 'steps' => array_merge($L, [
        form_step('save', '/admin/system/config', [remove('app[search_vod_rule][]'), remove('app[search_art_rule][]'),
            set('app[cache_flag]', 'abcdef1234')]),
    ])];
    $s[] = ['label' => 'config-cache-flag-random', 'steps' => array_merge($L, [
        form_step('save', '/admin/system/config', [set('app[cache_flag]', '')]),
    ])];
    $s[] = ['label' => 'config-template-ads-dir', 'files' => ['template/demo/info.ini' => "adsdir = myads\n",
        'template/default/info.ini' => "name = default\n"], 'steps' => array_merge($L, [
        get_page('page', '/admin/system/config'),
        form_step('save', '/admin/system/config', [set('site[template_dir]', 'demo'), set('site[mob_template_dir]', 'default'),
            set('app[cache_flag]', 'abcdef1234')]),
    ])];
    $s[] = ['label' => 'config-token', 'steps' => array_merge($L, [
        get_page('page', '/admin/system/config'),
        raw_post('missing', '/admin/system/config', 'site%5Bsite_name%5D=x'),
        raw_post('wrong', '/admin/system/config', '__token__=deadbeef&site%5Bsite_name%5D=x'),
        raw_post('reuse-after-wrong', '/admin/system/config', '__token__={token}&site%5Bsite_name%5D=x'),
    ])];
    $s[] = ['label' => 'config-invalid-utf8', 'steps' => array_merge($L, [
        form_step('save', '/admin/system/config', [set('site[site_name]', "bad\xFFutf8"), set('site[site_icp]', "ok\xC3"),
            set('app[cache_flag]', 'abcdef1234')]),
    ])];
    foreach (['configseo' => [set('seo[vod][name]', $special)], 'configuser' => [set('user[reg_points]', '12abc')],
                 'configupload' => [set('upload[thumb_size]', '300x400')], 'configcomment' => [set('comment[pagesize]', '5'),
            set('gbook[timespan]', '30')], 'configweixin' => [set('weixin[duihuan]', "多行\r\n文字")],
                 'configpay' => [set('pay[min]', '0.5')], 'configconnect' => [set('connect[qq][key]', $special)],
                 'configemail' => [set('email[tpl][test_body]', "<b>{\$code}</b>\r\n&nbsp;")],
                 'configsms' => [set('sms[content]', '验证码{$code}')], 'configplay' => [set('play[width]', '90%'),
            set('play[parse]', 'https://jx.example.test/?url='), set('play[prestrain]', '//cdn.example.test/p.html')]] as $page => $mods) {
        $s[] = ['label' => $page, 'steps' => array_merge($L, [
            get_page('page', '/admin/system/' . $page),
            form_step('save', '/admin/system/' . $page, $mods),
            get_page('after', '/admin/system/' . $page),
        ])];
    }
    $s[] = ['label' => 'configapi', 'steps' => array_merge($L, [
        form_step('save', '/admin/system/configapi', [set('api[vod][auth]', "a.example.test\r\nb.example.test\r\n"),
            set('api[art][auth]', ''), set('api[vod][charge]', '1')]),
        get_page('after', '/admin/system/configapi'),
    ])];
    $s[] = ['label' => 'configinterface', 'steps' => array_merge($L, [
        form_step('short-pass', '/admin/system/configinterface', [set('interface[status]', '1'), set('interface[pass]', 'short')]),
        form_step('save', '/admin/system/configinterface', [set('interface[status]', '1'), set('interface[pass]', '0123456789abcdef'),
            set('interface[vodtype]', "电影\r\n连续剧"), set('interface[arttype]', '')]),
        get_page('after', '/admin/system/configinterface'),
    ])];
    $s[] = ['label' => 'configcollect', 'steps' => array_merge($L, [
        form_step('save', '/admin/system/configcollect', [remove('collect[vod][inrule][]'), remove('collect[vod][uprule][]'),
            add('collect[art][uprule][]', 'x'), set('collect[vod][words]', "a=b\r\nc=d"), set('collect[vod][namewords]', "x\r\ny")]),
        get_page('after', '/admin/system/configcollect'),
    ])];
    $s[] = ['label' => 'configurl', 'steps' => array_merge($L, [
        form_step('save', '/admin/system/configurl', [set('rewrite[route]', "map   => map/index\r\nrss/index => rss/index\r\n"
            . "vod/:id=>vod/detail\r\nnoarrow\r\n a => b => c \r\n"), set('view[vod_detail]', '2'), set('path[vod_detail]', 'vod/{id}/')]),
        get_page('after', '/admin/system/configurl'),
    ])];
    $s[] = ['label' => 'configplay-missing-js', 'files' => ['static/js/playerconfig.js' => null], 'steps' => array_merge($L, [
        get_page('page', '/admin/system/configplay'),
    ])];
    $s[] = ['label' => 'config-permission', 'steps' => [
        req('login', 'POST', '/admin/index/login', '', ['admin_name' => 'editor', 'admin_pwd' => 'ed pass']),
        get_page('allowed', '/admin/system/config'),
        get_page('denied', '/admin/system/configseo'),
        req('denied-ajax', 'GET', '/admin/domain/index'),
    ]];

    // ---------------- 站群（Domain.php）
    $s[] = ['label' => 'domain', 'steps' => array_merge($L, [
        get_page('empty', '/admin/domain/index'),
        raw_post('save', '/admin/domain/index', http_build_query(['domain' => ['site_url' => ['a.example.test', 'b.example.test', 'a.example.test'],
            'site_name' => ['站A', 'B&<b>', '站A2'], 'site_keywords' => ['k1', 'k2', 'k3'], 'site_description' => ['d1', 'd2', 'd3'],
            'template_dir' => ['default', 'default', 'default'], 'html_dir' => ['html', 'html', 'html'], 'ads_dir' => ['ads', 'ads', 'ads']]])),
        get_page('list', '/admin/domain/index'),
        req('export', 'GET', '/admin/domain/export', '', '', ['ajax' => false]),
        req('del', 'GET', '/admin/domain/del', 'ids=b.example.test'),
        req('del-missing', 'GET', '/admin/domain/del', 'ids=nope.example.test'),
        req('del-empty', 'GET', '/admin/domain/del'),
        upload_step('import', '/admin/domain/import', 'file', 'domains.txt',
            "c.example.test\$站C\$kc\$dc\$default\$html\$ads\n\nd.example.test\$站D\$kd\$dd\$default\$html\n"),
        upload_step('import-bad-ext', '/admin/domain/import', 'file', 'domains.csv', "x\$y"),
    ])];

    // ---------------- 播放器、服务器组、下载器（Vodplayer.php、Vodserver.php、Voddowner.php）
    $s[] = ['label' => 'vodplayer', 'steps' => array_merge($L, [
        get_page('list', '/admin/vodplayer/index'),
        get_page('info', '/admin/vodplayer/info', 'id=dplayer'),
        get_page('info-new', '/admin/vodplayer/info'),
        form_step('save-new', '/admin/vodplayer/info', [set('from', 'myplayer'), set('show', '我的<b>播放器</b>'), set('des', 'des'),
            set('sort', '950'), set('code', "MacPlayer.Html = '<iframe src=\"'+MacPlayer.PlayUrl+'\"></iframe>';\r\nMacPlayer.Show();")]),
        req('checkcache', 'GET', '/admin/index/checkcache', '', '', ['ajax' => false]),
        form_step('save-numeric', '/admin/vodplayer/info', [set('from', '123'), set('show', 'num'), set('sort', '1')]),
        form_step('save-dot', '/admin/vodplayer/info', [set('from', '../evil'), set('show', 'x')]),
        form_step('save-edit-sort-tie', '/admin/vodplayer/info', [set('sort', '907'), set('show', '改')], ['query' => 'id=dplayer']),
        req('field-ps', 'GET', '/admin/vodplayer/field', 'ids=dplayer,videojs&col=ps&val=1'),
        req('field-bad-col', 'GET', '/admin/vodplayer/field', 'ids=dplayer&col=sort&val=1'),
        req('export', 'GET', '/admin/vodplayer/export', 'id=myplayer', '', ['ajax' => false]),
        req('del', 'GET', '/admin/vodplayer/del', 'ids=123_'),
        req('clear', 'GET', '/admin/index/clear'),
        req('checkcache-after-clear', 'GET', '/admin/index/checkcache', '', '', ['ajax' => false]),
        get_page('import-page', '/admin/vodplayer/import'),
        upload_step('import-no-token', '/admin/vodplayer/import', 'file', 'mac_imp.txt', 'x'),
        get_page('import-page-2', '/admin/vodplayer/import'),
        upload_step('import', '/admin/vodplayer/import', 'file', 'mac_imp.txt', base64_encode(json_encode(['status' => '1',
            'from' => 'impplayer', 'show' => '导入', 'des' => 'd', 'target' => '_self', 'ps' => '0', 'parse' => '', 'sort' => '5',
            'tip' => 't', 'id' => 'impplayer', 'code' => "// imported\n"])), [], '__token__={token}'),
        get_page('import-page-3', '/admin/vodplayer/import'),
        upload_step('import-bad-format', '/admin/vodplayer/import', 'file', 'mac_bad.txt', base64_encode(json_encode(['from' => 'x'])),
            [], '__token__={token}'),
        get_page('import-page-4', '/admin/vodplayer/import'),
        upload_step('import-not-base64', '/admin/vodplayer/import', 'file', 'mac_bad.csv', '{not json', [], '__token__={token}'),
        get_page('import-page-5', '/admin/vodplayer/import'),
        upload_step('import-dot', '/admin/vodplayer/import', 'file', 'mac_dot.txt', base64_encode(json_encode(['status' => '1',
            'from' => 'a/b', 'sort' => '1', 'code' => 'x'])), [], '__token__={token}'),
    ])];
    foreach (['vodserver' => 'parse_status', 'voddowner' => 'ps'] as $ctl => $col) {
        $s[] = ['label' => $ctl, 'steps' => array_merge($L, [
            get_page('list', '/admin/' . $ctl . '/index'),
            get_page('info-new', '/admin/' . $ctl . '/info'),
            form_step('save-new', '/admin/' . $ctl . '/info', [set('from', 'new1'), set('show', '新<b>组</b>'), set('sort', '3')]),
            form_step('save-dot', '/admin/' . $ctl . '/info', [set('from', 'a.b'), set('show', 'x')]),
            req('field', 'GET', '/admin/' . $ctl . '/field', 'ids=new1&col=' . $col . '&val=1'),
            req('field-status', 'GET', '/admin/' . $ctl . '/field', 'ids=new1&col=status&val=0'),
            get_page('info', '/admin/' . $ctl . '/info', 'id=new1'),
            req('del', 'GET', '/admin/' . $ctl . '/del', 'ids=new1'),
            req('checkcache', 'GET', '/admin/index/checkcache', '', '', ['ajax' => false]),
        ])];
    }

    // ---------------- 定时任务（Timming.php）、快捷菜单（Index.php:quickmenu）、推送配置（Urlsend.php:index）
    $s[] = ['label' => 'timming', 'steps' => array_merge($L, [
        get_page('list', '/admin/timming/index'),
        get_page('info', '/admin/timming/info', 'id=aa'),
        form_step('save', '/admin/timming/info', [set('name', 'cc'), set('des', '新任务'), set('file', 'make'), set('param', 'ac=index&x=1'),
            remove('weeks[]'), add('weeks[]', '1'), add('weeks[]', '3'), remove('hours[]'), add('hours[]', '02')]),
        req('field', 'GET', '/admin/timming/field', 'ids=cc,bb&col=status&val=1'),
        req('field-bad', 'GET', '/admin/timming/field', 'ids=cc&col=name&val=1'),
        req('del', 'GET', '/admin/timming/del', 'ids=aa'),
        get_page('after', '/admin/timming/index'),
    ])];
    $s[] = ['label' => 'quickmenu', 'steps' => array_merge($L, [
        form_step('save', '/admin/index/quickmenu', [set('quickmenu', "菜单一,system/config\r\n外部,http://www.example.test/\r\n\r\n分隔,###")]),
        get_page('after', '/admin/index/quickmenu'),
        req('frame', 'GET', '/admin/index/index', '', '', ['ajax' => false]),
    ])];
    $s[] = ['label' => 'urlsend', 'steps' => array_merge($L, [
        get_page('page', '/admin/urlsend/index'),
        form_step('save', '/admin/urlsend/index', [set('urlsend[baidu][token]', 'tok&<en>')]),
        get_page('after', '/admin/urlsend/index'),
    ])];

    // ---------------- 数据库工具（Database.php）：只读与纯 DML 的部分
    $s[] = ['label' => 'database', 'steps' => array_merge($L, [
        get_page('tables', '/admin/database/index'),
        get_page('backups-empty', '/admin/database/index', 'group=import'),
        raw_post('export-none', '/admin/database/export', ''),
        raw_post('export', '/admin/database/export', 'ids%5B%5D=mac_type&ids%5B%5D=mac_admin&ids%5B%5D=mac_vod&ids%5B%5D=mac_group'),
        get_page('backups', '/admin/database/index', 'group=import'),
        req('columns', 'GET', '/admin/database/columns', 'table=mac_type'),
        req('columns-invalid', 'GET', '/admin/database/columns', 'table=mac_nope'),
        req('columns-empty', 'GET', '/admin/database/columns'),
        get_page('rep-page', '/admin/database/rep'),
        form_step('rep', '/admin/database/rep', [set('table', 'mac_vod'), set('field', 'vod_play_url'),
            set('findstr', 'old.example.test'), set('tostr', 'new.example.test')]),
        form_step('rep-bad-field', '/admin/database/rep', [set('table', 'mac_vod'), set('field', 'vod_nope'),
            set('findstr', 'a'), set('tostr', 'b')]),
        form_step('rep-missing', '/admin/database/rep', [set('table', 'mac_vod'), set('field', 'vod_remarks'),
            set('findstr', ''), set('tostr', 'b')]),
        form_step('rep-case', '/admin/database/rep', [set('table', 'mac_vod'), set('field', 'vod_remarks'),
            set('findstr', 'old'), set('tostr', 'new')]),
        get_page('sql-page', '/admin/database/sql'),
        req('del-none', 'GET', '/admin/database/del'),
        req('del', 'GET', '/admin/database/del', 'id={backup_id}'),
        get_page('backups-after-del', '/admin/database/index', 'group=import'),
    ]), 'rows' => ['mac_vod' => 'SELECT vod_id, vod_play_url, vod_remarks, vod_content FROM mac_vod ORDER BY vod_id']];
    $s[] = ['label' => 'database-plain', 'config' => ['db' => ['compress' => 0]], 'steps' => array_merge($L, [
        raw_post('export', '/admin/database/export', 'ids%5B%5D=mac_type&ids%5B%5D=mac_vod'),
        get_page('backups', '/admin/database/index', 'group=import'),
    ])];

    // ---------------- 模板管理与广告位（Template.php）：列目录、广告位、静态文件的编辑与删除、路径与扩展名检查
    $tpl = [
        'template/default/html/index.html' => "<html>{\$maccms.site_name}</html>\n",
        'template/default/html/label/a.html' => "<p>a</p>\n",
        'template/default/html/style.css' => "body{}\n",
        'template/default/html/.hidden' => "h",
        'template/default/ads/a.js' => "document.write('a');\n",
        'template/default/ads/b.txt' => "b",
        'template/other/x.html' => str_repeat('x', 3000),
    ];
    $s[] = ['label' => 'template', 'files' => $tpl, 'steps' => array_merge($L, [
        get_page('root', '/admin/template/index'),
        get_page('dir', '/admin/template/index', 'path=.@template@default'),
        get_page('html', '/admin/template/index', 'path=.@template@default@html'),
        get_page('slashes', '/admin/template/index', 'path=.@template/default\\@html'),
        get_page('current', '/admin/template/index', 'current=1'),
        get_page('label', '/admin/template/index', 'label=1'),
        get_page('adsdir', '/admin/template/index', 'ads=1'),
        get_page('outside', '/admin/template/index', 'path=.@application'),
        req('illegal', 'GET', '/admin/template/index', 'path=.@template.@x'),
        get_page('ads', '/admin/template/ads'),
        get_page('info-page', '/admin/template/info', 'fpath=.@template@default@html@label&fname=a.html'),
        form_step('save-html', '/admin/template/info', [set('fcontent', "<p>b</p>\r\n")],
            ['query' => 'fpath=.@template@default@html@label&fname=a.html']),
        form_step('save-php', '/admin/template/info', [set('fcontent', "<?php echo 1;")],
            ['query' => 'fpath=.@template@default@html@label&fname=a.html']),
        form_step('save-tag', '/admin/template/info', [set('fcontent', "<p>{\$vo.name}</p>")],
            ['query' => 'fpath=.@template@default@html@label&fname=a.html']),
        form_step('save-new', '/admin/template/info', [set('fname', 'new.js'), set('fcontent', "var a = 1;")],
            ['query' => 'fpath=./template/default/ads&fname=']),
        form_step('save-noname', '/admin/template/info', [set('fcontent', "x")],
            ['query' => 'fpath=./template/default/ads&fname=']),
        raw_post('save-notoken', '/admin/template/info', 'fname=a.js&fpath=.%2Ftemplate%2Fdefault%2Fads&fcontent=x',
            ['query' => 'fpath=./template/default/ads&fname=a.js']),
        req('bad-ext', 'GET', '/admin/template/info', 'fpath=./template/default/html&fname=x.php'),
        req('bad-ext-htm', 'GET', '/admin/template/info', 'fpath=./template/default/html&fname=x.htm'),
        req('no-path', 'GET', '/admin/template/info', 'fname=a.html'),
        req('bad-path', 'GET', '/admin/template/info', 'fpath=./template/./default&fname=a.html'),
        req('outside-path', 'GET', '/admin/template/info', 'fpath=./application/extra&fname=a.html'),
        req('del', 'GET', '/admin/template/del', 'fname=./template/default/html/style.css'),
        req('del-outside', 'GET', '/admin/template/del', 'fname=./application/extra/quickmenu.php'),
        req('del-dots', 'GET', '/admin/template/del', 'fname=./template/./other/x.html'),
        req('del-missing', 'GET', '/admin/template/del', 'fname=./template/default/nope.html'),
        req('del-array', 'GET', '/admin/template/del', 'fname%5B%5D=./template/default/ads/a.js&fname%5B%5D=./template/other/x.html'),
        get_page('after', '/admin/template/index', 'path=.@template@default@html'),
        get_page('wizard', '/admin/template/wizard'),
    ])];
    $s[] = ['label' => 'template-ads-empty', 'steps' => array_merge($L, [
        get_page('ads', '/admin/template/ads'),
        get_page('root', '/admin/template/index'),
    ])];

    // ---------------- 本地插件（Addon.php → fastadmin-addons Service）
    $demo = demo_addon();
    $s[] = ['label' => 'addon', 'files' => $demo, 'steps' => array_merge($L, [
        get_page('index', '/admin/addon/index'),
        req('downloaded', 'GET', '/admin/addon/downloaded', 'offset=0&limit=10', '', ['ajax' => false]),
        req('downloaded-search', 'GET', '/admin/addon/downloaded', 'search=' . rawurlencode('背景'), '', ['ajax' => false]),
        get_page('add', '/admin/addon/add'),
        get_page('config', '/admin/addon/config', 'name=adminloginbg'),
        raw_post('config-save', '/admin/addon/config', 'name=adminloginbg&row%5Bmode%5D=fixed&row%5Bimage%5D=%2Fupload%2Fbg.jpg'),
        raw_post('config-empty', '/admin/addon/config', 'name=adminloginbg'),
        req('config-missing', 'GET', '/admin/addon/config', 'name=nope'),
        req('enable', 'GET', '/admin/addon/state', 'name=adminloginbg&action=enable'),
        get_page('login-page', '/admin/index/login'),
        req('disable', 'GET', '/admin/addon/state', 'name=adminloginbg&action=disable'),
        req('enable-demo', 'GET', '/admin/addon/state', 'name=demoaddon&action=enable'),
        req('disable-demo', 'GET', '/admin/addon/state', 'name=demoaddon&action=disable'),
        req('state-missing', 'GET', '/admin/addon/state', 'name=nope&action=enable'),
        req('uninstall-dot', 'GET', '/admin/addon/uninstall', 'name=../x'),
        req('uninstall-demo', 'GET', '/admin/addon/uninstall', 'name=demoaddon'),
        raw_post('local', '/admin/addon/local', '__token__=x'),
        get_page('add-again', '/admin/addon/add'),
        raw_post('local-token', '/admin/addon/local', '', ['query' => '__token__={token}']),
        req('info', 'GET', '/admin/addon/info'),
    ])];
    $s[] = ['label' => 'addon-login-random', 'steps' => array_merge($L, [
        req('enable', 'GET', '/admin/addon/state', 'name=adminloginbg&action=enable'),
        req('logout', 'GET', '/admin/index/logout', '', '', ['ajax' => false]),
        get_page('login-page', '/admin/index/login'),
    ])];
    return $s;
}

/** 合成插件：有 PHP 主类（install/uninstall/enable/disable 返回 true，另有一个钩子方法）、assets/ 资源与全局文件 static/demo/。 */
function demo_addon()
{
    return [
        'addons/demoaddon/info.ini' => "name = demoaddon\ntitle = 演示插件\nintro = 合成插件\nauthor = test\nwebsite = \nversion = 1.0.0\nstate = 0\nurl = /addons/demoaddon.html\n",
        'addons/demoaddon/Demoaddon.php' => "<?php\nnamespace addons\\demoaddon;\nuse think\\Addons;\nclass Demoaddon extends Addons\n{\n"
            . "    public function install() { return true; }\n    public function uninstall() { return true; }\n"
            . "    public function enable() { return true; }\n    public function disable() { return true; }\n"
            . "    public function demoHook(\$p) { return true; }\n}\n",
        'addons/demoaddon/config.php' => "<?php\n\nreturn array (\n  0 => \n  array (\n    'name' => 'k',\n    'title' => 'K',\n    'type' => 'string',\n"
            . "    'content' => \n    array (\n    ),\n    'value' => 'v',\n    'rule' => '',\n    'msg' => '',\n    'tip' => '',\n    'ok' => '',\n    'extend' => '',\n  ),\n);\n",
        'addons/demoaddon/assets/demo.css' => "body{}\n",
        'addons/demoaddon/static/demo/demo.js' => "// demo\n",
        'addons/demoaddon/bootstrap.js' => "console.log('demo');",
    ];
}

// ====================================================================== 执行

function generate($repo, $vendor, $pdo, $phpCgi, $work, $host, $port, $db, $user, $password)
{
    @mkdir($work, 0777, true);
    $base = $work . DIRECTORY_SEPARATOR . 'base';
    $site = $work . DIRECTORY_SEPARATOR . 'site';
    // 只读的部分复制一次
    copy_tree($repo . '/application', $base . '/application');
    copy_tree($repo . '/thinkphp', $base . '/thinkphp');
    copy_tree($vendor, $base . '/vendor');
    copy_tree($repo . '/static/js', $base . '/static/js');
    copy_tree($repo . '/static/player', $base . '/static/player');
    copy_tree($repo . '/template', $base . '/template');
    copy_tree($repo . '/addons', $base . '/addons');
    rm_tree($base . '/application/data/backup');
    @mkdir($base . '/application/data/backup', 0777, true);
    // 一次性库的连接与旧配置的基础值（后台登录不要验证码，简体）
    $database = include $repo . '/application/database.php';
    $database = array_merge($database, ['hostname' => $host, 'hostport' => $port, 'database' => $db,
        'username' => $user, 'password' => $password, 'debug' => false]);
    file_put_contents($base . '/application/database.php', '<?php return ' . var_export($database, true) . ';');
    $maccms = include $repo . '/application/extra/maccms.php';
    $maccms['app']['admin_login_verify'] = '0';
    $maccms['app']['lang'] = 'zh-cn';
    $maccms['db']['server'] = '127.0.0.1';
    $maccms['db']['name'] = 'maccms10';
    file_put_contents($base . '/application/extra/maccms.php', "<?php\nreturn " . var_export($maccms, true) . ';');
    // 仓库以 CRLF 检出时换回 LF（git 库里是 LF）
    foreach (array_merge(glob($base . '/application/extra/*.php'), [$base . '/application/route.php']) as $f) {
        file_put_contents($f, str_replace("\r\n", "\n", file_get_contents($f)));
    }
    copy_tree($base, $site);
    $initial = tracked_files($site);

    $out = [];
    foreach (scenarios($repo) as $sc) {
        reset_site($base, $site);
        if (isset($sc['config'])) {
            $cfg = deep_merge(include $base . '/application/extra/maccms.php', $sc['config']);
            file_put_contents($site . '/application/extra/maccms.php', "<?php\nreturn " . var_export($cfg, true) . ';');
        }
        foreach (isset($sc['files']) ? $sc['files'] : [] as $rel => $content) {
            put_file($site, $rel, $content);
        }
        $files0 = tracked_files($site);
        $runtime = $site . DIRECTORY_SEPARATOR . 'runtime';
        $jar = [];
        $token = null;
        $last = $files0;
        $steps = [];
        foreach ($sc['steps'] as $st) {
            if (isset($st['file'])) {
                put_file($site, $st['file'], $st['content']);
                $now = tracked_files($site);
                $steps[] = ['label' => $st['label'], 'file' => $st['file'], 'content' => $st['content'] === null ? null
                    : base64_encode($st['content']), 'changed' => (object)diff_files($last, $now)];
                $last = $now;
                continue;
            }
            if (isset($st['form'])) {
                $page = do_request($site, $phpCgi, $runtime, $jar, ['method' => 'GET', 'path' => $st['form'], 'query' => $st['query'],
                    'post' => '', 'ajax' => false, 'headers' => []]);
                if (preg_match('/name="__token__" value="([0-9a-f]+)"/', $page['body'], $tm)) {
                    $token = $tm[1];
                }
                $fields = apply_mods(serialize_form($page['body'], $st['formIndex']), $st['mods']);
                $post = encode_fields($fields);
                $req = ['method' => 'POST', 'path' => $st['action'], 'query' => $st['query'], 'post' => $post, 'ajax' => true,
                    'headers' => []];
                $res = do_request($site, $phpCgi, $runtime, $jar, $req, $token);
                $now = tracked_files($site);
                $rec = step_record($st['label'], $req, $res, diff_files($last, $now), $page);
                if (isset($sc['rows'])) {
                    $rec['rows'] = read_rows($pdo, $sc['rows']);
                }
                $steps[] = $rec;
                $last = $now;
                continue;
            }
            if (isset($st['upload'])) {
                $boundary = '----goldenboundary' . bin2hex(random_bytes(6));
                $body = '';
                foreach ($st['fields'] as $k => $v) {
                    $body .= "--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n";
                }
                $body .= "--$boundary\r\nContent-Disposition: form-data; name=\"{$st['field']}\"; filename=\"{$st['filename']}\"\r\n"
                    . "Content-Type: text/plain\r\n\r\n{$st['content']}\r\n--$boundary--\r\n";
                $req = ['method' => 'POST', 'path' => $st['upload'], 'query' => $st['query'], 'post' => $body, 'ajax' => true, 'headers' => [],
                    'contentType' => 'multipart/form-data; boundary=' . $boundary];
                $res = do_request($site, $phpCgi, $runtime, $jar, $req, $token);
                $now = tracked_files($site);
                $rec = step_record($st['label'], $req, $res, diff_files($last, $now), null);
                $rec['upload'] = ['field' => $st['field'], 'filename' => $st['filename'], 'content' => base64_encode($st['content']),
                    'fields' => (object)$st['fields']];
                unset($rec['request']['post']);
                $steps[] = $rec;
                $last = $now;
                continue;
            }
            if (is_array($st['post'])) {
                $post = isset($st['post']['__raw__']) ? $st['post']['__raw__'] : http_build_query($st['post']);
            } else {
                $post = $st['post'];
            }
            $req = ['method' => $st['method'], 'path' => $st['path'], 'query' => $st['query'], 'post' => $post, 'ajax' => $st['ajax'],
                'headers' => $st['headers']];
            $res = do_request($site, $phpCgi, $runtime, $jar, $req, $token);
            if (preg_match('/name="__token__" value="([0-9a-f]+)"/', $res['body'], $tm)) {
                $token = $tm[1];
            }
            $now = tracked_files($site);
            $rec = step_record($st['label'], $req, $res, diff_files($last, $now), null);
            if (isset($sc['rows'])) {
                $rec['rows'] = read_rows($pdo, $sc['rows']);
            }
            $steps[] = $rec;
            $last = $now;
        }
        $out[] = ['label' => $sc['label'], 'files' => (object)diff_files($initial, $files0), 'steps' => $steps,
            'config' => isset($sc['config']) ? $sc['config'] : null,
            'rows0' => isset($sc['rows']) ? read_rows($pdo, $sc['rows']) : null];
    }
    // 场景开始前的被跟踪文件（复放时据此重建站点，与检出时的换行设置无关）；static/player/ 下的播放器代码只记 SHA-256（复放时取仓库文件并核对）
    $initialFiles = [];
    foreach ($initial as $rel => $c) {
        $initialFiles[$rel] = strpos($rel, 'static/player/') === 0 ? 'sha256:' . hash('sha256', $c) : base64_encode($c);
    }
    return json_encode(['generator' => 'backend/src/test/php/admin_system_golden.php', 'php' => PHP_VERSION, 'timezone' => 'PRC',
            'entry' => ENTRY, 'host' => HOST, 'initialFiles' => (object)$initialFiles, 'scenarios' => $out],
            JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT | JSON_INVALID_UTF8_SUBSTITUTE) . "\n";
}

/** 场景关心的表行（每个查询的结果；文本列十六进制）。 */
function read_rows($pdo, array $queries)
{
    $out = [];
    foreach ($queries as $name => $sql) {
        $rows = [];
        foreach ($pdo->query($sql)->fetchAll(PDO::FETCH_ASSOC) as $r) {
            foreach ($r as $k => $v) {
                $r[$k] = $v === null ? null : bin2hex($v);
            }
            $rows[] = $r;
        }
        $out[$name] = $rows;
    }
    return $out;
}

function step_record($label, $req, $res, $changed, $page)
{
    $r = ['label' => $label, 'request' => ['method' => $req['method'], 'path' => $req['path'], 'query' => $req['query'],
        'post' => $req['post'], 'ajax' => $req['ajax'], 'headers' => (object)$req['headers']],
        'started' => $res['started'], 'after' => $res['after'], 'status' => $res['status'],
        'contentType' => isset($res['headers']['content-type']) ? $res['headers']['content-type'] : null,
        'location' => isset($res['headers']['location']) ? $res['headers']['location'] : null,
        'disposition' => isset($res['headers']['content-disposition']) ? $res['headers']['content-disposition'] : null,
        'body' => base64_encode($res['body']), 'changed' => (object)$changed];
    if ($page !== null) {
        $r['page'] = ['status' => $page['status'], 'body' => base64_encode($page['body'])];
    }
    return $r;
}

/** 执行一个请求（{token} 换成当前令牌），维护 cookie。 */
function do_request($site, $phpCgi, $runtime, &$jar, $req, $token = null)
{
    $headers = $req['headers'];
    if ($req['ajax']) {
        $headers['X-Requested-With'] = 'XMLHttpRequest';
    }
    if ($jar) {
        $pairs = [];
        foreach ($jar as $k => $v) {
            $pairs[] = $k . '=' . $v;
        }
        $headers['Cookie'] = implode('; ', $pairs);
    }
    $post = str_replace('{token}', rawurlencode((string)$token), $req['post']);
    $query = str_replace(['{token}', '{backup_id}'], [rawurlencode((string)$token), backup_id($site)], $req['query']);
    $started = time();
    list($status, $respHeaders, $setCookies, $body) = run_request($site, $phpCgi, $runtime, $req['method'], $req['path'],
        $query, $post, $headers, isset($req['contentType']) ? $req['contentType'] : null);
    $after = time();
    foreach ($setCookies as $c) {
        $pair = explode(';', $c, 2)[0];
        list($k, $v) = array_pad(explode('=', $pair, 2), 2, '');
        if ($v === 'deleted' || stripos($c, 'Max-Age=0') !== false) {
            unset($jar[$k]);
        } else {
            $jar[$k] = $v;
        }
    }
    return ['status' => $status, 'headers' => $respHeaders, 'body' => $body, 'started' => $started, 'after' => $after];
}

/** 第一份备份的时间戳（database/del 的 id），没有时为空串。 */
function backup_id($site)
{
    foreach (walk($site . '/application/data/backup/database') as $rel) {
        if (preg_match('/^(\d{8})-(\d{6})-\d+\.sql/', $rel, $m)) {
            return (string)strtotime(substr($m[1], 0, 4) . '-' . substr($m[1], 4, 2) . '-' . substr($m[1], 6, 2) . ' '
                . substr($m[2], 0, 2) . ':' . substr($m[2], 2, 2) . ':' . substr($m[2], 4, 2));
        }
    }
    return '';
}

/** 以 php-cgi 执行一个后台请求。返回 [status, headers(小写名 => 值), setCookies, body]。 */
function run_request($site, $phpCgi, $runtime, $method, $path, $query, $post, $headers, $contentType)
{
    $entryDir = dirname($site) . DIRECTORY_SEPARATOR . 'entry';
    $entryFile = $entryDir . DIRECTORY_SEPARATOR . '_admin.php';
    if (!is_file($entryFile)) {
        @mkdir($entryDir, 0777, true);
        file_put_contents($entryFile, '<?php require ' . var_export(__DIR__ . DIRECTORY_SEPARATOR . 'admin_system_child.php', true) . ';');
    }
    @mkdir($runtime, 0777, true);
    $env = [
        'GOLDEN_SITE_ROOT' => $site,
        'GOLDEN_SESSION_PATH' => $runtime,
        'REDIRECT_STATUS' => '200',
        'GATEWAY_INTERFACE' => 'CGI/1.1',
        'SERVER_PROTOCOL' => 'HTTP/1.1',
        'SERVER_SOFTWARE' => 'golden',
        'SERVER_NAME' => HOST,
        'SERVER_PORT' => '80',
        'HTTP_HOST' => HOST,
        'HTTP_USER_AGENT' => 'Mozilla/5.0 (golden)',
        'REQUEST_METHOD' => $method,
        'SCRIPT_FILENAME' => $entryFile,
        'SCRIPT_NAME' => ENTRY,
        'PHP_SELF' => ENTRY . $path,
        'REQUEST_URI' => ENTRY . $path . ($query === '' ? '' : '?' . $query),
        'QUERY_STRING' => $query,
        'REMOTE_ADDR' => '127.0.0.1',
        'REMOTE_PORT' => '50000',
        'DOCUMENT_ROOT' => $entryDir,
        'SystemRoot' => getenv('SystemRoot') ?: 'C:\\Windows',
        'TEMP' => $runtime,
        'TMP' => $runtime,
    ];
    if ($path !== '') {
        $env['PATH_INFO'] = $path;
    }
    if ($method === 'POST') {
        $env['CONTENT_TYPE'] = $contentType === null ? 'application/x-www-form-urlencoded' : $contentType;
        $env['CONTENT_LENGTH'] = (string)strlen($post);
    }
    foreach ($headers as $name => $value) {
        $env['HTTP_' . strtoupper(str_replace('-', '_', $name))] = $value;
    }
    $cmd = escapeshellarg($phpCgi) . ' -d session.save_path=' . escapeshellarg($runtime)
        . ' -d display_errors=0 -d error_reporting=0 -d session.gc_probability=0 -d upload_tmp_dir=' . escapeshellarg($runtime);
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
    $respHeaders = [];
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
        $respHeaders[$name] = $value;
    }
    return [$status, $respHeaders, $setCookies, substr($out, $sep + 4)];
}

// ====================================================================== 表单（浏览器 jQuery serialize() 的取值）

function serialize_form($html, $index)
{
    $doc = new DOMDocument();
    libxml_use_internal_errors(true);
    $doc->loadHTML('<?xml encoding="utf-8" ?>' . $html, LIBXML_NOERROR | LIBXML_NOWARNING);
    libxml_clear_errors();
    $forms = $doc->getElementsByTagName('form');
    if ($forms->length <= $index) {
        return [];
    }
    $form = $forms->item($index);
    $xp = new DOMXPath($doc);
    $fields = [];
    $radioLast = [];
    foreach ($xp->query('.//input|.//select|.//textarea', $form) as $el) {
        $name = $el->getAttribute('name');
        if ($name === '' || $el->hasAttribute('disabled')) {
            continue;
        }
        $tag = strtolower($el->nodeName);
        if ($tag === 'input') {
            $type = strtolower($el->getAttribute('type'));
            if (in_array($type, ['submit', 'button', 'image', 'reset', 'file'], true)) {
                continue;
            }
            $value = $el->hasAttribute('value') ? $el->getAttribute('value') : (in_array($type, ['checkbox', 'radio'], true) ? 'on' : '');
            if ($type === 'radio') {
                if ($el->hasAttribute('checked')) {
                    $radioLast[$name] = count($fields);
                    $fields[] = [$name, $value, 'radio'];
                }
                continue;
            }
            if ($type === 'checkbox' && !$el->hasAttribute('checked')) {
                continue;
            }
            $fields[] = [$name, str_replace(["\r\n", "\r", "\n"], ["\n", "\n", "\r\n"], $value), $type];
        } elseif ($tag === 'select') {
            $options = $xp->query('.//option', $el);
            $chosen = null;
            foreach ($options as $o) {
                if ($o->hasAttribute('selected')) {
                    $chosen = $o;
                }
            }
            if ($chosen === null && $options->length > 0 && !$el->hasAttribute('multiple')) {
                $chosen = $options->item(0);
            }
            if ($chosen !== null) {
                $v = $chosen->hasAttribute('value') ? $chosen->getAttribute('value') : trim(preg_replace('/\s+/', ' ', $chosen->textContent));
                $fields[] = [$name, $v, 'select'];
            }
        } else {
            $text = $el->textContent;
            if (substr($text, 0, 1) === "\n") {
                $text = substr($text, 1);
            }
            $fields[] = [$name, str_replace(["\r\n", "\r", "\n"], ["\n", "\n", "\r\n"], $text), 'textarea'];
        }
    }
    // 同名单选框：只有最后一个 checked 生效
    $out = [];
    foreach ($fields as $i => $f) {
        if ($f[2] === 'radio' && $radioLast[$f[0]] !== $i) {
            continue;
        }
        $out[] = [$f[0], $f[1]];
    }
    return $out;
}

function apply_mods(array $fields, array $mods)
{
    foreach ($mods as $m) {
        if ($m[0] === 'set') {
            $done = false;
            foreach ($fields as &$f) {
                if ($f[0] === $m[1] && !$done) {
                    $f[1] = $m[2];
                    $done = true;
                }
            }
            unset($f);
            if (!$done) {
                $fields[] = [$m[1], $m[2]];
            }
        } elseif ($m[0] === 'remove') {
            $fields = array_values(array_filter($fields, function ($f) use ($m) {
                return $f[0] !== $m[1];
            }));
        } else {
            $fields[] = [$m[1], $m[2]];
        }
    }
    return $fields;
}

/** jQuery.param：encodeURIComponent，空格写成 +；表单令牌写成 {token}。 */
function encode_fields(array $fields)
{
    $parts = [];
    foreach ($fields as $f) {
        $v = $f[0] === '__token__' ? '{token}' : encode_component($f[1]);
        $parts[] = encode_component($f[0]) . '=' . $v;
    }
    return implode('&', $parts);
}

function encode_component($s)
{
    $out = '';
    for ($i = 0; $i < strlen($s); $i++) {
        $c = $s[$i];
        $o = ord($c);
        // 不用 ctype_alnum：它随 LC_CTYPE 变化（PHP 7.3 在 Windows 上把部分 0x80 以上的字节也当作字母）
        if (($o >= 0x30 && $o <= 0x39) || ($o >= 0x41 && $o <= 0x5A) || ($o >= 0x61 && $o <= 0x7A) || strpos("-_.!~*'()", $c) !== false) {
            $out .= $c;
        } elseif ($c === ' ') {
            $out .= '+';
        } else {
            $out .= '%' . strtoupper(bin2hex($c));
        }
    }
    return $out;
}

// ====================================================================== 文件

/**
 * 被跟踪的文件：相对路径 => 内容。数据库备份：文件名里的时间（Ymd-His）写成 <time>，.gz 先解压（gzip 头里有压缩时间）并在名字后加
 * "(gunzip)"，内容里"Date :"一行换成固定文字。
 */
function tracked_files($site)
{
    $out = [];
    $add = function ($rel) use ($site, &$out) {
        $p = $site . '/' . $rel;
        if (is_file($p)) {
            $out[$rel] = file_get_contents($p);
        }
    };
    foreach (glob($site . '/application/extra/*.php') as $f) {
        $add('application/extra/' . basename($f));
    }
    $add('application/route.php');
    foreach (['static/js/playerconfig.js', 'static/js/tj.js', 'static/js/addons.js'] as $f) {
        $add($f);
    }
    foreach (['static/player', 'static/addons', 'static/demo', 'template', 'addons', 'application/data/backup'] as $dir) {
        foreach (walk($site . '/' . $dir) as $rel) {
            $add($dir . '/' . $rel);
        }
    }
    foreach ($out as $rel => $c) {
        if (strpos($rel, 'application/data/backup/') === 0) {
            unset($out[$rel]);
            $name = preg_replace('/\d{8}-\d{6}/', '<time>', $rel);
            if (substr($rel, -3) === '.gz') {
                $c = gzdecode($c);
                $name .= '(gunzip)';
            }
            $out[$name] = preg_replace('/^-- Date : .*$/m', '-- Date : <time>', $c);
        }
    }
    ksort($out, SORT_STRING);
    return $out;
}

function walk($dir)
{
    $out = [];
    if (!is_dir($dir)) {
        return $out;
    }
    $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($dir, FilesystemIterator::SKIP_DOTS));
    foreach ($it as $f) {
        if ($f->isFile()) {
            $out[] = str_replace('\\', '/', substr($f->getPathname(), strlen($dir) + 1));
        }
    }
    sort($out, SORT_STRING);
    return $out;
}

/** 两次快照的差异：新增或改变的文件 => base64，删除的 => null。 */
function diff_files($before, $after)
{
    $d = [];
    foreach ($after as $rel => $c) {
        if (!array_key_exists($rel, $before) || $before[$rel] !== $c) {
            $d[$rel] = base64_encode($c);
        }
    }
    foreach ($before as $rel => $c) {
        if (!array_key_exists($rel, $after)) {
            $d[$rel] = null;
        }
    }
    ksort($d, SORT_STRING);
    return $d;
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

function put_file($site, $rel, $content)
{
    $p = $site . '/' . $rel;
    if ($content === null) {
        @unlink($p);
        return;
    }
    @mkdir(dirname($p), 0777, true);
    file_put_contents($p, $content);
}

/** 把会被写的区域还原成 base 的样子。 */
function reset_site($base, $site)
{
    foreach (['application/extra', 'application/data/backup', 'static/js', 'static/player', 'static/addons', 'static/demo', 'template',
                 'addons', 'runtime'] as $dir) {
        rm_tree($site . '/' . $dir);
        if (is_dir($base . '/' . $dir)) {
            copy_tree($base . '/' . $dir, $site . '/' . $dir);
        }
    }
    @mkdir($site . '/application/data/backup', 0777, true);
    copy($base . '/application/route.php', $site . '/application/route.php');
}

function copy_tree($from, $to)
{
    @mkdir($to, 0777, true);
    if (!is_dir($from)) {
        return;
    }
    $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($from, FilesystemIterator::SKIP_DOTS),
        RecursiveIteratorIterator::SELF_FIRST);
    foreach ($it as $f) {
        $dst = $to . DIRECTORY_SEPARATOR . substr($f->getPathname(), strlen($from) + 1);
        if ($f->isDir()) {
            @mkdir($dst, 0777, true);
        } else {
            copy($f->getPathname(), $dst);
        }
    }
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
