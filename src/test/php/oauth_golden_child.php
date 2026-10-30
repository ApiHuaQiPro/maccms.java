<?php
/*
 * B26（MACC-49）第三方登录黄金样本的子进程：由 oauth_golden.php 以 php-cgi 执行，一次只处理一个用例（一次回调）。
 *
 * 只读加载仓库的 thinkphp/、application/ 与 vendor/（引导方式同 B24 的 pay_golden_child.php），调用旧代码本身：
 *   app\index\controller\User::logincallback($type, $code)（index/controller/User.php:212-285）→ index/event/LoginEvent.php →
 *   common/model/User.php 的 checkLogin、infoData、register、login。
 * 缺失的 login\ThinkOauth 由 oauth_golden_thinkoauth.php 替身提供（不发网络请求，按用例计划返回令牌、用户信息与 openid）。
 * 实例化方式：User 的子类，构造函数只调用 All::__construct()，跳过 index Base 的站点状态检查与 User 构造（logincallback 在旧版的免登录
 * 白名单里，构造对它只定义 THIRD_LOGIN_CALLBACK 常量）。登录态：会员用例由父进程经 HTTP_COOKIE 给出 user_id/user_name/user_check
 * （checkLogin 照常校验），并把 $GLOBALS['user'] 设为该会员（与 label_user 的结果相同）；游客用例为 label_user 的默认值。
 * 返回值：json() 的数据；success()/error()/redirect() 抛出的 HttpResponseException 记录状态与数据；写出的 cookie 名称（headers_list）。
 * 用例开始前清空 mac_user 并写入种子；结束后读出 mac_user。数据库只连父进程新建的一次性库 maccms_it_*。
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

$GLOBALS['golden_result'] = ['label' => $args['label'], 'now' => time()];
$GLOBALS['golden_done'] = false;
$GLOBALS['golden_repo'] = $repo;
$GLOBALS['golden_now'] = time();
$GLOBALS['golden_oauth'] = $args['oauth'];
$GLOBALS['golden_oauth_calls'] = [];

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
    $cookies = [];
    foreach (headers_list() as $h) {
        if (stripos($h, 'Set-Cookie:') === 0 && preg_match('/^Set-Cookie:\s*([^=]+)=([^;]*)/i', $h, $m)) {
            $cookies[] = ['name' => $m[1], 'deleted' => $m[2] === 'deleted' || $m[2] === ''];
        }
    }
    $r['cookies'] = $cookies;
    $r['userInfoCalls'] = $GLOBALS['golden_oauth_calls'];
    try {
        $r['after'] = golden_users();
    } catch (\Throwable $t) {
        $r['readError'] = get_class($t);
    }
    header_remove('Set-Cookie');
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
require __DIR__ . '/oauth_golden_thinkoauth.php';
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
        \app\common\controller\All::__construct();
    }
}

const USER_COLUMNS = ['user_id', 'group_id', 'user_name', 'user_pwd', 'user_status', 'user_points', 'user_nick_name',
    'user_reg_ip', 'user_reg_time', 'user_login_num', 'user_login_time', 'user_login_ip', 'user_last_login_time',
    'user_last_login_ip', 'user_random', 'user_openid_qq', 'user_openid_weixin', 'user_end_time'];

function golden_users()
{
    $out = [];
    foreach (\think\Db::name('user')->order('user_id')->select() as $u) {
        $row = [];
        foreach (USER_COLUMNS as $k) {
            $row[$k] = (string)$u[$k];
        }
        $out[] = $row;
    }
    return $out;
}

function golden_fatal(\Throwable $e)
{
    return ['class' => get_class($e), 'message' => golden_rel($e->getMessage()), 'file' => golden_path($e->getFile()),
        'line' => $e->getLine()];
}

function golden_data($resp)
{
    $data = $resp->getData();
    return ['status' => $resp->getCode(), 'class' => get_class($resp), 'data' => is_array($data) ? $data : (string)$data];
}

$result = &$GLOBALS['golden_result'];
try {
    \think\Db::execute('TRUNCATE TABLE mac_user');
    foreach ((array)($args['users'] ?? []) as $row) {
        \think\Db::name('user')->insert($row);
    }
    $groupList = model('Group')->getCache('group_list');
    if (empty($args['as'])) {
        $GLOBALS['user'] = ['user_id' => 0, 'user_name' => lang('controller/visitor'),
            'user_portrait' => 'static/images/touxiang.png', 'group_id' => 1, 'points' => 0, 'group' => $groupList[1]];
    } else {
        $info = \think\Db::name('user')->where('user_id', $args['as'])->find();
        $info['group'] = $groupList[$info['group_id']];
        $GLOBALS['user'] = $info;
    }
} catch (\Throwable $e) {
    $result['setupError'] = get_class($e) . ': ' . golden_rel($e->getMessage());
}

if (!isset($result['setupError'])) {
    try {
        $c = new GoldenUser();
        try {
            $r = $c->logincallback($args['type'], $args['code']);
            $result['returned'] = is_object($r) ? golden_data($r) : $r;
        } catch (\think\exception\HttpResponseException $e) {
            $result['response'] = golden_data($e->getResponse());
        }
    } catch (\Throwable $e) {
        $result['fatal'] = golden_fatal($e);
    }
}
exit;
