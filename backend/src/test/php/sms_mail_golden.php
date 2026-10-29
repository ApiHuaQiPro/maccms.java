<?php
/*
 * B25（MACC-48）短信与邮件验证码的 PHP 黄金样本生成脚本（旧代码：application/common.php mac_send_sms/mac_send_mail，
 * common/extend/sms/Aliyun.php、Qcloud.php，common/extend/email/Phpmailer.php，common/model/User.php 的 register 短信/邮件分支、
 * check_msg、send_msg、bind、unbind、bindmsg/findpass_msg/reg_msg、findpass_reset，admin/controller/System.php test_email）。
 *
 * 每个用例：子进程（sms_mail_golden_child.php，php-cgi）清空并写入 mac_user/mac_msg 种子、设置 $GLOBALS['user']（游客或会员）、覆盖配置，
 * 依次执行用例里的调用，记录每次调用的返回码与提示原文（或未捕获异常：类、消息、真实应用会输出的 HTTP 状态），以及之后的全部
 * mac_msg 行（msg_time 记为相对用例参考时间 now 的秒数 age）与 mac_user 行。
 * 外部请求：旧代码里阿里云（http://dysmsapi.aliyuncs.com/）与腾讯云（https://yun.tim.qq.com/v5/tlssmssvr/sendsms）的地址写死，
 * 子进程的环境变量 http_proxy / https_proxy 指向本机模拟服务 sms_mail_mock.py（Python，127.0.0.1 随机端口）：明文请求按代理方式收到
 * 绝对地址；https 经 CONNECT 隧道，由模拟服务用运行时以 openssl 命令行生成的一次性自签名证书终止 TLS（旧代码不校验证书）。
 * 没有改写任何旧代码或其副本（不需要 B28 那样复制站点）。模拟服务按用例的计划回复（状态码 + 正文、直接断开、或挂起超过 5 秒），
 * 并记录收到的请求；"拒绝连接"用例把代理指向一个没有监听的本机端口。每个请求另外给出：参数名、去掉签名/随机数/时间的正文、
 * 签名是否能用假密钥复算、请求里的验证码是否等于新写入的 mac_msg.msg_code。
 * PHPMailer（\phpmailer\src\PHPMailer）在仓库与备份里都不存在：email.type=Phpmailer 时记录的就是"类不存在"的未捕获错误，不补这个库。
 *
 * 流程：外部管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入仓库 install.sql → 启动模拟服务 → 逐个用例执行 → 输出 JSON →
 * 停止模拟服务（关闭其 stdin）、删除一次性库与临时目录。不修改任何旧 PHP 文件，不连接、不读写任何已有库（包括 maccms_dev），
 * 不读仓库外配置，不访问外部网络（所有请求都到 127.0.0.1）。短信/邮件的 appid、密钥都是假值。需要仓库根目录下的 vendor/
 * （按 AGENTS.md 从备份恢复）。
 *
 * 管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL（jdbc:mysql://host:port/）、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；
 * 另需 PHP_CGI；可选 GOLDEN_PYTHON（默认 python）、GOLDEN_OPENSSL（默认 C:/Program Files/Git/usr/bin/openssl.exe）。
 * 用法（Git Bash，仓库根目录，PHP 7.3 与 8.0 各一份）：
 *   MACCMS_IT_ADMIN_URL=jdbc:mysql://127.0.0.1:3306/ MACCMS_IT_ADMIN_USERNAME=root MACCMS_IT_ADMIN_PASSWORD=<本机密码> \
 *   PHP_CGI=D:/Tools/php/7.3/php-cgi.exe D:/Tools/php/7.3/php.exe backend/src/test/php/sms_mail_golden.php \
 *       > backend/src/test/resources/integration/b25/php73-golden.json
 *   MACCMS_IT_ADMIN_URL=jdbc:mysql://127.0.0.1:3306/ MACCMS_IT_ADMIN_USERNAME=root MACCMS_IT_ADMIN_PASSWORD=<本机密码> \
 *   PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe backend/src/test/php/sms_mail_golden.php \
 *       > backend/src/test/resources/integration/b25/php80-golden.json
 * 运行时间约 1 分钟（含一个 5 秒超时用例）。
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
$python = getenv('GOLDEN_PYTHON') ?: 'python';
$openssl = getenv('GOLDEN_OPENSSL') ?: 'C:/Program Files/Git/usr/bin/openssl.exe';
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

const ALIYUN_HOST = 'dysmsapi.aliyuncs.com';
const QCLOUD_HOST = 'yun.tim.qq.com';
const ALIYUN_ID = 'test-only-aliyun-id';
const ALIYUN_SECRET = 'test-only-aliyun-secret';
const QCLOUD_ID = '1400000000';
const QCLOUD_KEY = 'test-only-qcloud-key';

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$work = sys_get_temp_dir() . DIRECTORY_SEPARATOR . 'maccms-b25-golden-' . bin2hex(random_bytes(4));
$mock = null;
$exit = 0;
try {
    $pdo->exec("USE `$dbName`");
    foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
        $pdo->exec($sql);
    }
    @mkdir($work . '/runtime', 0777, true);
    $mock = start_mock($python, $openssl, $work . DIRECTORY_SEPARATOR . 'mock');
    $ctx = ['repo' => $repo, 'php_cgi' => $phpCgi, 'work' => $work, 'mock' => $mock,
        'db' => [$host, $port, $dbName, $adminUser, $adminPassword]];
    $out = [
        'generator' => 'backend/src/test/php/sms_mail_golden.php',
        'php' => PHP_VERSION,
        'timezone' => 'PRC',
        'remote_addr' => '127.0.0.1',
        'note' => 'msg_time 记为 age = 用例参考时间 now - msg_time（秒）；调用里新写入的行 age ≤ 0（写入时刻晚于 now，通常 0 或 -1；腾讯云拒绝连接用例约 -2）。验证码、阿里云 SignatureNonce/'
            . 'Timestamp/Signature、腾讯云 random/time/sig 每次运行都不同：比对时用 *Elided 字段与 signatureValid/codeMatchesStoredMsg。',
        'baseConfig' => base_config(),
        'cases' => [],
    ];
    foreach (cases() as $c) {
        // 只用于排查：GOLDEN_ONLY=<标签前缀> 只跑部分用例（这样生成的文件不完整，不能提交）
        if (getenv('GOLDEN_ONLY') && strpos($c['label'], getenv('GOLDEN_ONLY')) !== 0) {
            continue;
        }
        $out['cases'][] = run_case($ctx, $c);
    }
    echo json_encode($out, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE), "\n";
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

// ====================================================================== 配置与用例

/** 每个用例都带上的配置（仓库 extra/maccms.php 之上，点分路径）：注册不限 IP、不要验证码；短信/邮件默认"未配置"，假密钥。 */
function base_config()
{
    return [
        'site.site_name' => '黄金样本站', 'site.site_url' => 'www.example.test',
        'user.status' => '1', 'user.reg_open' => '1', 'user.reg_status' => '1', 'user.reg_verify' => '0',
        'user.login_verify' => '0', 'user.reg_points' => '10', 'user.reg_num' => '0',
        'user.filter_words' => 'admin,cao,sex,xxx', 'user.reg_phone_sms' => '0', 'user.reg_email_sms' => '0',
        'user.invite_reg_points' => '10', 'user.email_white_hosts' => '', 'user.email_black_hosts' => '',
        'sms.type' => '', 'sms.sign' => '测试签名',
        'sms.content' => '【测试】[用户]您好，您的[类型]验证码是[验证码]，[时长]分钟内有效',
        'sms.aliyun.appid' => ALIYUN_ID, 'sms.aliyun.appkey' => ALIYUN_SECRET,
        'sms.qcloud.appid' => QCLOUD_ID, 'sms.qcloud.appkey' => QCLOUD_KEY,
        'email.type' => '', 'email.time' => '5', 'email.nick' => '黄金样本', 'email.test' => 'golden@example.test',
        'email.phpmailer.host' => 'smtp.example.test', 'email.phpmailer.port' => '587',
        'email.phpmailer.secure' => 'tls', 'email.phpmailer.username' => 'golden@example.test',
        'email.phpmailer.password' => '',
    ];
}

/** 会员行（user_pwd = md5('oldpass1')）。 */
function u($id, $name, array $extra = [])
{
    return ['user_id' => $id, 'user_name' => $name, 'user_pwd' => md5('oldpass1'), 'group_id' => 2, 'user_status' => 1,
        'user_random' => 'r' . $id] + $extra;
}

/** mac_msg 种子；age = 距用例参考时间的秒数。 */
function msg($userId, $type, $to, $code, $age)
{
    return ['user_id' => $userId, 'msg_type' => $type, 'msg_status' => 0, 'msg_to' => $to, 'msg_code' => $code,
        'msg_content' => 'seed', 'age' => $age];
}

function respond($status, $body)
{
    return ['action' => 'respond', 'status' => $status, 'body' => $body];
}

function call($op, array $params)
{
    return ['op' => $op, 'params' => $params];
}

function cases()
{
    $c = [];
    $add = function ($label, array $calls, array $opt = []) use (&$c) {
        $c[] = ['label' => $label, 'calls' => $calls] + $opt + ['as' => 0, 'users' => [], 'msgs' => [], 'config' => [],
                'mock' => [], 'proxy' => 'mock', 'entrance' => 'index'];
    };
    $member = u(5, 'member05', ['user_phone' => '13800000005', 'user_email' => 'member05@example.com']);
    $other = u(6, 'other006', ['user_phone' => '13800000006', 'user_email' => 'other@example.com']);
    $ali = ['sms.type' => 'Aliyun'];
    $qq = ['sms.type' => 'Qcloud'];
    $aliOk = [ALIYUN_HOST => respond(200, '{"Code":"OK","Message":"OK","RequestId":"x","BizId":"y"}')];
    $qqOk = [QCLOUD_HOST => respond(200, '{"result":0,"errmsg":"OK","ext":"","sid":"s","fee":1}')];
    $phone = ['ac' => 'phone', 'to' => '13800000001'];
    $sendOps = ['reg' => 'reg_msg', 'findpass' => 'findpass_msg', 'bind' => 'bindmsg'];

    // ---------------- 1. 发送（send_msg → mac_send_sms → Aliyun/Qcloud::submit）
    foreach ($sendOps as $k => $op) {
        $as = $op === 'bindmsg' ? ['as' => 5, 'users' => [$member]] : [];
        $add("send-$k-sms-not-configured", [call($op, $phone)], $as);
        $add("send-$k-sms-unknown-type", [call($op, $phone)], $as + ['config' => ['sms.type' => 'foo']]);
        $add("send-$k-aliyun-ok", [call($op, $phone)], $as + ['config' => $ali, 'mock' => $aliOk]);
        $add("send-$k-qcloud-ok", [call($op, $phone)], $as + ['config' => $qq, 'mock' => $qqOk]);
    }
    $add('send-reg-bad-phone-11-digits', [call('reg_msg', ['ac' => 'phone', 'to' => '12345678901'])],
        ['config' => $ali, 'mock' => $aliOk]);
    $add('send-reg-bad-phone-short', [call('reg_msg', ['ac' => 'phone', 'to' => '1234'])], ['config' => $ali, 'mock' => $aliOk]);
    $add('send-reg-phone-urlencoded-to', [call('reg_msg', ['ac' => 'phone', 'to' => '%2013800000001%20'])],
        ['config' => $ali, 'mock' => $aliOk]);
    $add('send-reg-aliyun-ok-as-member', [call('reg_msg', $phone)], ['as' => 5, 'users' => [$member], 'config' => $ali,
        'mock' => $aliOk]);
    $add('send-reg-aliyun-business-error', [call('reg_msg', $phone)], ['config' => $ali,
        'mock' => [ALIYUN_HOST => respond(200, '{"Code":"isv.BUSINESS_LIMIT_CONTROL","Message":"触发分钟级流控Permits:1"}')]]);
    $add('send-reg-aliyun-non-json', [call('reg_msg', $phone)], ['config' => $ali,
        'mock' => [ALIYUN_HOST => respond(200, '<html>not json</html>')]]);
    $add('send-reg-aliyun-http-500-json', [call('reg_msg', $phone)], ['config' => $ali,
        'mock' => [ALIYUN_HOST => respond(500, '{"Code":"InternalError","Message":"The request processing has failed due to some unknown error."}')]]);
    $add('send-reg-aliyun-closed', [call('reg_msg', $phone)], ['config' => $ali, 'mock' => [ALIYUN_HOST => ['action' => 'close']]]);
    $add('send-reg-aliyun-refused', [call('reg_msg', $phone)], ['config' => $ali, 'proxy' => 'refuse']);
    $add('send-reg-aliyun-timeout', [call('reg_msg', $phone)], ['config' => $ali,
        'mock' => [ALIYUN_HOST => ['action' => 'hang', 'seconds' => 7]]]);
    $add('send-reg-qcloud-error-1014', [call('reg_msg', $phone)], ['config' => $qq,
        'mock' => [QCLOUD_HOST => respond(200, '{"result":1014,"errmsg":"package format error"}')]]);
    $add('send-reg-qcloud-http-500', [call('reg_msg', $phone)], ['config' => $qq,
        'mock' => [QCLOUD_HOST => respond(500, '{"result":500,"errmsg":"server error"}')]]);
    $add('send-reg-qcloud-closed', [call('reg_msg', $phone)], ['config' => $qq, 'mock' => [QCLOUD_HOST => ['action' => 'close']]]);
    $add('send-reg-qcloud-refused', [call('reg_msg', $phone)], ['config' => $qq, 'proxy' => 'refuse']);
    $add('send-bind-aliyun-lowercase-type', [call('bindmsg', $phone)], ['as' => 5, 'users' => [$member],
        'config' => ['sms.type' => 'aliyun'], 'mock' => $aliOk]);

    // ---------------- 2. 频率限制（send_msg 按 user_id + msg_type + msg_to + 时间窗查 mac_msg）
    $add('rate-same-100s-blocked', [call('reg_msg', $phone)], ['config' => $ali, 'mock' => $aliOk,
        'msgs' => [msg(0, 3, '13800000001', '111111', 100)]]);
    $add('rate-same-301s-sends', [call('reg_msg', $phone)], ['config' => $ali, 'mock' => $aliOk,
        'msgs' => [msg(0, 3, '13800000001', '111111', 301)]]);
    $add('rate-other-to-100s-sends', [call('reg_msg', $phone)], ['config' => $ali, 'mock' => $aliOk,
        'msgs' => [msg(0, 3, '13800000002', '111111', 100)]]);
    $add('rate-other-type-100s-sends', [call('reg_msg', $phone)], ['config' => $ali, 'mock' => $aliOk,
        'msgs' => [msg(0, 2, '13800000001', '111111', 100)]]);
    $add('rate-other-user-100s-sends', [call('reg_msg', $phone)], ['config' => $ali, 'mock' => $aliOk,
        'msgs' => [msg(5, 3, '13800000001', '111111', 100)]]);
    $add('rate-twice-in-one-request', [call('reg_msg', $phone), call('reg_msg', $phone)], ['config' => $ali,
        'mock' => $aliOk]);
    $emailRow = [msg(0, 3, 'a@example.com', '111111', 400)];
    $add('rate-email-time10-400s-blocked', [call('reg_msg', ['ac' => 'email', 'to' => 'a@example.com'])],
        ['config' => ['email.time' => '10'], 'msgs' => $emailRow]);
    $add('rate-email-time10-601s-not-blocked', [call('reg_msg', ['ac' => 'email', 'to' => 'a@example.com'])],
        ['config' => ['email.time' => '10'], 'msgs' => [msg(0, 3, 'a@example.com', '111111', 601)]]);
    $add('rate-email-time0-400s-not-blocked', [call('reg_msg', ['ac' => 'email', 'to' => 'a@example.com'])],
        ['config' => ['email.time' => '0'], 'msgs' => $emailRow]);
    $add('rate-phone-ignores-email-time', [call('reg_msg', $phone)], ['config' => $ali + ['email.time' => '10'],
        'mock' => $aliOk, 'msgs' => [msg(0, 3, '13800000001', '111111', 400)]]);

    // ---------------- 3. 参数错误与邮箱黑白名单（UserValidate::validateEmail 只对类型 1、3）
    $add('param-ac-missing', [call('reg_msg', ['to' => '13800000001'])], ['config' => $ali, 'mock' => $aliOk]);
    $add('param-ac-other', [call('reg_msg', ['ac' => 'fax', 'to' => '13800000001'])], ['config' => $ali, 'mock' => $aliOk]);
    $add('param-to-empty', [call('reg_msg', ['ac' => 'phone', 'to' => ''])], ['config' => $ali, 'mock' => $aliOk]);
    $add('param-to-missing', [call('reg_msg', ['ac' => 'phone'])], ['config' => $ali, 'mock' => $aliOk]);
    $add('param-to-spaces', [call('reg_msg', ['ac' => 'phone', 'to' => '   '])], ['config' => $ali, 'mock' => $aliOk]);
    $black = ['user.email_black_hosts' => "bad.com\nworse.com", 'email.type' => 'Phpmailer'];
    $white = ['user.email_white_hosts' => 'good.com', 'email.type' => ''];
    $add('email-black-reg', [call('reg_msg', ['ac' => 'email', 'to' => 'a@bad.com'])], ['config' => $black]);
    $add('email-black-bind', [call('bindmsg', ['ac' => 'email', 'to' => 'a@worse.com'])], ['as' => 5, 'users' => [$member],
        'config' => $black]);
    $add('email-black-findpass-not-checked', [call('findpass_msg', ['ac' => 'email', 'to' => 'a@bad.com'])],
        ['config' => ['user.email_black_hosts' => 'bad.com']]);
    $add('email-white-reg-other-host', [call('reg_msg', ['ac' => 'email', 'to' => 'a@other.com'])], ['config' => $white]);
    $add('email-white-reg-allowed-host', [call('reg_msg', ['ac' => 'email', 'to' => 'a@good.com'])], ['config' => $white]);
    $add('email-white-findpass-not-checked', [call('findpass_msg', ['ac' => 'email', 'to' => 'a@other.com'])],
        ['config' => $white]);

    // ---------------- 4. 邮件发送（send_msg → mac_send_mail）
    $add('email-reg-not-configured', [call('reg_msg', ['ac' => 'email', 'to' => 'a@example.com'])]);
    $add('email-bind-not-configured', [call('bindmsg', ['ac' => 'email', 'to' => 'a@example.com'])], ['as' => 5,
        'users' => [$member]]);
    $add('email-reg-bad-address', [call('reg_msg', ['ac' => 'email', 'to' => 'not-an-email'])],
        ['config' => ['email.type' => 'Phpmailer']]);
    $add('email-reg-phpmailer', [call('reg_msg', ['ac' => 'email', 'to' => 'a@example.com'])],
        ['config' => ['email.type' => 'Phpmailer']]);
    $add('email-findpass-phpmailer', [call('findpass_msg', ['ac' => 'email', 'to' => 'a@example.com'])],
        ['config' => ['email.type' => 'phpmailer']]);
    $add('email-reg-unknown-type', [call('reg_msg', ['ac' => 'email', 'to' => 'a@example.com'])],
        ['config' => ['email.type' => 'Foo']]);

    // ---------------- 5. 找回密码重置（findpass_reset，类型 2，游客）
    $resetUser = u(7, 'resetuser', ['user_phone' => '13800000007', 'user_email' => 'reset@example.com']);
    $fr = function ($label, array $params, array $msgs, array $more = []) use ($add, $resetUser) {
        $add('reset-' . $label, [call('findpass_reset', $params + ['user_pwd' => 'newpass1', 'user_pwd2' => 'newpass1'])],
            $more + ['users' => [$resetUser], 'msgs' => $msgs]);
    };
    $p7 = ['ac' => 'phone', 'to' => '13800000007', 'code' => '246810'];
    $e7 = ['ac' => 'email', 'to' => 'reset@example.com', 'code' => '246810'];
    $fr('phone-ok', $p7, [msg(0, 2, '13800000007', '246810', 60)]);
    $fr('email-ok', $e7, [msg(0, 2, 'reset@example.com', '246810', 60)]);
    $fr('email-ok-user-email-param', ['ac' => 'email', 'user_email' => 'reset@example.com', 'to' => 'ignored@example.com',
        'code' => '246810'], [msg(0, 2, 'reset@example.com', '246810', 60)]);
    $fr('expired-301s', $p7, [msg(0, 2, '13800000007', '246810', 301)]);
    $fr('wrong-code', ['code' => '999999'] + $p7, [msg(0, 2, '13800000007', '246810', 60)]);
    $fr('code-for-other-to', $p7, [msg(0, 2, '13900000009', '246810', 60)]);
    $add('reset-code-reused', [
        call('findpass_reset', $p7 + ['user_pwd' => 'newpass1', 'user_pwd2' => 'newpass1']),
        call('findpass_reset', $p7 + ['user_pwd' => 'newpass2', 'user_pwd2' => 'newpass2'])],
        ['users' => [$resetUser], 'msgs' => [msg(0, 2, '13800000007', '246810', 60)]]);
    $fr('wrong-type-bind-code', $p7, [msg(0, 1, '13800000007', '246810', 60)]);
    $fr('email-format', ['ac' => 'email', 'to' => 'reset-at-example', 'code' => '246810'],
        [msg(0, 2, 'reset-at-example', '246810', 60)]);
    $fr('email-not-found', ['ac' => 'email', 'to' => 'nobody@example.com', 'code' => '246810'],
        [msg(0, 2, 'nobody@example.com', '246810', 60)]);
    $fr('phone-format', ['ac' => 'phone', 'to' => '23800000007', 'code' => '246810'],
        [msg(0, 2, '23800000007', '246810', 60)]);
    $fr('phone-not-found', ['ac' => 'phone', 'to' => '13800000099', 'code' => '246810'],
        [msg(0, 2, '13800000099', '246810', 60)]);
    $add('reset-pwd-too-short', [call('findpass_reset', $p7 + ['user_pwd' => '12345', 'user_pwd2' => '12345'])],
        ['users' => [$resetUser], 'msgs' => [msg(0, 2, '13800000007', '246810', 60)]]);
    $add('reset-pwd-mismatch', [call('findpass_reset', $p7 + ['user_pwd' => 'newpass1', 'user_pwd2' => 'newpass2'])],
        ['users' => [$resetUser], 'msgs' => [msg(0, 2, '13800000007', '246810', 60)]]);
    $fr('code-of-other-user', $p7, [msg(5, 2, '13800000007', '246810', 60)]);
    $fr('ac-missing', ['to' => '13800000007', 'code' => '246810'], [msg(0, 2, '13800000007', '246810', 60)]);
    $fr('phone-ac-email-code', ['ac' => 'phone', 'to' => '13800000007', 'code' => '246810'],
        [msg(0, 2, 'reset@example.com', '246810', 60)]);

    // ---------------- 6. 绑定 / 解绑（会员 5 登录）
    $bindUser = u(5, 'member05', ['user_phone' => '', 'user_email' => '']);
    $bd = function ($label, array $params, array $msgs, array $more = []) use ($add, $bindUser, $other) {
        $add('bind-' . $label, [call('bind', $params)], $more + ['as' => 5, 'users' => [$bindUser, $other], 'msgs' => $msgs]);
    };
    $bd('phone-ok-steals-other', ['ac' => 'phone', 'to' => '13800000006', 'code' => '135790'],
        [msg(5, 1, '13800000006', '135790', 60)]);
    $bd('email-ok-steals-other', ['ac' => 'email', 'to' => 'other@example.com', 'code' => '135790'],
        [msg(5, 1, 'other@example.com', '135790', 60)]);
    $bd('wrong-code', ['ac' => 'phone', 'to' => '13800000006', 'code' => '000000'], [msg(5, 1, '13800000006', '135790', 60)]);
    $bd('expired-301s', ['ac' => 'phone', 'to' => '13800000006', 'code' => '135790'],
        [msg(5, 1, '13800000006', '135790', 301)]);
    $bd('code-for-other-to', ['ac' => 'phone', 'to' => '13800000006', 'code' => '135790'],
        [msg(5, 1, '13900000009', '135790', 60)]);
    $bd('email-black-host', ['ac' => 'email', 'to' => 'x@bad.com', 'code' => '135790'], [msg(5, 1, 'x@bad.com', '135790', 60)],
        ['config' => ['user.email_black_hosts' => 'bad.com']]);
    $bd('ac-other', ['ac' => 'fax', 'to' => '13800000006', 'code' => '135790'], [msg(5, 1, '13800000006', '135790', 60)]);
    $unbindUser = u(5, 'member05', ['user_phone' => '13800000005', 'user_email' => 'member05@example.com']);
    foreach (['phone', 'email', 'foo'] as $ac) {
        $add('unbind-' . $ac, [call('unbind', ['ac' => $ac])], ['as' => 5, 'users' => [$unbindUser, $other]]);
    }

    // ---------------- 7. 注册（register 的 reg_phone_sms / reg_email_sms 分支，验证码类型 3，游客）
    $reg = function ($label, array $extra, array $msgs, array $config, array $users = []) use ($add) {
        $add('register-' . $label, [call('register', ['user_name' => 'newuser01', 'user_pwd' => 'secret123',
            'user_pwd2' => 'secret123', 'verify' => ''] + $extra)], ['config' => $config, 'msgs' => $msgs, 'users' => $users]);
    };
    $regPhone = ['user.reg_phone_sms' => '1'];
    $regEmail = ['user.reg_email_sms' => '1'];
    $rp = ['ac' => 'phone', 'to' => '13800000011', 'code' => '864209'];
    $re = ['ac' => 'email', 'to' => 'new@example.com', 'code' => '864209'];
    $reg('phone-ok', $rp, [msg(0, 3, '13800000011', '864209', 60)], $regPhone);
    $reg('phone-taken', $rp, [msg(0, 3, '13800000011', '864209', 60)], $regPhone,
        [u(6, 'other006', ['user_phone' => '13800000011'])]);
    $reg('phone-invalid-code', ['code' => '000000'] + $rp, [msg(0, 3, '13800000011', '864209', 60)], $regPhone);
    $reg('phone-expired', $rp, [msg(0, 3, '13800000011', '864209', 301)], $regPhone);
    $reg('phone-code-for-other-to', $rp, [msg(0, 3, '13900000009', '864209', 60)], $regPhone);
    $reg('phone-sms-but-email-ac', $re, [msg(0, 3, 'new@example.com', '864209', 60)], $regPhone);
    $reg('phone-missing-code', ['ac' => 'phone', 'to' => '13800000011'], [msg(0, 3, '13800000011', '864209', 60)], $regPhone);
    $reg('email-ok', $re, [msg(0, 3, 'new@example.com', '864209', 60)], $regEmail);
    $reg('email-taken', $re, [msg(0, 3, 'new@example.com', '864209', 60)], $regEmail,
        [u(6, 'other006', ['user_email' => 'new@example.com'])]);
    $reg('email-invalid-code', ['code' => '000000'] + $re, [msg(0, 3, 'new@example.com', '864209', 60)], $regEmail);
    $reg('email-time10-400s', $re, [msg(0, 3, 'new@example.com', '864209', 400)], $regEmail + ['email.time' => '10']);
    $reg('email-black-host', ['to' => 'new@bad.com'] + $re, [msg(0, 3, 'new@bad.com', '864209', 60)],
        $regEmail + ['user.email_black_hosts' => 'bad.com']);
    $reg('both-flags-phone-wins', $rp, [msg(0, 3, '13800000011', '864209', 60)], $regPhone + $regEmail);

    // ---------------- 8. 后台测试邮件（admin System::test_email，POST type/nick/test）
    $te = function ($label, array $post, array $config) use ($add) {
        $add('test-email-' . $label, [call('test_email', $post)], ['entrance' => 'admin', 'config' => $config]);
    };
    $te('not-configured', ['type' => 'Phpmailer', 'nick' => '黄金样本', 'test' => 'golden@example.test'], ['email.type' => '']);
    $te('phpmailer', ['type' => 'Phpmailer', 'nick' => '黄金样本', 'test' => 'golden@example.test'],
        ['email.type' => 'Phpmailer']);
    $te('bad-address', ['type' => 'Phpmailer', 'nick' => '黄金样本', 'test' => 'bad-address'], ['email.type' => 'Phpmailer']);
    $te('unknown-type', ['type' => 'Foo', 'nick' => '黄金样本', 'test' => 'golden@example.test'], ['email.type' => 'Foo']);
    return $c;
}

// ====================================================================== 执行

function run_case($ctx, $c)
{
    $mock = $ctx['mock'];
    file_put_contents($mock['dir'] . '/plan.json', json_encode((object)$c['mock'], JSON_UNESCAPED_UNICODE));
    @unlink($mock['dir'] . '/log.jsonl');
    $config = $c['config'] + base_config();
    $args = ['label' => $c['label'], 'entrance' => $c['entrance'], 'as' => $c['as'], 'users' => $c['users'],
        'msgs' => $c['msgs'], 'config' => $config, 'calls' => $c['calls']];
    $proxy = $c['proxy'] === 'refuse' ? 'http://127.0.0.1:' . $mock['deadPort'] : $mock['base'];
    $post = '';
    if (count($c['calls']) === 1 && $c['calls'][0]['op'] === 'test_email') {
        $post = http_build_query($c['calls'][0]['params']);
    }
    $child = run_child($ctx, $args, $proxy, $post);
    // 模拟服务可能还在挂起（超时用例），等它把日志写完
    usleep(200000);
    $requests = [];
    $f = $mock['dir'] . '/log.jsonl';
    if (is_file($f)) {
        foreach (file($f, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $line) {
            $requests[] = json_decode($line, true);
        }
    }
    $newCodes = [];
    foreach ((array)($child['msgsAfter'] ?? []) as $m) {
        if (!$m['seeded']) {
            $newCodes[] = $m['msg_code'];
        }
    }
    $described = [];
    foreach ($requests as $r) {
        $described[] = describe_request($r, $newCodes);
    }
    $overrides = $c['config'];
    return ['label' => $c['label'], 'entrance' => $c['entrance'], 'as' => $c['as'], 'configOverrides' => (object)$overrides,
        'proxy' => $c['proxy'], 'mockPlan' => (object)$c['mock'], 'seedUsers' => $c['users'], 'seedMsgs' => $c['msgs'],
        'user' => $child['user'] ?? null, 'calls' => $child['calls'] ?? [],
        'shutdownFatal' => $child['shutdownFatal'] ?? null, 'setupError' => $child['setupError'] ?? null,
        'msgsAfter' => $child['msgsAfter'] ?? null, 'usersAfter' => $child['usersAfter'] ?? null,
        'requests' => $described];
}

/** 模拟服务记录的一条请求 → 黄金记录（原文 + 去掉随机值的形式 + 签名复算 + 验证码比对）。 */
function describe_request($r, $newCodes)
{
    $body = base64_decode((string)($r['body'] ?? ''));
    $out = ['method' => $r['method'] ?? null, 'url' => $r['url'] ?? null, 'host' => $r['host'] ?? null,
        'path' => $r['path'] ?? null, 'query' => $r['query'] ?? null, 'tunnel' => $r['tunnel'] ?? false,
        'connectHeaders' => isset($r['connect']['headers']) ? $r['connect']['headers'] : null,
        'requestLine' => $r['requestLine'] ?? null, 'rawHeaders' => $r['rawHeaders'] ?? [], 'headers' => $r['headers'] ?? [],
        'body' => $body, 'bodyBase64' => $r['body'] ?? '', 'mockAction' => $r['action'] ?? null];
    if (isset($r['tlsError'])) {
        $out['tlsError'] = $r['tlsError'];
    }
    $code = null;
    if (($r['host'] ?? '') === ALIYUN_HOST) {
        $names = [];
        $params = [];
        foreach (explode('&', $body) as $pair) {
            list($k, $v) = array_pad(explode('=', $pair, 2), 2, '');
            $k = rawurldecode($k);
            $names[] = $k;
            $params[$k] = rawurldecode($v);
        }
        $out['paramNames'] = $names;
        $elided = $params;
        foreach (['Signature' => '<signature>', 'SignatureNonce' => '<nonce>', 'Timestamp' => '<timestamp>'] as $k => $ph) {
            if (isset($elided[$k])) {
                $elided[$k] = $ph;
            }
        }
        $out['params'] = $elided;
        $out['bodyElided'] = preg_replace(['/^Signature=[^&]*/', '/&SignatureNonce=[^&]*/', '/&Timestamp=[^&]*/'],
            ['Signature=<signature>', '&SignatureNonce=<nonce>', '&Timestamp=<timestamp>'], $body);
        $out['timestampFormatOk'] = isset($params['Timestamp'])
            && preg_match('/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/', $params['Timestamp']) === 1;
        // uniqid(mt_rand(0,0xffff), true)：十进制前缀 + 13 位十六进制 + "." + 8 位数字
        $out['nonceFormatOk'] = isset($params['SignatureNonce'])
            && preg_match('/^\d{1,5}[0-9a-f]{13}\.\d{8}$/', $params['SignatureNonce']) === 1;
        // 复算签名：正文 = "Signature=<sig>" + 排好序、已编码的 "&k=v..."（Aliyun::request）
        $p = strpos($body, '&');
        if ($p !== false && strpos($body, 'Signature=') === 0) {
            $rest = substr($body, $p + 1);
            $toSign = 'POST&%2F&' . aliyun_encode($rest);
            $sign = base64_encode(hash_hmac('sha1', $toSign, ALIYUN_SECRET . '&', true));
            $out['signatureValid'] = aliyun_encode($sign) === substr($body, strlen('Signature='), $p - strlen('Signature='));
        }
        $tp = isset($params['TemplateParam']) ? json_decode($params['TemplateParam'], true) : null;
        $code = is_array($tp) && isset($tp['code']) ? (string)$tp['code'] : null;
    } elseif (($r['host'] ?? '') === QCLOUD_HOST) {
        parse_str((string)$r['query'], $q);
        $out['queryNames'] = array_keys($q);
        $out['urlElided'] = preg_replace('/random=\d+/', 'random=<random>', (string)$r['url']);
        $out['randomFormatOk'] = isset($q['random']) && preg_match('/^[1-9]\d{5}$/', $q['random']) === 1;
        $json = json_decode($body, true);
        $out['jsonKeys'] = is_array($json) ? array_keys($json) : null;
        $out['bodyElided'] = preg_replace(['/"sig":"[0-9a-f]{64}"/', '/"time":\d+/'], ['"sig":"<sig>"', '"time":<time>'], $body);
        if (is_array($json) && isset($q['random'])) {
            $expect = hash('sha256', 'appkey=' . QCLOUD_KEY . '&random=' . $q['random'] . '&time=' . $json['time']
                . '&mobile=' . $json['tel']['mobile']);
            $out['signatureValid'] = $expect === $json['sig'];
            $out['timeIsInt'] = is_int($json['time']);
        }
        $code = is_array($json) && isset($json['params'][0]) ? (string)$json['params'][0] : null;
    }
    $out['code'] = $code;
    $out['codeMatchesStoredMsg'] = $newCodes ? in_array($code, $newCodes, true) : null;
    return $out;
}

function aliyun_encode($s)
{
    $res = urlencode($s);
    $res = preg_replace('/\+/', '%20', $res);
    $res = preg_replace('/\*/', '%2A', $res);
    return preg_replace('/%7E/', '~', $res);
}

/** 以 php-cgi 执行一个用例，返回其 JSON 正文（解码后）。 */
function run_child($ctx, $args, $proxy, $post)
{
    list($host, $port, $db, $user, $password) = $ctx['db'];
    $child = __DIR__ . DIRECTORY_SEPARATOR . 'sms_mail_golden_child.php';
    $admin = $args['entrance'] === 'admin';
    $env = [
        'GOLDEN_ARGS' => json_encode($args, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE),
        'GOLDEN_RUNTIME_PATH' => $ctx['work'],
        'GOLDEN_DB_HOST' => $host, 'GOLDEN_DB_PORT' => $port, 'GOLDEN_DB_NAME' => $db, 'GOLDEN_DB_USER' => $user,
        'GOLDEN_DB_PASSWORD' => $password,
        'http_proxy' => $proxy,
        'https_proxy' => $proxy,
        'no_proxy' => '127.0.0.1,localhost',
        'REDIRECT_STATUS' => '200',
        'GATEWAY_INTERFACE' => 'CGI/1.1',
        'SERVER_PROTOCOL' => 'HTTP/1.1',
        'SERVER_SOFTWARE' => 'golden',
        'SERVER_NAME' => 'www.example.test',
        'SERVER_PORT' => '80',
        'HTTP_HOST' => 'www.example.test',
        'HTTP_USER_AGENT' => 'Mozilla/5.0 (golden)',
        'REQUEST_METHOD' => $post !== '' ? 'POST' : 'GET',
        'SCRIPT_FILENAME' => $child,
        'SCRIPT_NAME' => $admin ? '/_admin.php' : '/index.php',
        'PHP_SELF' => $admin ? '/_admin.php' : '/index.php',
        'REQUEST_URI' => $admin ? '/_admin.php/admin/system/test_email' : '/index.php',
        'QUERY_STRING' => '',
        'REMOTE_ADDR' => '127.0.0.1',
        'REMOTE_PORT' => '50000',
        'DOCUMENT_ROOT' => dirname($child),
        'SystemRoot' => getenv('SystemRoot') ?: 'C:\\Windows',
        'TEMP' => $ctx['work'],
        'TMP' => $ctx['work'],
    ];
    if ($post !== '') {
        $env['CONTENT_TYPE'] = 'application/x-www-form-urlencoded';
        $env['CONTENT_LENGTH'] = (string)strlen($post);
        $env['HTTP_X_REQUESTED_WITH'] = 'XMLHttpRequest';
    }
    $cmd = escapeshellarg($ctx['php_cgi']) . ' -d session.save_path=' . escapeshellarg($ctx['work'] . '/runtime')
        . ' -d display_errors=0 -d error_reporting=0 -d session.gc_probability=0';
    $proc = proc_open($cmd, [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['pipe', 'w']], $pipes, $ctx['repo'], $env,
        ['bypass_shell' => true]);
    if (!is_resource($proc)) {
        throw new RuntimeException('php-cgi');
    }
    fwrite($pipes[0], $post);
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

// ====================================================================== 本机模拟服务

function free_port()
{
    $sock = stream_socket_server('tcp://127.0.0.1:0');
    $name = stream_socket_get_name($sock, false);
    fclose($sock);
    return (int)substr($name, strrpos($name, ':') + 1);
}

function start_mock($python, $openssl, $dir)
{
    @mkdir($dir, 0777, true);
    $cmd = escapeshellarg($python) . ' -I ' . escapeshellarg(__DIR__ . DIRECTORY_SEPARATOR . 'sms_mail_mock.py')
        . ' --dir ' . escapeshellarg($dir) . ' --openssl ' . escapeshellarg($openssl);
    $proc = proc_open($cmd, [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['file', $dir . '/mock.err', 'w']], $pipes,
        $dir, null, ['bypass_shell' => true]);
    if (!is_resource($proc)) {
        throw new RuntimeException('mock');
    }
    $line = fgets($pipes[1]);
    if (!preg_match('/^PORT (\d+)/', (string)$line, $m)) {
        throw new RuntimeException('mock did not start: ' . @file_get_contents($dir . '/mock.err'));
    }
    $port = (int)$m[1];
    return ['proc' => $proc, 'pipes' => $pipes, 'dir' => $dir, 'port' => $port, 'base' => 'http://127.0.0.1:' . $port,
        'deadPort' => free_port()];
}

/** 关闭模拟服务的 stdin，它自行退出；只等本脚本启动的那个进程。 */
function stop_mock($mock)
{
    fclose($mock['pipes'][0]);
    fclose($mock['pipes'][1]);
    proc_close($mock['proc']);
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
