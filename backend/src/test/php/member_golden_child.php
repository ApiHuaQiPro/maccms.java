<?php
/*
 * B08（MACC-32）会员登录态、验证码与频率限制黄金样本的子进程：由 member_golden.php 以 php-cgi 执行，一次只处理一个操作。
 *
 * php-cgi 按真实请求解析 HTTP_COOKIE（$_COOKIE 的拆分、解码、重名与数组名都由 PHP 自己完成），然后只读加载仓库的 thinkphp/、
 * application/ 与 vendor/topthink/think-captcha（vendor 不在 git 中，按 AGENTS.md 从备份恢复），调用旧代码本身：
 *   - label_user：经反射调用 app\common\controller\All::label_user（common/controller/All.php:118-147），其中走
 *     model('User')->checkLogin()（common/model/User.php:422-473）；
 *   - login：model('User')->login()（User.php:321-392），只关心签发的 cookie 与写入的 user_random；
 *   - setcookie：ThinkPHP cookie() 助手（thinkphp/helper.php:323-341、library/think/Cookie.php）；
 *   - parse：$_COOKIE 原样；
 *   - captcha_check / captcha_image：think-captcha 的 Captcha::check / entry（会话里放入构造的验证码记录）；
 *   - timespan：mac_get_time_span（application/common.php:306-316）；
 *   - expire：model('User')->expire()（User.php:394-408）。
 * 不修改任何旧 PHP 文件；数据库只连父进程新建的一次性库 maccms_it_*；缓存与会话目录是父进程为本次运行新建的临时目录。
 * 输出：正文是一个 JSON 对象（字符串一律给出十六进制字节，避免非 UTF-8 输入让 json_encode 失败）。
 *
 * 环境变量：GOLDEN_OP、GOLDEN_ARGS（JSON）、GOLDEN_RUNTIME_PATH、GOLDEN_DB_HOST/PORT/NAME/USER/PASSWORD，以及 CGI 变量。
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
// think-captcha 由 base.php 注册的 composer 自动加载引入（vendor/composer/autoload_files.php 含其 helper.php）
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
foreach (['maccms', 'captcha'] as $name) {
    \think\Config::set(include APP_PATH . 'extra/' . $name . '.php', $name);
}
$config = include APP_PATH . 'extra/maccms.php';
$config['app']['cache_core'] = 0;
$config['app']['cache_flag'] = 'golden';
$config['user']['login_verify'] = '0';
$GLOBALS['config'] = $config;
define('MAC_PATH', '/');

function hexs($v)
{
    if (is_array($v)) {
        $out = [];
        foreach ($v as $k => $x) {
            $out[] = ['k' => bin2hex((string)$k), 'v' => hexs($x)];
        }
        return ['array' => $out];
    }
    if ($v === null) {
        return null;
    }
    return bin2hex((string)$v);
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

function user_row($id)
{
    $row = \think\Db::name('user')->where('user_id', $id)->field('group_id,user_random,user_end_time')->find();
    return $row === null ? null : ['group_id' => (int)$row['group_id'], 'user_random' => $row['user_random'],
        'user_end_time' => (int)$row['user_end_time']];
}

/** think-captcha 的私有 authcode（Captcha.php），用于构造会话里的验证码记录。 */
function captcha_authcode($str)
{
    $key = substr(md5('ThinkPHP.CN'), 5, 8);
    $str = substr(md5($str), 8, 10);
    return md5($key . $str);
}

class GoldenView
{
    public function assign($name, $value = '')
    {
    }
}

$op = getenv('GOLDEN_OP');
$args = json_decode((string)getenv('GOLDEN_ARGS'), true);
$result = ['op' => $op, 'cookies_seen' => hexs($_COOKIE)];
try {
    switch ($op) {
        case 'parse':
            break;

        case 'setcookie':
            foreach ($args['calls'] as $call) {
                $value = $call['value'] === null ? null : hex2bin($call['value']);
                if (array_key_exists('option', $call)) {
                    cookie($call['name'], $value, $call['option']);
                } else {
                    cookie($call['name'], $value);
                }
            }
            $result['set_cookies'] = set_cookie_headers();
            $result['request_time'] = $_SERVER['REQUEST_TIME'];
            break;

        case 'label_user':
            $attempts = 0;
            do {
                $attempts++;
                $t = time();
                if (isset($args['end_offset'])) {
                    \think\Db::name('user')->where('user_id', $args['end_offset']['user_id'])->update([
                        'user_end_time' => $t + $args['end_offset']['offset'],
                        'group_id' => $args['end_offset']['group_id']]);
                }
                $GLOBALS['user'] = null;
                $controller = (new ReflectionClass('app\index\controller\Base'))->newInstanceWithoutConstructor();
                $view = new ReflectionProperty('think\Controller', 'view');
                $view->setAccessible(true);
                $view->setValue($controller, new GoldenView());
                $m = new ReflectionMethod('app\common\controller\All', 'label_user');
                $m->setAccessible(true);
                $m->invoke($controller);
            } while (isset($args['end_offset']) && time() !== $t && $attempts < 5);
            $u = $GLOBALS['user'];
            $result['now'] = $t;
            $result['same_second'] = time() === $t;
            $result['member'] = (int)$u['user_id'] > 0;
            $result['user_id'] = (int)$u['user_id'];
            $result['user_name'] = bin2hex((string)$u['user_name']);
            $result['info_group_id'] = isset($u['group_id']) ? (int)$u['group_id'] : null;
            $result['group_group_id'] = isset($u['group']['group_id']) ? (int)$u['group']['group_id'] : null;
            $result['set_cookies'] = set_cookie_headers();
            if (isset($args['row'])) {
                $result['row_after'] = user_row($args['row']);
            }
            break;

        case 'login':
            $res = model('User')->login(['user_name' => $args['user_name'], 'user_pwd' => $args['user_pwd'],
                'verify' => '', 'openid' => '', 'col' => '']);
            $result['code'] = $res['code'];
            $result['set_cookies'] = set_cookie_headers();
            $result['request_time'] = $_SERVER['REQUEST_TIME'];
            $result['row_after'] = user_row($args['row']);
            break;

        case 'captcha_check':
            $now = time();
            $key = captcha_authcode('ThinkPHP.CN');
            if ($args['stored'] !== null) {
                \think\Session::set($key . $args['stored']['id'], [
                    'verify_code' => captcha_authcode(strtoupper($args['stored']['code'])),
                    'verify_time' => $now - $args['stored']['age']], '');
            }
            $checks = [];
            foreach ($args['inputs'] as $in) {
                $ok = captcha_check(hex2bin($in['code']), $in['id']);
                $checks[] = ['ok' => $ok, 'still_stored' => \think\Session::has($key . ($args['stored'] === null
                    ? '' : $args['stored']['id']), '')];
            }
            $result['same_second'] = time() === $now;
            $result['checks'] = $checks;
            break;

        case 'captcha_image':
            $captcha = new \think\captcha\Captcha(array_merge((array)\think\Config::get('captcha'), $args['override']));
            $response = $captcha->entry($args['id']);
            $png = $response->getContent();
            $size = getimagesizefromstring($png);
            $key = captcha_authcode('ThinkPHP.CN') . $args['id'];
            $stored = \think\Session::get($key, '');
            $result['width'] = $size[0];
            $result['height'] = $size[1];
            $result['mime'] = $size['mime'];
            $result['stored_fields'] = is_array($stored) ? array_keys($stored) : null;
            $result['stored_age'] = is_array($stored) ? time() - $stored['verify_time'] : null;
            break;

        case 'timespan':
            $t = time();
            if ($args['offset'] !== null) {
                session($args['key'], $t - $args['offset']);
            }
            $span = mac_get_time_span($args['key']);
            $result['same_second'] = time() === $t;
            $result['span'] = $span;
            $result['offset'] = $args['offset'];
            $result['stored_is_now'] = (int)session($args['key']) === $t;
            break;

        case 'expire':
            $attempts = 0;
            do {
                $attempts++;
                $t = time();
                foreach ($args['rows'] as $r) {
                    \think\Db::name('user')->where('user_id', $r['user_id'])->update([
                        'user_end_time' => max(0, $t + $r['offset']), 'group_id' => $r['group_id']]);
                }
                $res = model('User')->expire();
            } while (time() !== $t && $attempts < 5);
            $result['same_second'] = time() === $t;
            $result['code'] = $res['code'];
            $after = [];
            foreach ($args['rows'] as $r) {
                $after[] = ['user_id' => $r['user_id'], 'offset' => $r['offset'], 'group_before' => $r['group_id'],
                    'group_after' => user_row($r['user_id'])['group_id']];
            }
            $result['rows'] = $after;
            break;

        default:
            throw new RuntimeException('unknown op');
    }
} catch (\Throwable $e) {
    // 不输出 message（可能含连接信息），只给类型
    $result['error'] = get_class($e);
}
echo json_encode($result, JSON_UNESCAPED_SLASHES);
