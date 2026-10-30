<?php
/*
 * B22（MACC-45）安装向导黄金样本的子进程入口：由 install_golden.php 以 php-cgi 执行，一次只处理一个请求。
 *
 * 与仓库的 install.php 逐行相同（版本检查、安装锁、runtime 可写检查、BIND_MODULE=install），只是 ROOT_PATH/APP_PATH 指向父进程为本场景
 * 复制出来的临时站点（GOLDEN_SITE_ROOT）：旧向导写的 application/database.php、extra/maccms.php、data/install/install.lock 都只落在临时副本里。
 * 不修改任何旧 PHP 文件。
 */

$site = rtrim(getenv('GOLDEN_SITE_ROOT'), '/\\');
chdir($site);
header('Content-Type:text/html;charset=utf-8');
if (version_compare(PHP_VERSION, '5.5.0', '<')) die('PHP版本需要>=5.5，请升级【PHP version requires > = 5.5，please upgrade】');
@ini_set('max_execution_time', '0');
@ini_set("memory_limit", '-1');
define('ROOT_PATH', $site . '/');
define('APP_PATH', $site . '/application/');
define('RUNTIME_PATH', $site . '/runtime/');
define('MAC_COMM', $site . '/application/common/common/');
define('MAC_HOME_COMM', $site . '/application/index/common/');
define('MAC_ADMIN_COMM', $site . '/application/admin/common/');
define('MAC_START_TIME', microtime(true));
define('BIND_MODULE', 'install');
define('ENTRANCE', 'install');
$in_file = rtrim($_SERVER['SCRIPT_NAME'], '/');
if (substr($in_file, strlen($in_file) - 4) !== '.php') {
    $in_file = substr($in_file, 0, strpos($in_file, '.php')) . '.php';
}
define('IN_FILE', $in_file);
if (getenv('GOLDEN_SESSION_PATH')) {
    ini_set('session.save_path', getenv('GOLDEN_SESSION_PATH'));
}
if (is_file('./application/data/install/install.lock')) {
    echo '如需重新安装请删除【To re install, please remove】 >>> /application/data/install/install.lock';
    exit;
}
if (!is_writable('./runtime')) {
    echo '请开启[runtime]目录的读写权限【Please turn on the read and write permissions of the [runtime] folder】';
    exit;
}
require $site . '/thinkphp/start.php';
