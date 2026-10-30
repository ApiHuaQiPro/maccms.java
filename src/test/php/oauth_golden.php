<?php
/*
 * B26（MACC-49）QQ/微信第三方登录回调的 PHP 黄金样本生成脚本（旧代码：index/controller/User.php::logincallback、
 * index/event/LoginEvent.php、common/model/User.php 的 checkLogin/infoData/register/login）。
 *
 * 旧版依赖的 extend/login（ThinkOauth）在仓库与备份中都缺失：子进程用 oauth_golden_thinkoauth.php 的替身代替，它不发网络请求，
 * 按用例计划直接给出"换令牌是否成功"、openid 与用户信息。因此黄金锁定的是**拿到 openid 之后**旧代码的行为：改绑、自动注册、
 * 凭 openid 登录的返回与写库效果；QQ/微信协议本身（授权、换令牌、取用户信息的请求）旧版无从对照，见 34 文档第 3 节。
 *
 * 每个用例：子进程（oauth_golden_child.php，php-cgi）清空并写入 mac_user 种子、按用例设置登录态（会员用 cookie，checkLogin 照常校验）与
 * 配置覆盖，调用 logincallback($type, $code)，记录返回（json 数据，或 error/redirect 的状态与数据）、写出的 cookie 名称、之后的全部
 * mac_user 行（指定列）。
 *
 * 流程：外部管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入仓库 install.sql → 逐个用例执行 → 输出 JSON → 删除一次性库与临时目录。
 * 不修改任何旧 PHP 文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，不访问网络。需要仓库根目录下的 vendor/
 * （按 AGENTS.md 从备份恢复）。
 *
 * 管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL（jdbc:mysql://host:port/）、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；另需 PHP_CGI。
 * 用法（Git Bash，仓库根目录，PHP 7.3 与 8.0 各一份）：
 *   MACCMS_IT_ADMIN_URL=jdbc:mysql://127.0.0.1:3306/ MACCMS_IT_ADMIN_USERNAME=root MACCMS_IT_ADMIN_PASSWORD=<本机密码> \
 *   PHP_CGI=D:/Tools/php/7.3/php-cgi.exe D:/Tools/php/7.3/php.exe src/test/php/oauth_golden.php \
 *       > src/test/resources/integration/b26/php73-golden.json
 *   （8.0 同理，输出 php80-golden.json）
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
if (!is_file($repo . '/vendor/topthink/think-captcha/src/Captcha.php')) {
    fwrite(STDERR, "缺少 vendor/topthink/think-captcha（按 AGENTS.md 从备份恢复 vendor/）\n");
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

const QQ_NEW = '0123456789ABCDEF0123456789ABCDEF';
const QQ_BOUND = 'B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0';
const QQ_VIP = 'C1C1C1C1C1C1C1C1C1C1C1C1C1C1C1C1';
const QQ_COLLIDE = 'ABCDEF1234567890ABCDEF1234567890';
const QQ_SHORT = 'AB12C';
const WX_NEW = 'oAbCdEfGhIjKlMnOpQrStUvWxYz0';
const WX_DASH = 'o_6bmjrPTlm6_2sgVt7hMZOPfL2M';
const WX_OFF = 'oDisabledUser000000000000001';
const T0 = 1760000000;

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$work = sys_get_temp_dir() . DIRECTORY_SEPARATOR . 'maccms-b26-golden-' . bin2hex(random_bytes(4));
$exit = 0;
try {
    $pdo->exec("USE `$dbName`");
    foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
        $pdo->exec($sql);
    }
    $sqlMode = $pdo->query('SELECT @@SESSION.sql_mode')->fetchColumn();
    @mkdir($work . '/runtime', 0777, true);
    $ctx = ['repo' => $repo, 'php_cgi' => $phpCgi, 'work' => $work,
        'db' => [$host, $port, $dbName, $adminUser, $adminPassword]];
    $out = [
        'generator' => 'backend/src/test/php/oauth_golden.php',
        'php' => PHP_VERSION,
        'timezone' => 'PRC',
        'sqlMode' => $sqlMode,
        'remote_addr' => '127.0.0.1',
        'note' => 'ThinkOauth 为替身（oauth_golden_thinkoauth.php）。时间列原样记录，比对时按用例的 now 换算。'
            . 'member 用例的登录 cookie 是 user_id/user_name/user_check（md5(user_random-user_name-user_id-)）。',
        'cases' => [],
    ];
    foreach (cases() as $c) {
        if (getenv('GOLDEN_ONLY') && strpos($c['label'], getenv('GOLDEN_ONLY')) !== 0) {
            continue;
        }
        $out['cases'][] = run_case($ctx, $c);
    }
    echo json_encode($out, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE), "\n";
} catch (\Throwable $e) {
    fwrite(STDERR, 'failed: ' . get_class($e) . ' at ' . $e->getFile() . ':' . $e->getLine() . "\n");
    if (getenv('GOLDEN_DEBUG') === '1') {
        fwrite(STDERR, $e->getMessage() . "\n");
    }
    $exit = 1;
} finally {
    try {
        $pdo->exec("DROP DATABASE IF EXISTS `$dbName`");
    } catch (\Throwable $e) {
        fwrite(STDERR, "drop failed\n");
        $exit = 1;
    }
    rm_tree($work);
}
exit($exit);

// ====================================================================== 数据与用例

/** 会员 7 是登录会员；8 绑定 QQ_BOUND；9 绑定 WX_OFF 且停用；10 绑定 QQ_VIP、VIP 组已过期；11 的用户名与 QQ_COLLIDE 前 10 位相同。 */
function seed_users()
{
    $base = ['user_pwd' => md5('x'), 'user_status' => 1, 'group_id' => 2, 'user_reg_time' => T0, 'user_reg_ip' => 16909060,
        'user_login_time' => T0 + 10, 'user_login_ip' => 16909061, 'user_login_num' => 3,
        'user_last_login_time' => T0 + 5, 'user_last_login_ip' => 16909062, 'user_openid_qq' => '', 'user_openid_weixin' => ''];
    return [
        ['user_id' => 7, 'user_name' => 'golden07', 'user_random' => 'r7'] + $base,
        ['user_id' => 8, 'user_name' => 'golden08', 'user_random' => 'r8', 'user_openid_qq' => QQ_BOUND] + $base,
        ['user_id' => 9, 'user_name' => 'golden09', 'user_random' => 'r9', 'user_status' => 0,
            'user_openid_weixin' => WX_OFF] + $base,
        ['user_id' => 10, 'user_name' => 'golden10', 'user_random' => 'r10', 'group_id' => 3, 'user_end_time' => T0,
            'user_openid_qq' => QQ_VIP] + $base,
        ['user_id' => 11, 'user_name' => 'ABCDEF1234', 'user_random' => 'r11'] + $base,
    ];
}

function qq_ok($nick = 'QQ昵称')
{
    return ['ret' => 0, 'msg' => '', 'nickname' => $nick, 'figureurl_2' => 'http://qzapp.qlogo.cn/x'];
}

function wx_ok($nick = '微信昵称')
{
    return ['openid' => '', 'nickname' => $nick, 'headimgurl' => 'http://wx.qlogo.cn/x'];
}

function c($label, $type, $openid, array $extra = [])
{
    return $extra + ['label' => $label, 'type' => $type, 'code' => 'golden-code', 'as' => 0, 'config' => [],
            'users' => seed_users(), 'oauth' => ['token' => true, 'openid' => $openid,
                'info' => $type === 'qq' ? qq_ok() : wx_ok()]];
}

function cases()
{
    $now = time();
    $ipLimitSeed = seed_users();
    $ipLimitSeed[] = ['user_id' => 12, 'user_name' => 'golden12', 'user_pwd' => md5('x'), 'user_status' => 1, 'group_id' => 2,
        'user_reg_time' => $now - 60, 'user_reg_ip' => 2130706433, 'user_random' => 'r12'];
    $sevenHasQq = seed_users();
    $sevenHasQq[0]['user_openid_qq'] = QQ_NEW;
    return [
        c('qq-guest-new', 'qq', QQ_NEW),
        c('qq-guest-bound', 'qq', QQ_BOUND),
        c('qq-guest-vip-expired', 'qq', QQ_VIP),
        c('weixin-guest-new', 'weixin', WX_NEW),
        c('weixin-guest-underscore', 'weixin', WX_DASH),
        c('qq-guest-name-taken', 'qq', QQ_COLLIDE),
        c('qq-guest-short', 'qq', QQ_SHORT),
        c('qq-guest-reg-closed', 'qq', QQ_NEW, ['config' => ['user.reg_open' => '0']]),
        c('qq-guest-reg-closed-bound', 'qq', QQ_BOUND, ['config' => ['user.reg_open' => '0']]),
        c('qq-guest-user-off', 'qq', QQ_NEW, ['config' => ['user.status' => '0']]),
        c('qq-guest-ip-limit', 'qq', QQ_NEW, ['config' => ['user.reg_num' => '1'], 'users' => $ipLimitSeed]),
        c('qq-guest-filter', 'qq', QQ_NEW, ['config' => ['user.filter_words' => 'xyz,5678']]),
        c('qq-guest-reg-points-status', 'qq', QQ_NEW, ['config' => ['user.reg_points' => '15', 'user.reg_status' => '0']]),
        c('weixin-guest-disabled', 'weixin', WX_OFF),
        c('qq-member-bind-taken', 'qq', QQ_BOUND, ['as' => 7]),
        c('qq-member-bind-same', 'qq', QQ_NEW, ['as' => 7, 'users' => $sevenHasQq]),
        c('weixin-member-bind', 'weixin', WX_NEW, ['as' => 7]),
        c('qq-member-rebind', 'qq', QQ_BOUND, ['as' => 7, 'users' => $sevenHasQq]),
        c('qq-userinfo-failed', 'qq', QQ_NEW, ['oauth' => ['token' => true, 'openid' => QQ_NEW,
            'info' => ['ret' => 1002, 'msg' => 'invalid openid']]]),
        c('weixin-userinfo-failed', 'weixin', WX_NEW, ['oauth' => ['token' => true, 'openid' => WX_NEW,
            'info' => ['errcode' => 40003, 'errmsg' => 'invalid openid']]]),
        c('qq-token-failed', 'qq', QQ_NEW, ['oauth' => ['token' => false, 'openid' => QQ_NEW, 'info' => qq_ok()]]),
        c('qq-missing-code', 'qq', QQ_NEW, ['code' => '']),
    ];
}

function run_case($ctx, $c)
{
    $cookie = '';
    if ($c['as']) {
        foreach ($c['users'] as $u) {
            if ($u['user_id'] === $c['as']) {
                $cookie = 'user_id=' . $u['user_id'] . '; user_name=' . $u['user_name'] . '; user_check='
                    . md5($u['user_random'] . '-' . $u['user_name'] . '-' . $u['user_id'] . '-');
            }
        }
    }
    $args = ['label' => $c['label'], 'type' => $c['type'], 'code' => $c['code'], 'as' => $c['as'], 'users' => $c['users'],
        'config' => $c['config'], 'oauth' => $c['oauth']];
    $child = run_child($ctx, $args, $cookie);
    $rec = ['label' => $c['label'], 'type' => $c['type'], 'code' => $c['code'], 'as' => $c['as'],
        'configOverrides' => (object)$c['config'], 'oauth' => $c['oauth'], 'seedUsers' => $c['users']];
    foreach (['returned', 'response', 'fatal', 'shutdownFatal', 'setupError', 'output', 'cookies', 'userInfoCalls', 'after',
                 'readError'] as $k) {
        if (array_key_exists($k, $child)) {
            $rec[$k] = $child[$k];
        }
    }
    $rec['now'] = $child['now'] ?? null;
    return $rec;
}

function run_child($ctx, $args, $cookie)
{
    list($host, $port, $db, $user, $password) = $ctx['db'];
    $child = __DIR__ . DIRECTORY_SEPARATOR . 'oauth_golden_child.php';
    $env = [
        'GOLDEN_ARGS' => json_encode($args, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE),
        'GOLDEN_RUNTIME_PATH' => $ctx['work'],
        'GOLDEN_DB_HOST' => $host, 'GOLDEN_DB_PORT' => $port, 'GOLDEN_DB_NAME' => $db, 'GOLDEN_DB_USER' => $user,
        'GOLDEN_DB_PASSWORD' => $password,
        'REDIRECT_STATUS' => '200',
        'GATEWAY_INTERFACE' => 'CGI/1.1',
        'SERVER_PROTOCOL' => 'HTTP/1.1',
        'SERVER_SOFTWARE' => 'golden',
        'SERVER_NAME' => 'www.example.test',
        'SERVER_PORT' => '80',
        'HTTP_HOST' => 'www.example.test',
        'HTTP_USER_AGENT' => 'Mozilla/5.0 (golden)',
        'HTTP_COOKIE' => $cookie,
        'REQUEST_METHOD' => 'GET',
        'SCRIPT_FILENAME' => $child,
        'SCRIPT_NAME' => '/index.php',
        'PHP_SELF' => '/index.php',
        'REQUEST_URI' => '/index.php/user/logincallback/type/' . $args['type'],
        'QUERY_STRING' => '',
        'REMOTE_ADDR' => '127.0.0.1',
        'REMOTE_PORT' => '50000',
        'DOCUMENT_ROOT' => dirname($child),
        'SystemRoot' => getenv('SystemRoot') ?: 'C:\\Windows',
        'TEMP' => $ctx['work'],
        'TMP' => $ctx['work'],
        // error() 在 ajax 请求下返回 JSON（code/msg/data/url/wait），便于记录提示原文
        'HTTP_X_REQUESTED_WITH' => 'XMLHttpRequest',
    ];
    $cmd = escapeshellarg($ctx['php_cgi']) . ' -d session.save_path=' . escapeshellarg($ctx['work'] . '/runtime')
        . ' -d display_errors=0 -d error_reporting=0 -d session.gc_probability=0';
    $proc = proc_open($cmd, [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['pipe', 'w']], $pipes, $ctx['repo'], $env,
        ['bypass_shell' => true]);
    if (!is_resource($proc)) {
        throw new RuntimeException('php-cgi');
    }
    fclose($pipes[0]);
    $raw = stream_get_contents($pipes[1]);
    stream_get_contents($pipes[2]);
    fclose($pipes[1]);
    fclose($pipes[2]);
    proc_close($proc);
    $sep = strpos($raw, "\r\n\r\n");
    $body = $sep === false ? $raw : substr($raw, $sep + 4);
    $json = json_decode($body, true);
    if (!is_array($json)) {
        if (getenv('GOLDEN_DEBUG') === '1') {
            fwrite(STDERR, $raw . "\n");
        }
        throw new RuntimeException('child output is not JSON for ' . $args['label']);
    }
    return $json;
}

// ====================================================================== 工具

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
