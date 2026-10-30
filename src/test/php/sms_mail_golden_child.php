<?php
/*
 * B25（MACC-48）短信与邮件验证码黄金样本的子进程：由 sms_mail_golden.php 以 php-cgi 执行，一次只处理一个用例（一个用例可以有几次调用）。
 *
 * 只读加载仓库的 thinkphp/、application/ 与 vendor/，调用旧代码本身（与 B09 的 account_golden_child.php 同样的引导方式）：
 *   - reg_msg / findpass_msg / bindmsg / findpass_reset / bind / unbind / register：model('User') 的同名方法（common/model/User.php），
 *     参数等同控制器 input() 之后的值（index/controller/User.php 的这些动作都是 input() 原样交给模型）；
 *   - test_email：后台 app\admin\controller\System::test_email（admin/controller/System.php:12-40）。实例化方式：子进程内定义一个
 *     System 的子类，构造函数只调用 All::__construct()（即 think\Controller 的构造 + _cl/_ac），跳过 admin Base 的登录与权限检查，
 *     然后经 Request 放入 POST 字段 type/nick/test（与 configemail.html 的 test_email() 一样）并调用 test_email()，取返回的 Json 响应。
 * 与真实前台一致的部分：
 *   - $GLOBALS['user']：游客与 common/controller/All.php:127-145 label_user 相同（user_id 0、"游客"、头像、group_id 1、points 0、group=组 1），
 *     会员 = 会员行 + group（与 User::checkLogin 相同）；
 *   - 前台 index/controller/Base 构造时会 label_maccms()，把 maccms 变量赋给 View 单例——邮件模板里的 {$maccms.site_name} 依赖它，
 *     这里同样调用一次（经 All 子类反射调用）；
 *   - 错误处理：thinkphp/base.php 已注册 think\Error（error_reporting(E_ALL) + set_error_handler），之后 common.php 把 error_reporting 改成
 *     E_ERROR|E_PARSE——与真实请求的顺序相同。所以阿里云 curl 失败时 trigger_error(E_USER_ERROR) 走 Error::appError：不在 error_reporting
 *     里 → 只记日志、函数返回后继续执行（不会中止）；
 *   - 未捕获的异常/错误（如 PHPMailer 类不存在）在真实应用里由 think\Error::appException 交给 Handle::render 输出 500 页面，这里同样调用
 *     Handle::render 得到状态码与 Content-Type，并记录异常类、消息与（仓库内相对）位置；该调用之后的调用不再执行（真实请求已经结束）。
 * 用例开始前清空 mac_user 与 mac_msg，按 users/msgs 写入种子（msgs 的 age 是相对用例参考时间 now 的秒数），覆盖配置项；结束后读出
 * 全部 mac_msg 与 mac_user 行。不修改任何旧 PHP 文件；数据库只连父进程新建的一次性库 maccms_it_*。
 * 输出：一个 JSON 对象（UTF-8 原文）。
 */

ob_start();
error_reporting(E_ERROR | E_PARSE);
$repo = realpath(__DIR__ . '/../../../php');
chdir($repo);

$args = json_decode((string)getenv('GOLDEN_ARGS'), true);
$runtime = rtrim(getenv('GOLDEN_RUNTIME_PATH'), '/\\');
$entrance = isset($args['entrance']) ? $args['entrance'] : 'index';
define('APP_PATH', $repo . '/application/');
define('ROOT_PATH', $repo . '/');
define('RUNTIME_PATH', $runtime . '/runtime/');
if ($entrance === 'index') {
    define('BIND_MODULE', 'index');
}
define('ENTRANCE', $entrance);
define('IN_FILE', $entrance === 'admin' ? '/_admin.php' : '/index.php');

$GLOBALS['golden_result'] = ['label' => $args['label'], 'calls' => []];
$GLOBALS['golden_done'] = false;
$GLOBALS['golden_repo'] = $repo;

/** 致命错误兜底（PHP 层面的 E_ERROR，不是异常）：清掉已有输出，照样输出 JSON。 */
register_shutdown_function(function () {
    if ($GLOBALS['golden_done']) {
        return;
    }
    $e = error_get_last();
    $r = $GLOBALS['golden_result'];
    $r['shutdownFatal'] = $e ? ['type' => $e['type'], 'message' => golden_rel($e['message']),
        'file' => golden_path($e['file']), 'line' => $e['line']] : null;
    while (ob_get_level() > 0) {
        ob_end_clean();
    }
    header('Content-Type: application/json', true, 200);
    echo json_encode($r, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE);
});

function golden_rel($s)
{
    $repo = $GLOBALS['golden_repo'];
    return str_replace([$repo . DIRECTORY_SEPARATOR, str_replace('\\', '/', $repo) . '/', $repo], '', (string)$s);
}

/** 仓库内文件的相对路径，分隔符统一为 /。 */
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
\think\Request::instance()->module($entrance === 'admin' ? 'admin' : 'index');
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
$GLOBALS['MAC_PATH_TEMPLATE'] = MAC_PATH . 'template/default/';
$GLOBALS['MAC_PATH_ADS'] = $GLOBALS['MAC_PATH_TEMPLATE'] . 'ads/';
$GLOBALS['http_type'] = 'http://';

// 配置：仓库的 extra/maccms.php，再按用例覆盖（键是点分路径，可以多层）
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

class GoldenAll extends \app\common\controller\All
{
    public function maccms()
    {
        $this->label_maccms();
    }
}

class GoldenSystem extends \app\admin\controller\System
{
    public function __construct()
    {
        // 跳过 admin/controller/Base 的登录与权限检查，只做 All（think\Controller）的构造
        \app\common\controller\All::__construct();
    }
}

const USER_COLUMNS = ['user_id', 'group_id', 'user_name', 'user_pwd', 'user_status', 'user_phone', 'user_email',
    'user_reg_ip', 'user_points'];

$result = &$GLOBALS['golden_result'];
$now = time();
$result['now'] = $now;
try {
    \think\Db::execute('TRUNCATE TABLE mac_user');
    \think\Db::execute('TRUNCATE TABLE mac_msg');
    foreach ((array)($args['users'] ?? []) as $row) {
        \think\Db::name('user')->insert($row);
    }
    foreach ((array)($args['msgs'] ?? []) as $m) {
        $row = $m;
        unset($row['age']);
        $row['msg_time'] = $now - $m['age'];
        \think\Db::name('msg')->insert($row);
    }
    $seededMsgIds = \think\Db::name('msg')->column('msg_id');

    // $GLOBALS['user']：游客 = label_user 的默认值，会员 = checkLogin 的 info
    $groupList = model('Group')->getCache('group_list');
    if (empty($args['as'])) {
        $GLOBALS['user'] = ['user_id' => 0, 'user_name' => lang('controller/visitor'),
            'user_portrait' => 'static/images/touxiang.png', 'group_id' => 1, 'points' => 0, 'group' => $groupList[1]];
    } else {
        $info = \think\Db::name('user')->where('user_id', $args['as'])->find();
        $info['group'] = $groupList[$info['group_id']];
        $GLOBALS['user'] = $info;
    }
    $result['user'] = ['user_id' => $GLOBALS['user']['user_id'], 'user_name' => $GLOBALS['user']['user_name']];

    if ($entrance === 'index') {
        (new GoldenAll())->maccms();
    }
} catch (\Throwable $e) {
    $result['setupError'] = get_class($e) . ': ' . golden_rel($e->getMessage());
}

if (!isset($result['setupError'])) {
    foreach ($args['calls'] as $call) {
        $rec = ['op' => $call['op'], 'params' => (object)$call['params']];
        try {
            $res = golden_call($call['op'], $call['params']);
            $rec['code'] = $res['code'];
            $rec['msg'] = $res['msg'];
            if (isset($res['http'])) {
                $rec['http'] = $res['http'];
            }
            $result['calls'][] = $rec;
        } catch (\Throwable $e) {
            $rec['fatal'] = golden_fatal($e);
            $result['calls'][] = $rec;
            break;
        }
    }
}

try {
    $msgs = [];
    foreach (\think\Db::name('msg')->order('msg_id')->select() as $m) {
        $msgs[] = ['msg_id' => (int)$m['msg_id'], 'seeded' => in_array($m['msg_id'], $seededMsgIds),
            'user_id' => (int)$m['user_id'], 'msg_type' => (int)$m['msg_type'], 'msg_status' => (int)$m['msg_status'],
            'msg_to' => $m['msg_to'], 'msg_code' => $m['msg_code'], 'msg_content' => $m['msg_content'],
            'age' => $now - (int)$m['msg_time']];
    }
    $result['msgsAfter'] = $msgs;
    $users = [];
    foreach (\think\Db::name('user')->order('user_id')->select() as $u) {
        $row = [];
        foreach (USER_COLUMNS as $c) {
            $row[$c] = in_array($c, ['user_id', 'group_id', 'user_status', 'user_reg_ip', 'user_points']) ? (int)$u[$c] : $u[$c];
        }
        $users[] = $row;
    }
    $result['usersAfter'] = $users;
} catch (\Throwable $e) {
    $result['readError'] = get_class($e);
}

$GLOBALS['golden_done'] = true;
while (ob_get_level() > 0) {
    ob_end_clean();
}
header('Content-Type: application/json');
echo json_encode($result, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE);

function golden_call($op, $params)
{
    $model = model('User');
    switch ($op) {
        case 'reg_msg':
        case 'findpass_msg':
        case 'bindmsg':
        case 'findpass_reset':
        case 'bind':
        case 'unbind':
        case 'register':
            return $model->$op($params);
        case 'test_email':
            // 父进程把 params 作为真实的 POST 表单体（application/x-www-form-urlencoded）交给 php-cgi，input() 照常读取
            $controller = new GoldenSystem();
            $resp = $controller->test_email();
            $data = $resp->getData();
            $data['http'] = ['status' => $resp->getCode(), 'contentType' => 'application/json'];
            return $data;
        default:
            throw new RuntimeException('unknown op');
    }
}

/** 真实应用里未捕获的 Throwable：think\Error::appException → Handle::report + render。 */
function golden_fatal(\Throwable $e)
{
    $out = ['class' => get_class($e), 'message' => golden_rel($e->getMessage()), 'file' => golden_path($e->getFile()),
        'line' => $e->getLine()];
    try {
        $wrapped = $e instanceof \Exception ? $e : new \think\exception\ThrowableError($e);
        $handler = \think\Error::getExceptionHandler();
        $handler->report($wrapped);
        $level = ob_get_level();
        $resp = $handler->render($wrapped);
        while (ob_get_level() < $level) {
            ob_start();
        }
        $headers = $resp->getHeader();
        $ct = isset($headers['Content-Type']) ? $headers['Content-Type'] : null;
        $out['http'] = ['status' => $resp->getCode(), 'contentType' => $ct,
            'bodyContainsMessage' => strpos($resp->getContent(), htmlentities($e->getMessage())) !== false
                || strpos($resp->getContent(), $e->getMessage()) !== false];
    } catch (\Throwable $e2) {
        $out['renderError'] = get_class($e2) . ': ' . golden_rel($e2->getMessage());
    }
    return $out;
}
