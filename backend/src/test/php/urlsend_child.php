<?php
/*
 * B28（MACC-51）推送与回链检测黄金样本的子进程入口：由 urlsend_backlink_golden.php 以 php-cgi 执行，一次只处理一个请求。
 *
 * 与仓库的 _admin.php（GOLDEN_ENTRY=admin）或 api.php（GOLDEN_ENTRY=api）相同地定义常量，经 thinkphp/start.php 跑完整的 ThinkPHP 路由、
 * admin/controller/Base 与控制器（不修改任何旧 PHP 文件）。ROOT_PATH/APP_PATH 指向父进程为本次运行复制出来的临时站点
 * （GOLDEN_SITE_ROOT，其中的 application/database.php 指向一次性库 maccms_it_*）：定时任务会回写 extra/timming.php，只落在临时副本里。
 * 不检查 install.lock 与入口文件名。百度接口的请求经父进程设置的 http_proxy 到本机模拟服务（curl 读取小写的 http_proxy 环境变量）。
 */

$site = rtrim(getenv('GOLDEN_SITE_ROOT'), '/\\');
$entry = getenv('GOLDEN_ENTRY');
if ($entry !== 'admin' && $entry !== 'api') {
    header('Status: 599');
    echo 'GOLDEN_ENTRY';
    exit;
}
chdir($site);
header('Content-Type:text/html;charset=utf-8');
@ini_set('max_execution_time', '0');
@ini_set('memory_limit', '-1');
define('ROOT_PATH', $site . '/');
define('APP_PATH', $site . '/application/');
define('RUNTIME_PATH', $site . '/runtime/');
define('MAC_COMM', $site . '/application/common/common/');
define('MAC_HOME_COMM', $site . '/application/index/common/');
define('MAC_ADMIN_COMM', $site . '/application/admin/common/');
define('MAC_START_TIME', microtime(true));
if ($entry === 'api') {
    define('BIND_MODULE', 'api');
}
define('ENTRANCE', $entry);
$in_file = rtrim($_SERVER['SCRIPT_NAME'], '/');
if (substr($in_file, strlen($in_file) - 4) !== '.php') {
    $in_file = substr($in_file, 0, strpos($in_file, '.php')) . '.php';
}
define('IN_FILE', $in_file);
if (getenv('GOLDEN_SESSION_PATH')) {
    ini_set('session.save_path', getenv('GOLDEN_SESSION_PATH'));
}
require $site . '/thinkphp/start.php';
