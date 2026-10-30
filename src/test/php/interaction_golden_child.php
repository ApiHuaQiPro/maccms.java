<?php
/*
 * B11（MACC-35）黄金样本的子进程：由 interaction_golden.php 以 php-cgi 执行，一次只处理一个用例。
 *
 * 只读加载仓库的 thinkphp/、application/，调用旧代码本身：
 *   - vodsearch：model('Vod')->listCacheData(['paging' => 'yes', 'num' => 20, 'by' => 'id', 'order' => 'asc'])，
 *     即旧搜索页模板里 {maccms:vod paging="yes"} 标签的调用；请求参数（wd/actor/…）放在 QUERY_STRING，经 mac_param_url() 读取。
 * 不修改任何旧 PHP 文件；数据库只连父进程新建的一次性库 maccms_it_*。
 * 输出：一个 JSON 对象（ids：结果的 vod_id 列表；total：总数）。
 */

error_reporting(E_ERROR | E_PARSE);
$repo = realpath(__DIR__ . '/../../../php');
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

$args = json_decode((string)getenv('GOLDEN_ARGS'), true);
$config = $args['config'];
\think\Config::set($config, 'maccms');
$GLOBALS['config'] = $config;
$GLOBALS['user'] = ['user_id' => 0, 'group_id' => 1, 'group' => ['group_type' => '']];

$result = ['op' => $args['op']];
try {
    switch ($args['op']) {
        case 'vodsearch':
            $res = model('Vod')->listCacheData(['paging' => 'yes', 'num' => 20, 'by' => 'id', 'order' => 'asc']);
            $ids = [];
            foreach ($res['list'] as $v) {
                $ids[] = (int)$v['vod_id'];
            }
            $result['ids'] = $ids;
            $result['total'] = $res['total'];
            break;
        default:
            throw new RuntimeException('unknown op');
    }
} catch (\Throwable $e) {
    // 不输出 message（可能含连接信息），只给类型
    $result['error'] = get_class($e);
    if (getenv('GOLDEN_DEBUG') === '1') {
        $result['debug'] = $e->getMessage() . ' @ ' . $e->getFile() . ':' . $e->getLine();
    }
}
echo json_encode($result, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
