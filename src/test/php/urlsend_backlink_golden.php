<?php
/*
 * B28（MACC-51）百度推送（E09）与友链回链检测（E11）的 PHP 黄金样本（契约见 docs/architecture/30-b28-urlsend-backlink.md 第 8 节）。
 *
 * 每个场景是一串真实的请求：经 urlsend_child.php 以 php-cgi 跑完整的 ThinkPHP 路由与旧控制器——后台 admin/controller/Urlsend 的
 * push/data、Index::check_back_link（mac_check_back_link），定时任务 api/controller/Timming::index 的 urlsend 任务。
 * 外部请求都落到本机模拟服务（php -S 127.0.0.1:随机端口 + 本脚本生成的 router.php）：
 *   - 百度接口的地址在旧代码里写死（http://data.zz.baidu.com/urls?...），子进程的环境变量 http_proxy 指向模拟服务，curl 以代理方式
 *     把请求（绝对地址）发给它；模拟服务按场景的 baidu 队列依次回复，并记录方法、完整地址、Content-Type 与请求体；
 *   - 回链检测抓取的页面就是模拟服务上的路径（no_proxy 直连），内容由场景的 pages 给出。
 * 每一步记录状态码、Content-Type、Location、正文（base64）与这一步模拟服务收到的请求。时间相关的种子按"今天零点 + 偏移"写入
 * （today），新版复放时按自己的今天换算。
 *
 * 流程：外部管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入 install.sql → 写入种子 → 复制临时站点 → 启动模拟服务 → 逐个场景执行 →
 * 输出 JSON → 停止模拟服务、删除一次性库与临时目录。不修改任何仓库文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，
 * 不访问外部网络（所有请求都到 127.0.0.1）。令牌是假值。
 *
 * 管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；另需 PHP_CGI 与 GOLDEN_VENDOR_DIR
 * （备份 zip 里解出的 vendor/ 目录）。用法（仓库根目录，PHP 8.0 与 7.3 各一份）：
 *   PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe src/test/php/urlsend_backlink_golden.php \
 *       > src/test/resources/integration/b28/php80-golden.json
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
if (!is_file($vendor . '/autoload.php')) {
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

const HOST = 'admin.example.test';
const SITE_URL = 'www.example.test';
const SITE_WAPURL = 'm.example.test';
const BAIDU_TOKEN = 'test-only-baidu-token';
const FAST_TOKEN = 'test-only-fast-token';

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$work = sys_get_temp_dir() . DIRECTORY_SEPARATOR . 'maccms-b28-golden-' . bin2hex(random_bytes(4));
$mock = null;
$exit = 0;
try {
    $pdo->exec("USE `$dbName`");
    foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
        $pdo->exec($sql);
    }
    $today = strtotime(date('Y-m-d'));
    $pdo->exec("SET SESSION sql_mode = ''");
    foreach (seed_sql($today) as $sql) {
        $pdo->exec($sql);
    }
    $pdo->exec("SET SESSION sql_mode = DEFAULT");
    @mkdir($work, 0777, true);
    $mock = start_mock($work);
    echo generate($repo, $vendor, $phpCgi, $work, $mock, $host, $port, $dbName, $adminUser, $adminPassword, $today);
} catch (\Throwable $e) {
    // 不输出 message（可能含连接信息），只给类型与位置
    fwrite(STDERR, 'failed: ' . get_class($e) . ' at ' . $e->getFile() . ':' . $e->getLine() . "\n");
    if (getenv('GOLDEN_DEBUG') === '1') {
        fwrite(STDERR, $e->getMessage() . "\n");
    }
    $exit = 1;
} finally {
    if ($mock !== null) {
        stop_mock($mock);
    }
    $pdo->exec("DROP DATABASE `$dbName`");
    rm_tree($work);
}
exit($exit);

// ====================================================================== 数据

/**
 * 种子（新版复放 UrlsendBacklinkGoldenMySqlIT 用同一份语句，{T} 换成今天零点的秒数）：管理员；分类；视频 5 部（4 未审核；3、5 今天更新，
 * 只有 5 今天新增；2 的名称含标签）；文章 3 篇（3 未审核，2 今天更新）；专题、演员、角色、网址。
 */
function seed_template()
{
    $old = '{T}-432000';
    return [
        "INSERT INTO mac_admin (admin_id, admin_name, admin_pwd, admin_status, admin_auth) VALUES "
        . "(1, 'admin', '" . md5('admin888') . "', 1, ''), (2, 'linker', '" . md5('lk pass') . "', 1, ',index/check_back_link,')",
        "INSERT INTO mac_type (type_id, type_name, type_en, type_sort, type_mid, type_pid, type_status) VALUES "
        . "(1, '电影', 'dianying', 1, 1, 0, 1), (6, '动作片', 'dongzuopian', 2, 1, 1, 1), (20, '资讯', 'zixun', 3, 2, 0, 1)",
        "INSERT INTO mac_vod (vod_id, type_id, type_id_1, vod_name, vod_en, vod_status, vod_time, vod_time_add) VALUES "
        . "(1, 6, 1, '视频一', 'shipin1', 1, $old, $old), (2, 6, 1, '视频<b>二</b>', 'shipin2', 1, $old, $old), "
        . "(3, 1, 0, '视频三', 'shipin3', 1, {T}+1, $old), (4, 6, 1, '视频四未审', 'shipin4', 0, {T}+1, {T}+1), "
        . "(5, 6, 1, '视频五', 'shipin5', 1, {T}+2, {T}+2)",
        "INSERT INTO mac_art (art_id, type_id, type_id_1, art_name, art_en, art_status, art_time, art_time_add) VALUES "
        . "(1, 20, 0, '文章一', 'wenzhang1', 1, $old, $old), (2, 20, 0, '文章二', 'wenzhang2', 1, {T}+1, $old), "
        . "(3, 20, 0, '文章三', 'wenzhang3', 0, {T}+1, {T}+1)",
        "INSERT INTO mac_topic (topic_id, topic_name, topic_en, topic_status, topic_time, topic_time_add) VALUES "
        . "(1, '专题一', 'zhuanti1', 1, $old, $old), (2, '专题二', 'zhuanti2', 0, $old, $old)",
        "INSERT INTO mac_actor (actor_id, type_id, actor_name, actor_en, actor_status, actor_time, actor_time_add) VALUES "
        . "(1, 0, '演员一', 'yanyuan1', 1, $old, $old), (2, 0, '演员二', 'yanyuan2', 1, {T}+1, {T}+1)",
        "INSERT INTO mac_role (role_id, role_rid, role_name, role_en, role_status, role_time, role_time_add) VALUES "
        . "(1, 1, '角色一', 'juese1', 1, $old, $old)",
        "INSERT INTO mac_website (website_id, type_id, website_name, website_en, website_status, website_time, website_time_add) VALUES "
        . "(1, 0, '网址一', 'wangzhi1', 1, $old, $old)",
    ];
}

function seed_sql($today)
{
    return array_map(function ($s) use ($today) {
        return str_replace('{T}', (string)$today, $s);
    }, seed_template());
}

// ====================================================================== 场景

function step($label, $entry, $method, $path, $query = '', $post = '', $ajax = false)
{
    return ['label' => $label, 'entry' => $entry, 'method' => $method, 'path' => $path, 'query' => $query, 'post' => $post,
        'ajax' => $ajax];
}

function login()
{
    return step('login', 'admin', 'POST', '/admin/index/login', '', 'admin_name=admin&admin_pwd=admin888', true);
}

/** 后台推送页的"推送"按钮：表单 POST 到 urlsend/push?ac=..&limit=..&page=..&range=..&ac2=..&mid=..（index.html:116-124）。 */
function push($label, $query)
{
    parse_str($query, $q);
    $post = http_build_query(['ac' => $q['ac'], 'range' => isset($q['range']) ? $q['range'] : '0',
        'limit' => isset($q['limit']) ? $q['limit'] : '50', 'page' => isset($q['page']) ? $q['page'] : '1']);
    return step($label, 'admin', 'POST', '/admin/urlsend/push', $query, $post);
}

function check($label, $url)
{
    return step($label, 'admin', 'GET', '/admin/index/check_back_link', $url === null ? '' : 'url=' . rawurlencode($url), '', true);
}

function ok($success, $remain)
{
    return ['status' => 200, 'body' => json_encode(['remain' => $remain, 'success' => $success])];
}

function scenarios($work)
{
    $both = '<html><body><a href="http://' . SITE_URL . '/">主站</a> <a href="http://' . SITE_WAPURL . '/">手机站</a></body></html>';
    $mainOnly = '<html><body><a href="http://' . SITE_URL . '/">主站</a></body></html>';
    $wapOnly = '<html><body><a href="http://' . SITE_WAPURL . '/">手机站</a></body></html>';
    $none = '<html><body><a href="http://other.example.test/">别的站</a></body></html>';
    $s = [];

    // ---------------- 推送（Urlsend::push → data → Baidu/Baidufast::submit）
    $s[] = ['label' => 'baidu-vod-all', 'baidu' => [ok(2, 498), ok(2, 496)], 'steps' => [login(),
        push('page1', 'ac=baidu&limit=2&page=1&range=0&ac2=all&mid=1'),
        push('page2', 'ac=baidu&limit=2&page=2&range=0&ac2=all&mid=1')]];
    $s[] = ['label' => 'baidu-vod-today', 'baidu' => [ok(2, 10), ok(1, 9)], 'steps' => [login(),
        push('update', 'ac=baidu&limit=50&page=1&range=0&ac2=today&mid=1'),
        push('add', 'ac=baidu&limit=50&page=1&range=1&ac2=today&mid=1')]];
    $s[] = ['label' => 'baidu-vod-ids', 'baidu' => [ok(2, 10)], 'steps' => [login(),
        push('ids', 'ac=baidu&limit=50&page=1&range=0&ac2=all&mid=1&ids=3,1,4')]];
    $s[] = ['label' => 'baidu-other-mids', 'baidu' => [ok(2, 10), ok(1, 9), ok(1, 8), ok(2, 7), ok(1, 6), ok(1, 5), ok(1, 4)], 'steps' => [login(),
        push('art', 'ac=baidu&limit=50&page=1&range=0&ac2=all&mid=2'),
        push('art-today', 'ac=baidu&limit=50&page=1&range=0&ac2=today&mid=2'),
        push('topic', 'ac=baidu&limit=50&page=1&range=0&ac2=all&mid=3'),
        push('actor', 'ac=baidu&limit=50&page=1&range=0&ac2=all&mid=8'),
        push('actor-today-add', 'ac=baidu&limit=50&page=1&range=1&ac2=today&mid=8'),
        push('role', 'ac=baidu&limit=50&page=1&range=0&ac2=all&mid=9'),
        push('website', 'ac=baidu&limit=50&page=1&range=0&ac2=all&mid=11')]];
    $s[] = ['label' => 'baidu-errors', 'baidu' => [
        ['status' => 200, 'body' => '{"error":401,"message":"token is not valid"}'],
        ['status' => 400, 'body' => '{"error":400,"message":"over quota"}'],
        ['status' => 200, 'body' => 'not json'],
        ['status' => 500, 'body' => ''],
        ['status' => 200, 'body' => '{"remain":3,"success":0,"not_same_site":["x"]}'],
    ], 'steps' => [login(),
        push('token-invalid', 'ac=baidu&limit=50&page=1&range=0&ac2=all&mid=3'),
        push('http-400', 'ac=baidu&limit=50&page=1&range=0&ac2=all&mid=3'),
        push('not-json', 'ac=baidu&limit=50&page=1&range=0&ac2=all&mid=3'),
        push('empty-500', 'ac=baidu&limit=50&page=1&range=0&ac2=all&mid=3'),
        push('zero-success', 'ac=baidu&limit=50&page=1&range=0&ac2=all&mid=3')]];
    $s[] = ['label' => 'baidufast', 'baidu' => [
        ok(2, 10),
        ok(1, 0), ok(1, 0),
        ok(2, 0), ['status' => 200, 'body' => 'x'],
        ok(2, 0), ['status' => 200, 'body' => '{"error":400,"message":"daily quota"}'],
        ['status' => 200, 'body' => '{"error":401,"message":"token is not valid"}'],
    ], 'steps' => [login(),
        push('normal', 'ac=baidufast&limit=50&page=1&range=0&ac2=all&mid=1'),
        push('quota-resend', 'ac=baidufast&limit=50&page=1&range=0&ac2=all&mid=1'),
        push('resend-fails', 'ac=baidufast&limit=50&page=1&range=0&ac2=all&mid=1'),
        push('resend-error', 'ac=baidufast&limit=50&page=1&range=0&ac2=all&mid=1'),
        push('error', 'ac=baidufast&limit=50&page=1&range=0&ac2=all&mid=1')]];
    // 百度队列为空：旧版在取不到数据时仍把空列表推出去（模拟服务回 599 → 请求失败），新版不发请求，队列不错位
    $s[] = ['label' => 'push-edge', 'baidu' => [], 'steps' => [login(),
        push('no-data', 'ac=baidu&limit=50&page=1&range=0&ac2=today&mid=3'),
        push('bad-mid', 'ac=baidu&limit=50&page=1&range=0&ac2=all&mid=5'),
        push('bad-ac', 'ac=nope&limit=50&page=1&range=0&ac2=all&mid=1'),
        push('limit-default', 'ac=baidu&limit=0&page=0&range=0&ac2=all&mid=3'),
        step('data', 'admin', 'GET', '/admin/urlsend/data', 'mid=1&limit=3&page=1&ac2=all')]];
    // 地址形态：静态详情页（view.*_detail=2，含占位符的规则）与伪静态（rewrite.status=1、suffix_hide=1）
    $s[] = ['label' => 'push-static-urls', 'config' => ['view' => ['vod_detail' => '2', 'art_detail' => '2', 'topic_detail' => '2'],
        'path' => ['vod_detail' => 'v/{type_pen}/{type_en}/{id}-{md5}', 'art_detail' => 'a/{type_id}/{en}-{id}', 'topic_detail' => 'zt/{en}/',
            'suffix' => 'htm']],
        'baidu' => [ok(4, 10), ok(2, 9), ok(1, 8)], 'steps' => [login(),
            push('vod', 'ac=baidu&limit=50&page=1&range=0&ac2=all&mid=1'),
            push('art', 'ac=baidu&limit=50&page=1&range=0&ac2=all&mid=2'),
            push('topic', 'ac=baidu&limit=50&page=1&range=0&ac2=all&mid=3')]];
    $s[] = ['label' => 'push-rewrite-urls', 'config' => ['rewrite' => ['status' => '1', 'suffix_hide' => '1']],
        'baidu' => [ok(1, 10), ok(1, 9)], 'steps' => [login(),
            push('vod', 'ac=baidu&limit=1&page=1&range=0&ac2=all&mid=1'),
            push('actor', 'ac=baidu&limit=50&page=1&range=1&ac2=today&mid=8')]];
    $s[] = ['label' => 'push-empty-token', 'config' => ['urlsend' => ['baidu' => ['token' => '']]], 'baidu' => [ok(0, 0)],
        'steps' => [login(), push('empty-token', 'ac=baidu&limit=50&page=1&range=0&ac2=all&mid=3')]];

    // ---------------- 定时任务（Timming::urlsend → Urlsend::push，api 入口逐页推完）
    $s[] = ['label' => 'timming-urlsend', 'baidu' => [ok(2, 10), ok(2, 8)],
        'timming' => ['push' => ['id' => 'push', 'status' => '1', 'name' => 'push', 'des' => '推送', 'file' => 'urlsend',
            'param' => 'ac=baidu&mid=1&ac2=all&limit=2', 'weeks' => '1,2,3,4,5,6,0',
            'hours' => '00,01,02,03,04,05,06,07,08,09,10,11,12,13,14,15,16,17,18,19,20,21,22,23', 'runtime' => 0]],
        'steps' => [step('run', 'api', 'GET', '/timming/index', 'name=push&enforce=1')]];
    $s[] = ['label' => 'timming-urlsend-error', 'baidu' => [['status' => 200, 'body' => '{"error":401,"message":"token is not valid"}']],
        'timming' => ['push' => ['id' => 'push', 'status' => '1', 'name' => 'push', 'des' => '推送', 'file' => 'urlsend',
            'param' => 'ac=baidu&mid=1&ac2=all&limit=2', 'weeks' => '1,2,3,4,5,6,0',
            'hours' => '00,01,02,03,04,05,06,07,08,09,10,11,12,13,14,15,16,17,18,19,20,21,22,23', 'runtime' => 0]],
        'steps' => [step('run', 'api', 'GET', '/timming/index', 'name=push&enforce=1')]];

    // ---------------- 回链检测（Index::check_back_link → mac_check_back_link）
    $local = $work . DIRECTORY_SEPARATOR . 'local-page.html';
    $s[] = ['label' => 'backlink', 'pages' => [
        '/both' => ['status' => 200, 'body' => $both], '/main-only' => ['status' => 200, 'body' => $mainOnly],
        '/wap-only' => ['status' => 200, 'body' => $wapOnly], '/none' => ['status' => 200, 'body' => $none],
        '/gone' => ['status' => 404, 'body' => $both], '/moved' => ['status' => 302, 'location' => '/both', 'body' => ''],
    ], 'files' => [$local => $both], 'steps' => [login(),
        check('both', '{mock}/both'), check('main-only', '{mock}/main-only'), check('wap-only', '{mock}/wap-only'),
        check('none', '{mock}/none'), check('http-404', '{mock}/gone'), check('redirect', '{mock}/moved'),
        check('connect-refused', 'http://127.0.0.1:1/'), check('empty-url', null), check('blank-url', ''),
        check('file-scheme', '{local}'),
        step('not-ajax', 'admin', 'GET', '/admin/index/check_back_link', 'url=' . rawurlencode('{mock}/both'))]];
    $s[] = ['label' => 'backlink-empty-wapurl', 'config' => ['site' => ['site_wapurl' => '']],
        'pages' => ['/main-only' => ['status' => 200, 'body' => $mainOnly], '/none' => ['status' => 200, 'body' => $none]],
        'steps' => [login(), check('main-only', '{mock}/main-only'), check('none', '{mock}/none'),
            check('connect-refused', 'http://127.0.0.1:1/')]];
    $s[] = ['label' => 'backlink-permission', 'pages' => ['/both' => ['status' => 200, 'body' => $both]],
        'steps' => [step('login', 'admin', 'POST', '/admin/index/login', '', 'admin_name=linker&admin_pwd=' . rawurlencode('lk pass'), true),
            check('allowed', '{mock}/both'),
            push('push-denied', 'ac=baidu&limit=50&page=1&range=0&ac2=all&mid=3')]];
    return $s;
}

// ====================================================================== 执行

function generate($repo, $vendor, $phpCgi, $work, $mock, $host, $port, $db, $user, $password, $today)
{
    $base = $work . DIRECTORY_SEPARATOR . 'base';
    $site = $work . DIRECTORY_SEPARATOR . 'site';
    copy_tree($repo . '/application', $base . '/application');
    copy_tree($repo . '/thinkphp', $base . '/thinkphp');
    copy_tree($vendor, $base . '/vendor');
    $database = include $repo . '/application/database.php';
    $database = array_merge($database, ['hostname' => $host, 'hostport' => $port, 'database' => $db,
        'username' => $user, 'password' => $password, 'debug' => false]);
    file_put_contents($base . '/application/database.php', '<?php return ' . var_export($database, true) . ';');
    $maccms = include $repo . '/application/extra/maccms.php';
    $maccms = deep_merge($maccms, base_config());
    file_put_contents($base . '/application/extra/maccms.php', "<?php\nreturn " . var_export($maccms, true) . ';');
    foreach (glob($base . '/application/extra/*.php') as $f) {
        file_put_contents($f, str_replace("\r\n", "\n", file_get_contents($f)));
    }
    copy_tree($base, $site);

    $out = [];
    foreach (scenarios($work) as $sc) {
        foreach (['application/extra', 'runtime'] as $dir) {
            rm_tree($site . '/' . $dir);
            if (is_dir($base . '/' . $dir)) {
                copy_tree($base . '/' . $dir, $site . '/' . $dir);
            }
        }
        if (isset($sc['config'])) {
            $cfg = deep_merge(include $base . '/application/extra/maccms.php', $sc['config']);
            file_put_contents($site . '/application/extra/maccms.php', "<?php\nreturn " . var_export($cfg, true) . ';');
        }
        if (isset($sc['timming'])) {
            file_put_contents($site . '/application/extra/timming.php', "<?php\nreturn " . var_export($sc['timming'], true) . ';');
        }
        foreach (isset($sc['files']) ? $sc['files'] : [] as $path => $content) {
            file_put_contents($path, $content);
        }
        $pages = isset($sc['pages']) ? $sc['pages'] : [];
        set_plan($mock, isset($sc['baidu']) ? $sc['baidu'] : [], $pages);
        $jar = [];
        $steps = [];
        // {local}：本机临时文件的 file:// 地址（只在本次运行存在，黄金里不记录路径）
        $localUrl = 'file:///' . str_replace('\\', '/', $work . DIRECTORY_SEPARATOR . 'local-page.html');
        foreach ($sc['steps'] as $st) {
            $st['query'] = str_replace([rawurlencode('{mock}'), rawurlencode('{local}')],
                [rawurlencode($mock['base']), rawurlencode($localUrl)], $st['query']);
            $res = do_request($site, $phpCgi, $mock, $jar, $st);
            $rec = ['label' => $st['label'], 'request' => ['entry' => $st['entry'], 'method' => $st['method'], 'path' => $st['path'],
                'query' => str_replace([rawurlencode($mock['base']), rawurlencode($localUrl)], [rawurlencode('{mock}'),
                    rawurlencode('{local}')], $st['query']), 'post' => $st['post'],
                'ajax' => $st['ajax']],
                'status' => $res['status'],
                'contentType' => isset($res['headers']['content-type']) ? $res['headers']['content-type'] : null,
                'location' => isset($res['headers']['location']) ? $res['headers']['location'] : null,
                'body' => base64_encode(str_replace($mock['base'], '{mock}', $res['body'])),
                'mock' => take_log($mock)];
            $steps[] = $rec;
        }
        $timming = null;
        if (isset($sc['timming'])) {
            $after = include $site . '/application/extra/timming.php';
            $timming = ['runtimeWritten' => $after['push']['runtime'] > 0];
        }
        $out[] = ['label' => $sc['label'], 'config' => isset($sc['config']) ? $sc['config'] : null,
            'timming' => isset($sc['timming']) ? $sc['timming'] : null, 'timmingAfter' => $timming,
            'baidu' => isset($sc['baidu']) ? $sc['baidu'] : [], 'pages' => (object)$pages,
            'localFile' => isset($sc['files']) ? true : false, 'steps' => $steps];
    }
    return json_encode(['generator' => 'backend/src/test/php/urlsend_backlink_golden.php', 'php' => PHP_VERSION,
            'timezone' => 'PRC', 'host' => HOST, 'baseConfig' => base_config(), 'seed' => seed_template(), 'scenarios' => $out],
            JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT | JSON_INVALID_UTF8_SUBSTITUTE) . "\n";
}

/** 所有场景共用的配置（并入仓库的 maccms.php）：后台登录不要验证码、简体，站点域名与假令牌。 */
function base_config()
{
    return ['app' => ['admin_login_verify' => '0', 'lang' => 'zh-cn'],
        'site' => ['site_url' => SITE_URL, 'site_wapurl' => SITE_WAPURL],
        'urlsend' => ['baidu' => ['token' => BAIDU_TOKEN], 'baidufast' => ['token' => FAST_TOKEN]]];
}

function do_request($site, $phpCgi, $mock, &$jar, $req)
{
    $headers = [];
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
    list($status, $respHeaders, $setCookies, $body) = run_request($site, $phpCgi, $mock, $req['entry'], $req['method'],
        $req['path'], $req['query'], $req['post'], $headers);
    foreach ($setCookies as $c) {
        $pair = explode(';', $c, 2)[0];
        list($k, $v) = array_pad(explode('=', $pair, 2), 2, '');
        if ($v === 'deleted' || stripos($c, 'Max-Age=0') !== false) {
            unset($jar[$k]);
        } else {
            $jar[$k] = $v;
        }
    }
    return ['status' => $status, 'headers' => $respHeaders, 'body' => $body];
}

/** 以 php-cgi 执行一个请求。返回 [status, headers(小写名 => 值), setCookies, body]。 */
function run_request($site, $phpCgi, $mock, $entry, $method, $path, $query, $post, $headers)
{
    $script = $entry === 'api' ? '/api.php' : '/_admin.php';
    $entryDir = dirname($site) . DIRECTORY_SEPARATOR . 'entry';
    $entryFile = $entryDir . $script;
    if (!is_file($entryFile)) {
        @mkdir($entryDir, 0777, true);
        file_put_contents($entryFile, '<?php require ' . var_export(__DIR__ . DIRECTORY_SEPARATOR . 'urlsend_child.php', true) . ';');
    }
    $runtime = $site . DIRECTORY_SEPARATOR . 'runtime';
    @mkdir($runtime, 0777, true);
    $env = [
        'GOLDEN_SITE_ROOT' => $site,
        'GOLDEN_ENTRY' => $entry,
        'GOLDEN_SESSION_PATH' => $runtime,
        'http_proxy' => $mock['base'],
        'no_proxy' => '127.0.0.1,localhost',
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
        'SCRIPT_NAME' => $script,
        'PHP_SELF' => $script . $path,
        'REQUEST_URI' => $script . $path . ($query === '' ? '' : '?' . $query),
        'QUERY_STRING' => $query,
        'PATH_INFO' => $path,
        'REMOTE_ADDR' => '127.0.0.1',
        'REMOTE_PORT' => '50000',
        'DOCUMENT_ROOT' => $entryDir,
        'SystemRoot' => getenv('SystemRoot') ?: 'C:\\Windows',
        'TEMP' => $runtime,
        'TMP' => $runtime,
    ];
    if ($method === 'POST') {
        $env['CONTENT_TYPE'] = 'application/x-www-form-urlencoded';
        $env['CONTENT_LENGTH'] = (string)strlen($post);
    }
    foreach ($headers as $name => $value) {
        $env['HTTP_' . strtoupper(str_replace('-', '_', $name))] = $value;
    }
    $cmd = escapeshellarg($phpCgi) . ' -d display_errors=0 -d error_reporting=0 -d session.gc_probability=0';
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

// ====================================================================== 本机模拟服务

function start_mock($work)
{
    $dir = $work . DIRECTORY_SEPARATOR . 'mock';
    @mkdir($dir, 0777, true);
    file_put_contents($dir . '/router.php', <<<'PHP'
<?php
$dir = __DIR__;
$plan = json_decode(file_get_contents($dir . '/plan.json'), true);
$uri = $_SERVER['REQUEST_URI'];
$body = file_get_contents('php://input');
file_put_contents($dir . '/log.jsonl', json_encode(['method' => $_SERVER['REQUEST_METHOD'], 'uri' => $uri,
    'contentType' => isset($_SERVER['CONTENT_TYPE']) ? $_SERVER['CONTENT_TYPE'] : (isset($_SERVER['HTTP_CONTENT_TYPE']) ? $_SERVER['HTTP_CONTENT_TYPE'] : ''),
    'userAgent' => isset($_SERVER['HTTP_USER_AGENT']) ? $_SERVER['HTTP_USER_AGENT'] : '',
    'body' => base64_encode($body)]) . "\n", FILE_APPEND);
if (strpos($uri, 'http://data.zz.baidu.com/') === 0) {
    $n = (int)@file_get_contents($dir . '/baidu_count');
    file_put_contents($dir . '/baidu_count', (string)($n + 1));
    $r = isset($plan['baidu'][$n]) ? $plan['baidu'][$n] : ['status' => 599, 'body' => 'unplanned'];
    http_response_code($r['status']);
    header('Content-Type: application/json');
    echo $r['body'];
    return true;
}
$path = parse_url($uri, PHP_URL_PATH);
if (isset($plan['pages'][$path])) {
    $p = $plan['pages'][$path];
    http_response_code($p['status']);
    if (isset($p['location'])) {
        header('Location: ' . $p['location']);
    }
    header('Content-Type: text/html; charset=utf-8');
    echo $p['body'];
    return true;
}
http_response_code(404);
echo 'not found';
return true;
PHP
    );
    file_put_contents($dir . '/plan.json', json_encode(['baidu' => [], 'pages' => (object)[]]));
    $sock = stream_socket_server('tcp://127.0.0.1:0');
    $name = stream_socket_get_name($sock, false);
    fclose($sock);
    $port = (int)substr($name, strrpos($name, ':') + 1);
    $proc = proc_open(escapeshellarg(PHP_BINARY) . ' -S 127.0.0.1:' . $port . ' ' . escapeshellarg($dir . '/router.php'),
        [0 => ['pipe', 'r'], 1 => ['file', $dir . '/server.out', 'w'], 2 => ['file', $dir . '/server.err', 'w']], $pipes, $dir, null,
        ['bypass_shell' => true]);
    for ($i = 0; $i < 50; $i++) {
        $c = @fsockopen('127.0.0.1', $port, $errno, $errstr, 0.2);
        if ($c) {
            fclose($c);
            break;
        }
        usleep(100000);
    }
    return ['proc' => $proc, 'pipes' => $pipes, 'dir' => $dir, 'port' => $port, 'base' => 'http://127.0.0.1:' . $port];
}

function set_plan($mock, $baidu, $pages)
{
    file_put_contents($mock['dir'] . '/plan.json', json_encode(['baidu' => $baidu, 'pages' => (object)$pages]));
    @unlink($mock['dir'] . '/baidu_count');
    @unlink($mock['dir'] . '/log.jsonl');
}

/** 取出并清空模拟服务这一步收到的请求。 */
function take_log($mock)
{
    $f = $mock['dir'] . '/log.jsonl';
    $out = [];
    if (is_file($f)) {
        foreach (file($f, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $line) {
            $r = json_decode($line, true);
            $r['uri'] = str_replace($mock['base'], '{mock}', $r['uri']);
            $out[] = $r;
        }
        @unlink($f);
    }
    return $out;
}

function stop_mock($mock)
{
    $status = proc_get_status($mock['proc']);
    if ($status['running']) {
        // 只结束本脚本启动的那个进程（Windows 上 php -S 由 proc_open 直接启动，pid 即 php.exe）
        if (DIRECTORY_SEPARATOR === '\\') {
            exec('taskkill /F /T /PID ' . (int)$status['pid'] . ' 2>NUL');
        } else {
            proc_terminate($mock['proc']);
        }
    }
    fclose($mock['pipes'][0]);
    proc_close($mock['proc']);
}

// ====================================================================== 工具

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
        $f->isDir() ? @rmdir($f->getPathname()) : @unlink($f->getPathname());
    }
    @rmdir($dir);
}
