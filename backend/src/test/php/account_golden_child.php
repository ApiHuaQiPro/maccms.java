<?php
/*
 * B09（MACC-33）会员账号黄金样本的子进程：由 account_golden.php 以 php-cgi 执行，一次只处理一个用例。
 *
 * 只读加载仓库的 thinkphp/、application/ 与 vendor/topthink/think-captcha，调用旧代码本身：
 *   - register / login / info / findpass / regcheck / reg_msg / findpass_msg / findpass_reset：model('User') 的同名方法
 *     （common/model/User.php），参数按字节（十六进制）给出，等同控制器 input() 之后的值；
 *   - popedom：经反射调用 app\index\controller\Base::check_user_popedom（index/controller/Base.php:92-227）。
 * 用例开始前按 seed 写入（或覆盖）指定会员行、按 absent 删除指定用户名，覆盖配置项；结束后读出 rows 指定的行。
 * 不修改任何旧 PHP 文件；数据库只连父进程新建的一次性库 maccms_it_*；会话目录是父进程新建的临时目录。
 * 输出：一个 JSON 对象，字符串一律十六进制。
 */

error_reporting(E_ERROR | E_PARSE);
$repo = realpath(__DIR__ . '/../../../..');
chdir($repo);
header('Content-Type: application/json');

$runtime = rtrim(getenv('GOLDEN_RUNTIME_PATH'), '/\\');
define('APP_PATH', $repo . '/application/');
define('ROOT_PATH', $repo . '/');
define('RUNTIME_PATH', $runtime . '/runtime/');
define('BIND_MODULE', 'index');
define('ENTRANCE', 'index');
define('IN_FILE', '/index.php');
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
\think\Config::set(include APP_PATH . 'extra/captcha.php', 'captcha');
define('MAC_PATH', '/');

$args = json_decode((string)getenv('GOLDEN_ARGS'), true);

// 配置：仓库的 extra/maccms.php，再按用例覆盖（user.* 与短信/邮件"未配置"）
$config = include APP_PATH . 'extra/maccms.php';
$config['app']['cache_core'] = 0;
$config['app']['cache_flag'] = 'golden';
foreach ((array)($args['config'] ?? []) as $path => $value) {
    list($a, $b) = explode('.', $path, 2);
    $config[$a][$b] = $value;
}
\think\Config::set($config, 'maccms');
$GLOBALS['config'] = $config;

function unhex($v)
{
    return $v === null ? null : hex2bin($v);
}

function hexs($v)
{
    return $v === null ? null : bin2hex((string)$v);
}

function set_cookie_headers()
{
    $out = [];
    foreach (headers_list() as $h) {
        if (stripos($h, 'Set-Cookie:') === 0) {
            $out[] = bin2hex(trim(substr($h, strlen('Set-Cookie:'))));
        }
    }
    return $out;
}

/** think-captcha 的私有 authcode（Captcha.php），用于构造会话里的验证码记录。 */
function captcha_authcode($str)
{
    $key = substr(md5('ThinkPHP.CN'), 5, 8);
    $str = substr(md5($str), 8, 10);
    return md5($key . $str);
}

const ROW_COLUMNS = ['user_id', 'group_id', 'user_name', 'user_pwd', 'user_status', 'user_random', 'user_end_time',
    'user_nick_name', 'user_qq', 'user_email', 'user_question', 'user_answer', 'user_points', 'user_login_num',
    'user_pid', 'user_pid_2', 'user_pid_3', 'user_reg_ip', 'user_login_ip', 'user_openid_qq'];

function row_hex($row)
{
    if ($row === null) {
        return null;
    }
    $out = [];
    foreach (ROW_COLUMNS as $c) {
        $out[$c] = bin2hex((string)$row[$c]);
    }
    return $out;
}

$result = ['op' => $args['op']];
try {
    $db = \think\Db::name('user');
    foreach ((array)($args['absent'] ?? []) as $name) {
        \think\Db::name('user')->where('user_name', unhex($name))->delete();
    }
    $now = time();
    foreach ((array)($args['seed'] ?? []) as $seed) {
        $row = [];
        foreach ($seed as $k => $v) {
            $row[$k] = is_string($v) && $k !== 'user_id' ? unhex($v) : $v;
        }
        if (isset($seed['end_offset'])) {
            unset($row['end_offset']);
            $row['user_end_time'] = $now + $seed['end_offset'];
        }
        if (isset($seed['reg_now'])) {
            unset($row['reg_now']);
            $row['user_reg_time'] = $now;
        }
        \think\Db::name('user')->where('user_id', $row['user_id'])->delete();
        \think\Db::name('user')->insert($row);
    }
    if (isset($args['captcha'])) {
        $key = captcha_authcode('ThinkPHP.CN');
        \think\Session::set($key, ['verify_code' => captcha_authcode(strtoupper($args['captcha'])),
            'verify_time' => time()], '');
    }
    $param = [];
    foreach ((array)($args['params'] ?? []) as $k => $v) {
        $param[$k] = unhex($v);
    }
    // 不能叫 $user：子进程在全局作用域，$user 就是 $GLOBALS['user']
    $model = model('User');
    switch ($args['op']) {
        case 'register':
            $res = $model->register($param);
            break;
        case 'login':
            $res = $model->login($param);
            break;
        case 'info':
            $info = \think\Db::name('user')->where('user_id', $args['as_user'])->find();
            $GLOBALS['user'] = $info;
            $res = $model->info($param);
            break;
        case 'findpass':
            $res = $model->findpass($param);
            break;
        case 'regcheck':
            // 控制器先 htmlspecialchars(urldecode(trim()))（index/controller/User.php:340-341）
            $t = htmlspecialchars(urldecode(trim($param['t'])));
            $str = htmlspecialchars(urldecode(trim($param['str'])));
            $res = $model->regcheck($t, $str);
            $result['body'] = $res['code'] > 1 ? bin2hex($str) : null;
            break;
        case 'reg_msg':
            $GLOBALS['user'] = ['user_id' => 0, 'user_name' => '游客'];
            $res = $model->reg_msg($param);
            break;
        case 'findpass_msg':
            $GLOBALS['user'] = ['user_id' => 0, 'user_name' => '游客'];
            $res = $model->findpass_msg($param);
            break;
        case 'findpass_reset':
            $GLOBALS['user'] = ['user_id' => 0, 'user_name' => '游客'];
            $res = $model->findpass_reset($param);
            break;
        case 'popedom':
            $g = $args['group'];
            $group = $g === null ? null : ['group_id' => $g['group_id'], 'group_type' => $g['group_type'],
                'group_popedom' => json_decode($g['group_popedom'], true)];
            $GLOBALS['user'] = ['user_id' => $g === null ? 0 : 99, 'group_id' => $g === null ? 0 : $g['group_id'],
                'user_points' => $args['user_points'], 'group' => $group];
            $controller = (new ReflectionClass('app\index\controller\Base'))->newInstanceWithoutConstructor();
            $m = new ReflectionMethod('app\index\controller\Base', 'check_user_popedom');
            $m->setAccessible(true);
            $res = $m->invoke($controller, $args['type_id'], $args['popedom'], $args['param'], $args['flag'],
                $args['info'], $args['trysee']);
            break;
        default:
            throw new RuntimeException('unknown op');
    }
    $result['code'] = $res['code'];
    $result['msg'] = hexs($res['msg']);
    $result['set_cookies'] = set_cookie_headers();
    $rows = [];
    foreach ((array)($args['rows'] ?? []) as $name) {
        $rows[$name] = row_hex(\think\Db::name('user')->where('user_name', unhex($name))->order('user_id')->find());
    }
    $result['rows'] = $rows;
    $result['now'] = $now;
} catch (\Throwable $e) {
    // 不输出 message（可能含连接信息），只给类型
    $result['error'] = get_class($e);
    if (getenv('GOLDEN_DEBUG') === '1') {
        $result['debug'] = $e->getMessage() . ' @ ' . $e->getFile() . ':' . $e->getLine();
    }
}
echo json_encode($result, JSON_UNESCAPED_SLASHES);
