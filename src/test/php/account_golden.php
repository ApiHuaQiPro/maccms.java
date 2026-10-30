<?php
/*
 * B09（MACC-33）会员账号的 PHP 黄金样本生成脚本（契约见 docs/architecture/18-b09-member-account.md 第 3、5 节）：
 *   - 03 草稿第 4.3 (1) 节逐入口的密码字节语义：注册、登录、资料改密、密保找回各自存入/比较的值；
 *   - 各入口的校验顺序、返回码与提示原文；短信/邮件"未配置"时的结果；
 *   - check_user_popedom 按会员组（游客 1、默认 2、VIP 3、组不存在）对分类详情/播放/下载/试看与文章积分的判定。
 *
 * 流程：外部管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入仓库 install.sql → 逐个用例以 php-cgi 执行 account_golden_child.php
 * （用例自带要写入的会员行与配置覆盖）→ 输出 JSON → 删除一次性库与临时目录。用例之间互不依赖：每个用例只用自己写入的行。
 * 不修改任何旧 PHP 文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，不访问网络。需要仓库根目录下的 vendor/。
 *
 * 管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；另需 PHP_CGI。
 * 用法（仓库根目录，PHP 8.0 与 7.3 各一份）：
 *   PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe src/test/php/account_golden.php \
 *       > src/test/resources/member/account-php80-golden.json
 */

error_reporting(E_ERROR | E_PARSE);
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

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$work = sys_get_temp_dir() . '/maccms-b09-golden-' . bin2hex(random_bytes(4));
$exit = 0;
try {
    $pdo->exec("USE `$dbName`");
    foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
        $pdo->exec($sql);
    }
    @mkdir($work . '/runtime', 0777, true);
    $ctx = ['repo' => $repo, 'php_cgi' => $phpCgi, 'work' => $work, 'db' => [$host, $port, $dbName, $adminUser,
        $adminPassword]];
    $out = [
        'generator' => 'backend/src/test/php/account_golden.php',
        'php_version' => PHP_VERSION,
        'remote_addr' => '127.0.0.1',
        'base_config' => base_config(),
        'cases' => [],
    ];
    foreach (cases() as $c) {
        // 只用于排查：GOLDEN_ONLY=<标签前缀> 只跑部分用例（这样生成的文件不完整，不能提交）
        if (getenv('GOLDEN_ONLY') && strpos($c['label'], getenv('GOLDEN_ONLY')) !== 0) {
            continue;
        }
        $args = $c + ['config' => []];
        $args['config'] = $args['config'] + base_config();
        $r = run_child($ctx, $args);
        $out['cases'][] = ['label' => $c['label'], 'args' => $args] + $r;
    }
    echo json_encode($out, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE), "\n";
} catch (\Throwable $e) {
    fwrite(STDERR, 'failed: ' . get_class($e) . ' at ' . $e->getFile() . ':' . $e->getLine() . "\n");
    $exit = 1;
} finally {
    $pdo->exec("DROP DATABASE `$dbName`");
    rm_tree($work);
}
exit($exit);

/** 每个用例都带上的配置（仓库值之上）：短信/邮件按"未配置"，注册不限 IP、不要验证码，权限判定开启。 */
function base_config()
{
    return ['user.status' => '1', 'user.reg_open' => '1', 'user.reg_status' => '1', 'user.reg_verify' => '0',
        'user.login_verify' => '0', 'user.reg_points' => '10', 'user.reg_num' => '0',
        'user.filter_words' => 'admin,cao,sex,xxx', 'user.reg_phone_sms' => '0', 'user.reg_email_sms' => '0',
        'user.invite_reg_points' => '10', 'user.email_white_hosts' => '', 'user.email_black_hosts' => '',
        'user.vod_points_type' => '0', 'user.art_points_type' => '0', 'sms.type' => '', 'email.type' => ''];
}

function h($s)
{
    return bin2hex($s);
}

/** 参数：名 → 原始字节（十六进制）。 */
function p(array $kv)
{
    $out = [];
    foreach ($kv as $k => $v) {
        $out[$k] = h($v);
    }
    return $out;
}

/** 要写入的会员行（文本列十六进制，数值列原样）。 */
function u($id, $name, $pwdHash, array $extra = [])
{
    $row = ['user_id' => $id, 'user_name' => h($name), 'user_pwd' => h($pwdHash), 'group_id' => 2,
        'user_status' => 1, 'user_random' => h('r' . $id)];
    foreach ($extra as $k => $v) {
        $row[$k] = is_string($v) ? h($v) : $v;
    }
    return $row;
}

function cases()
{
    $c = [];
    // ---------------- 注册：密码字节语义与校验顺序（common/model/User.php:119-267）
    $reg = function ($label, $name, $pwd, $pwd2, array $more = []) use (&$c) {
        $c[] = array_merge(['label' => 'reg-' . $label, 'op' => 'register', 'absent' => [h($name)],
            'params' => p(['user_name' => $name, 'user_pwd' => $pwd, 'user_pwd2' => $pwd2, 'verify' => '']),
            'rows' => [h($name)]], $more);
    };
    $reg('plain', 'regplain01', 'secret123', 'secret123');
    $reg('pwd-spaces', 'regspace01', ' sec ret ', ' sec ret ');
    $reg('pwd-tab-nl', 'regtabnl01', "\tsecret\n", "\tsecret\n");
    $reg('pwd-nul-trailing', 'regnul0001', "secret\0", "secret\0");
    $reg('pwd-nul-leading', 'regnul0002', "\0secret", "\0secret");
    $reg('pwd-vtab-cr', 'regvtab001', "\x0Bsecret\r", "\x0Bsecret\r");
    $reg('pwd-plus', 'regplus001', 'a+b+c1', 'a+b+c1');
    $reg('pwd-pct', 'regpct0001', '%41bc123', '%41bc123');
    $reg('pwd-pct-vs-decoded', 'regpct0002', '%41bc123', 'Abc123');
    $reg('pwd-html', 'reghtml001', "a&b<c>\"'1", "a&b<c>\"'1");
    $reg('pwd-zh', 'regzh00001', '中文密码1', '中文密码1');
    $reg('pwd-emoji', 'regemoji01', "\xF0\x9F\x98\x80pass12", "\xF0\x9F\x98\x80pass12");
    $reg('pwd-fullwidth-space', 'regfwsp001', "\xE3\x80\x80secret", "\xE3\x80\x80secret");
    $reg('pwd-zero', 'regzero001', '0', '0');
    $reg('pwd-empty', 'regempty01', '', '');
    $reg('pwd-spaces-only', 'regsponly1', '   ', '   ');
    $reg('pwd-mismatch', 'regmis0001', 'abc123', 'abc124');
    $reg('pwd-trailing-space-vs-plain', 'regtrail01', 'abc123 ', 'abc123');
    $reg('pwd-invalid-utf8', 'reginv0001', "\xFFabc123", "\xFFabc123");
    $reg('pwd-numeric-loose', 'regloose01', '10', '1e1');
    $reg('closed-reg-open', 'regclose01', 'secret123', 'secret123', ['config' => ['user.reg_open' => '0']]);
    $reg('closed-status', 'regclose02', 'secret123', 'secret123', ['config' => ['user.status' => '0']]);
    $reg('name-empty', '', 'secret123', 'secret123', ['absent' => [], 'rows' => []]);
    $c[] = ['label' => 'reg-exists-case-insensitive', 'op' => 'register', 'seed' => [u(150, 'existuser1', md5('x'))],
        'params' => p(['user_name' => 'EXISTUSER1', 'user_pwd' => 'secret123', 'user_pwd2' => 'secret123']),
        'rows' => [h('existuser1')]];
    $reg('name-non-alnum', 'bad_name01', 'secret123', 'secret123');
    $reg('name-pct-decoded', 'regname%31x', 'secret123', 'secret123', ['absent' => [h('regname1x')], 'rows' => [h('regname1x')]]);
    $reg('name-trailing-newline', "abcdefg%0A", 'secret123', 'secret123', ['absent' => [h("abcdefg\n"), h('abcdefg')], 'rows' => [h("abcdefg\n")]]);
    $reg('name-short', 'abc12', 'secret123', 'secret123');
    $reg('name-filter', 'admin1234', 'secret123', 'secret123');
    $reg('name-filter-case', 'ADMIN1234', 'secret123', 'secret123');
    $c[] = ['label' => 'reg-ip-limit', 'op' => 'register', 'absent' => [h('regiplim01')],
        'seed' => [u(151, 'ipseeduser', md5('x'), ['user_reg_ip' => 2130706433, 'reg_now' => 1])],
        'params' => p(['user_name' => 'regiplim01', 'user_pwd' => 'secret123', 'user_pwd2' => 'secret123']),
        'config' => ['user.reg_num' => '1'], 'rows' => [h('regiplim01')]];
    $reg('sms-phone', 'regsms0001', 'secret123', 'secret123', ['config' => ['user.reg_phone_sms' => '1'],
        'params' => p(['user_name' => 'regsms0001', 'user_pwd' => 'secret123', 'user_pwd2' => 'secret123',
            'ac' => 'phone', 'to' => '13800000000', 'code' => '123456'])]);
    $reg('sms-missing-code', 'regsms0002', 'secret123', 'secret123', ['config' => ['user.reg_phone_sms' => '1'],
        'params' => p(['user_name' => 'regsms0002', 'user_pwd' => 'secret123', 'user_pwd2' => 'secret123',
            'ac' => 'phone', 'to' => '13800000000'])]);
    $reg('email-black-host', 'regmail001', 'secret123', 'secret123', ['config' => ['user.reg_email_sms' => '1',
        'user.email_black_hosts' => "bad.com\nworse.com"], 'params' => p(['user_name' => 'regmail001',
        'user_pwd' => 'secret123', 'user_pwd2' => 'secret123', 'ac' => 'email', 'to' => 'a@bad.com', 'code' => '1'])]);
    $reg('email-white-host', 'regmail002', 'secret123', 'secret123', ['config' => ['user.reg_email_sms' => '1',
        'user.email_white_hosts' => 'good.com'], 'params' => p(['user_name' => 'regmail002',
        'user_pwd' => 'secret123', 'user_pwd2' => 'secret123', 'ac' => 'email', 'to' => 'a@other.com', 'code' => '1'])]);
    $reg('verify-wrong', 'regverif01', 'secret123', 'secret123', ['config' => ['user.reg_verify' => '1'],
        'captcha' => '1234', 'params' => p(['user_name' => 'regverif01', 'user_pwd' => 'secret123',
            'user_pwd2' => 'secret123', 'verify' => '0000'])]);
    $reg('verify-right', 'regverif02', 'secret123', 'secret123', ['config' => ['user.reg_verify' => '1'],
        'captcha' => '1234', 'params' => p(['user_name' => 'regverif02', 'user_pwd' => 'secret123',
            'user_pwd2' => 'secret123', 'verify' => '1234'])]);
    // D2 ② 的延伸：旧版带 user_openid_qq 即跳过验证码并把它写进新账号；新版忽略（18 文档 D-B09-2）
    $reg('openid-skips-verify', 'regopenid1', 'secret123', 'secret123', ['config' => ['user.reg_verify' => '1'],
        'params' => p(['user_name' => 'regopenid1', 'user_pwd' => 'secret123', 'user_pwd2' => 'secret123',
            'user_openid_qq' => 'FORGEDOPENID'])]);
    $reg('points-status', 'regpoint01', 'secret123', 'secret123', ['config' => ['user.reg_points' => '7',
        'user.reg_status' => '0']]);
    $c[] = ['label' => 'reg-inviter', 'op' => 'register', 'absent' => [h('reginvite1')],
        'seed' => [u(152, 'inviter001', md5('x'), ['user_pid' => 40, 'user_pid_2' => 30, 'user_points' => 5])],
        'params' => p(['user_name' => 'reginvite1', 'user_pwd' => 'secret123', 'user_pwd2' => 'secret123',
            'uid' => '152']), 'rows' => [h('reginvite1'), h('inviter001')]];

    // ---------------- 登录（User.php:321-393）：只看返回码、写入与签发的 cookie 名
    $loginUser = u(101, 'loginuser1', md5('pass word'), ['user_email' => 'login1@example.com',
        'user_openid_qq' => 'OPENIDLOGIN1', 'user_login_num' => 3]);
    $login = function ($label, $name, $pwd, array $more = []) use (&$c, $loginUser) {
        $c[] = array_merge(['label' => 'login-' . $label, 'op' => 'login', 'seed' => [$loginUser],
            'params' => p(['user_name' => $name, 'user_pwd' => $pwd, 'verify' => '']), 'rows' => [h('loginuser1')]],
            $more);
    };
    $login('ok-trim', 'loginuser1', ' pass word ');
    $login('wrong', 'loginuser1', 'pass  word');
    // D2 ①：旧版接受库里的 md5 值本身
    $login('stored-hash', 'loginuser1', md5('pass word'));
    $login('email', 'login1@example.com', 'pass word');
    $login('upper-name', 'LOGINUSER1', 'pass word');
    $login('pct-name', 'loginuser%31', 'pass word');
    $login('empty-pwd', 'loginuser1', '');
    $login('zero-pwd', 'loginuser1', '0');
    // D2 ②：旧版按 col + openid 直接登录
    $c[] = ['label' => 'login-openid', 'op' => 'login', 'seed' => [$loginUser],
        'params' => p(['openid' => 'OPENIDLOGIN1', 'col' => 'user_openid_qq']), 'rows' => [h('loginuser1')]];
    $c[] = ['label' => 'login-disabled', 'op' => 'login', 'seed' => [u(102, 'loginoff01', md5('pw'), ['user_status' => 0])],
        'params' => p(['user_name' => 'loginoff01', 'user_pwd' => 'pw']), 'rows' => [h('loginoff01')]];
    $c[] = ['label' => 'login-expired', 'op' => 'login', 'seed' => [u(103, 'loginexp01', md5('pw'), ['group_id' => 3,
        'end_offset' => -100])], 'params' => p(['user_name' => 'loginexp01', 'user_pwd' => 'pw']),
        'rows' => [h('loginexp01')]];
    $c[] = ['label' => 'login-vip-valid', 'op' => 'login', 'seed' => [u(106, 'loginvip01', md5('pw'), ['group_id' => 3,
        'end_offset' => 86400])], 'params' => p(['user_name' => 'loginvip01', 'user_pwd' => 'pw']),
        'rows' => [h('loginvip01')]];
    $c[] = ['label' => 'login-group-missing', 'op' => 'login', 'seed' => [u(104, 'loginnog01', md5('pw'),
        ['group_id' => 9, 'end_offset' => 86400])], 'params' => p(['user_name' => 'loginnog01', 'user_pwd' => 'pw']),
        'rows' => [h('loginnog01')]];
    $c[] = ['label' => 'login-pwd-tab-nul', 'op' => 'login', 'seed' => [u(105, 'logintab01', md5('tab'))],
        'params' => p(['user_name' => 'logintab01', 'user_pwd' => "\ttab\0"]), 'rows' => [h('logintab01')]];
    $c[] = ['label' => 'login-pwd-plus-raw', 'op' => 'login', 'seed' => [u(107, 'loginplus1', md5('a+b'))],
        'params' => p(['user_name' => 'loginplus1', 'user_pwd' => 'a+b']), 'rows' => [h('loginplus1')]];
    $c[] = ['label' => 'login-pwd-plus-decoded', 'op' => 'login', 'seed' => [u(107, 'loginplus1', md5('a+b'))],
        'params' => p(['user_name' => 'loginplus1', 'user_pwd' => 'a b']), 'rows' => [h('loginplus1')]];
    $c[] = ['label' => 'login-pwd-pct-raw', 'op' => 'login', 'seed' => [u(108, 'loginpct01', md5('%41b'))],
        'params' => p(['user_name' => 'loginpct01', 'user_pwd' => '%41b']), 'rows' => [h('loginpct01')]];
    $c[] = ['label' => 'login-pwd-zh', 'op' => 'login', 'seed' => [u(109, 'loginzh001', md5('中文密码'))],
        'params' => p(['user_name' => 'loginzh001', 'user_pwd' => ' 中文密码 ']), 'rows' => [h('loginzh001')]];

    // ---------------- 资料与改密（User.php:292-319 → saveData）
    $infoUser = u(201, 'infouser01', md5('oldpass'), ['user_nick_name' => 'OldNick', 'user_qq' => '111',
        'user_question' => 'q', 'user_answer' => 'a']);
    $info = function ($label, array $params, array $more = []) use (&$c, $infoUser) {
        $c[] = array_merge(['label' => 'info-' . $label, 'op' => 'info', 'seed' => [$infoUser], 'as_user' => 201,
            'params' => p($params), 'rows' => [h('infouser01')]], $more);
    };
    $base = ['user_nick_name' => 'Nick', 'user_qq' => '12345', 'user_question' => 'Q?', 'user_answer' => 'A!',
        'user_pwd1' => '', 'user_pwd2' => ''];
    $info('no-old-pwd', ['user_pwd' => ''] + $base);
    $info('wrong-old-pwd', ['user_pwd' => 'nope'] + $base);
    $info('old-pwd-trim', ['user_pwd' => ' oldpass '] + $base);
    $info('change-pwd', ['user_pwd' => 'oldpass', 'user_pwd1' => ' new pass ', 'user_pwd2' => ' new pass '] + $base);
    $info('change-pwd-zero', ['user_pwd' => 'oldpass', 'user_pwd1' => '0', 'user_pwd2' => '0'] + $base);
    $info('change-pwd-spaces', ['user_pwd' => 'oldpass', 'user_pwd1' => '  ', 'user_pwd2' => '  '] + $base);
    $info('change-pwd-mismatch', ['user_pwd' => 'oldpass', 'user_pwd1' => 'a1', 'user_pwd2' => 'a2'] + $base);
    $info('html-fields', ['user_pwd' => 'oldpass', 'user_nick_name' => '<b>', 'user_qq' => 'a&b',
        'user_question' => '"x"', 'user_answer' => '%26lt;', 'user_pwd1' => '', 'user_pwd2' => '']);
    $info('empty-nick-keeps', ['user_pwd' => 'oldpass', 'user_nick_name' => '', 'user_qq' => '',
        'user_question' => '', 'user_answer' => '', 'user_pwd1' => '', 'user_pwd2' => '']);
    $c[] = ['label' => 'info-old-pwd-formatted', 'op' => 'info', 'seed' => [u(202, 'infouser02', md5('a&amp;b'))],
        'as_user' => 202, 'params' => p(['user_pwd' => 'a&b'] + $base), 'rows' => [h('infouser02')]];
    $c[] = ['label' => 'info-short-name', 'op' => 'info', 'seed' => [u(203, 'short', md5('oldpass'))],
        'as_user' => 203, 'params' => p(['user_pwd' => 'oldpass'] + $base), 'rows' => [h('short')]];

    // ---------------- 密保找回（User.php:480-526）；会话里放入验证码 1234
    $fpUser = u(301, 'findpass01', md5('old'), ['user_question' => 'my q', 'user_answer' => 'my a']);
    $fp = function ($label, array $params, array $more = []) use (&$c, $fpUser) {
        $c[] = array_merge(['label' => 'findpass-' . $label, 'op' => 'findpass', 'seed' => [$fpUser],
            'captcha' => '1234', 'params' => p($params), 'rows' => [h('findpass01')]], $more);
    };
    $fpBase = ['user_name' => 'findpass01', 'user_question' => 'my q', 'user_answer' => 'my a',
        'user_pwd' => ' newpw ', 'user_pwd2' => ' newpw ', 'verify' => '1234'];
    $fp('ok', $fpBase);
    $fp('wrong-answer', ['user_answer' => 'nope'] + $fpBase);
    $fp('mismatch', ['user_pwd2' => 'other'] + $fpBase);
    $fp('no-verify', ['verify' => ''] + $fpBase);
    $fp('wrong-verify', ['verify' => '0000'] + $fpBase);
    $fp('question-encoded', ['user_question' => 'my%20q'] + $fpBase);
    $fp('disabled-user', $fpBase, ['seed' => [u(301, 'findpass01', md5('old'), ['user_question' => 'my q',
        'user_answer' => 'my a', 'user_status' => 0])]]);

    // ---------------- regcheck（index/controller/User.php:337-347）
    $chk = u(401, 'checkuser1', md5('x'), ['user_email' => 'chk@example.com']);
    foreach (['name-taken' => ['user_name', 'checkuser1'], 'name-free' => ['user_name', 'freename01'],
                 'name-upper' => ['user_name', 'CHECKUSER1'], 'email-taken' => ['user_email', 'chk@example.com'],
                 'verify-wrong' => ['verify', '0000'], 'other-t' => ['nick', 'x<y']] as $label => $tv) {
        $c[] = ['label' => 'regcheck-' . $label, 'op' => 'regcheck', 'seed' => [$chk], 'captcha' => '1234',
            'params' => p(['t' => $tv[0], 'str' => $tv[1]])];
    }

    // ---------------- 短信/邮件"未配置"（User.php:602-712、785-846）
    $msg = function ($label, $op, array $params, array $config = []) use (&$c) {
        $c[] = ['label' => $op . '-' . $label, 'op' => $op, 'params' => p($params), 'config' => $config];
    };
    $msg('phone', 'reg_msg', ['ac' => 'phone', 'to' => '13800000000']);
    $msg('email', 'reg_msg', ['ac' => 'email', 'to' => 'a@example.com']);
    $msg('bad-ac', 'reg_msg', ['ac' => 'fax', 'to' => '1']);
    $msg('empty-to', 'reg_msg', ['ac' => 'email', 'to' => '']);
    $msg('email-black', 'reg_msg', ['ac' => 'email', 'to' => 'a@bad.com'], ['user.email_black_hosts' => 'bad.com']);
    $msg('email-black', 'findpass_msg', ['ac' => 'email', 'to' => 'a@bad.com'], ['user.email_black_hosts' => 'bad.com']);
    $msg('phone', 'findpass_msg', ['ac' => 'phone', 'to' => '13800000000']);
    $msg('short', 'findpass_reset', ['ac' => 'email', 'to' => 'a@example.com', 'code' => '1', 'user_pwd' => '12345',
        'user_pwd2' => '12345']);
    $msg('short-multibyte', 'findpass_reset', ['ac' => 'email', 'to' => 'a@example.com', 'code' => '1',
        'user_pwd' => '中文', 'user_pwd2' => '中文']);
    $msg('mismatch', 'findpass_reset', ['ac' => 'email', 'to' => 'a@example.com', 'code' => '1',
        'user_pwd' => '123456', 'user_pwd2' => '1234567']);
    $msg('no-code', 'findpass_reset', ['ac' => 'email', 'to' => 'a@example.com', 'user_pwd' => '123456',
        'user_pwd2' => '123456']);
    $msg('double-clean-code', 'findpass_reset', ['ac' => 'email', 'to' => 'a@example.com', 'code' => '%2530',
        'user_pwd' => '123456', 'user_pwd2' => '123456']);
    $msg('wrong-code', 'findpass_reset', ['ac' => 'phone', 'to' => '13800000000', 'code' => '999999',
        'user_pwd' => '123456', 'user_pwd2' => '123456']);

    // ---------------- check_user_popedom 按会员组（index/controller/Base.php:92-227），mac_ulog 为空（没有购买记录）
    $groups = [
        'g1' => ['group_id' => 1, 'group_type' => ',1,'],
        'g2' => ['group_id' => 2, 'group_type' => ',1,'],
        'g3' => ['group_id' => 3, 'group_type' => ',1,'],
        'missing' => null,
    ];
    $bitsets = ['all' => ['1', '2', '3', '4', '5'], 'trysee-only' => ['1', '2', '5'], 'none' => ['1']];
    $param = ['id' => 1, 'sid' => 1, 'nid' => 1, 'page' => 1];
    foreach ($groups as $gl => $g) {
        foreach ($bitsets as $bl => $bits) {
            $popedom = [];
            foreach ($bits as $b) {
                $popedom[$b] = '1';
            }
            $group = $g === null ? null : $g + ['group_popedom' => json_encode(['1' => $popedom])];
            foreach ([0, 5] as $points) {
                foreach ([0, 5] as $trysee) {
                    $c[] = ['label' => "popedom-play-$gl-$bl-p$points-t$trysee", 'op' => 'popedom', 'group' => $group,
                        'type_id' => 1, 'popedom' => 3, 'flag' => 'play', 'param' => $param,
                        'info' => ['vod_points_play' => $points, 'vod_points_down' => $points, 'vod_points' => 0],
                        'trysee' => $trysee, 'user_points' => 0];
                }
                $c[] = ['label' => "popedom-down-$gl-$bl-p$points", 'op' => 'popedom', 'group' => $group,
                    'type_id' => 1, 'popedom' => 4, 'flag' => 'down', 'param' => $param,
                    'info' => ['vod_points_play' => $points, 'vod_points_down' => $points, 'vod_points' => 0],
                    'trysee' => 0, 'user_points' => 0];
                $c[] = ['label' => "popedom-art-$gl-$bl-p$points", 'op' => 'popedom', 'group' => $group,
                    'type_id' => 1, 'popedom' => 2, 'flag' => 'art', 'param' => $param,
                    'info' => ['art_points_detail' => $points, 'art_points' => 0], 'trysee' => 0, 'user_points' => 0];
            }
            $c[] = ['label' => "popedom-vod-detail-$gl-$bl", 'op' => 'popedom', 'group' => $group, 'type_id' => 1,
                'popedom' => 2, 'flag' => 'vod', 'param' => $param, 'info' => [], 'trysee' => 0, 'user_points' => 0];
        }
    }
    return $c;
}

/** 以 php-cgi 执行一个用例，返回其 JSON 正文（解码后）。 */
function run_child($ctx, $args)
{
    list($host, $port, $db, $user, $password) = $ctx['db'];
    $child = __DIR__ . DIRECTORY_SEPARATOR . 'account_golden_child.php';
    $env = [
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
    if (getenv('GOLDEN_DEBUG') === '1') {
        $env['GOLDEN_DEBUG'] = '1';
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
            fwrite(STDERR, $raw . "\n");
        }
        throw new RuntimeException('child output is not JSON for ' . $args['label']);
    }
    unset($json['op']);
    return $json;
}
