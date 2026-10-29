<?php
/*
 * B29（MACC-52）插件市场安装/升级（E13）的 PHP 黄金样本（契约见 docs/architecture/35-b29-addon-market.md 第 9 节）。
 *
 * 每个场景是一串真实的后台请求：经 admin_system_child.php 以 php-cgi 跑完整的 ThinkPHP 路由、admin/controller/Base 与
 * admin/controller/Addon（install、upgrade、state、uninstall）→ fastadmin-addons Service。站点是本次运行在临时目录里复制出来的副本，
 * 每个场景开始前整体还原。
 *
 * 这份生产代码里旧版的插件市场根本不能用：vendor/ 与 extend/ 里都没有 Service::download 用到的 fast\Http 类，也没有配置
 * fastadmin.api_url（场景 prod-as-is 记录的就是这个现状：安装请求得到致命错误页）。为了得到"能下载时"旧版的文件效果，其余场景在临时副本里
 * 加一个测试替身 extend/fast/Http.php（按 fastadmin 原版 Http::sendRequest 的 curl 用法写成，见 fast_http_shim()）和
 * application/extra/fastadmin.php（api_url 指向本机模拟市场）。这两个文件只存在于临时副本，仓库文件不动。
 *
 * 模拟市场：php -S 127.0.0.1:随机端口 + 本脚本生成的 router.php。/addon/download 按场景的 download 队列依次回复，/pkg/<文件> 返回场景里的
 * 插件包；记录每个请求的方法、地址与 X-REQUESTED-WITH 头。插件包由本脚本生成（ZipArchive），以 base64 写进黄金，新版复放用同一份字节。
 *
 * 每一步记录状态码、Content-Type、正文（base64）、模拟市场收到的请求、这一步之后站点里变化了的文件（路径 → sha256，被删为 null；
 * runtime/ 下只看 runtime/addons/，备份文件名里的时间换成 {ts}），以及一次性库里新出现的表与 mac_link 的行数（插件 SQL 的效果）。
 *
 * 流程：外部管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入 install.sql → 写入管理员 → 复制临时站点 → 启动模拟市场 → 逐个场景执行 →
 * 输出 JSON → 停止模拟市场、删除一次性库与临时目录。不修改任何仓库文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，
 * 不访问外部网络（所有请求都到 127.0.0.1）。
 *
 * 管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；另需 PHP_CGI 与 GOLDEN_VENDOR_DIR
 * （备份 zip 里解出的 vendor/ 目录）。用法（仓库根目录，PHP 8.0 与 7.3 各一份）：
 *   PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe backend/src/test/php/addon_market_golden.php \
 *       > backend/src/test/resources/admin/addonmarket/php80-golden.json
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

const HOST = 'admin.example.test';

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$work = sys_get_temp_dir() . DIRECTORY_SEPARATOR . 'maccms-b29-golden-' . bin2hex(random_bytes(4));
$mock = null;
$exit = 0;
try {
    $pdo->exec("USE `$dbName`");
    foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
        $pdo->exec($sql);
    }
    foreach (seed_sql() as $sql) {
        $pdo->exec($sql);
    }
    @mkdir($work, 0777, true);
    $mock = start_mock($work);
    echo generate($repo, $vendor, $phpCgi, $work, $mock, $pdo, $host, $port, $dbName, $adminUser, $adminPassword);
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

function seed_sql()
{
    return ["INSERT INTO mac_admin (admin_id, admin_name, admin_pwd, admin_status, admin_auth) VALUES (1, 'admin', '"
        . md5('admin888') . "', 1, '')"];
}

/** 插件主类（fastadmin 插件的最小写法；钩子 demoHook → demo_hook）。 */
function addon_class($ns, $class)
{
    return "<?php\n\nnamespace addons\\$ns;\n\nuse think\\Addons;\n\nclass $class extends Addons\n{\n"
        . "    public function install()\n    {\n        return true;\n    }\n\n"
        . "    public function uninstall()\n    {\n        return true;\n    }\n\n"
        . "    public function enable()\n    {\n        return true;\n    }\n\n"
        . "    public function disable()\n    {\n        return true;\n    }\n\n"
        . "    public function upgrade()\n    {\n        return true;\n    }\n\n"
        . "    public function demoHook(\$param)\n    {\n    }\n}\n";
}

function info_ini($name, $version, $state)
{
    return "name = $name\ntitle = 测试插件$name\nintro = 插件市场黄金样本\nauthor = golden\nwebsite = \nversion = $version\nstate = $state\n";
}

function config_php($mode)
{
    return "<?php\n\nreturn array (\n  0 => \n  array (\n    'name' => 'mode',\n    'title' => '模式',\n    'type' => 'radio',\n"
        . "    'content' => \n    array (\n      'random' => '随机',\n      'daily' => '每日',\n      'fixed' => '固定',\n    ),\n"
        . "    'value' => '$mode',\n    'rule' => 'required',\n    'msg' => '',\n    'tip' => '',\n    'ok' => '',\n    'extend' => '',\n  ),\n"
        . "  1 => \n  array (\n    'name' => 'image',\n    'title' => '固定背景图',\n    'type' => 'image',\n    'content' => \n    array (\n    ),\n"
        . "    'value' => '/uploads/v1.png',\n    'rule' => 'required',\n    'msg' => '',\n    'tip' => '',\n    'ok' => '',\n    'extend' => '',\n  ),\n);\n";
}

/** 插件包（条目名 => 内容；以 / 结尾的是目录条目）。 */
function packages($repo)
{
    $abg = [];
    foreach (['Adminloginbg.php', 'controller/Index.php'] as $f) {
        $abg[$f] = file_get_contents($repo . '/addons/adminloginbg/' . $f);
    }
    $p = [];
    $p['adminloginbg-1.0.1.zip'] = $abg + ['info.ini' => str_replace('version = 1.0.0', 'version = 1.0.1',
            str_replace("url = /addons/adminloginbg.html\n", '', file_get_contents($repo . '/addons/adminloginbg/info.ini'))),
        'config.php' => config_php('fixed'), 'assets/' => '', 'assets/css/login.css' => "body{background:#000}\n",
        'bootstrap.js' => "console.log('adminloginbg');"];
    $p['adminloginbg-1.0.2.zip'] = $abg + ['info.ini' => str_replace('version = 1.0.0', 'version = 1.0.2',
            str_replace("url = /addons/adminloginbg.html\n", '', file_get_contents($repo . '/addons/adminloginbg/info.ini'))),
        'config.php' => config_php('daily'), 'assets/css/login.css' => "body{background:#111}\n", 'assets/css/new.css' => "a{}\n",
        'bootstrap.js' => "console.log('adminloginbg 1.0.2');"];
    $p['demo.zip'] = ['Demo.php' => addon_class('demo', 'Demo'), 'info.ini' => info_ini('demo', '1.0.0', 1),
        'config.php' => config_php('random'), 'bootstrap.js' => "console.log('demo');", 'assets/demo.css' => ".demo{}\n",
        'application/index/view/demo/x.html' => "<p>demo</p>\n", 'static/demo/demo.js' => "var demo = 1;\n",
        'install.sql' => "-- demo\nINSERT INTO `__PREFIX__link` (`link_name`, `link_url`, `link_type`) VALUES ('插件友链', 'http://demo.example.test/', 0);\n"];
    $p['demo-conflict.zip'] = ['Demo.php' => addon_class('demo', 'Demo'), 'info.ini' => info_ini('demo', '1.0.0', 1),
        'static/js/addons.js' => "/* conflict */\n"];
    $p['noclass.zip'] = ['info.ini' => info_ini('noclass', '1.0.0', 1), 'readme.txt' => "no class\n"];
    $p['ddl.zip'] = ['Ddl.php' => addon_class('ddl', 'Ddl'), 'info.ini' => info_ini('ddl', '1.0.0', 0),
        'install.sql' => "CREATE TABLE IF NOT EXISTS `__PREFIX__ddl_demo` (\n  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,\n"
            . "  PRIMARY KEY (`id`)\n) ENGINE=MyISAM DEFAULT CHARSET=utf8;\n"
            . "INSERT INTO `__PREFIX__link` (`link_name`, `link_url`, `link_type`) VALUES ('ddl', 'http://ddl.example.test/', 0);\n"];
    $p['evil.zip'] = ['Evil.php' => addon_class('evil', 'Evil'), 'info.ini' => info_ini('evil', '1.0.0', 0),
        '../../evil-traversal.txt' => "escaped\n"];
    $p['bomb.zip'] = ['Bomb.php' => addon_class('bomb', 'Bomb'), 'info.ini' => info_ini('bomb', '1.0.0', 0),
        'assets/zero.bin' => str_repeat("\0", 40 * 1024 * 1024)];
    return $p;
}

function build_zip($entries)
{
    $tmp = tempnam(sys_get_temp_dir(), 'b29zip');
    @unlink($tmp);
    $zip = new ZipArchive();
    if ($zip->open($tmp, ZipArchive::CREATE) !== true) {
        throw new RuntimeException('zip');
    }
    foreach ($entries as $name => $content) {
        if (substr($name, -1) === '/') {
            $zip->addEmptyDir(rtrim($name, '/'));
        } else {
            $zip->addFromString($name, $content);
        }
    }
    $zip->close();
    $b = file_get_contents($tmp);
    @unlink($tmp);
    return $b;
}

// ====================================================================== 场景

function step($label, $method, $path, $query = '', $post = '', $ajax = true)
{
    return ['label' => $label, 'method' => $method, 'path' => $path, 'query' => $query, 'post' => $post, 'ajax' => $ajax];
}

function login()
{
    return step('login', 'POST', '/admin/index/login', '', 'admin_name=admin&admin_pwd=admin888');
}

function install($label, $name, $force = 0, $extra = '')
{
    return step($label, 'POST', '/admin/addon/install', '', 'name=' . rawurlencode($name) . '&force=' . $force . $extra);
}

function upgrade($label, $name, $extra = '')
{
    return step($label, 'POST', '/admin/addon/upgrade', '', 'name=' . rawurlencode($name) . $extra);
}

function pkg($file)
{
    return ['status' => 200, 'pkg' => $file];
}

function scenarios()
{
    $s = [];
    // 生产现状：没有 fast\Http、没有 fastadmin.api_url
    $s[] = ['label' => 'prod-as-is', 'shim' => false, 'download' => [], 'steps' => [login(), install('install', 'demo')]];
    // 有 Java 实现的插件：卸载仓库里的版本 → 从市场安装（state 置 1、复制资源）→ 启用状态下升级 → 禁用后升级（配置值保留）
    $s[] = ['label' => 'adminloginbg', 'download' => [pkg('adminloginbg-1.0.1.zip'), pkg('adminloginbg-1.0.2.zip')], 'steps' => [login(),
        step('uninstall', 'GET', '/admin/addon/uninstall', 'name=adminloginbg'),
        install('install', 'adminloginbg', 0, '&uid=7&token=' . rawurlencode('test-only market token') . '&version=1.0&faversion=1.0'),
        upgrade('upgrade-enabled', 'adminloginbg'),
        step('disable', 'GET', '/admin/addon/state', 'name=adminloginbg&action=disable'),
        upgrade('upgrade', 'adminloginbg', '&uid=7&token=test-only')]];
    // 第三方插件（没有 Java 实现）：资源、全局文件、bootstrap.js、install.sql 的数据语句；再装一次（已存在）
    $s[] = ['label' => 'third-party', 'download' => [pkg('demo.zip')], 'steps' => [login(),
        install('install', 'demo'), install('again', 'demo')]];
    // 市场返回 JSON 下载地址 → 再下载一次
    $s[] = ['label' => 'json-url', 'download' => [['status' => 200, 'json' => ['code' => 1, 'msg' => 'ok', 'data' => ['url' => '{mock}/pkg/demo.zip']]]],
        'steps' => [login(), install('install', 'demo')]];
    $s[] = ['label' => 'json-url-404', 'download' => [['status' => 200, 'json' => ['code' => 1, 'msg' => '请下载', 'data' => ['url' => 'http://127.0.0.1:1/x.zip']]]],
        'steps' => [login(), install('install', 'demo')]];
    $s[] = ['label' => 'market-refused', 'download' => [
        ['status' => 200, 'json' => ['code' => 0, 'msg' => '插件不存在或已下架', 'data' => null]],
        ['status' => 200, 'json' => ['code' => -1, 'msg' => '请登录后再操作', 'data' => ['url' => null, 'login' => 1]]]],
        'steps' => [login(), install('missing', 'demo'), install('login-required', 'demo')]];
    $s[] = ['label' => 'http-404', 'download' => [['status' => 404, 'body' => 'not found']], 'steps' => [login(), install('install', 'demo')]];
    $s[] = ['label' => 'connect-refused', 'apiUrl' => 'http://127.0.0.1:1', 'download' => [], 'steps' => [login(), install('install', 'demo')]];
    $s[] = ['label' => 'bad-zip', 'download' => [['status' => 200, 'body' => "PK\x03\x04garbage"]], 'steps' => [login(), install('install', 'demo')]];
    $s[] = ['label' => 'traversal', 'download' => [pkg('evil.zip')], 'steps' => [login(), install('install', 'evil')]];
    $s[] = ['label' => 'zip-bomb', 'download' => [pkg('bomb.zip')], 'steps' => [login(), install('install', 'bomb')]];
    $s[] = ['label' => 'structural-sql', 'download' => [pkg('ddl.zip')], 'steps' => [login(), install('install', 'ddl')]];
    $s[] = ['label' => 'no-class', 'download' => [pkg('noclass.zip')], 'steps' => [login(), install('install', 'noclass')]];
    $s[] = ['label' => 'conflict', 'download' => [pkg('demo-conflict.zip')], 'steps' => [login(), install('install', 'demo')]];
    $s[] = ['label' => 'params', 'download' => [], 'steps' => [login(),
        step('install-no-name', 'POST', '/admin/addon/install', '', 'force=0'),
        step('upgrade-no-name', 'POST', '/admin/addon/upgrade', '', ''),
        upgrade('upgrade-missing', 'nothere')]];
    return $s;
}

// ====================================================================== 执行

function generate($repo, $vendor, $phpCgi, $work, $mock, $pdo, $host, $port, $db, $user, $password)
{
    $base = $work . DIRECTORY_SEPARATOR . 'base';
    $site = $work . DIRECTORY_SEPARATOR . 'site';
    copy_tree($repo . '/application', $base . '/application');
    copy_tree($repo . '/thinkphp', $base . '/thinkphp');
    copy_tree($repo . '/addons', $base . '/addons');
    copy_tree($vendor, $base . '/vendor');
    @mkdir($base . '/static/js', 0777, true);
    copy($repo . '/static/js/addons.js', $base . '/static/js/addons.js');
    @mkdir($base . '/extend', 0777, true);
    @mkdir($base . '/runtime', 0777, true);
    $database = include $repo . '/application/database.php';
    $database = array_merge($database, ['hostname' => $host, 'hostport' => $port, 'database' => $db,
        'username' => $user, 'password' => $password, 'debug' => false]);
    file_put_contents($base . '/application/database.php', '<?php return ' . var_export($database, true) . ';');
    $maccms = include $repo . '/application/extra/maccms.php';
    $maccms['app']['admin_login_verify'] = '0';
    $maccms['app']['lang'] = 'zh-cn';
    file_put_contents($base . '/application/extra/maccms.php', "<?php\nreturn " . var_export($maccms, true) . ';');
    foreach (glob($base . '/application/extra/*.php') as $f) {
        file_put_contents($f, str_replace("\r\n", "\n", file_get_contents($f)));
    }
    foreach (['addons/adminloginbg/info.ini', 'addons/adminloginbg/config.php', 'static/js/addons.js'] as $f) {
        file_put_contents($base . '/' . $f, str_replace("\r\n", "\n", file_get_contents($base . '/' . $f)));
    }

    $packages = [];
    foreach (packages($repo) as $file => $entries) {
        $packages[$file] = build_zip($entries);
        file_put_contents($mock['dir'] . '/pkg-' . $file, $packages[$file]);
    }
    $baseTables = tables($pdo);

    $out = [];
    foreach (scenarios() as $sc) {
        rm_tree($site);
        copy_tree($base, $site);
        $shim = !isset($sc['shim']) || $sc['shim'];
        if ($shim) {
            @mkdir($site . '/extend/fast', 0777, true);
            file_put_contents($site . '/extend/fast/Http.php', fast_http_shim());
            $api = isset($sc['apiUrl']) ? $sc['apiUrl'] : $mock['base'];
            file_put_contents($site . '/application/extra/fastadmin.php', "<?php\nreturn " . var_export(['api_url' => $api], true) . ';');
        }
        set_plan($mock, $sc['download']);
        $before = site_state($site);
        $jar = [];
        $steps = [];
        foreach ($sc['steps'] as $st) {
            $res = do_request($site, $phpCgi, $jar, $st);
            $after = site_state($site);
            $changes = [];
            foreach ($after as $p => $h) {
                if (!isset($before[$p]) || $before[$p] !== $h) {
                    $changes[$p] = $h;
                }
            }
            foreach ($before as $p => $h) {
                if (!isset($after[$p])) {
                    $changes[$p] = null;
                }
            }
            ksort($changes);
            $before = $after;
            $steps[] = ['label' => $st['label'], 'request' => ['method' => $st['method'], 'path' => $st['path'], 'query' => $st['query'],
                'post' => $st['post'], 'ajax' => $st['ajax']],
                'status' => $res['status'],
                'contentType' => isset($res['headers']['content-type']) ? $res['headers']['content-type'] : null,
                'body' => base64_encode(scrub($res['body'], $mock)),
                'mock' => take_log($mock), 'changes' => (object)$changes,
                'newTables' => array_values(array_diff(tables($pdo), $baseTables)),
                'linkRows' => (int)$pdo->query('SELECT COUNT(*) FROM mac_link')->fetchColumn()];
        }
        // 场景之间还原库：删掉插件建的表与写的行（只在一次性库里）
        foreach (array_diff(tables($pdo), $baseTables) as $t) {
            $pdo->exec("DROP TABLE `$t`");
        }
        $pdo->exec('DELETE FROM mac_link');
        $out[] = ['label' => $sc['label'], 'shim' => $shim, 'apiUrl' => isset($sc['apiUrl']) ? $sc['apiUrl'] : ($shim ? '{mock}' : null),
            'download' => $sc['download'], 'steps' => $steps];
    }
    $pk = [];
    foreach ($packages as $f => $b) {
        $pk[$f] = base64_encode($b);
    }
    return json_encode(['generator' => 'backend/src/test/php/addon_market_golden.php', 'php' => PHP_VERSION,
            'timezone' => 'PRC', 'host' => HOST, 'seed' => seed_sql(), 'packages' => $pk, 'scenarios' => $out],
            JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT | JSON_INVALID_UTF8_SUBSTITUTE) . "\n";
}

/** 正文里的模拟市场地址换成 {mock}；致命错误页里本次运行的临时目录（含 8.3 短路径写法）换成 {work}。 */
function scrub($body, $mock)
{
    $body = str_replace($mock['base'], '{mock}', $body);
    return preg_replace('#[A-Za-z]:(?:\\\\|/)[^\'"<>]*?maccms-b29-golden-[0-9a-f]{8}#', '{work}', $body);
}

function tables($pdo)
{
    $out = [];
    foreach ($pdo->query('SHOW TABLES') as $r) {
        $out[] = $r[0];
    }
    sort($out);
    return $out;
}

/**
 * 站点里被跟踪的文件：addons/、static/、application/（不含 database.php）、extend/ 与 runtime/addons/。路径 → sha256；
 * runtime/addons/ 下备份文件名里的时间换成 {ts}。
 */
function site_state($site)
{
    $out = [];
    foreach (['addons', 'static', 'application', 'runtime/addons'] as $dir) {
        $root = $site . '/' . $dir;
        if (!is_dir($root)) {
            continue;
        }
        $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($root, FilesystemIterator::SKIP_DOTS));
        foreach ($it as $f) {
            if (!$f->isFile()) {
                continue;
            }
            $rel = $dir . '/' . str_replace('\\', '/', substr($f->getPathname(), strlen($root) + 1));
            if ($rel === 'application/database.php') {
                continue;
            }
            $rel = preg_replace('#^(runtime/addons/[A-Za-z0-9_]+-backup-)[0-9]{14}(\.zip)$#', '$1{ts}$2', $rel);
            $out[$rel] = strpos($rel, 'runtime/addons/') === 0 && strpos($rel, '-backup-') !== false
                ? 'zip' : hash_file('sha256', $f->getPathname());
        }
    }
    // 穿越出插件目录的文件（addons/evil/../../ 即站点根目录，再往上是临时目录）
    foreach (['' => $site, '../' => dirname($site)] as $prefix => $d) {
        if (is_file($d . '/evil-traversal.txt')) {
            $out[$prefix . 'evil-traversal.txt'] = hash_file('sha256', $d . '/evil-traversal.txt');
        }
    }
    ksort($out);
    return $out;
}

/** fastadmin 原版 fast\Http::sendRequest 的 curl 用法（测试替身，只放进临时站点）。 */
function fast_http_shim()
{
    return <<<'PHP'
<?php

namespace fast;

class Http
{
    public static function sendRequest($url, $params = [], $method = 'POST', $options = [])
    {
        $method = strtoupper($method);
        $protocol = substr($url, 0, 5);
        $query_string = is_array($params) ? http_build_query($params) : $params;
        $ch = curl_init();
        $defaults = [];
        if ('GET' == $method) {
            $geturl = $query_string ? $url . (stripos($url, "?") !== false ? "&" : "?") . $query_string : $url;
            $defaults[CURLOPT_URL] = $geturl;
        } else {
            $defaults[CURLOPT_URL] = $url;
            if ($method == 'POST') {
                $defaults[CURLOPT_POST] = 1;
            } else {
                $defaults[CURLOPT_CUSTOMREQUEST] = $method;
            }
            $defaults[CURLOPT_POSTFIELDS] = $query_string;
        }
        $defaults[CURLOPT_HEADER] = false;
        $defaults[CURLOPT_USERAGENT] = "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/45.0.2454.98 Safari/537.36";
        $defaults[CURLOPT_FOLLOWLOCATION] = true;
        $defaults[CURLOPT_RETURNTRANSFER] = true;
        $defaults[CURLOPT_CONNECTTIMEOUT] = 3;
        $defaults[CURLOPT_TIMEOUT] = 3;
        curl_setopt($ch, CURLOPT_HTTPHEADER, array('Expect:'));
        if ('https' == $protocol) {
            $defaults[CURLOPT_SSL_VERIFYPEER] = false;
            $defaults[CURLOPT_SSL_VERIFYHOST] = false;
        }
        curl_setopt_array($ch, (array)$options + $defaults);
        $ret = curl_exec($ch);
        $err = curl_error($ch);
        if (false === $ret || !empty($err)) {
            $errno = curl_errno($ch);
            $info = curl_getinfo($ch);
            curl_close($ch);
            return ['ret' => false, 'errno' => $errno, 'msg' => $err, 'info' => $info];
        }
        curl_close($ch);
        return ['ret' => true, 'msg' => $ret];
    }
}
PHP;
}

function do_request($site, $phpCgi, &$jar, $req)
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
    list($status, $respHeaders, $setCookies, $body) = run_request($site, $phpCgi, $req['method'], $req['path'], $req['query'],
        $req['post'], $headers);
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

/** 以 php-cgi 执行一个后台请求。返回 [status, headers(小写名 => 值), setCookies, body]。 */
function run_request($site, $phpCgi, $method, $path, $query, $post, $headers)
{
    $script = '/_admin.php';
    $entryDir = dirname($site) . DIRECTORY_SEPARATOR . 'entry';
    $entryFile = $entryDir . $script;
    if (!is_file($entryFile)) {
        @mkdir($entryDir, 0777, true);
        file_put_contents($entryFile, '<?php require ' . var_export(__DIR__ . DIRECTORY_SEPARATOR . 'admin_system_child.php', true) . ';');
    }
    $sessions = dirname($site) . DIRECTORY_SEPARATOR . 'sessions';
    @mkdir($sessions, 0777, true);
    $env = [
        'GOLDEN_SITE_ROOT' => $site,
        'GOLDEN_SESSION_PATH' => $sessions,
        'no_proxy' => '*',
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
        'TEMP' => $sessions,
        'TMP' => $sessions,
    ];
    if ($method === 'POST') {
        $env['CONTENT_TYPE'] = 'application/x-www-form-urlencoded';
        $env['CONTENT_LENGTH'] = (string)strlen($post);
    }
    foreach ($headers as $name => $value) {
        $env['HTTP_' . strtoupper(str_replace('-', '_', $name))] = $value;
    }
    $cmd = escapeshellarg($phpCgi) . ' -d display_errors=0 -d error_reporting=0 -d session.gc_probability=0 -d memory_limit=-1';
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

// ====================================================================== 本机模拟市场

function start_mock($work)
{
    $dir = $work . DIRECTORY_SEPARATOR . 'mock';
    @mkdir($dir, 0777, true);
    file_put_contents($dir . '/router.php', <<<'PHP'
<?php
$dir = __DIR__;
$plan = json_decode(file_get_contents($dir . '/plan.json'), true);
$uri = $_SERVER['REQUEST_URI'];
file_put_contents($dir . '/log.jsonl', json_encode(['method' => $_SERVER['REQUEST_METHOD'], 'uri' => $uri,
    'xrw' => isset($_SERVER['HTTP_X_REQUESTED_WITH']) ? $_SERVER['HTTP_X_REQUESTED_WITH'] : null]) . "\n", FILE_APPEND);
$path = parse_url($uri, PHP_URL_PATH);
if ($path === '/addon/download') {
    $n = (int)@file_get_contents($dir . '/count');
    file_put_contents($dir . '/count', (string)($n + 1));
    $r = isset($plan['download'][$n]) ? $plan['download'][$n] : ['status' => 599, 'body' => 'unplanned'];
    http_response_code($r['status']);
    if (isset($r['pkg'])) {
        header('Content-Type: application/zip');
        readfile($dir . '/pkg-' . $r['pkg']);
    } elseif (isset($r['json'])) {
        header('Content-Type: application/json');
        echo str_replace('{mock}', 'http://127.0.0.1:' . $_SERVER['SERVER_PORT'], json_encode($r['json'], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
    } else {
        header('Content-Type: text/plain');
        echo $r['body'];
    }
    return true;
}
if (strpos($path, '/pkg/') === 0 && is_file($dir . '/pkg-' . basename($path))) {
    header('Content-Type: application/zip');
    readfile($dir . '/pkg-' . basename($path));
    return true;
}
http_response_code(404);
echo 'not found';
return true;
PHP
    );
    file_put_contents($dir . '/plan.json', json_encode(['download' => []]));
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

function set_plan($mock, $download)
{
    file_put_contents($mock['dir'] . '/plan.json', json_encode(['download' => $download], JSON_UNESCAPED_UNICODE));
    @unlink($mock['dir'] . '/count');
    @unlink($mock['dir'] . '/log.jsonl');
}

/** 取出并清空模拟市场这一步收到的请求。 */
function take_log($mock)
{
    $f = $mock['dir'] . '/log.jsonl';
    $out = [];
    if (is_file($f)) {
        foreach (file($f, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $line) {
            $out[] = json_decode($line, true);
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
