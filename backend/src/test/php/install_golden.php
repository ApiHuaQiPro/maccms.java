<?php
/*
 * B22（MACC-45）安装向导的 PHP 黄金样本（契约见 docs/architecture/31-b22-install-wizard.md 第 9 节）。
 *
 * 每个场景是一串真实的请求：经 install_child.php 以 php-cgi 跑完整的 ThinkPHP 路由与旧控制器 application/install/controller/Index.php
 * （首页、环境检测、数据库配置页、测试连接、执行安装，以及安装锁）。每个场景用一份新复制的临时站点（application/ thinkphp/ vendor/，
 * database.php 换成空配置 = 新下载的安装包状态）与一个新的空一次性库 maccms_it_<时间>_<随机>：旧向导在这个一次性库上照旧执行
 * CREATE DATABASE IF NOT EXISTS 与 install.sql（采集黄金时允许，路线图第 1.2 节"测试时在一次性库中原样导入 install.sql"）。
 * 每一步记录状态码、Content-Type 与正文（base64）；安装成功的场景另记录 mac_type、mac_admin 的全部行与写出的 extra/maccms.php、install.lock。
 * 表单里的连接参数写成占位符 {host} {port} {db} {user} {password}，新版复放时换成自己的一次性库与账号（JSON 里没有任何账号密码）。
 *
 * 流程：外部管理账号 → 每个场景：建空库 → 复制临时站点 → 逐步请求 → 取库与文件状态 → 删库、删临时目录 → 输出 JSON。
 * 不修改任何仓库文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，不访问外部网络。
 *
 * 管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；另需 PHP_CGI 与 GOLDEN_VENDOR_DIR
 * （备份 zip 里解出的 vendor/ 目录）。用法（仓库根目录，PHP 8.0 与 7.3 各一份）：
 *   PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe backend/src/test/php/install_golden.php \
 *       > backend/src/test/resources/install/b22/php80-golden.json
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
const HOST = 'install.example.test';

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);

// ---------------------------------------------------------------------- 场景
// 每步：[标签, 方法, 查询串, 表单（null 表示不带正文）, 是否 Ajax]
$conn = ['hostname' => '{host}', 'hostport' => '{port}', 'database' => '{db}', 'username' => '{user}',
    'password' => '{password}', 'prefix' => 'mac_', 'cover' => '0'];
$admin = ['install_dir' => '/', 'account' => 'admin', 'password' => 'Test-only-1', 'initdata' => '1'];
$scenarios = [
    ['label' => 'pages', 'install' => false, 'steps' => [
        ['index', 'GET', '', null, false],
        ['index-lang-zh-tw', 'GET', 'lang=zh-tw', null, false],
        ['step2-zh-tw', 'GET', 'step=2', null, false],
        ['step3-zh-tw', 'GET', 'step=3', null, false],
        ['back-index', 'GET', 'index', null, false],
        ['step3', 'GET', 'step=3', null, false],
        ['step-unknown', 'GET', 'step=9', null, false],
        ['step4-get', 'GET', 'step=4', null, false],
        ['step4-get-ajax', 'GET', 'step=4', null, true],
        ['step5-before-test', 'POST', 'step=5', $admin, false],
        ['step5-before-test-ajax', 'POST', 'step=5', $admin, true],
        ['index-lang-bad', 'GET', 'lang=xx', null, false],
    ]],
    ['label' => 'step4-validation', 'install' => false, 'steps' => [
        ['index', 'GET', '', null, false],
        ['empty', 'POST', 'step=4', [], true],
        ['hostname-missing', 'POST', 'step=4', array_diff_key($conn, ['hostname' => 1]), true],
        ['hostport-empty', 'POST', 'step=4', array_merge($conn, ['hostport' => '']), true],
        ['hostport-alpha', 'POST', 'step=4', array_merge($conn, ['hostport' => 'abc']), true],
        ['database-missing', 'POST', 'step=4', array_diff_key($conn, ['database' => 1]), true],
        ['username-missing', 'POST', 'step=4', array_diff_key($conn, ['username' => 1]), true],
        ['prefix-upper', 'POST', 'step=4', array_merge($conn, ['prefix' => 'MAC_']), true],
        ['prefix-no-underscore', 'POST', 'step=4', array_merge($conn, ['prefix' => 'mac']), true],
        ['cover-2', 'POST', 'step=4', array_merge($conn, ['cover' => '2']), true],
        ['cover-missing', 'POST', 'step=4', array_diff_key($conn, ['cover' => 1]), true],
        ['unknown-field', 'POST', 'step=4', array_merge($conn, ['foo' => '1']), true],
        ['wrong-password', 'POST', 'step=4', array_merge($conn, ['password' => '{password}-wrong']), true],
        ['not-ajax', 'POST', 'step=4', array_diff_key($conn, ['hostname' => 1]), false],
        ['index-zh-tw', 'GET', 'lang=zh-tw', null, false],
        ['zh-tw-hostport-alpha', 'POST', 'step=4', array_merge($conn, ['hostport' => 'abc']), true],
        ['zh-tw-prefix-upper', 'POST', 'step=4', array_merge($conn, ['prefix' => 'MAC_']), true],
    ]],
    ['label' => 'install-initdata', 'install' => true, 'steps' => [
        ['index', 'GET', '', null, false],
        ['step2', 'GET', 'step=2', null, false],
        ['step3', 'GET', 'step=3', null, false],
        ['step4-not-cover', 'POST', 'step=4', $conn, true],
        ['step5-account-empty', 'POST', 'step=5', array_merge($admin, ['account' => '']), false],
        ['step5-password-empty', 'POST', 'step=5', array_merge($admin, ['password' => '']), false],
        ['step5-account-alnum', 'POST', 'step=5', array_merge($admin, ['account' => 'ad-min']), false],
        ['step5-password-short', 'POST', 'step=5', array_merge($admin, ['password' => '12345']), false],
        ['step5-password-long', 'POST', 'step=5', array_merge($admin, ['password' => str_repeat('a', 21)]), true],
        ['step5', 'POST', 'step=5', $admin, false],
        ['locked-index', 'GET', '', null, false],
        ['locked-step5', 'POST', 'step=5', $admin, true],
    ]],
    ['label' => 'install-no-initdata', 'install' => true, 'steps' => [
        ['index', 'GET', '', null, false],
        ['step2', 'GET', 'step=2', null, false],
        ['step3', 'GET', 'step=3', null, false],
        ['step4-cover', 'POST', 'step=4', array_merge($conn, ['cover' => '1']), true],
        ['step5', 'POST', 'step=5', array_merge($admin, ['account' => 'Admin2', 'password' => '中文密码abc', 'initdata' => '0']), true],
        ['locked-step2', 'GET', 'step=2', null, false],
    ]],
];

$out = ['php' => PHP_VERSION, 'host' => HOST, 'scenarios' => []];
$exit = 0;
foreach ($scenarios as $sc) {
    $dbName = 'maccms_it_' . date('YmdHis') . '_' . bin2hex(random_bytes(4));
    if (!preg_match('/^maccms_it_[0-9]{14}_[0-9a-f]{8}$/', $dbName)) {
        fwrite(STDERR, "一次性库名不合法\n");
        exit(2);
    }
    $work = sys_get_temp_dir() . DIRECTORY_SEPARATOR . 'maccms-b22-golden-' . bin2hex(random_bytes(4));
    $pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
    try {
        $site = $work . DIRECTORY_SEPARATOR . 'site';
        copy_tree($repo . '/application', $site . '/application');
        copy_tree($repo . '/thinkphp', $site . '/thinkphp');
        copy_tree($vendor, $site . '/vendor');
        @mkdir($site . '/runtime', 0777, true);
        @mkdir($site . '/upload', 0777, true);
        @unlink($site . '/application/data/install/install.lock');
        // 新下载的安装包：database.php 的连接项为空（旧 mkDatabase 的模板，Index.php:377-433）
        file_put_contents($site . '/application/database.php', empty_database_php());
        $vars = ['{host}' => $host, '{port}' => $port, '{db}' => $dbName, '{user}' => $adminUser, '{password}' => $adminPassword];
        $jar = [];
        $steps = [];
        foreach ($sc['steps'] as $st) {
            list($label, $method, $query, $form, $ajax) = $st;
            $body = '';
            if ($form !== null) {
                $pairs = [];
                foreach ($form as $k => $v) {
                    $pairs[] = rawurlencode($k) . '=' . rawurlencode(strtr($v, $vars));
                }
                $body = implode('&', $pairs);
            }
            $headers = $ajax ? ['X-Requested-With' => 'XMLHttpRequest'] : [];
            $r = request($site, $phpCgi, $method, $query, $body, $headers, $jar);
            $steps[] = ['label' => $label, 'request' => ['method' => $method, 'query' => $query, 'form' => $form, 'ajax' => $ajax],
                'status' => $r['status'], 'contentType' => isset($r['headers']['content-type']) ? $r['headers']['content-type'] : '',
                'body' => base64_encode($r['body'])];
        }
        $entry = ['label' => $sc['label'], 'steps' => $steps];
        if ($sc['install']) {
            $pdo->exec("USE `$dbName`");
            $entry['state'] = [
                'mac_type' => $pdo->query('SELECT * FROM mac_type ORDER BY type_id')->fetchAll(PDO::FETCH_ASSOC),
                'mac_admin' => $pdo->query('SELECT * FROM mac_admin ORDER BY admin_id')->fetchAll(PDO::FETCH_ASSOC),
                'maccms' => base64_encode(file_get_contents($site . '/application/extra/maccms.php')),
                'lock' => is_file($site . '/application/data/install/install.lock')
                    ? file_get_contents($site . '/application/data/install/install.lock') : null,
            ];
        }
        $out['scenarios'][] = $entry;
    } catch (Throwable $e) {
        fwrite(STDERR, get_class($e) . "\n");
        $exit = 1;
    } finally {
        $pdo->exec("DROP DATABASE IF EXISTS `$dbName`");
        $left = $pdo->query("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '$dbName'")->fetchColumn();
        if ((int)$left !== 0) {
            fwrite(STDERR, "一次性库未删除\n");
            $exit = 1;
        }
        rm_tree($work);
    }
}
echo json_encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT), "\n";
exit($exit);

// ====================================================================== 请求

/** 以 php-cgi 执行一个请求（会话 cookie 记在 $jar）。 */
function request($site, $phpCgi, $method, $query, $post, $headers, &$jar)
{
    if ($jar) {
        $pairs = [];
        foreach ($jar as $k => $v) {
            $pairs[] = $k . '=' . $v;
        }
        $headers['Cookie'] = implode('; ', $pairs);
    }
    $script = '/install.php';
    $entryDir = dirname($site) . DIRECTORY_SEPARATOR . 'entry';
    $entryFile = $entryDir . $script;
    if (!is_file($entryFile)) {
        @mkdir($entryDir, 0777, true);
        file_put_contents($entryFile, '<?php require ' . var_export(__DIR__ . DIRECTORY_SEPARATOR . 'install_child.php', true) . ';');
    }
    $runtime = $site . DIRECTORY_SEPARATOR . 'runtime';
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
        'SCRIPT_NAME' => $script,
        'PHP_SELF' => $script,
        'REQUEST_URI' => $script . ($query === '' ? '' : '?' . $query),
        'QUERY_STRING' => $query,
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
        } elseif ($name === 'set-cookie') {
            $kv = explode(';', $value, 2)[0];
            $eq = strpos($kv, '=');
            if ($eq !== false) {
                $jar[substr($kv, 0, $eq)] = substr($kv, $eq + 1);
            }
        } else {
            $respHeaders[$name] = $value;
        }
    }
    return ['status' => $status, 'headers' => $respHeaders, 'body' => substr($out, $sep + 4)];
}

// ====================================================================== 工具

function empty_database_php()
{
    return "<?php\nreturn [\n    'type' => 'mysql',\n    'hostname' => '',\n    'database' => '',\n    'username' => '',\n"
        . "    'password' => '',\n    'hostport' => '',\n    'dsn' => '',\n    'params' => [],\n    'charset' => 'utf8',\n"
        . "    'prefix' => 'mac_',\n    'debug' => false,\n    'deploy' => 0,\n    'rw_separate' => false,\n    'master_num' => 1,\n"
        . "    'slave_no' => '',\n    'fields_strict' => false,\n    'resultset_type' => 'array',\n    'auto_timestamp' => false,\n"
        . "    'datetime_format' => 'Y-m-d H:i:s',\n    'sql_explain' => false,\n    'builder' => '',\n"
        . "    'query' => '\\\\think\\\\db\\\\Query',\n];\n";
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
