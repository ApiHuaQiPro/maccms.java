<?php
/*
 * B12（MACC-36）后台黄金样本的子进程入口：由 admin_golden.php 以 php-cgi 执行，一次只处理一个请求。
 *
 * 与仓库的 _admin.php 相同地定义常量（ENTRANCE=admin，不绑定模块，URL 里带 admin 模块名）并经 thinkphp/start.php 跑完整的
 * ThinkPHP 路由、admin/controller/Base 的构造函数（登录检查与 check_auth）与控制器本身（不修改任何旧 PHP 文件），只有三处不同：
 * ① 不检查 application/data/install/install.lock；② 不检查入口文件名是否为 admin.php；③ 配置目录 CONF_PATH 与运行目录
 * RUNTIME_PATH 指向父进程为本次运行创建的临时目录（其中的 database.php 指向一次性库 maccms_it_*，extra/maccms.php 是按用例改写的副本）。
 * 会话文件目录经环境变量 GOLDEN_SESSION_PATH 传入（原因见 legacy_http_child.php 的说明）。
 */

$repo = realpath(__DIR__ . '/../../../..');
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
define('ENTRANCE', 'admin');
$in_file = rtrim($_SERVER['SCRIPT_NAME'], '/');
if (substr($in_file, strlen($in_file) - 4) !== '.php') {
    $in_file = substr($in_file, 0, strpos($in_file, '.php')) . '.php';
}
define('IN_FILE', $in_file);
if (getenv('GOLDEN_SESSION_PATH')) {
    ini_set('session.save_path', getenv('GOLDEN_SESSION_PATH'));
}
if (isset($_SERVER['PATH_INFO']) && !@mb_check_encoding($_SERVER['PATH_INFO'], 'utf-8')) {
    $_SERVER['PATH_INFO'] = @mb_convert_encoding($_SERVER['PATH_INFO'], 'UTF-8', 'GBK');
}
require $repo . '/thinkphp/start.php';
