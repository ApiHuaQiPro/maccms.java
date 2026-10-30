<?php
/*
 * B12（MACC-36）后台框架的 PHP 黄金样本生成脚本（契约见 docs/architecture/21-b12-admin-framework.md）。
 *
 * 每个场景是一串真实的后台请求：经 admin_http_child.php 跑完整的 ThinkPHP 路由、admin/controller/Base 的构造函数（登录检查与
 * check_auth）与 Index、Admin 控制器本身；同一场景内按浏览器的方式保存并回送 cookie（PHP 会话），记录每个请求的状态码、
 * Location、Content-Type、全部 Set-Cookie 与正文字节（base64；只关心是否登录的步骤不记正文），以及请求后 mac_admin 的全部行
 * （与上一次记录相同时为 null；场景开始时写入的行在 rows0）。文本列一律十六进制。
 * 场景之间互不依赖：每个场景先清空 mac_admin、写入自己的行、使用新的 cookie 与会话目录。
 * 需要验证码的步骤直接把验证码记录写进会话文件（与 interaction_golden.php 相同的做法）；需要直接改库的步骤（演示旧版会话快照，
 * 路线图 D2 ⑤）经 PDO 执行。
 *
 * 流程：外部管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入 install.sql → 逐个场景执行 → 输出 JSON → 删除一次性库与临时目录。
 * 不修改任何旧 PHP 文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，不访问网络。
 * 不调用会写仓库文件的动作：index/clear（重写 static/js/playerconfig.js）与 index/quickmenu 的保存（重写 extra/quickmenu.php）。
 *
 * 管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；另需 PHP_CGI。
 * 用法（仓库根目录，PHP 8.0 与 7.3 各一份）：
 *   PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe src/test/php/admin_golden.php \
 *       > src/test/resources/admin/php80-golden.json
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
const ADMIN_COLUMNS = 'admin_id, admin_name, admin_pwd, admin_random, admin_status, admin_auth, admin_login_time, '
    . 'admin_login_ip, admin_login_num, admin_last_login_time, admin_last_login_ip';

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$work = sys_get_temp_dir() . '/maccms-b12-golden-' . bin2hex(random_bytes(4));
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

/** 每个场景都带上的配置（仓库值之上）：后台登录默认不要验证码（验证码另有场景），语言简体。 */
function base_config()
{
    return ['app' => ['admin_login_verify' => '0', 'lang' => 'zh-cn', 'pagesize' => '20']];
}

/** 一行管理员（文本列原样；数值列整数）。 */
function a($id, $name, $pwdHash, array $extra = [])
{
    return array_merge(['admin_id' => $id, 'admin_name' => $name, 'admin_pwd' => $pwdHash, 'admin_random' => '',
        'admin_status' => 1, 'admin_auth' => '', 'admin_login_time' => 0, 'admin_login_ip' => 0, 'admin_login_num' => 0,
        'admin_last_login_time' => 0, 'admin_last_login_ip' => 0], $extra);
}

/** 把 md5 的十六进制字母按位置交替大小写（偶数位大写），用作库里存量的混合大小写哈希。 */
function mixed_case($hex)
{
    $out = '';
    for ($i = 0; $i < strlen($hex); $i++) {
        $out .= $i % 2 === 0 ? strtoupper($hex[$i]) : $hex[$i];
    }
    return $out;
}

/** 公共的几位管理员：1 超级管理员、2 编辑（有部分权限）、3 无权限、4 已停用。 */
function base_admins()
{
    return [
        a(1, 'admin', md5('admin888'), ['admin_login_num' => 5, 'admin_login_time' => 1700000000,
            'admin_login_ip' => 167772161, 'admin_last_login_time' => 1690000000, 'admin_last_login_ip' => 167772162]),
        a(2, 'editor', md5('ed pass'), ['admin_auth' => ',vod/data,admin/index,index/unlocked,']),
        a(3, 'viewer', md5('viewer1')),
        a(4, 'disabled', md5('pw'), ['admin_status' => 0]),
    ];
}

/** 一个请求步骤。$post 为数组时按表单编码，为字符串时原样作为正文。 */
function req($label, $method, $path, $query = '', $post = '', array $opt = [])
{
    return ['label' => $label, 'method' => $method, 'path' => $path, 'query' => $query, 'post' => $post,
        'ajax' => !isset($opt['ajax']) || $opt['ajax'], 'headers' => isset($opt['headers']) ? $opt['headers'] : [],
        'body' => !isset($opt['body']) || $opt['body']];
}

function login_step($name, $pwd, $label = 'login', array $more = [])
{
    return req($label, 'POST', '/admin/index/login', '', array_merge(['admin_name' => $name, 'admin_pwd' => $pwd], $more));
}

/** 原始正文（不经 http_build_query，用于构造 + 号、数组名与重复字段）。 */
function raw_post($label, $path, $raw, array $opt = [])
{
    return req($label, 'POST', $path, '', ['__raw__' => $raw], $opt);
}

function scenarios($repo)
{
    $s = [];

    // ---------------- 登录：密码与账号的字节语义（common/model/Admin.php:114-152；input() 的 filterExp，Request.php:1122-1129）
    $vectors = [
        // [标签, 库里的账号, 库里的明文（md5 入库）, 提交的账号, 提交的密码（原始字节，按表单编码一次）]
        ['ok', 'admin', 'admin888', 'admin', 'admin888'],
        ['wrong', 'admin', 'admin888', 'admin', 'admin889'],
        ['name-upper', 'admin', 'admin888', 'ADMIN', 'admin888'],
        ['name-trailing-space', 'admin', 'admin888', 'admin ', 'admin888'],
        ['name-leading-space', 'admin', 'admin888', ' admin', 'admin888'],
        ['pwd-edge-spaces', 'sp', ' sp ace ', 'sp', ' sp ace '],
        ['pwd-edge-spaces-trimmed', 'sp', ' sp ace ', 'sp', 'sp ace'],
        ['pwd-spaces-only', 'spaces', '   ', 'spaces', '   '],
        ['pwd-tab-newline', 'tabnl', "\tx\n", 'tabnl', "\tx\n"],
        ['pwd-nul', 'nul', "a\0b", 'nul', "a\0b"],
        ['pwd-plus', 'plus', 'a+b', 'plus', 'a+b'],
        ['pwd-percent', 'pct', '%41', 'pct', '%41'],
        ['pwd-html', 'html', "a&b<c>\"'", 'html', "a&b<c>\"'"],
        ['pwd-zh', 'zh', '密码中文', 'zh', '密码中文'],
        ['pwd-emoji', 'emoji', "\xF0\x9F\x98\x80x", 'emoji', "\xF0\x9F\x98\x80x"],
        ['pwd-invalid-utf8', 'inv', "\xFFab", 'inv', "\xFFab"],
        ['pwd-long', 'long', str_repeat('p', 100), 'long', str_repeat('p', 100)],
        // filterExp：整值匹配表达式关键字（不分大小写，$ 也匹配末尾换行之前）时末尾加一个空格
        ['pwd-like-filterexp', 'likeuser', 'like ', 'likeuser', 'like'],
        ['pwd-like-plain-stored', 'likeplain', 'like', 'likeplain', 'like'],
        ['pwd-in-lower', 'inuser', 'in ', 'inuser', 'in'],
        ['pwd-not-in', 'notin', 'NOT IN ', 'notin', 'NOT IN'],
        ['pwd-like-newline', 'likenl', "like\n ", 'likenl', "like\n"],
        ['pwd-not-keyword', 'likex', 'likex', 'likex', 'likex'],
        ['name-filterexp', 'exp', 'x1', 'exp', 'x1'],
        ['stored-hash', 'admin', 'admin888', 'admin', md5('admin888')],
        ['empty-pwd', 'admin', 'admin888', 'admin', ''],
        ['zero-pwd', 'zero', '0', 'zero', '0'],
        ['zero-name', '0', 'pw0', '0', 'pw0'],
        ['name-emoji', 'admin', 'admin888', "ad\xF0\x9F\x98\x80", 'admin888'],
    ];
    foreach ($vectors as $v) {
        list($label, $storedName, $storedPwd, $name, $pwd) = $v;
        $s[] = ['label' => 'login-' . $label, 'admins' => [a(1, 'root', md5('rootpw')), a(9, $storedName, md5($storedPwd),
            ['admin_login_num' => 3, 'admin_login_time' => 1700000000, 'admin_login_ip' => 167772161])],
            'steps' => [login_step($name, $pwd), req('after', 'GET', '/admin/index/welcome', '', '', ['ajax' => false, 'body' => false])]];
    }
    $s[] = ['label' => 'login-disabled', 'admins' => base_admins(),
        'steps' => [login_step('disabled', 'pw'), req('after', 'GET', '/admin/index/welcome', '', '', ['ajax' => false, 'body' => false])]];
    $s[] = ['label' => 'login-raw-plus', 'admins' => [a(9, 'plus', md5('a+b'))],
        'steps' => [raw_post('login', '/admin/index/login', 'admin_name=plus&admin_pwd=a+b'),
            raw_post('login-space', '/admin/index/login', 'admin_name=plus&admin_pwd=a%2Bb')]];
    $s[] = ['label' => 'login-missing-fields', 'admins' => base_admins(),
        'steps' => [raw_post('no-name', '/admin/index/login', 'admin_pwd=admin888'),
            raw_post('no-pwd', '/admin/index/login', 'admin_name=admin'),
            raw_post('nothing', '/admin/index/login', '')]];
    $s[] = ['label' => 'login-array-fields', 'admins' => base_admins(),
        'steps' => [raw_post('pwd-array', '/admin/index/login', 'admin_name=admin&admin_pwd%5B%5D=admin888'),
            raw_post('name-array', '/admin/index/login', 'admin_name%5B%5D=admin&admin_pwd=admin888')]];
    $s[] = ['label' => 'login-query-string-pwd', 'admins' => base_admins(),
        'steps' => [req('login', 'POST', '/admin/index/login', 'admin_pwd=admin888', ['admin_name' => 'admin'])]];
    $s[] = ['label' => 'login-html', 'admins' => base_admins(),
        'steps' => [array_merge(login_step('admin', 'admin888', 'ok'), ['ajax' => false]),
            array_merge(login_step('admin', 'bad', 'bad'), ['ajax' => false]),
            req('ajax-param', 'POST', '/admin/index/login', '_ajax=1', ['admin_name' => 'admin', 'admin_pwd' => 'bad'],
                ['ajax' => false]),
            req('with-referer', 'POST', '/admin/index/login', '', ['admin_name' => 'admin', 'admin_pwd' => 'admin888'],
                ['headers' => ['Referer' => 'http://www.example.test/_admin.php/admin/index/login.html']])]];
    $s[] = ['label' => 'login-page', 'admins' => base_admins(),
        'steps' => [req('page', 'GET', '/admin/index/login', '', '', ['ajax' => false])]];
    $s[] = ['label' => 'login-page-verify', 'config' => ['app' => ['admin_login_verify' => '1']], 'admins' => base_admins(),
        'steps' => [req('page', 'GET', '/admin/index/login', '', '', ['ajax' => false])]];
    $s[] = ['label' => 'login-twice', 'admins' => base_admins(),
        'steps' => [login_step('editor', 'ed pass', 'first'), login_step('admin', 'admin888', 'second'),
            req('welcome', 'GET', '/admin/index/welcome', '', '', ['ajax' => false])]];
    $s[] = ['label' => 'login-duplicate-name', 'admins' => [a(5, 'dup', md5('same'), ['admin_auth' => ',a/b,']),
        a(6, 'dup', md5('same'), ['admin_auth' => ',c/d,'])],
        'steps' => [login_step('dup', 'same'), req('welcome', 'GET', '/admin/index/welcome', '', '', ['ajax' => false])]];

    // ---------------- 验证码（admin_login_verify != '0' 时 captcha_check，Admin.php:118-122）
    $verify = ['app' => ['admin_login_verify' => '1']];
    $s[] = ['label' => 'captcha', 'config' => $verify, 'admins' => base_admins(), 'steps' => [
        login_step('admin', 'admin888', 'no-session'),
        ['label' => 'set', 'captcha' => '4321'],
        login_step('admin', 'admin888', 'missing'),
        login_step('admin', 'admin888', 'wrong', ['verify' => '1234']),
        login_step('admin', 'admin888', 'right-after-wrong', ['verify' => '4321']),
        req('welcome', 'GET', '/admin/index/welcome', '', '', ['ajax' => false]),
    ]];
    $s[] = ['label' => 'captcha-consumed', 'config' => $verify, 'admins' => base_admins(), 'steps' => [
        ['label' => 'set', 'captcha' => '1111'],
        login_step('admin', 'bad', 'right-code-bad-pwd', ['verify' => '1111']),
        login_step('admin', 'admin888', 'reuse', ['verify' => '1111']),
    ]];
    $s[] = ['label' => 'captcha-after-param-check', 'config' => $verify, 'admins' => base_admins(), 'steps' => [
        ['label' => 'set', 'captcha' => '2222'],
        login_step('admin', '', 'empty-pwd', ['verify' => '2222']),
        login_step('admin', 'admin888', 'then-ok', ['verify' => '2222']),
    ]];
    $s[] = ['label' => 'captcha-verify-zero-config', 'config' => ['app' => ['admin_login_verify' => '']], 'admins' => base_admins(),
        'steps' => [login_step('admin', 'admin888')]];

    // ---------------- 未登录访问（Base.php:18-31）与地址写法
    $paths = ['/admin/index/index', '/admin/index/welcome', '/admin/index/quickmenu', '/admin/index/checkcache',
        '/admin/index/clear', '/admin/index/iframe', '/admin/index/unlocked', '/admin/index/logout',
        '/admin/index/select', '/admin/admin/index', '/admin/admin/info', '/admin/admin/del', '/admin/admin/field',
        '/admin/vod/data', '/admin/system/config', '/admin/update/step2', '/admin/nosuch/thing', '/admin/index/nosuch'];
    $steps = [];
    foreach ($paths as $p) {
        $steps[] = req('html ' . $p, 'GET', $p, '', '', ['ajax' => false]);
        $steps[] = req('ajax ' . $p, 'GET', $p);
    }
    foreach (['', '/', '/admin', '/admin/', '/admin/index', '/admin/index/login.html', '/admin/Index/LOGIN.html',
                 '/admin/index/login/', '/admin/index/login.htm', '/admin/index/login.shtml', '/admin/index/login.xml',
                 '/admin/index/login.json', '/admin/index/welcome.html', '/ADMIN/index/welcome',
                 '/admin/admin/info/id/2.html', '/index/index/index'] as $p) {
        $steps[] = req('form ' . ($p === '' ? '(empty)' : $p), 'GET', $p, '', '', ['ajax' => false]);
    }
    $s[] = ['label' => 'anonymous', 'admins' => base_admins(), 'steps' => $steps];

    // ---------------- 权限（Base.php:42-58）：无权限管理员逐个访问 auth.php 的全部权限码
    $menus = auth_codes($repo);
    $steps = [login_step('viewer', 'viewer1')];
    foreach ($menus as $code) {
        $steps[] = req('deny ' . $code, 'GET', '/admin/' . $code);
    }
    foreach (['index/index', 'index/welcome', 'index/logout'] as $code) {
        $steps[] = req('html ' . $code, 'GET', '/admin/' . $code, '', '', ['ajax' => false]);
    }
    $s[] = ['label' => 'auth-viewer', 'admins' => base_admins(), 'steps' => $steps];
    $s[] = ['label' => 'auth-editor', 'admins' => base_admins(), 'steps' => [
        login_step('editor', 'ed pass'),
        req('admin/index', 'GET', '/admin/admin/index', '', '', ['ajax' => false]),
        req('admin/info', 'GET', '/admin/admin/info'),
        req('admin/info-html', 'GET', '/admin/admin/info', '', '', ['ajax' => false]),
        req('vod/data', 'GET', '/admin/vod/data', '', '', ['ajax' => false, 'body' => false]),
        req('index/checkcache', 'GET', '/admin/index/checkcache'),
        req('index/unlocked', 'POST', '/admin/index/unlocked', '', ['password' => 'ed pass']),
        req('index/clear', 'GET', '/admin/index/clear'),
        req('index/index', 'GET', '/admin/index/index', '', '', ['ajax' => false]),
        req('index/logout', 'GET', '/admin/index/logout', '', '', ['ajax' => false]),
        req('after-logout', 'GET', '/admin/admin/index', '', '', ['ajax' => false]),
    ]];
    foreach ([
                 ['no-commas', 'admin/index'],
                 ['upper', ',ADMIN/INDEX,'],
                 ['no-trailing-comma', ',admin/index'],
                 ['no-leading-comma', 'admin/index,'],
                 ['spaces', ', admin/index ,'],
                 ['exact', ',admin/index,'],
             ] as $v) {
        $s[] = ['label' => 'auth-string-' . $v[0], 'admins' => [a(1, 'root', md5('rootpw')), a(7, 'tester', md5('t1'), ['admin_auth' => $v[1]])],
            'steps' => [login_step('tester', 't1'), req('admin/index', 'GET', '/admin/admin/index'),
                req('admin/Index', 'GET', '/admin/Admin/INDEX')]];
    }

    // ---------------- 旧版的会话快照（Admin.php:145-146,166-176；D2 ⑤ 的旧行为）
    $s[] = ['label' => 'legacy-snapshot', 'admins' => base_admins(), 'steps' => [
        login_step('editor', 'ed pass'),
        ['label' => 'db-revoke', 'sql' => "UPDATE mac_admin SET admin_auth = '' WHERE admin_id = 2"],
        req('after-revoke', 'GET', '/admin/admin/index'),
        ['label' => 'db-grant', 'sql' => "UPDATE mac_admin SET admin_auth = ',admin/info,' WHERE admin_id = 2"],
        req('after-grant', 'GET', '/admin/admin/info'),
        ['label' => 'db-password', 'sql' => "UPDATE mac_admin SET admin_pwd = '" . md5('changed') . "' WHERE admin_id = 2"],
        req('after-password', 'GET', '/admin/index/welcome', '', '', ['ajax' => false]),
        req('unlock-old', 'POST', '/admin/index/unlocked', '', ['password' => 'ed pass']),
        req('unlock-new', 'POST', '/admin/index/unlocked', '', ['password' => 'changed']),
        ['label' => 'db-disable', 'sql' => 'UPDATE mac_admin SET admin_status = 0 WHERE admin_id = 2'],
        req('after-disable', 'GET', '/admin/index/welcome', '', '', ['ajax' => false]),
        ['label' => 'db-delete', 'sql' => 'DELETE FROM mac_admin WHERE admin_id = 2'],
        req('after-delete', 'GET', '/admin/index/welcome', '', '', ['ajax' => false]),
    ]];

    // ---------------- 框架页、欢迎页、快捷菜单页（Index.php:31-155）
    $s[] = ['label' => 'frame-super', 'admins' => base_admins(), 'steps' => [
        login_step('admin', 'admin888'),
        req('index', 'GET', '/admin/index/index', '', '', ['ajax' => false]),
        req('welcome', 'GET', '/admin/index/welcome', '', '', ['ajax' => false]),
        req('quickmenu', 'GET', '/admin/index/quickmenu', '', '', ['ajax' => false]),
        req('checkcache', 'GET', '/admin/index/checkcache'),
    ]];
    $s[] = ['label' => 'frame-editor', 'admins' => base_admins(), 'steps' => [
        login_step('editor', 'ed pass'),
        req('index', 'GET', '/admin/index/index', '', '', ['ajax' => false]),
    ]];
    $s[] = ['label' => 'frame-limited', 'admins' => [a(1, 'root', md5('rootpw')), a(8, 'limited', md5('l1'),
        ['admin_auth' => ',index/welcome,system/config,system/configseo,art/data,art/info,make/index?ac2=wap,make/index,admin/index,'])],
        'steps' => [login_step('limited', 'l1'), req('index', 'GET', '/admin/index/index', '', '', ['ajax' => false])]];
    $s[] = ['label' => 'frame-no-quickmenu', 'quickmenu' => [], 'admins' => base_admins(), 'steps' => [
        login_step('admin', 'admin888'),
        req('index', 'GET', '/admin/index/index', '', '', ['ajax' => false]),
        req('quickmenu', 'GET', '/admin/index/quickmenu', '', '', ['ajax' => false]),
    ]];
    $s[] = ['label' => 'frame-quickmenu-forms', 'quickmenu' => ['首页,/', '外部,https://example.org/a?b=1', '协议相对,//example.org/x',
        '脚本,javascript:alert(1)', '锚点,###', '模块,vod/data', '带参数,vod/data?status=0', '空,', '', '只有名称', ' 前空格,admin/index'],
        'admins' => base_admins(), 'steps' => [
        login_step('admin', 'admin888'),
        req('index', 'GET', '/admin/index/index', '', '', ['ajax' => false]),
        req('quickmenu', 'GET', '/admin/index/quickmenu', '', '', ['ajax' => false]),
    ]];
    $s[] = ['label' => 'frame-zh-tw', 'config' => ['app' => ['lang' => 'zh-tw']], 'admins' => base_admins(), 'steps' => [
        login_step('admin', 'admin888'),
        req('index', 'GET', '/admin/index/index', '', '', ['ajax' => false]),
        req('welcome', 'GET', '/admin/index/welcome', '', '', ['ajax' => false]),
        req('list', 'GET', '/admin/admin/index', '', '', ['ajax' => false]),
        req('info', 'GET', '/admin/admin/info', 'id=2', '', ['ajax' => false]),
        req('denied', 'GET', '/admin/index/nosuchaction'),
        req('bad-pwd', 'POST', '/admin/index/unlocked', '', ['password' => 'x']),
        req('login-page', 'GET', '/admin/index/login', '', '', ['ajax' => false]),
    ]];

    // ---------------- 管理员列表（Admin.php:12-40）
    $listAdmins = array_merge(base_admins(), [a(5, 'editor2', md5('x'), ['admin_last_login_time' => time(),
        'admin_last_login_ip' => 3232235777, 'admin_login_num' => 12]), a(6, 'per%cent', md5('x')), a(7, 'under_score', md5('x')),
        a(8, 'amp&<b>', md5('x'))]);
    $s[] = ['label' => 'admin-list', 'admins' => $listAdmins, 'steps' => [
        login_step('admin', 'admin888'),
        req('default', 'GET', '/admin/admin/index', '', '', ['ajax' => false]),
        req('wd', 'GET', '/admin/admin/index', 'wd=edi', '', ['ajax' => false]),
        req('wd-percent', 'GET', '/admin/admin/index', 'wd=%25', '', ['ajax' => false]),
        req('wd-underscore', 'GET', '/admin/admin/index', 'wd=_', '', ['ajax' => false]),
        req('wd-encoded-twice', 'GET', '/admin/admin/index', 'wd=%2565di', '', ['ajax' => false]),
        req('wd-amp', 'GET', '/admin/admin/index', 'wd=%26', '', ['ajax' => false]),
        req('page2', 'GET', '/admin/admin/index', 'page=2&limit=3', '', ['ajax' => false]),
        req('limit0', 'GET', '/admin/admin/index', 'limit=0', '', ['ajax' => false]),
        req('page0', 'GET', '/admin/admin/index', 'page=0&limit=2', '', ['ajax' => false]),
        req('page-big', 'GET', '/admin/admin/index', 'page=99&limit=2', '', ['ajax' => false]),
        req('limit-text', 'GET', '/admin/admin/index', 'limit=2abc', '', ['ajax' => false]),
        req('page-path', 'GET', '/admin/admin/index/page/2/limit/2.html', '', '', ['ajax' => false]),
    ]];

    // ---------------- 管理员表单（Admin.php:42-97）：显示
    $s[] = ['label' => 'admin-info-view', 'admins' => array_merge(base_admins(), [a(5, 'wap', md5('x'),
        ['admin_auth' => ',make/index?ac2=wap,']), a(6, 'partial', md5('x'), ['admin_auth' => 'vod/dat']),
        a(7, 'allindex', md5('x'), ['admin_auth' => ',index/welcome,index/quickmenu,index/iframe,index/clear,index/unlocked,index/select,upload/upload,']),
        a(8, 'q"uote', md5('x'))]),
        'steps' => [
            login_step('admin', 'admin888'),
            req('id2', 'GET', '/admin/admin/info', 'id=2', '', ['ajax' => false]),
            req('none', 'GET', '/admin/admin/info', '', '', ['ajax' => false]),
            req('missing', 'GET', '/admin/admin/info', 'id=999', '', ['ajax' => false]),
            req('wap', 'GET', '/admin/admin/info/id/5.html', '', '', ['ajax' => false]),
            req('partial', 'GET', '/admin/admin/info', 'id=6', '', ['ajax' => false]),
            req('allindex', 'GET', '/admin/admin/info', 'id=7', '', ['ajax' => false]),
            req('quote', 'GET', '/admin/admin/info', 'id=8', '', ['ajax' => false]),
            req('disabled', 'GET', '/admin/admin/info', 'id=4', '', ['ajax' => false]),
        ]];

    // ---------------- 管理员表单（Admin.php:42-97）：保存。{token} 换成上一个页面里的 __token__
    $save = function ($label, $fields) {
        return raw_post($label, '/admin/admin/info', $fields);
    };
    $s[] = ['label' => 'admin-save', 'admins' => base_admins(), 'steps' => [
        login_step('admin', 'admin888'),
        req('form', 'GET', '/admin/admin/info', '', '', ['ajax' => false, 'body' => false]),
        $save('add', 'admin_id=&__token__={token}&admin_name=newbie&admin_pwd=%20np%20&admin_status=1&admin_auth%5B%5D=vod%2Fdata&admin_auth%5B%5D=admin%2Findex'),
        $save('replay-token', 'admin_id=&__token__={token}&admin_name=newbie2&admin_pwd=x&admin_status=1&admin_auth%5B%5D=vod%2Fdata'),
        $save('no-token', 'admin_id=&admin_name=newbie3&admin_pwd=x&admin_status=1&admin_auth%5B%5D=vod%2Fdata'),
        req('form3', 'GET', '/admin/admin/info', 'id=2', '', ['ajax' => false, 'body' => false]),
        $save('edit-spaces-pwd', 'admin_id=2&__token__={token}&admin_name=editor&admin_pwd=%20%20%20&admin_status=0&admin_auth%5B%5D=index%2Fwelcome&admin_auth%5B%5D=a%2Cb'),
        req('form4', 'GET', '/admin/admin/info', 'id=3', '', ['ajax' => false, 'body' => false]),
        $save('edit-empty-name', 'admin_id=3&__token__={token}&admin_name=&admin_pwd=&admin_status=1&admin_auth%5B%5D=vod%2Fdata'),
        req('form6', 'GET', '/admin/admin/info', '', '', ['ajax' => false, 'body' => false]),
        $save('add-empty-pwd', 'admin_id=&__token__={token}&admin_name=nopwd&admin_pwd=&admin_status=1&admin_auth%5B%5D=vod%2Fdata'),
        req('form7', 'GET', '/admin/admin/info', '', '', ['ajax' => false, 'body' => false]),
        $save('add-duplicate-name', 'admin_id=&__token__={token}&admin_name=editor&admin_pwd=dup&admin_status=1&admin_auth%5B%5D=vod%2Fdata'),
        req('form8', 'GET', '/admin/admin/info', '', '', ['ajax' => false, 'body' => false]),
        $save('add-like-pwd', 'admin_id=&__token__={token}&admin_name=likepwd&admin_pwd=like&admin_status=1&admin_auth%5B%5D=vod%2Fdata'),
        req('form9', 'GET', '/admin/admin/info', '', '', ['ajax' => false, 'body' => false]),
        $save('add-no-status', 'admin_id=&__token__={token}&admin_name=nostatus&admin_pwd=ns&admin_auth%5B%5D=vod%2Fdata'),
        req('form10', 'GET', '/admin/admin/info', '', '', ['ajax' => false, 'body' => false]),
        $save('add-html-name', 'admin_id=&__token__={token}&admin_name=a%26b%3Cc%3E&admin_pwd=h&admin_status=1&admin_auth%5B%5D=vod%2Fdata'),
        req('form11', 'GET', '/admin/admin/info', 'id=9999', '', ['ajax' => false, 'body' => false]),
        $save('edit-missing-id', 'admin_id=9999&__token__={token}&admin_name=ghost&admin_pwd=g&admin_status=1&admin_auth%5B%5D=vod%2Fdata'),
        req('form12', 'GET', '/admin/admin/info', '', '', ['ajax' => false, 'body' => false]),
        array_merge($save('non-ajax', 'admin_id=&__token__={token}&admin_name=plainpost&admin_pwd=pp&admin_status=1&admin_auth%5B%5D=vod%2Fdata'), ['ajax' => false]),
        // 以下两步新旧结果不同（新版只写表单的四列、状态只接受 0/1，21 文档 D-B12-9），放在最后，不影响前面各步的对照
        req('form5', 'GET', '/admin/admin/info', 'id=3', '', ['ajax' => false, 'body' => false]),
        $save('extra-fields', 'admin_id=3&__token__={token}&admin_name=viewer&admin_pwd=&admin_status=1&admin_login_num=99&admin_random=abc&admin_auth%5B%5D=vod%2Fdata'),
        req('form13', 'GET', '/admin/admin/info', 'id=3', '', ['ajax' => false, 'body' => false]),
        $save('status-5', 'admin_id=3&__token__={token}&admin_name=viewer&admin_pwd=&admin_status=5&admin_auth%5B%5D=vod%2Fdata'),
    ]];
    // 编辑时不提交 admin_auth[]：PHP 8 在 in_array(null) 处 TypeError（500），PHP 7.3 只留 index/welcome（新版同 7.3）
    $s[] = ['label' => 'admin-save-keep-pwd', 'admins' => base_admins(), 'steps' => [
        login_step('admin', 'admin888'),
        req('form2', 'GET', '/admin/admin/info', 'id=2', '', ['ajax' => false, 'body' => false]),
        $save('edit-keep-pwd', 'admin_id=2&__token__={token}&admin_name=editor&admin_pwd=&admin_status=1'),
    ]];
    $s[] = ['label' => 'admin-save-no-auth-field', 'admins' => base_admins(), 'steps' => [
        login_step('admin', 'admin888'),
        req('form', 'GET', '/admin/admin/info', '', '', ['ajax' => false, 'body' => false]),
        $save('add', 'admin_id=&__token__={token}&admin_name=noauth&admin_pwd=na&admin_status=1'),
    ]];
    $s[] = ['label' => 'admin-save-editor-self', 'admins' => array_merge(base_admins(), [a(5, 'manager', md5('m1'),
        ['admin_auth' => ',admin/index,admin/info,'])]), 'steps' => [
        login_step('manager', 'm1'),
        req('form', 'GET', '/admin/admin/info', 'id=1', '', ['ajax' => false, 'body' => false]),
        $save('edit-super', 'admin_id=1&__token__={token}&admin_name=admin&admin_pwd=hijack&admin_status=1'),
    ]];

    // ---------------- 删除与状态（Admin.php:99-140）
    $more = array_merge(base_admins(), [a(5, 'e5', md5('x')), a(6, 'e6', md5('x')), a(7, 'e7', md5('x')), a(8, 'e8', md5('x'))]);
    $s[] = ['label' => 'admin-del', 'admins' => $more, 'steps' => [
        login_step('admin', 'admin888'),
        req('self', 'GET', '/admin/admin/del', 'ids=1'),
        req('self-in-list', 'GET', '/admin/admin/del', 'ids=5,1'),
        req('one', 'GET', '/admin/admin/del', 'ids=3'),
        raw_post('array', '/admin/admin/del', 'ids%5B%5D=5&ids%5B%5D=6'),
        req('list', 'POST', '/admin/admin/del', '', ['ids' => '7,8']),
        req('missing', 'GET', '/admin/admin/del'),
        req('empty', 'GET', '/admin/admin/del', 'ids='),
        req('nonexistent', 'GET', '/admin/admin/del', 'ids=999'),
        req('text', 'GET', '/admin/admin/del', 'ids=abc'),
        req('path', 'GET', '/admin/admin/del/ids/4.html'),
        req('html', 'GET', '/admin/admin/del', 'ids=2', '', ['ajax' => false]),
        // PHP 8 下 in_array(1, ['1abc']) 为假，MySQL 却按 1 删除——删掉了当前登录的超级管理员（7.3 拒绝）；新版参数错误（D-B12-10）
        req('self-loose', 'GET', '/admin/admin/del', 'ids=1abc'),
    ]];
    $s[] = ['label' => 'admin-field', 'admins' => $more, 'steps' => [
        login_step('admin', 'admin888'),
        req('off', 'GET', '/admin/admin/field', 'col=admin_status&ids=3&val=0'),
        req('on', 'GET', '/admin/admin/field', 'col=admin_status&ids=3&val=1'),
        req('two', 'GET', '/admin/admin/field', 'col=admin_status&ids=5,6&val=0'),
        req('bad-val', 'GET', '/admin/admin/field', 'col=admin_status&ids=3&val=2'),
        req('loose-val', 'GET', '/admin/admin/field', 'col=admin_status&ids=7&val=1.0'),
        req('bad-col', 'GET', '/admin/admin/field', 'col=admin_name&ids=3&val=1'),
        req('no-ids', 'GET', '/admin/admin/field', 'col=admin_status&val=1'),
        req('self-off', 'GET', '/admin/admin/field/col/admin_status/ids/1.html', 'val=0'),
        req('after-self-off', 'GET', '/admin/index/welcome', '', '', ['ajax' => false]),
    ]];

    // ---------------- 锁屏解锁、布局 cookie、登出（Index.php:157-195、20-24）
    $s[] = ['label' => 'unlock', 'admins' => base_admins(), 'steps' => [
        login_step('admin', 'admin888'),
        req('right', 'POST', '/admin/index/unlocked', '', ['password' => 'admin888']),
        req('wrong', 'POST', '/admin/index/unlocked', '', ['password' => 'admin889']),
        req('empty', 'POST', '/admin/index/unlocked', '', ['password' => '']),
        req('missing', 'POST', '/admin/index/unlocked', '', ''),
        req('query', 'GET', '/admin/index/unlocked', 'password=admin888'),
        req('html', 'POST', '/admin/index/unlocked', '', ['password' => 'admin889'], ['ajax' => false]),
        req('like', 'POST', '/admin/index/unlocked', '', ['password' => 'like']),
    ]];
    // ---------------- 库里存量的大写/混合大小写 md5（MACC-36 返工）：登录查询与登录记录按 admin_pwd 列的排序规则（utf8_general_ci，
    // 不分大小写）比较，会话里存的是查到的那一行（原值）；解锁是 PHP 的 md5(输入) != 会话里的 admin_pwd（区分大小写）
    $upper = strtoupper(md5('admin888'));
    $mixed = mixed_case(md5('admin888'));
    foreach (['upper' => $upper, 'mixed' => $mixed] as $tag => $stored) {
        $s[] = ['label' => 'stored-case-' . $tag, 'admins' => [a(1, 'admin', $stored, ['admin_login_num' => 5,
            'admin_login_time' => 1700000000, 'admin_login_ip' => 167772161]),
            a(2, 'editor', strtoupper(md5('ed pass')), ['admin_auth' => ',admin/index,index/unlocked,'])],
            'steps' => [
                login_step('admin', 'admin888'),
                req('next-page', 'GET', '/admin/index/welcome', '', '', ['ajax' => false]),
                req('list', 'GET', '/admin/admin/index', '', '', ['ajax' => false]),
                req('unlock-right', 'POST', '/admin/index/unlocked', '', ['password' => 'admin888']),
                req('unlock-wrong', 'POST', '/admin/index/unlocked', '', ['password' => 'admin889']),
                req('unlock-stored-hash', 'POST', '/admin/index/unlocked', '', ['password' => $stored]),
                req('still-in', 'GET', '/admin/index/welcome', '', '', ['ajax' => false, 'body' => false]),
                ['label' => 'db-password', 'sql' => "UPDATE mac_admin SET admin_pwd = '" . md5('changed') . "' WHERE admin_id = 1"],
                req('after-password', 'GET', '/admin/index/welcome', '', '', ['ajax' => false]),
                req('unlock-new', 'POST', '/admin/index/unlocked', '', ['password' => 'changed']),
            ]];
    }
    // 非超级管理员（有 index/unlocked 权限）、库里是大写哈希：登录、下一页、解锁
    $s[] = ['label' => 'stored-case-editor', 'admins' => [a(1, 'admin', md5('admin888')),
        a(2, 'editor', strtoupper(md5('ed pass')), ['admin_auth' => ',admin/index,index/unlocked,'])], 'steps' => [
        login_step('editor', 'ed pass'),
        req('next-page', 'GET', '/admin/admin/index', '', '', ['ajax' => false]),
        req('unlock-right', 'POST', '/admin/index/unlocked', '', ['password' => 'ed pass']),
        req('unlock-wrong', 'POST', '/admin/index/unlocked', '', ['password' => 'ed pas']),
    ]];
    $s[] = ['label' => 'iframe', 'admins' => base_admins(), 'steps' => [
        login_step('admin', 'admin888'),
        req('on', 'POST', '/admin/index/iframe', '', ['val' => '1']),
        req('off', 'POST', '/admin/index/iframe', '', ['val' => '0']),
        req('bad', 'POST', '/admin/index/iframe', '', ['val' => '2']),
        req('missing', 'POST', '/admin/index/iframe', '', ''),
        req('get', 'GET', '/admin/index/iframe', 'val=1'),
    ]];
    $s[] = ['label' => 'logout', 'admins' => base_admins(), 'steps' => [
        login_step('admin', 'admin888'),
        req('logout', 'GET', '/admin/index/logout', '', '', ['ajax' => false]),
        req('after', 'GET', '/admin/index/welcome', '', '', ['ajax' => false]),
        req('logout-again', 'GET', '/admin/index/logout'),
    ]];
    return $s;
}

/** auth.php 的全部权限码（控制器/动作，去重，保持出现顺序）。 */
function auth_codes($repo)
{
    if (!function_exists('lang')) {
        function lang($k)
        {
            return $k;
        }
    }
    $menus = include $repo . '/application/admin/common/auth.php';
    $out = [];
    foreach ($menus as $group) {
        foreach ($group['sub'] as $sub) {
            $code = $sub['controller'] . '/' . $sub['action'];
            if (strpos($code, '?') !== false) {
                continue;
            }
            $out[$code] = true;
        }
    }
    return array_keys($out);
}

function generate($repo, $pdo, $phpCgi, $work, $host, $port, $db, $user, $password)
{
    $out = [];
    $only = getenv('GOLDEN_ONLY');
    $base = deep_merge(include $repo . '/application/extra/maccms.php', base_config());
    foreach (scenarios($repo) as $sc) {
        if ($only !== false && $only !== '' && strpos($sc['label'], $only) !== 0) {
            continue;
        }
        $conf = $work . '/conf';
        $runtime = $work . '/runtime';
        rm_tree($conf);
        rm_tree($runtime);
        @mkdir($runtime, 0777, true);
        $over = isset($sc['config']) ? $sc['config'] : [];
        $maccms = deep_merge($base, $over);
        write_conf($repo, $conf, $maccms, isset($sc['quickmenu']) ? $sc['quickmenu'] : null, $host, $port, $db, $user, $password);
        $pdo->exec('DELETE FROM mac_admin');
        foreach ($sc['admins'] as $row) {
            $cols = array_keys($row);
            $stmt = $pdo->prepare('INSERT INTO mac_admin (' . implode(', ', $cols) . ') VALUES ('
                . implode(', ', array_fill(0, count($cols), '?')) . ')');
            $stmt->execute(array_values($row));
        }
        $sc['rows0'] = read_rows($pdo);
        $jar = [];
        $token = null;
        $lastRows = null;
        $steps = [];
        foreach ($sc['steps'] as $st) {
            if (isset($st['sql'])) {
                $pdo->exec($st['sql']);
                $lastRows = read_rows($pdo);
                $steps[] = ['label' => $st['label'], 'sql' => $st['sql'], 'rows' => $lastRows];
                continue;
            }
            if (isset($st['captcha'])) {
                if (!isset($jar['PHPSESSID'])) {
                    $jar['PHPSESSID'] = 'golden' . bin2hex(random_bytes(8));
                }
                $file = $runtime . '/sess_' . $jar['PHPSESSID'];
                $raw = is_file($file) ? file_get_contents($file) : '';
                $raw .= captcha_authcode('ThinkPHP.CN') . '|' . serialize(['verify_code' => captcha_authcode(strtoupper($st['captcha'])),
                        'verify_time' => time()]);
                file_put_contents($file, $raw);
                $steps[] = ['label' => $st['label'], 'captcha' => $st['captcha']];
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
            if (is_array($st['post'])) {
                $post = isset($st['post']['__raw__']) ? $st['post']['__raw__'] : http_build_query($st['post']);
            } else {
                $post = $st['post'];
            }
            $post = str_replace('{token}', rawurlencode((string)$token), $post);
            $req = ['path' => $st['path'], 'query' => $st['query'], 'method' => $st['method'], 'post' => $post,
                'headers' => $headers, 'remote' => '127.0.0.1'];
            $started = time();
            list($status, $respHeaders, $setCookies, $body) = run_request($repo, $phpCgi, $conf, $runtime, $req);
            $after = time();
            // mac_admin 的全部行：与上一次记录相同时记 null（场景开始时的行见 admins）
            $rows = read_rows($pdo);
            if ($lastRows === null) {
                $lastRows = $sc['rows0'];
            }
            if ($rows === $lastRows) {
                $rows = null;
            } else {
                $lastRows = $rows;
            }
            foreach ($setCookies as $c) {
                $pair = explode(';', $c, 2)[0];
                list($k, $v) = array_pad(explode('=', $pair, 2), 2, '');
                if ($v === 'deleted' || stripos($c, 'Max-Age=0') !== false) {
                    unset($jar[$k]);
                } else {
                    $jar[$k] = $v;
                }
            }
            if (preg_match('/name="__token__" value="([0-9a-f]+)"/', $body, $tm)) {
                $token = $tm[1];
            }
            $steps[] = ['label' => $st['label'], 'request' => ['method' => $st['method'], 'path' => $st['path'],
                'query' => $st['query'], 'post' => $post, 'ajax' => $st['ajax'], 'headers' => (object)$st['headers']],
                'started' => $started, 'after' => $after, 'status' => $status,
                'contentType' => isset($respHeaders['content-type']) ? $respHeaders['content-type'] : null,
                'location' => isset($respHeaders['location']) ? $respHeaders['location'] : null,
                'setCookies' => $setCookies, 'body' => $status >= 500 || !$st['body'] ? null : base64_encode($body),
                'rows' => $rows];
        }
        $out[] = ['label' => $sc['label'], 'config' => (object)$over,
            'quickmenu' => isset($sc['quickmenu']) ? $sc['quickmenu'] : null, 'rows0' => $sc['rows0'], 'steps' => $steps];
    }
    return json_encode(['generator' => 'backend/src/test/php/admin_golden.php', 'php' => PHP_VERSION, 'timezone' => 'PRC',
            'entry' => ENTRY, 'host' => 'www.example.test', 'remote' => '127.0.0.1', 'baseConfig' => base_config(),
            'scenarios' => $out],
            JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT | JSON_INVALID_UTF8_SUBSTITUTE) . "\n";
}

/** mac_admin 的全部行（文本列十六进制，避免二进制密码与非法 UTF-8 丢失）。 */
function read_rows($pdo)
{
    $rows = [];
    foreach ($pdo->query('SELECT ' . ADMIN_COLUMNS . ' FROM mac_admin ORDER BY admin_id')->fetchAll(PDO::FETCH_ASSOC) as $r) {
        foreach (['admin_name', 'admin_pwd', 'admin_random', 'admin_auth'] as $c) {
            $r[$c] = bin2hex($r[$c]);
        }
        foreach (['admin_id', 'admin_status', 'admin_login_time', 'admin_login_ip', 'admin_login_num', 'admin_last_login_time',
                     'admin_last_login_ip'] as $c) {
            $r[$c] = (int)$r[$c];
        }
        $rows[] = $r;
    }
    return $rows;
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

function write_conf($repo, $conf, $maccms, $quickmenu, $host, $port, $db, $user, $password)
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
    if ($quickmenu !== null) {
        file_put_contents($conf . '/extra/quickmenu.php', '<?php return ' . var_export($quickmenu, true) . ';');
    }
}

/** 以 php-cgi 执行一个后台请求。返回 [status, headers(小写名 => 值), setCookies, body]。 */
function run_request($repo, $phpCgi, $conf, $runtime, $req)
{
    $query = $req['query'];
    $method = $req['method'];
    $post = $req['post'];
    $entryDir = dirname($runtime) . DIRECTORY_SEPARATOR . 'entry';
    $entryFile = $entryDir . DIRECTORY_SEPARATOR . '_admin.php';
    if (!is_file($entryFile)) {
        @mkdir($entryDir, 0777, true);
        file_put_contents($entryFile, '<?php require ' . var_export(__DIR__ . DIRECTORY_SEPARATOR . 'admin_http_child.php', true) . ';');
    }
    $env = [
        'GOLDEN_CONF_PATH' => $conf,
        'GOLDEN_RUNTIME_PATH' => $runtime,
        'GOLDEN_SESSION_PATH' => $runtime,
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
        $env['CONTENT_TYPE'] = 'application/x-www-form-urlencoded';
        $env['CONTENT_LENGTH'] = (string)strlen($post);
    }
    foreach ($req['headers'] as $name => $value) {
        $env['HTTP_' . strtoupper(str_replace('-', '_', $name))] = $value;
    }
    $cmd = escapeshellarg($phpCgi) . ' -d session.save_path=' . escapeshellarg($runtime)
        . ' -d display_errors=0 -d error_reporting=0 -d session.gc_probability=0';
    $proc = proc_open($cmd, [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['pipe', 'w']], $pipes, $repo, $env,
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

/** think-captcha 的私有 authcode（Captcha.php），用于构造会话里的验证码记录（同 interaction_golden.php）。 */
function captcha_authcode($str)
{
    $key = substr(md5('ThinkPHP.CN'), 5, 8);
    $str = substr(md5($str), 8, 10);
    return md5($key . $str);
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
