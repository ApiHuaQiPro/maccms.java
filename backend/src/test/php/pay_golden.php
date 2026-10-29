<?php
/*
 * B24（MACC-47）在线支付的 PHP 黄金样本生成脚本（旧代码：index/controller/User.php gopay、index/controller/Payment.php notify、
 * common/extend/pay/{Alipay,Codepay,Weixin,Zhapay}.php、common/model/Order.php notify、common.php 的 mac_array2xml/mac_xml2array/
 * mac_redirect/mac_curl_post）。
 *
 * 每个用例：子进程（pay_golden_child.php，php-cgi）清空并写入 mac_user/mac_order 种子，以会员 7 的身份调用 gopay，或以支付平台的身份
 * 调用 notify（真实的 GET/POST 请求：查询串、表单或 XML 正文经 CGI 交给 php-cgi），记录输出原文、HTTP 状态/提示（success/error）、
 * 未捕获异常，以及之后的 mac_order/mac_user/mac_plog 行（时间记为相对用例参考时间的秒数 age）。
 * 外部请求：只有微信统一下单（https://api.mch.weixin.qq.com/pay/unifiedorder，旧代码写死）。子进程的 https_proxy 指向 B25 的本机模拟服务
 * sms_mail_mock.py（127.0.0.1 随机端口，CONNECT 隧道 + 运行时生成的一次性自签名证书；旧代码不校验证书），按用例计划回复并记录请求。
 * 支付宝、码支付、Zhapay 只生成网关地址/表单，不发请求。
 *
 * 流程：外部管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入仓库 install.sql → 启动模拟服务 → 逐个用例执行 → 输出 JSON →
 * 停止模拟服务、删除一次性库与临时目录。不修改任何旧 PHP 文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，
 * 不访问外部网络。支付的 appid、商户号、密钥都是假值（test-only-…）。需要仓库根目录下的 vendor/（按 AGENTS.md 从备份恢复）。
 *
 * 管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL（jdbc:mysql://host:port/）、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；
 * 另需 PHP_CGI；可选 GOLDEN_PYTHON（默认 python）、GOLDEN_OPENSSL（默认 C:/Program Files/Git/usr/bin/openssl.exe）。
 * 用法（Git Bash，仓库根目录，PHP 7.3 与 8.0 各一份）：
 *   MACCMS_IT_ADMIN_URL=jdbc:mysql://127.0.0.1:3306/ MACCMS_IT_ADMIN_USERNAME=root MACCMS_IT_ADMIN_PASSWORD=<本机密码> \
 *   PHP_CGI=D:/Tools/php/7.3/php-cgi.exe D:/Tools/php/7.3/php.exe backend/src/test/php/pay_golden.php \
 *       > backend/src/test/resources/integration/b24/php73-golden.json
 *   （8.0 同样，输出 php80-golden.json）
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

const WEIXIN_HOST = 'api.mch.weixin.qq.com';
const ALIPAY_KEY = 'test-only-alipay-key';
const CODEPAY_KEY = 'test-only-codepay-key';
const WEIXIN_KEY = 'test-only-weixin-key-0123456789ab';
const ZHAPAY_KEY = 'test-only-zhapay-key';

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$work = sys_get_temp_dir() . DIRECTORY_SEPARATOR . 'maccms-b24-golden-' . bin2hex(random_bytes(4));
$mock = null;
$exit = 0;
try {
    $pdo->exec("USE `$dbName`");
    foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
        $pdo->exec($sql);
    }
    $sqlMode = $pdo->query('SELECT @@SESSION.sql_mode')->fetchColumn();
    @mkdir($work . '/runtime', 0777, true);
    $mock = start_mock($python, $openssl, $work . DIRECTORY_SEPARATOR . 'mock');
    $ctx = ['repo' => $repo, 'php_cgi' => $phpCgi, 'work' => $work, 'mock' => $mock,
        'db' => [$host, $port, $dbName, $adminUser, $adminPassword]];
    $out = [
        'generator' => 'backend/src/test/php/pay_golden.php',
        'php' => PHP_VERSION,
        'timezone' => 'PRC',
        'sqlMode' => $sqlMode,
        'host' => 'www.example.test',
        'remote_addr' => '127.0.0.1',
        'note' => '时间列记为 age = 用例参考时间 - 写入时间（秒，通常 0 或 -1）。微信 nonce_str 与 sign 每次不同：比对用 bodyElided 与 signatureValid。'
            . '会员 7 是登录会员；gopay 的 $user 参数在旧代码里是未定义的 $this->user（null），见 assignedPayment.user_id 与 subject/body。',
        'baseConfig' => base_config(),
        'seedUsers' => seed_users(),
        'seedOrders' => seed_orders(),
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
    if ($mock !== null) {
        stop_mock($mock);
    }
    try {
        $pdo->exec("DROP DATABASE IF EXISTS `$dbName`");
    } catch (\Throwable $e) {
        fwrite(STDERR, "drop failed\n");
        $exit = 1;
    }
    rm_tree($work);
}
exit($exit);

// ====================================================================== 数据与配置

function base_config()
{
    return [
        'pay.min' => '10', 'pay.scale' => '1',
        'pay.alipay.appid' => 'test-only-alipay-partner', 'pay.alipay.account' => 'seller@example.test',
        'pay.alipay.appkey' => ALIPAY_KEY,
        'pay.codepay.appid' => '10001', 'pay.codepay.appkey' => CODEPAY_KEY, 'pay.codepay.act' => '0',
        'pay.weixin.appid' => 'wx-test-only-appid', 'pay.weixin.mchid' => '1900000109', 'pay.weixin.appkey' => WEIXIN_KEY,
        'pay.zhapay.appid' => '20001', 'pay.zhapay.appkey' => ZHAPAY_KEY, 'pay.zhapay.act' => '2',
    ];
}

function seed_users()
{
    return [
        ['user_id' => 7, 'group_id' => 2, 'user_name' => 'golden7', 'user_pwd' => md5('x'), 'user_status' => 1,
            'user_points' => 100],
        ['user_id' => 8, 'group_id' => 2, 'user_name' => 'golden8', 'user_pwd' => md5('x'), 'user_status' => 1,
            'user_points' => 0],
    ];
}

/** 订单：1～3 会员 7 未支付（20.00/19.99/0.01），4 已支付，5 属于不存在的会员 99，6 积分超出 mac_plog.plog_points（smallint）。 */
function seed_orders()
{
    $t = 1760000000;
    return [
        ['order_id' => 1, 'user_id' => 7, 'order_status' => 0, 'order_code' => 'PAY20261029120000123456',
            'order_price' => '20.00', 'order_time' => $t, 'order_points' => 20],
        ['order_id' => 2, 'user_id' => 7, 'order_status' => 0, 'order_code' => 'PAY20261029120001234567',
            'order_price' => '19.99', 'order_time' => $t, 'order_points' => 19],
        ['order_id' => 3, 'user_id' => 7, 'order_status' => 0, 'order_code' => 'PAY20261029120002345678',
            'order_price' => '0.01', 'order_time' => $t, 'order_points' => 0],
        ['order_id' => 4, 'user_id' => 7, 'order_status' => 1, 'order_code' => 'PAY20261029120003456789',
            'order_price' => '30.00', 'order_time' => $t, 'order_points' => 30, 'order_pay_type' => 'alipay',
            'order_pay_time' => $t + 60],
        ['order_id' => 5, 'user_id' => 99, 'order_status' => 0, 'order_code' => 'PAY20261029120004567890',
            'order_price' => '20.00', 'order_time' => $t, 'order_points' => 20],
        ['order_id' => 6, 'user_id' => 8, 'order_status' => 0, 'order_code' => 'PAY20261029120005678901',
            'order_price' => '70000.00', 'order_time' => $t, 'order_points' => 70000],
    ];
}

function code($id)
{
    foreach (seed_orders() as $o) {
        if ($o['order_id'] === $id) {
            return $o['order_code'];
        }
    }
    throw new RuntimeException('no order');
}

// ====================================================================== 签名（按旧代码的算法构造平台通知；验签仍由旧代码完成）

function alipay_sign(array $p, $key)
{
    $f = [];
    foreach ($p as $k => $v) {
        if ($k === 'sign' || $k === 'sign_type' || $v === '') {
            continue;
        }
        $f[$k] = $v;
    }
    ksort($f);
    $s = [];
    foreach ($f as $k => $v) {
        $s[] = "$k=$v";
    }
    return md5(implode('&', $s) . $key);
}

function form_sign(array $p, $key)
{
    ksort($p);
    $s = [];
    foreach ($p as $k => $v) {
        if ($v === '' || $k === 'sign') {
            continue;
        }
        $s[] = "$k=$v";
    }
    return md5(implode('&', $s) . $key);
}

function weixin_sign(array $p, $key)
{
    $p = array_filter($p);
    ksort($p);
    return strtoupper(md5(urldecode(http_build_query($p)) . '&key=' . $key));
}

function weixin_xml(array $p)
{
    $s = '<xml>';
    foreach ($p as $k => $v) {
        $s .= "<$k><![CDATA[$v]]></$k>";
    }
    return $s . '</xml>';
}

function alipay_notify($id, array $over = [], $key = ALIPAY_KEY)
{
    $p = ['discount' => '0.00', 'payment_type' => '1', 'subject' => '积分充值（UID：）', 'trade_no' => '2026102921001004000000000001',
        'buyer_email' => 'buyer@example.test', 'gmt_create' => '2026-10-29 12:00:00', 'notify_type' => 'trade_status_sync',
        'quantity' => '1', 'out_trade_no' => code($id), 'seller_id' => 'test-only-alipay-partner',
        'notify_time' => '2026-10-29 12:00:05', 'body' => '', 'trade_status' => 'TRADE_SUCCESS', 'is_total_fee_adjust' => 'N',
        'total_fee' => '20.00', 'gmt_payment' => '2026-10-29 12:00:05', 'seller_email' => 'seller@example.test',
        'price' => '20.00', 'buyer_id' => '2088000000000001', 'notify_id' => 'test-only-notify-id', 'use_coupon' => 'N',
        'sign_type' => 'MD5'];
    $p = array_merge($p, $over);
    $p['sign'] = alipay_sign($p, $key);
    return $p;
}

function codepay_notify($id, array $over = [], $key = CODEPAY_KEY)
{
    $p = ['pay_id' => code($id), 'money' => '20.00', 'price' => '20.00', 'type' => '1', 'pay_no' => 'test-only-pay-no-1',
        'param' => '', 'pay_time' => '1761710405', 'pay_tag' => '0'];
    $p = array_merge($p, $over);
    $p['sign'] = form_sign($p, $key);
    return $p;
}

function zhapay_notify($id, array $over = [], $key = ZHAPAY_KEY)
{
    $p = ['out_trade_no' => code($id), 'total_fee' => '20.00', 'transaction_id' => 'test-only-zhapay-tx-1',
        'pay_type_id' => '1', 'param' => '', 'time_end' => '20261029120005'];
    $p = array_merge($p, $over);
    $p['sign'] = form_sign($p, $key);
    return $p;
}

function weixin_notify($id, array $over = [], $key = WEIXIN_KEY)
{
    $p = ['appid' => 'wx-test-only-appid', 'bank_type' => 'CFT', 'cash_fee' => '2000', 'fee_type' => 'CNY',
        'is_subscribe' => 'N', 'mch_id' => '1900000109', 'nonce_str' => 'TESTONLYNONCE0000000000000000001',
        'openid' => 'test-only-openid', 'out_trade_no' => code($id), 'result_code' => 'SUCCESS', 'return_code' => 'SUCCESS',
        'time_end' => '20261029120005', 'total_fee' => '2000', 'trade_type' => 'NATIVE',
        'transaction_id' => '4200000000202610290000000001'];
    $p = array_merge($p, $over);
    $p['sign'] = weixin_sign($p, $key);
    return $p;
}

function unified_ok()
{
    return ['action' => 'respond', 'status' => 200, 'body' => '<xml><return_code><![CDATA[SUCCESS]]></return_code>'
        . '<return_msg><![CDATA[OK]]></return_msg><appid><![CDATA[wx-test-only-appid]]></appid>'
        . '<mch_id><![CDATA[1900000109]]></mch_id><nonce_str><![CDATA[MOCKNONCE]]></nonce_str><sign><![CDATA[MOCKSIGN]]></sign>'
        . '<result_code><![CDATA[SUCCESS]]></result_code><prepay_id><![CDATA[wx-test-only-prepay]]></prepay_id>'
        . '<trade_type><![CDATA[NATIVE]]></trade_type><code_url><![CDATA[weixin://wxpay/bizpayurl?pr=TestOnly1]]></code_url></xml>'];
}

function gopay($label, array $q, array $extra = [])
{
    return array_merge(['label' => $label, 'op' => 'gopay', 'query' => $q, 'config' => [], 'mock' => (object)[],
        'proxy' => 'mock'], $extra);
}

function notify($label, $type, $method, $body, $contentType, array $extra = [])
{
    return array_merge(['label' => $label, 'op' => 'notify', 'payType' => $type, 'method' => $method, 'body' => $body,
        'contentType' => $contentType, 'config' => [], 'mock' => (object)[], 'proxy' => 'mock'], $extra);
}

function form($label, $type, array $p, array $extra = [])
{
    return notify($label, $type, 'POST', http_build_query($p), 'application/x-www-form-urlencoded', $extra);
}

function xml($label, $body, array $extra = [])
{
    return notify($label, 'weixin', 'POST', $body, 'text/xml', $extra);
}

function cases()
{
    $o1 = ['order_id' => '1', 'order_code' => code(1)];
    $c = [];
    // ---------------------------------------------------------------- gopay：前置检查
    $c[] = gopay('gopay.empty', []);
    $c[] = gopay('gopay.unconfigured', $o1 + ['payment' => 'alipay'], ['config' => ['pay.alipay.appid' => '']]);
    $c[] = gopay('gopay.unknown', $o1 + ['payment' => 'foo']);
    $c[] = gopay('gopay.scalar-group', $o1 + ['payment' => 'min']);
    $c[] = gopay('gopay.order-mismatch', ['order_id' => '2', 'order_code' => code(1), 'payment' => 'codepay']);
    $c[] = gopay('gopay.order-code-only', ['order_code' => code(1), 'payment' => 'codepay']);
    $c[] = gopay('gopay.other-user', ['order_id' => '6', 'order_code' => code(6), 'payment' => 'codepay']);
    $c[] = gopay('gopay.paid', ['order_id' => '4', 'order_code' => code(4), 'payment' => 'codepay']);
    // ---------------------------------------------------------------- 支付宝
    $c[] = gopay('gopay.alipay', $o1 + ['payment' => 'alipay']);
    $c[] = gopay('gopay.alipay.upper', $o1 + ['payment' => 'ALIPAY']);
    $c[] = gopay('gopay.alipay.1999', ['order_id' => '2', 'order_code' => code(2), 'payment' => 'alipay']);
    $c[] = gopay('gopay.alipay.trim', $o1 + ['payment' => 'alipay'],
        ['config' => ['pay.alipay.appid' => ' test-only-alipay-partner ', 'pay.alipay.appkey' => ' ' . ALIPAY_KEY . ' ']]);
    // ---------------------------------------------------------------- 码支付
    $c[] = gopay('gopay.codepay', $o1 + ['payment' => 'codepay']);
    $c[] = gopay('gopay.codepay.type3', $o1 + ['payment' => 'codepay', 'paytype' => '3']);
    $c[] = gopay('gopay.codepay.type-text', $o1 + ['payment' => 'codepay', 'paytype' => 'abc']);
    $c[] = gopay('gopay.codepay.001', ['order_id' => '3', 'order_code' => code(3), 'payment' => 'codepay']);
    // ---------------------------------------------------------------- Zhapay
    $c[] = gopay('gopay.zhapay', $o1 + ['payment' => 'zhapay']);
    $c[] = gopay('gopay.zhapay.type2', $o1 + ['payment' => 'zhapay', 'paytype' => '2']);
    $c[] = gopay('gopay.zhapay.act-empty', $o1 + ['payment' => 'zhapay'], ['config' => ['pay.zhapay.act' => '']]);
    $c[] = gopay('gopay.zhapay.1999', ['order_id' => '2', 'order_code' => code(2), 'payment' => 'zhapay']);
    // ---------------------------------------------------------------- 微信 NATIVE（服务端统一下单）
    $c[] = gopay('gopay.weixin.ok', $o1 + ['payment' => 'weixin'], ['mock' => [WEIXIN_HOST => unified_ok()]]);
    $c[] = gopay('gopay.weixin.1999', ['order_id' => '2', 'order_code' => code(2), 'payment' => 'weixin'],
        ['mock' => [WEIXIN_HOST => unified_ok()]]);
    $c[] = gopay('gopay.weixin.001', ['order_id' => '3', 'order_code' => code(3), 'payment' => 'weixin'],
        ['mock' => [WEIXIN_HOST => unified_ok()]]);
    $c[] = gopay('gopay.weixin.mchid-empty', $o1 + ['payment' => 'weixin'],
        ['config' => ['pay.weixin.mchid' => ''], 'mock' => [WEIXIN_HOST => unified_ok()]]);
    $c[] = gopay('gopay.weixin.return-fail', $o1 + ['payment' => 'weixin'], ['mock' => [WEIXIN_HOST => ['action' => 'respond',
        'status' => 200, 'body' => '<xml><return_code><![CDATA[FAIL]]></return_code><return_msg><![CDATA[签名错误]]></return_msg></xml>']]]);
    $c[] = gopay('gopay.weixin.result-fail', $o1 + ['payment' => 'weixin'], ['mock' => [WEIXIN_HOST => ['action' => 'respond',
        'status' => 200, 'body' => '<xml><return_code><![CDATA[SUCCESS]]></return_code><result_code><![CDATA[FAIL]]></result_code>'
            . '<err_code><![CDATA[ORDERPAID]]></err_code></xml>']]]);
    $c[] = gopay('gopay.weixin.http500', $o1 + ['payment' => 'weixin'],
        ['mock' => [WEIXIN_HOST => ['action' => 'respond', 'status' => 500, 'body' => 'oops']]]);
    $c[] = gopay('gopay.weixin.refused', $o1 + ['payment' => 'weixin'], ['proxy' => 'refuse']);

    // ---------------------------------------------------------------- notify：入口
    $c[] = notify('notify.get', 'alipay', 'GET', '', '');
    $c[] = form('notify.unconfigured', 'alipay', alipay_notify(1), ['config' => ['pay.alipay.appid' => '']]);
    $c[] = form('notify.unknown', 'foo', alipay_notify(1));
    $c[] = form('notify.scalar-group', 'min', alipay_notify(1));
    $c[] = form('notify.upper', 'Alipay', alipay_notify(1));
    // ---------------------------------------------------------------- 支付宝
    $c[] = form('notify.alipay.ok', 'alipay', alipay_notify(1));
    $c[] = form('notify.alipay.paid', 'alipay', alipay_notify(4, ['total_fee' => '30.00', 'price' => '30.00']));
    $c[] = form('notify.alipay.wait', 'alipay', alipay_notify(1, ['trade_status' => 'WAIT_BUYER_PAY']));
    $c[] = form('notify.alipay.finished', 'alipay', alipay_notify(1, ['trade_status' => 'TRADE_FINISHED']));
    $bad = alipay_notify(1);
    $bad['sign'] = md5('forged');
    $c[] = form('notify.alipay.bad-sign', 'alipay', $bad);
    $tampered = alipay_notify(1);
    $tampered['total_fee'] = '0.01';
    $c[] = form('notify.alipay.tampered', 'alipay', $tampered);
    $nosign = alipay_notify(1);
    unset($nosign['sign']);
    $c[] = form('notify.alipay.no-sign', 'alipay', $nosign);
    $c[] = form('notify.alipay.upper-sign', 'alipay', array_merge(alipay_notify(1),
        ['sign' => strtoupper(alipay_notify(1)['sign'])]));
    $c[] = form('notify.alipay.no-order', 'alipay', alipay_notify(1, ['out_trade_no' => 'PAY00000000000000000000']));
    $c[] = form('notify.alipay.no-user', 'alipay', alipay_notify(5));
    $c[] = form('notify.alipay.amount-mismatch', 'alipay', alipay_notify(1, ['total_fee' => '0.01', 'price' => '0.01']));
    $c[] = form('notify.alipay.plog-overflow', 'alipay', alipay_notify(6, ['total_fee' => '70000.00']));
    $c[] = form('notify.alipay.zero-points', 'alipay', alipay_notify(3, ['total_fee' => '0.01']));
    $c[] = notify('notify.alipay.query', 'alipay', 'POST', http_build_query(alipay_notify(1, ['extra' => 'q'])),
        'application/x-www-form-urlencoded', ['query' => 'extra=q']);
    $c[] = notify('notify.alipay.query-tamper', 'alipay', 'POST', http_build_query(alipay_notify(1)),
        'application/x-www-form-urlencoded', ['query' => 'total_fee=0.01']);
    $arr = alipay_notify(1, ['extra' => 'Array']);
    $c[] = notify('notify.alipay.array', 'alipay', 'POST', str_replace('extra=Array', 'extra%5B%5D=1',
        http_build_query($arr)), 'application/x-www-form-urlencoded');
    // ---------------------------------------------------------------- 码支付
    $c[] = form('notify.codepay.ok', 'codepay', codepay_notify(1));
    $c[] = form('notify.codepay.paid', 'codepay', codepay_notify(4));
    $c[] = form('notify.codepay.paytype', 'codepay', array_merge(codepay_notify(1), ['paytype' => 'x']));
    $c[] = form('notify.codepay.no-pay-no', 'codepay', codepay_notify(1, ['pay_no' => '']));
    $c[] = form('notify.codepay.pay-no-0', 'codepay', codepay_notify(1, ['pay_no' => '0']));
    $bad = codepay_notify(1);
    $bad['sign'] = md5('forged');
    $c[] = form('notify.codepay.bad-sign', 'codepay', $bad);
    $tampered = codepay_notify(1);
    $tampered['money'] = '0.01';
    $c[] = form('notify.codepay.tampered', 'codepay', $tampered);
    $c[] = form('notify.codepay.no-order', 'codepay', codepay_notify(1, ['pay_id' => 'PAY00000000000000000000']));
    $c[] = notify('notify.codepay.query-only', 'codepay', 'POST', '', 'application/x-www-form-urlencoded',
        ['query' => http_build_query(codepay_notify(1))]);
    $c[] = form('notify.codepay.key-space', 'codepay', codepay_notify(1),
        ['config' => ['pay.codepay.appkey' => ' ' . CODEPAY_KEY . ' ']]);
    // ---------------------------------------------------------------- Zhapay
    $c[] = form('notify.zhapay.ok', 'zhapay', zhapay_notify(1));
    $c[] = form('notify.zhapay.paid', 'zhapay', zhapay_notify(4));
    $c[] = form('notify.zhapay.no-tx', 'zhapay', zhapay_notify(1, ['transaction_id' => '']));
    $bad = zhapay_notify(1);
    $bad['sign'] = md5('forged');
    $c[] = form('notify.zhapay.bad-sign', 'zhapay', $bad);
    $c[] = form('notify.zhapay.no-order', 'zhapay', zhapay_notify(1, ['out_trade_no' => 'PAY00000000000000000000']));
    $c[] = form('notify.zhapay.key-space', 'zhapay', zhapay_notify(1),
        ['config' => ['pay.zhapay.appkey' => ' ' . ZHAPAY_KEY . ' ']]);
    $c[] = form('notify.zhapay.key-space-signed', 'zhapay', zhapay_notify(1, [], ' ' . ZHAPAY_KEY . ' '),
        ['config' => ['pay.zhapay.appkey' => ' ' . ZHAPAY_KEY . ' ']]);
    // ---------------------------------------------------------------- 微信
    $c[] = xml('notify.weixin.ok', weixin_xml(weixin_notify(1)));
    $c[] = xml('notify.weixin.paid', weixin_xml(weixin_notify(4)));
    $bad = weixin_notify(1);
    $bad['sign'] = strtoupper(md5('forged'));
    $c[] = xml('notify.weixin.bad-sign', weixin_xml($bad));
    $low = weixin_notify(1);
    $low['sign'] = strtolower($low['sign']);
    $c[] = xml('notify.weixin.lower-sign', weixin_xml($low));
    $nosign = weixin_notify(1);
    unset($nosign['sign']);
    $c[] = xml('notify.weixin.no-sign', weixin_xml($nosign));
    $c[] = xml('notify.weixin.result-fail', weixin_xml(weixin_notify(1, ['result_code' => 'FAIL'])));
    $c[] = xml('notify.weixin.zero-field', weixin_xml(weixin_notify(1, ['coupon_fee' => '0'])));
    $c[] = xml('notify.weixin.no-order', weixin_xml(weixin_notify(1, ['out_trade_no' => 'PAY00000000000000000000'])));
    $c[] = xml('notify.weixin.no-user', weixin_xml(weixin_notify(5)));
    $c[] = xml('notify.weixin.numeric-tags', str_replace(['<![CDATA[2000]]>'], ['2000'], weixin_xml(weixin_notify(1))));
    $c[] = xml('notify.weixin.not-xml', 'not xml');
    $c[] = xml('notify.weixin.empty', '');
    $c[] = notify('notify.weixin.form', 'weixin', 'POST', http_build_query(weixin_notify(1)),
        'application/x-www-form-urlencoded');
    return $c;
}

// ====================================================================== 执行

function run_case($ctx, $c)
{
    $mock = $ctx['mock'];
    file_put_contents($mock['dir'] . '/plan.json', json_encode((object)$c['mock'], JSON_UNESCAPED_UNICODE));
    @unlink($mock['dir'] . '/log.jsonl');
    $config = $c['config'] + base_config();
    $args = ['label' => $c['label'], 'op' => $c['op'], 'payType' => $c['payType'] ?? null, 'users' => seed_users(),
        'orders' => seed_orders(), 'config' => $config];
    $proxy = $c['proxy'] === 'refuse' ? 'http://127.0.0.1:' . $mock['deadPort'] : $mock['base'];
    if ($c['op'] === 'gopay') {
        $method = 'GET';
        $query = http_build_query($c['query']);
        $body = '';
        $type = '';
    } else {
        $method = $c['method'];
        $query = $c['query'] ?? '';
        $body = $c['body'];
        $type = $c['contentType'];
    }
    $child = run_child($ctx, $args, $proxy, $method, $query, $body, $type);
    usleep(100000);
    $requests = [];
    $f = $mock['dir'] . '/log.jsonl';
    if (is_file($f)) {
        foreach (file($f, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $line) {
            $requests[] = describe_request(json_decode($line, true));
        }
    }
    $rec = ['label' => $c['label'], 'op' => $c['op'], 'configOverrides' => (object)$c['config'], 'proxy' => $c['proxy'],
        'mockPlan' => (object)$c['mock'], 'method' => $method, 'query' => $query, 'contentType' => $type, 'body' => $body];
    if ($c['op'] === 'notify') {
        $rec['payType'] = $c['payType'];
    }
    foreach (['output', 'response', 'returned', 'fatal', 'shutdownFatal', 'setupError', 'assignedPayment', 'after',
                 'readError'] as $k) {
        if (array_key_exists($k, $child)) {
            $rec[$k] = $child[$k];
        }
    }
    $rec['outputParsed'] = parse_output((string)($child['output'] ?? ''));
    $rec['requests'] = $requests;
    return $rec;
}

/** 输出原文 → 支付宝表单（action + 隐藏字段，按顺序）或 mac_redirect 的跳转地址。 */
function parse_output($html)
{
    if (preg_match("#^<form id='alipaysubmit' name='alipaysubmit' action='([^']*)' method='POST'>(.*)<input type='submit' value='正在提交'></form><script>document.forms\\['alipaysubmit'\\].submit\\(\\);</script>$#su",
        $html, $m)) {
        preg_match_all("#<input type='hidden' name='([^']*)' value='([^']*)'/>#u", $m[2], $mm, PREG_SET_ORDER);
        $fields = [];
        foreach ($mm as $x) {
            $fields[] = [$x[1], $x[2]];
        }
        return ['kind' => 'alipayForm', 'action' => $m[1], 'fields' => $fields];
    }
    if (preg_match('#^<script>location\.href="([^"]*)";</script>$#', $html, $m)) {
        return ['kind' => 'redirect', 'url' => $m[1]];
    }
    return null;
}

/** 模拟服务记录的一条请求 → 原文 + 去掉随机值的形式 + 签名复算。 */
function describe_request($r)
{
    $body = base64_decode((string)($r['body'] ?? ''));
    $out = ['method' => $r['method'] ?? null, 'url' => $r['url'] ?? null, 'host' => $r['host'] ?? null,
        'path' => $r['path'] ?? null, 'tunnel' => $r['tunnel'] ?? false, 'headers' => $r['headers'] ?? [],
        'body' => $body, 'mockAction' => $r['action'] ?? null];
    if (($r['host'] ?? '') === WEIXIN_HOST) {
        $out['bodyElided'] = preg_replace(['#<nonce_str><!\[CDATA\[[^\]]*\]\]></nonce_str>#', '#<sign><!\[CDATA\[[0-9A-F]{32}\]\]></sign>#'],
            ['<nonce_str><nonce></nonce_str>', '<sign><sign></sign>'], $body);
        $data = json_decode(json_encode(simplexml_load_string($body, 'SimpleXMLElement', LIBXML_NOCDATA)), true);
        $sign = is_array($data) && isset($data['sign']) ? $data['sign'] : null;
        if (is_array($data)) {
            unset($data['sign']);
        }
        $out['nonceFormatOk'] = is_array($data) && isset($data['nonce_str'])
            && preg_match('/^[1-9A-Z][0-9A-Z]{31}$/', $data['nonce_str']) === 1;
        $out['signatureValid'] = $sign !== null && is_array($data) && weixin_sign($data, WEIXIN_KEY) === $sign;
    }
    return $out;
}

function run_child($ctx, $args, $proxy, $method, $query, $post, $type)
{
    list($host, $port, $db, $user, $password) = $ctx['db'];
    $child = __DIR__ . DIRECTORY_SEPARATOR . 'pay_golden_child.php';
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
        'REQUEST_METHOD' => $method,
        'SCRIPT_FILENAME' => $child,
        'SCRIPT_NAME' => '/index.php',
        'PHP_SELF' => '/index.php',
        'REQUEST_URI' => '/index.php' . ($query !== '' ? '?' . $query : ''),
        'QUERY_STRING' => $query,
        'REMOTE_ADDR' => '127.0.0.1',
        'REMOTE_PORT' => '50000',
        'DOCUMENT_ROOT' => dirname($child),
        'SystemRoot' => getenv('SystemRoot') ?: 'C:\\Windows',
        'TEMP' => $ctx['work'],
        'TMP' => $ctx['work'],
    ];
    // success()/error() 在 ajax 请求下返回 JSON（code/msg/data/url/wait），便于记录提示原文；页面形态的提示页由新版测试单独覆盖
    $env['HTTP_X_REQUESTED_WITH'] = 'XMLHttpRequest';
    if ($method === 'POST') {
        $env['CONTENT_TYPE'] = $type;
        $env['CONTENT_LENGTH'] = (string)strlen($post);
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

// ====================================================================== 本机模拟服务（B25 的 sms_mail_mock.py）

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
        . ' --dir ' . escapeshellarg($dir) . ' --openssl ' . escapeshellarg($openssl) . ' --cn ' . WEIXIN_HOST;
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
