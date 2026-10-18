<?php
/*
 * B06（MACC-30）PHP 黄金样本的子进程入口：由 provide_golden.php 以 php-cgi 执行，一次只处理一个请求。
 *
 * 与仓库的 api.php / index.php 相同地定义常量并经 thinkphp/start.php 跑完整的 ThinkPHP 路由与控制器（不修改任何旧 PHP 文件），
 * 只有两处不同：① 不检查 application/data/install/install.lock（黄金样本不在仓库里创建该文件）；② 配置目录 CONF_PATH 与运行目录
 * RUNTIME_PATH 指向父进程为本次运行创建的临时目录（其中的 database.php 指向一次性库 maccms_it_*，extra/maccms.php 是按用例改写的副本）。
 * php-cgi 把响应头与正文原样写到标准输出，父进程据此记录状态码、Content-Type 与正文字节。
 *
 * 环境变量（父进程设置）：GOLDEN_ENTRY（api|index）、GOLDEN_CONF_PATH、GOLDEN_RUNTIME_PATH，以及 CGI 变量
 * （REQUEST_METHOD、REQUEST_URI、QUERY_STRING、PATH_INFO、REMOTE_ADDR、HTTP_*）。
 */

$repo = realpath(__DIR__ . '/../../../..');
$entry = getenv('GOLDEN_ENTRY');
if ($entry !== 'api' && $entry !== 'index') {
    header('Status: 599');
    echo 'GOLDEN_ENTRY';
    exit;
}
chdir($repo);
header('Content-Type:text/html;charset=utf-8');
@ini_set('max_execution_time', '0');
@ini_set('memory_limit', '-1');
define('ROOT_PATH', $repo . '/');
define('APP_PATH', $repo . '/application/');
define('CONF_PATH', rtrim(getenv('GOLDEN_CONF_PATH'), '/\\') . '/');
define('RUNTIME_PATH', rtrim(getenv('GOLDEN_RUNTIME_PATH'), '/\\') . '/');
define('MAC_COMM', $repo . '/application/common/common/');
define('MAC_HOME_COMM', $repo . '/application/index/common/');
define('MAC_ADMIN_COMM', $repo . '/application/admin/common/');
define('MAC_START_TIME', microtime(true));
define('BIND_MODULE', $entry);
define('ENTRANCE', $entry);
define('IN_FILE', '/' . $entry . '.php');
if (isset($_SERVER['PATH_INFO']) && !@mb_check_encoding($_SERVER['PATH_INFO'], 'utf-8')) {
    $_SERVER['PATH_INFO'] = @mb_convert_encoding($_SERVER['PATH_INFO'], 'UTF-8', 'GBK');
}
require $repo . '/thinkphp/start.php';
