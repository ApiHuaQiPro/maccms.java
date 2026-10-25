<?php
/*
 * B08（MACC-32）会员登录态 cookie、会员组到期、验证码与频率限制的 PHP 黄金样本生成脚本（契约见
 * docs/architecture/17-b08-member-session-captcha.md 第 3 节）。
 *
 * 流程：用外部提供的管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入仓库 application/install/sql/install.sql 的表结构与自带的
 * 三个会员组 → 写入本脚本 users() 中的合成会员 → 逐个用例以 php-cgi 执行 member_golden_child.php（真实的 HTTP_COOKIE 解析 +
 * 旧代码本身）→ 输出 JSON → 删除一次性库与临时目录。
 * 不修改任何旧 PHP 文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，不访问网络。
 * 需要仓库根目录下的 vendor/（不在 git 中，按 AGENTS.md 从备份恢复；只用其中的 think-captcha）。
 *
 * 管理账号只经环境变量传入（与 Java 集成测试相同）：MACCMS_IT_ADMIN_URL（如 jdbc:mysql://127.0.0.1:3306/）、
 * MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD。另需 PHP_CGI（与运行本脚本的 php 同版本的 php-cgi 可执行文件）。
 *
 * 用法（仓库根目录，PHP 8.0 与 7.3 各一份）：
 *   PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe backend/src/test/php/member_golden.php \
 *       > backend/src/test/resources/member/php80-golden.json
 */

error_reporting(E_ERROR | E_PARSE);
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

/** 与 Java 集成测试相同的拆分：去掉以 -- 开头的行，按"分号 + 空白 + 换行"切分。 */
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

/**
 * 合成会员（Java 的 MemberGoldenMySqlIT 按 golden.users 写入同样的行）。user_end_time 为 0 或相对值的用例在子进程里按当时的秒设置。
 * 用户名按库里存放的字节给出：前台注册只允许字母数字（User.php:150），其余形态来自后台或第三方登录，用来覆盖编码边界。
 */
function users()
{
    return [
        [1, 2, 'alice', 1, 'r1alice'],
        [2, 2, '中文会员', 1, 'r2zh'],
        [3, 2, 'a&amp;b', 1, 'r3amp'],
        [4, 2, 'a&b', 1, 'r4rawamp'],
        [5, 3, 'vipnow', 1, 'r5vip'],
        [8, 2, 'grp2past', 1, 'r8g2'],
        [9, 2, 'disabled', 0, 'r9off'],
        [10, 2, 'neverlogin', 1, ''],
        [11, 2, "o'neil", 1, 'r11apos'],
        [12, 2, 'q"x', 1, 'r12quot'],
        [13, 2, 'a b', 1, 'r13space'],
        [14, 2, 'a+b', 1, 'r14plus'],
        [15, 2, '100%', 1, 'r15pct'],
        [16, 2, 'x<y', 1, 'r16lt'],
        [17, 2, 'Bob', 1, 'r17bob'],
        [18, 2, 'pad ', 1, 'r18pad'],
        [19, 9, 'nogroup', 1, 'r19nog'],
        [20, 2, 'a%41', 1, 'r20pct41'],
        [21, 2, 'magic', 1, 'b08ma4ctsl'],
        [22, 3, 'vipzero', 1, 'r22vz'],
        [23, 2, '0', 1, 'r23zero'],
        [30, 3, 'exp_m1', 1, 'r30'],
        [31, 3, 'exp_0', 1, 'r31'],
        [32, 3, 'exp_p1', 1, 'r32'],
        [33, 2, 'exp_g2', 1, 'r33'],
        [34, 1, 'exp_g1', 1, 'r34'],
        [35, 9, 'exp_g9', 1, 'r35'],
    ];
}

const USER_PASSWORD = 'b08-pass';

function user_check($random, $name, $id)
{
    return md5($random . '-' . $name . '-' . $id . '-');
}

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$work = sys_get_temp_dir() . '/maccms-b08-golden-' . bin2hex(random_bytes(4));
$exit = 0;
try {
    $pdo->exec("USE `$dbName`");
    foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
        $pdo->exec($sql);
    }
    $insert = $pdo->prepare('INSERT INTO mac_user (user_id, group_id, user_name, user_pwd, user_status, user_random,'
        . ' user_end_time) VALUES (?, ?, ?, ?, ?, ?, ?)');
    foreach (users() as $u) {
        $insert->execute([$u[0], $u[1], $u[2], md5(USER_PASSWORD), $u[3], $u[4], 0]);
    }
    @mkdir($work . '/runtime', 0777, true);
    $ctx = ['repo' => $repo, 'php_cgi' => $phpCgi, 'work' => $work, 'db' => [$host, $port, $dbName, $adminUser,
        $adminPassword]];
    $out = [
        'generator' => 'backend/src/test/php/member_golden.php',
        'php_version' => PHP_VERSION,
        'user_password' => USER_PASSWORD,
        'users' => array_map(function ($u) {
            return ['user_id' => $u[0], 'group_id' => $u[1], 'user_name' => bin2hex($u[2]), 'user_status' => $u[3],
                'user_random' => $u[4]];
        }, users()),
        'parse' => parse_cases($ctx),
        'setcookie' => setcookie_cases($ctx),
        'label_user' => label_user_cases($ctx),
        'java_issued' => java_issued_cases($ctx),
        'login' => login_cases($ctx),
        'captcha_check' => captcha_check_cases($ctx),
        'verify_check' => verify_check_cases($ctx),
        'captcha_image' => captcha_image_cases($ctx),
        'timespan' => timespan_cases($ctx),
        'expire' => expire_cases($ctx),
        'expiry_repro' => expiry_repro_cases($ctx),
    ];
    echo json_encode($out, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE), "\n";
} catch (\Throwable $e) {
    // 不输出 message（可能含连接信息），只给类型与位置
    fwrite(STDERR, 'failed: ' . get_class($e) . ' at ' . $e->getFile() . ':' . $e->getLine() . "\n");
    $exit = 1;
} finally {
    $pdo->exec("DROP DATABASE `$dbName`");
    rm_tree($work);
}
exit($exit);

/**
 * 以 php-cgi 执行子进程的一个操作，返回其 JSON 正文（解码后）。验证码与计时用例要求"构造记录与校验在同一秒"，子进程报告
 * same_second=false 时换一个新进程重跑（最多 5 次；B09 重新生成时机器较慢，曾有用例跨秒）。
 */
function run_child($ctx, $op, $args, $cookieHeader = null)
{
    for ($i = 1; ; $i++) {
        $r = run_child_once($ctx, $op, $args, $cookieHeader);
        if (!in_array($op, ['captcha_check', 'verify_check', 'timespan'], true) || !empty($r['same_second']) || $i >= 5) {
            return $r;
        }
    }
}

function run_child_once($ctx, $op, $args, $cookieHeader)
{
    list($host, $port, $db, $user, $password) = $ctx['db'];
    $child = __DIR__ . DIRECTORY_SEPARATOR . 'member_golden_child.php';
    $env = [
        'GOLDEN_OP' => $op,
        'GOLDEN_ARGS' => json_encode($args, JSON_UNESCAPED_SLASHES),
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
        'REQUEST_METHOD' => 'GET',
        'SCRIPT_FILENAME' => $child,
        'SCRIPT_NAME' => '/index.php',
        'PHP_SELF' => '/index.php',
        'REQUEST_URI' => '/index.php',
        'QUERY_STRING' => '',
        'REMOTE_ADDR' => '127.0.0.1',
        'REMOTE_PORT' => '50000',
        'DOCUMENT_ROOT' => dirname($child),
        'SystemRoot' => getenv('SystemRoot') ?: 'C:\\Windows',
        'TEMP' => $ctx['work'],
        'TMP' => $ctx['work'],
    ];
    if ($cookieHeader !== null) {
        $env['HTTP_COOKIE'] = $cookieHeader;
    }
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
            // 只用于排查：子进程输出可能含路径，不进入黄金样本
            fwrite(STDERR, $raw . "\n");
        }
        throw new RuntimeException('child output is not JSON for op ' . $op);
    }
    unset($json['op']);
    return $json;
}

function cookie_header(array $pairs)
{
    $parts = [];
    foreach ($pairs as $name => $value) {
        $parts[] = $name . '=' . $value;
    }
    return implode('; ', $parts);
}

/** 浏览器收到 PHP 签发的 cookie 后回送的形式：值就是 setcookie 写出的 urlencode 结果。 */
function login_cookie_header($id, $name, $random)
{
    return cookie_header(['user_id' => $id, 'user_name' => urlencode($name),
        'user_check' => user_check($random, $name, $id)]);
}

function find_user($id)
{
    foreach (users() as $u) {
        if ($u[0] === $id) {
            return $u;
        }
    }
    throw new RuntimeException('no user ' . $id);
}

function parse_cases($ctx)
{
    $headers = [
        'a=1; a=2', 'a=1;a=2', ' a=1', 'a b=1', 'a.b=1', 'a[]=1', 'a[x]=1; a[y]=2', 'a=%E4%B8%AD', 'a=%zz', 'a=+',
        'a=%2B', 'a', '=1', 'a=1=2', 'a="q"', 'a=1, b=2', 'user_id=1;  user_name=x', 'a=%00b', "a=\xff\xfe",
        'a=%2541', 'user_id=1;user_id=2;user_name=a', 'a=1;;b=2', 'a=; b=2', 'A=1; a=2', "a=1;\tb=2", 'a=x%',
        'a=%e4%b8%ad', '[a]=1', 'a]=1', 'a[=1', 'a[b=1', ' a = 1 ',
        'a=1; a[]=2', 'a[]=1; a=2', '%61=1', 'a%20b=1', 'a%5B%5D=1', 'a%3D=1', "\ta=1", 'a.b c=1; a_b_c=2',
    ];
    $out = [];
    foreach ($headers as $h) {
        $r = run_child($ctx, 'parse', [], $h);
        $out[] = ['header' => bin2hex($h), 'cookies' => $r['cookies_seen'], 'error' => isset($r['error']) ? $r['error'] : null];
    }
    return $out;
}

function setcookie_cases($ctx)
{
    $values = ['中文会员', 'a&amp;b', 'a b', 'a+b', '100%', '~*-._!()\'', '<>"', "\xF0\x9F\x98\x80", 'Bob', '0', '',
        'x;y', "x,y", 'x=y', "tab\tx"];
    $calls = [];
    foreach ($values as $v) {
        $calls[] = ['name' => 'user_name', 'value' => bin2hex($v), 'option' => ['expire' => 2592000]];
    }
    // user_check 加 HttpOnly 时 PHP 8.0/7.3 写出的字节（新版签发 user_check 的有意差异 D-B08-7，以 PHP 自己的写法为准）
    $calls[] = ['name' => 'user_check', 'value' => bin2hex(md5('-b08-1-')),
        'option' => ['expire' => 2592000, 'httponly' => true]];
    $out = [];
    foreach ($calls as $call) {
        $r = run_child($ctx, 'setcookie', ['calls' => [$call]]);
        $out[] = ['call' => $call, 'set_cookies' => isset($r['set_cookies']) ? $r['set_cookies'] : null,
            'request_time' => isset($r['request_time']) ? $r['request_time'] : null,
            'error' => isset($r['error']) ? $r['error'] : null];
    }
    // 注销（User.php:411-419：cookie(name, null) → Cookie::delete）与 label_user 失败时的回写（All.php:135-138，默认选项、expire=0；
    // cookie('user_check','') 在助手里是"读取"，并不写出）
    foreach ([
        'logout' => array_map(function ($n) {
            return ['name' => $n, 'value' => null];
        }, ['user_id', 'user_name', 'group_id', 'group_name', 'user_check', 'user_portrait']),
        'guest_reset' => [['name' => 'user_id', 'value' => bin2hex('0')],
            ['name' => 'user_name', 'value' => bin2hex('游客')], ['name' => 'user_check', 'value' => '']],
    ] as $label => $calls2) {
        $r = run_child($ctx, 'setcookie', ['calls' => $calls2]);
        $out[] = ['label' => $label, 'set_cookies' => $r['set_cookies'] ?? null,
            'request_time' => $r['request_time'] ?? null, 'error' => $r['error'] ?? null];
    }
    return $out;
}

function label_user_cases($ctx)
{
    $cases = [];
    $valid = function ($id) {
        $u = find_user($id);
        return login_cookie_header($u[0], $u[2], $u[4]);
    };
    foreach ([1, 2, 3, 4, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23] as $id) {
        $cases[] = ['label' => 'valid-' . $id, 'header' => $valid($id), 'row' => $id];
    }
    $alice = find_user(1);
    $check = user_check($alice[4], 'alice', 1);
    $tampered = substr($check, 0, -1) . ($check[31] === '0' ? '1' : '0');
    $variants = [
        'tampered-check' => ['user_id' => '1', 'user_name' => 'alice', 'user_check' => $tampered],
        'upper-check' => ['user_id' => '1', 'user_name' => 'alice', 'user_check' => strtoupper($check)],
        'check-trailing-space' => ['user_id' => '1', 'user_name' => 'alice', 'user_check' => $check . '%20'],
        'check-plus' => ['user_id' => '1', 'user_name' => 'alice', 'user_check' => $check . '+'],
        'check-pct-encoded' => ['user_id' => '1', 'user_name' => 'alice', 'user_check' => '%' . bin2hex($check[0]) . substr($check, 1)],
        'check-double-encoded' => ['user_id' => '1', 'user_name' => 'alice', 'user_check' => '%25' . bin2hex($check[0]) . substr($check, 1)],
        'wrong-id' => ['user_id' => '2', 'user_name' => 'alice', 'user_check' => $check],
        'id-leading-zero' => ['user_id' => '01', 'user_name' => 'alice', 'user_check' => $check],
        'id-suffix' => ['user_id' => '1abc', 'user_name' => 'alice', 'user_check' => $check],
        'id-decimal' => ['user_id' => '1.0', 'user_name' => 'alice', 'user_check' => $check],
        'id-decimal-frac' => ['user_id' => '1.5', 'user_name' => 'alice', 'user_check' => $check],
        'id-exponent' => ['user_id' => '1e0', 'user_name' => 'alice', 'user_check' => $check],
        'id-plus' => ['user_id' => '%2B1', 'user_name' => 'alice', 'user_check' => $check],
        'id-space-encoded' => ['user_id' => '%201', 'user_name' => 'alice', 'user_check' => $check],
        'id-double-encoded' => ['user_id' => '%2531', 'user_name' => 'alice', 'user_check' => $check],
        'id-hex' => ['user_id' => '0x1', 'user_name' => 'alice', 'user_check' => $check],
        'id-zero' => ['user_id' => '0', 'user_name' => 'alice', 'user_check' => $check],
        'id-empty' => ['user_id' => '', 'user_name' => 'alice', 'user_check' => $check],
        'id-zero-point-five-e1' => ['user_id' => '0.1e1', 'user_name' => 'alice', 'user_check' => $check],
        'id-huge' => ['user_id' => '1e999', 'user_name' => 'alice', 'user_check' => $check],
        'name-upper' => ['user_id' => '1', 'user_name' => 'ALICE', 'user_check' => $check],
        'name-trailing-space' => ['user_id' => '1', 'user_name' => 'alice%20', 'user_check' => $check],
        'name-leading-space' => ['user_id' => '1', 'user_name' => '%20alice', 'user_check' => $check],
        'name-double-encoded' => ['user_id' => '1', 'user_name' => '%2561lice', 'user_check' => $check],
        'name-emoji' => ['user_id' => '1', 'user_name' => 'alice%F0%9F%98%80', 'user_check' => $check],
        'name-invalid-utf8' => ['user_id' => '1', 'user_name' => 'alice%FF', 'user_check' => $check],
        'name-zero' => ['user_id' => '23', 'user_name' => '0', 'user_check' => user_check('r23zero', '0', 23)],
        'check-zero' => ['user_id' => '1', 'user_name' => 'alice', 'user_check' => '0'],
        'missing-check' => ['user_id' => '1', 'user_name' => 'alice'],
        'missing-name' => ['user_id' => '1', 'user_check' => $check],
        'missing-id' => ['user_name' => 'alice', 'user_check' => $check],
        'magic-0e0' => ['user_id' => '21', 'user_name' => 'magic', 'user_check' => '0e0'],
        'magic-00' => ['user_id' => '21', 'user_name' => 'magic', 'user_check' => '00'],
        'magic-0e999' => ['user_id' => '21', 'user_name' => 'magic', 'user_check' => '0e999'],
        'magic-0.0' => ['user_id' => '21', 'user_name' => 'magic', 'user_check' => '0.0'],
        'magic-1e0' => ['user_id' => '21', 'user_name' => 'magic', 'user_check' => '1e0'],
        'neverlogin-forged' => ['user_id' => '10', 'user_name' => 'neverlogin', 'user_check' => md5('-neverlogin-10-')],
        'amp-decoded-name' => ['user_id' => '3', 'user_name' => 'a%26b', 'user_check' => user_check('r3amp', 'a&amp;b', 3)],
        'quot-escaped-name' => ['user_id' => '12', 'user_name' => 'q%26quot%3Bx', 'user_check' => user_check('r12quot', 'q"x', 12)],
    ];
    foreach ($variants as $label => $pairs) {
        $cases[] = ['label' => $label, 'header' => cookie_header($pairs), 'row' => (int)($pairs['user_id'] ?? 1)];
    }
    $raw = [
        'duplicate-user-id-first' => 'user_id=1; user_id=2; user_name=alice; user_check=' . $check,
        'duplicate-user-id-second' => 'user_id=2; user_id=1; user_name=alice; user_check=' . $check,
        'duplicate-check' => 'user_id=1; user_name=alice; user_check=' . $tampered . '; user_check=' . $check,
        'array-user-id' => 'user_id[]=1; user_name=alice; user_check=' . $check,
        'array-check' => 'user_id=1; user_name=alice; user_check[a]=' . $check,
        'no-space-separators' => 'user_id=1;user_name=alice;user_check=' . $check,
        'extra-cookies' => 'PHPSESSID=abc; user_id=1; group_id=3; group_name=VIP; user_name=alice; user_check=' . $check,
        'quoted-check' => 'user_id=1; user_name=alice; user_check="' . $check . '"',
    ];
    foreach ($raw as $label => $h) {
        $cases[] = ['label' => $label, 'header' => $h, 'row' => 1];
    }
    $out = [];
    foreach ($cases as $c) {
        $r = run_child($ctx, 'label_user', ['row' => $c['row']], $c['header']);
        $out[] = ['label' => $c['label'], 'header' => bin2hex($c['header'])] + $r;
    }
    // 会员组到期边界：在子进程里把 user_end_time 设为"当前秒 + offset"后立即检查（同一秒内完成，否则重试）
    foreach ([[5, 3, -1], [5, 3, 0], [5, 3, 1], [5, 3, -86400], [8, 2, -1], [19, 9, -1], [19, 9, 1]] as $e) {
        list($id, $group, $offset) = $e;
        $u = find_user($id);
        $r = run_child($ctx, 'label_user', ['row' => $id, 'end_offset' => ['user_id' => $id, 'offset' => $offset,
            'group_id' => $group]], login_cookie_header($u[0], $u[2], $u[4]));
        $out[] = ['label' => 'expiry-' . $id . '-g' . $group . '-' . $offset, 'end_offset' => $offset,
            'group_before' => $group, 'header' => bin2hex(login_cookie_header($u[0], $u[2], $u[4]))] + $r;
    }
    return $out;
}

/**
 * Java → PHP：backend/src/test/resources/member/java-issued.json（JavaIssuedCookiesTest 用 MemberCookies.login 为上面的合成会员签发、
 * 按浏览器回送形式拼好的 Cookie 头）原样交给旧 label_user。必须在 login_cases 之前执行（登录会改写 user_random）。
 */
function java_issued_cases($ctx)
{
    $file = $ctx['repo'] . '/backend/src/test/resources/member/java-issued.json';
    if (!is_file($file)) {
        return null;
    }
    $out = [];
    foreach (json_decode(file_get_contents($file), true) as $e) {
        $r = run_child($ctx, 'label_user', ['row' => $e['user_id']], hex2bin($e['header']));
        $out[] = ['user_id' => $e['user_id'], 'header' => $e['header'], 'member' => $r['member'],
            'replayed_user_id' => $r['user_id'], 'group_group_id' => $r['group_group_id'],
            'error' => isset($r['error']) ? $r['error'] : null];
    }
    return $out;
}

/** 真实登录（login_verify 关闭）后，用 PHP 签发的 cookie 原样回送给 label_user，记录 PHP 自己认不认。 */
function login_cases($ctx)
{
    $out = [];
    foreach ([[1, 'alice'], [2, '中文会员'], [3, 'a&b'], [13, 'a b'], [14, 'a+b'], [15, '100%'], [17, 'bob'],
                 [18, 'pad'], [20, 'a%41'], [22, 'vipzero']] as $l) {
        list($id, $input) = $l;
        $r = run_child($ctx, 'login', ['user_name' => $input, 'user_pwd' => USER_PASSWORD, 'row' => $id]);
        $pairs = [];
        foreach ($r['set_cookies'] as $hex) {
            $line = hex2bin($hex);
            $nv = explode(';', $line, 2)[0];
            $eq = strpos($nv, '=');
            $pairs[substr($nv, 0, $eq)] = substr($nv, $eq + 1);
        }
        $header = cookie_header($pairs);
        $check = run_child($ctx, 'label_user', ['row' => $id], $header);
        $out[] = ['user_id' => $id, 'input_name' => bin2hex($input), 'code' => $r['code'],
            'set_cookies' => $r['set_cookies'], 'request_time' => $r['request_time'], 'row_after' => $r['row_after'],
            'replay_header' => bin2hex($header), 'replay' => $check];
    }
    return $out;
}

function captcha_check_cases($ctx)
{
    $s = function ($code, $age = 0, $id = '') {
        return ['code' => $code, 'age' => $age, 'id' => $id];
    };
    $i = function ($code, $id = '') {
        return ['code' => bin2hex($code), 'id' => $id];
    };
    $cases = [
        'correct-then-replay' => [$s('1234'), [$i('1234'), $i('1234')]],
        'wrong-then-correct' => [$s('1234'), [$i('0000'), $i('1234')]],
        'age-1800' => [$s('1234', 1800), [$i('1234')]],
        'age-1801' => [$s('1234', 1801), [$i('1234'), $i('1234')]],
        'letters-lower-input' => [$s('ABCD'), [$i('abcd')]],
        'letters-lower-stored' => [$s('abcd'), [$i('ABCD')]],
        'leading-space' => [$s('1234'), [$i(' 1234')]],
        'trailing-space' => [$s('1234'), [$i('1234 ')]],
        'input-zero' => [$s('0'), [$i('0')]],
        'input-empty' => [$s('1234'), [$i('')]],
        'leading-zero-code' => [$s('0123'), [$i('0123')]],
        'nothing-stored' => [null, [$i('1234')]],
        'other-slot' => [$s('1234', 0, 'x'), [$i('1234'), $i('1234', 'x')]],
        'fullwidth-digits' => [$s('1234'), [$i('１２３４')]],
        // 中文验证码（useZh）：记录同样是 authcode(strtoupper(码))，strtoupper 不改多字节字符
        'zh-correct-then-replay' => [$s('们以我到'), [$i('们以我到'), $i('们以我到')]],
        'zh-wrong-then-correct' => [$s('们以我到'), [$i('们以我他'), $i('们以我到')]],
        'zh-age-1800' => [$s('们以我到', 1800), [$i('们以我到')]],
        'zh-age-1801' => [$s('们以我到', 1801), [$i('们以我到')]],
        'zh-traditional' => [$s('们以我到'), [$i('們以我到')]],
        'zh-trailing-space' => [$s('们以我到'), [$i('们以我到 ')]],
        'zh-partial' => [$s('们以我到'), [$i('们以我')]],
        'zh-other-slot' => [$s('们以我到', 0, 'x'), [$i('们以我到'), $i('们以我到', 'x')]],
    ];
    $out = [];
    foreach ($cases as $label => $c) {
        $r = run_child($ctx, 'captcha_check', ['stored' => $c[0], 'inputs' => $c[1]]);
        $out[] = ['label' => $label, 'stored' => $c[0], 'inputs' => $c[1]] + $r;
    }
    return $out;
}

/**
 * 经过旧控制器 Verify::check($verify, $id)（application/index/controller/Verify.php:21-29）的校验：旧代码接收 id 却不传给
 * captcha_check，所以永远校验默认槽。stored 是放入会话的记录（槽 id），calls 是依次的 check 调用。
 */
function verify_check_cases($ctx)
{
    $cases = [
        'default-slot-ignores-id' => [['code' => '1234', 'id' => ''], [['code' => '1234', 'id' => 'x']]],
        'named-slot-unreachable' => [['code' => '1234', 'id' => 'x'], [['code' => '1234', 'id' => 'x'],
            ['code' => '1234', 'id' => '']]],
        'default-slot-replay' => [['code' => '1234', 'id' => ''], [['code' => '1234', 'id' => ''],
            ['code' => '1234', 'id' => '']]],
        'wrong-keeps-default' => [['code' => '1234', 'id' => ''], [['code' => '0000', 'id' => 'x'],
            ['code' => '1234', 'id' => 'x']]],
        'zh-default-slot' => [['code' => '们以我到', 'id' => ''], [['code' => '们以我到', 'id' => 'zh']]],
    ];
    $out = [];
    foreach ($cases as $label => $c) {
        $calls = array_map(function ($x) {
            return ['code' => bin2hex($x['code']), 'id' => $x['id']];
        }, $c[1]);
        $r = run_child($ctx, 'verify_check', ['stored' => $c[0], 'calls' => $calls]);
        $out[] = ['label' => $label, 'stored' => $c[0], 'calls' => $calls] + $r;
    }
    return $out;
}

function captcha_image_cases($ctx)
{
    $out = [];
    // zh-*：useZh=true。备份的 vendor 没有 assets/zhttfs/，按原样运行时 entry() 出错（zh-backup-vendor）；zh-ttfs-font 让 fontttf
    // 经 ../ttfs/ 指到自带的英文字体，只为得到中文分支的图片尺寸与会话记录（字形本身画不出中文）
    foreach (['repo-config' => [], 'length5-font20' => ['length' => 5, 'fontSize' => 20],
                 'fixed-size' => ['imageW' => 150, 'imageH' => 50], 'noise-curve' => ['useNoise' => true, 'useCurve' => true],
                 'zh-backup-vendor' => ['useZh' => true], 'zh-ttfs-font' => ['useZh' => true, 'fontttf' => '../ttfs/1.ttf']]
             as $label => $override) {
        $r = run_child($ctx, 'captcha_image', ['override' => $override, 'id' => '']);
        $out[] = ['label' => $label, 'override' => $override] + $r;
    }
    return $out;
}

function timespan_cases($ctx)
{
    $out = [];
    foreach ([null, 0, 1, 2, 3, 4, 100, -5] as $offset) {
        $r = run_child($ctx, 'timespan', ['key' => 'last_searchtime', 'offset' => $offset]);
        $out[] = $r;
    }
    return $out;
}

/**
 * B09 复现 B08 的 7.3 黄金 expiry-5-g3--1 记下 4 个 Set-Cookie 的原因（18 文档第 9 节）：label_user 用例为保证"写库与判定在同一秒"
 * 会在跨秒时于同一进程内重试，旧 harness 不清除上一轮的头，跨秒一次就多出一组 group_id/group_name。
 * straddle 强制首轮跨秒；legacy_harness 按 B08 原样累加。每种组合跑两次，确认可复现。
 */
function expiry_repro_cases($ctx)
{
    $u = find_user(5);
    $header = login_cookie_header($u[0], $u[2], $u[4]);
    $out = [];
    foreach (['legacy-straddle' => ['legacy_harness' => true, 'straddle' => true],
                 'fixed-straddle' => ['straddle' => true], 'fixed-plain' => []] as $label => $flags) {
        for ($i = 1; $i <= 2; $i++) {
            $r = run_child($ctx, 'label_user', ['row' => 5, 'end_offset' => ['user_id' => 5, 'offset' => -1,
                    'group_id' => 3]] + $flags, $header);
            $out[] = ['label' => $label . '-' . $i, 'flags' => $flags, 'attempts' => $r['attempts'],
                'request_time' => $r['request_time'], 'now' => $r['now'], 'same_second' => $r['same_second'],
                'group_group_id' => $r['group_group_id'], 'set_cookies' => $r['set_cookies'],
                'row_after' => $r['row_after'], 'error' => $r['error'] ?? null];
        }
    }
    return $out;
}

function expire_cases($ctx)
{
    $rows = [['user_id' => 30, 'group_id' => 3, 'offset' => -1], ['user_id' => 31, 'group_id' => 3, 'offset' => 0],
        ['user_id' => 32, 'group_id' => 3, 'offset' => 1], ['user_id' => 33, 'group_id' => 2, 'offset' => -1],
        ['user_id' => 34, 'group_id' => 1, 'offset' => -1], ['user_id' => 35, 'group_id' => 9, 'offset' => -1]];
    return run_child($ctx, 'expire', ['rows' => $rows]);
}
