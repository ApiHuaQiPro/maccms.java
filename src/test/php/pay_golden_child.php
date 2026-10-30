<?php
/*
 * B24（MACC-47）在线支付黄金样本的子进程：由 pay_golden.php 以 php-cgi 执行，一次只处理一个用例（一次调用）。
 *
 * 只读加载仓库的 thinkphp/、application/ 与 vendor/，调用旧代码本身（引导方式同 B25 的 sms_mail_golden_child.php）：
 *   - gopay：app\index\controller\User::gopay（index/controller/User.php:491-533）。实例化方式：子进程内定义 User 的子类，构造函数只调用
 *     All::__construct()（think\Controller 的构造 + _cl/_ac），跳过 index Base 的站点状态与 User 构造里的登录检查（登录态由 $GLOBALS['user']
 *     给出，与真实请求在登录后相同）。参数经 CGI 查询串交给 Request（input() 照常读取）。
 *   - notify：app\index\controller\Payment::notify（index/controller/Payment.php:13-37）。同样跳过 Base 的构造；路由变量 pay_type 经
 *     Request::route() 放入（与 PATH_INFO 调度 payment/notify/pay_type/<方式> 的结果相同），POST 正文由父进程经 stdin 交给 php-cgi，
 *     所以 $_POST、input() 与 php://input 都是真实值。
 * 旧代码里的 die/exit（Alipay::submit、mac_redirect）会结束子进程：输出由 ob_start 收集，在 shutdown 函数里连同数据库状态一起写成 JSON。
 * ThinkPHP 的 success()/error() 抛出 HttpResponseException：记录其中的 code/msg/url。未捕获的 Throwable 按 think\Error 的方式记录。
 * 错误级别与真实请求相同：thinkphp/base.php 注册 think\Error，common.php 把 error_reporting 改成 E_ERROR|E_PARSE（Notice/Warning 不中止）。
 * 用例开始前清空 mac_user、mac_order、mac_plog 并写入种子；结束后读出这三张表。数据库只连父进程新建的一次性库 maccms_it_*。
 */

ob_start();
error_reporting(E_ERROR | E_PARSE);
$repo = realpath(__DIR__ . '/../../../php');
chdir($repo);

$args = json_decode((string)getenv('GOLDEN_ARGS'), true);
$runtime = rtrim(getenv('GOLDEN_RUNTIME_PATH'), '/\\');
define('APP_PATH', $repo . '/application/');
define('ROOT_PATH', $repo . '/');
define('RUNTIME_PATH', $runtime . '/runtime/');
define('BIND_MODULE', 'index');
define('ENTRANCE', 'index');
define('IN_FILE', '/index.php');

$GLOBALS['golden_result'] = ['label' => $args['label']];
$GLOBALS['golden_done'] = false;
$GLOBALS['golden_repo'] = $repo;
$GLOBALS['golden_now'] = time();

/** 结束（含 die/exit 与 PHP 致命错误）：输出收集到的正文与数据库状态。 */
register_shutdown_function(function () {
    if ($GLOBALS['golden_done']) {
        return;
    }
    $r = $GLOBALS['golden_result'];
    $output = '';
    while (ob_get_level() > 0) {
        $output = ob_get_contents() . $output;
        ob_end_clean();
    }
    if (!isset($r['output'])) {
        $r['output'] = $output;
    }
    $e = error_get_last();
    if ($e && in_array($e['type'], [E_ERROR, E_PARSE, E_CORE_ERROR, E_COMPILE_ERROR], true) && !isset($r['response'])
        && !isset($r['fatal'])) {
        $r['shutdownFatal'] = ['type' => $e['type'], 'message' => golden_rel($e['message']),
            'file' => golden_path($e['file']), 'line' => $e['line']];
    }
    try {
        $r['after'] = golden_tables();
    } catch (\Throwable $t) {
        $r['readError'] = get_class($t);
    }
    header('Content-Type: application/json', true, 200);
    echo json_encode($r, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE);
});

function golden_rel($s)
{
    $repo = $GLOBALS['golden_repo'];
    return str_replace([$repo . DIRECTORY_SEPARATOR, str_replace('\\', '/', $repo) . '/', $repo], '', (string)$s);
}

function golden_path($file)
{
    return str_replace('\\', '/', golden_rel($file));
}

require $repo . '/thinkphp/base.php';
\think\Config::set(include APP_PATH . 'config.php');
require THINK_PATH . 'helper.php';
require APP_PATH . 'common.php';
if (!function_exists('captcha_check')) {
    require $repo . '/vendor/topthink/think-captcha/src/helper.php';
}
error_reporting(E_ERROR | E_PARSE);
\think\Loader::addNamespace('app', APP_PATH);
\think\Request::instance()->module('index');
\think\Lang::load(APP_PATH . 'lang/zh-cn.php');
\think\Config::set('database', [
    'type' => 'mysql', 'hostname' => getenv('GOLDEN_DB_HOST'), 'hostport' => getenv('GOLDEN_DB_PORT'),
    'database' => getenv('GOLDEN_DB_NAME'), 'username' => getenv('GOLDEN_DB_USER'),
    'password' => getenv('GOLDEN_DB_PASSWORD'), 'charset' => 'utf8', 'prefix' => 'mac_', 'debug' => false,
    'resultset_type' => 'array', 'fields_strict' => false,
]);
\think\Config::set('cache', ['type' => 'File', 'path' => $runtime . '/cache/', 'prefix' => '', 'expire' => 0]);
define('MAC_PATH', '/');
$GLOBALS['MAC_PATH_TEMPLATE'] = MAC_PATH . 'template/default/';
$GLOBALS['http_type'] = 'http://';

// 配置：仓库的 extra/maccms.php，再按用例覆盖（键是点分路径）
$config = include APP_PATH . 'extra/maccms.php';
$config['app']['cache_core'] = 0;
$config['app']['cache_flag'] = 'golden';
foreach ((array)($args['config'] ?? []) as $path => $value) {
    $ref = &$config;
    foreach (explode('.', $path) as $seg) {
        if (!isset($ref[$seg]) || !is_array($ref[$seg])) {
            $ref[$seg] = isset($ref[$seg]) ? $ref[$seg] : [];
        }
        $ref = &$ref[$seg];
    }
    $ref = $value;
    unset($ref);
}
\think\Config::set($config, 'maccms');
$GLOBALS['config'] = $config;

class GoldenUser extends \app\index\controller\User
{
    public function __construct()
    {
        // 跳过 index Base（站点状态）与 User 构造里的登录检查：$GLOBALS['user'] 已是登录会员
        \app\common\controller\All::__construct();
    }

    public function assigned($name)
    {
        $p = new \ReflectionProperty(\think\View::class, 'data');
        $p->setAccessible(true);
        $data = $p->getValue($this->view);
        return isset($data[$name]) ? $data[$name] : null;
    }
}

class GoldenPayment extends \app\index\controller\Payment
{
    public function __construct()
    {
        \app\common\controller\All::__construct();
    }
}

function golden_tables()
{
    $now = $GLOBALS['golden_now'];
    $orders = [];
    foreach (\think\Db::name('order')->order('order_id')->select() as $o) {
        $orders[] = ['order_id' => (int)$o['order_id'], 'user_id' => (int)$o['user_id'],
            'order_status' => (int)$o['order_status'], 'order_code' => $o['order_code'], 'order_price' => $o['order_price'],
            'order_points' => (int)$o['order_points'], 'order_pay_type' => $o['order_pay_type'],
            'order_pay_age' => (int)$o['order_pay_time'] === 0 ? null : $now - (int)$o['order_pay_time'],
            'order_pay_time_zero' => (int)$o['order_pay_time'] === 0, 'order_remarks' => $o['order_remarks']];
    }
    $users = [];
    foreach (\think\Db::name('user')->order('user_id')->select() as $u) {
        $users[] = ['user_id' => (int)$u['user_id'], 'user_points' => (int)$u['user_points'],
            'group_id' => (int)$u['group_id']];
    }
    $plogs = [];
    foreach (\think\Db::name('plog')->order('plog_id')->select() as $p) {
        $plogs[] = ['plog_id' => (int)$p['plog_id'], 'user_id' => (int)$p['user_id'], 'plog_type' => (int)$p['plog_type'],
            'plog_points' => (int)$p['plog_points'], 'plog_remarks' => $p['plog_remarks'],
            'plog_age' => $now - (int)$p['plog_time']];
    }
    return ['orders' => $orders, 'users' => $users, 'plogs' => $plogs];
}

function golden_response(\think\exception\HttpResponseException $e)
{
    $resp = $e->getResponse();
    $data = $resp->getData();
    return ['status' => $resp->getCode(), 'data' => is_array($data) ? $data : (string)$data];
}

function golden_fatal(\Throwable $e)
{
    return ['class' => get_class($e), 'message' => golden_rel($e->getMessage()), 'file' => golden_path($e->getFile()),
        'line' => $e->getLine()];
}

$result = &$GLOBALS['golden_result'];
try {
    \think\Db::execute('TRUNCATE TABLE mac_user');
    \think\Db::execute('TRUNCATE TABLE mac_order');
    \think\Db::execute('TRUNCATE TABLE mac_plog');
    foreach ((array)($args['users'] ?? []) as $row) {
        \think\Db::name('user')->insert($row);
    }
    foreach ((array)($args['orders'] ?? []) as $row) {
        \think\Db::name('order')->insert($row);
    }
    $groupList = model('Group')->getCache('group_list');
    $info = \think\Db::name('user')->where('user_id', 7)->find();
    if ($info) {
        $info['group'] = $groupList[$info['group_id']];
        $GLOBALS['user'] = $info;
    }
} catch (\Throwable $e) {
    $result['setupError'] = get_class($e) . ': ' . golden_rel($e->getMessage());
}

if (!isset($result['setupError'])) {
    try {
        if ($args['op'] === 'gopay') {
            $c = new GoldenUser();
            try {
                $c->gopay();
                $result['returned'] = true;
            } catch (\think\exception\HttpResponseException $e) {
                $result['response'] = golden_response($e);
            } catch (\Throwable $e) {
                // 微信成功时 fetch('user/payment_weixin') 因模板缺失（D9-a）失败：记下控制器交给模板的 $payment
                $result['fatal'] = golden_fatal($e);
                $result['assignedPayment'] = $c->assigned('payment');
            }
        } else {
            \think\Request::instance()->route(['pay_type' => $args['payType']]);
            $c = new GoldenPayment();
            try {
                $c->notify();
                $result['returned'] = true;
            } catch (\think\exception\HttpResponseException $e) {
                $result['response'] = golden_response($e);
            }
        }
    } catch (\Throwable $e) {
        $result['fatal'] = golden_fatal($e);
    }
}
// 交给 shutdown 函数输出（与 die/exit 的路径相同）
exit;
