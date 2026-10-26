<?php
/*
 * B13（MACC-37）后台内容管理黄金样本的子进程入口：由 admin_content_golden.php 以 php-cgi 执行，一次只处理一个请求。
 *
 * 与 admin_http_child.php（B12）相同地经 thinkphp/start.php 跑完整的 ThinkPHP 路由、admin/controller/Base 的构造函数与控制器本身
 * （不修改任何旧 PHP 文件），另有一处不同：工作目录与 ROOT_PATH 指向父进程为本次运行创建的临时站点根目录（GOLDEN_SITE_ROOT，
 * 其中有 upload/ 与只读复制的 static/data/pinyin.dat、static/ueditor/config.json），所以旧代码按 "./upload/…" 删除、按 ROOT_PATH
 * 移动上传文件、按 "./static/data/pinyin.dat" 读拼音字典时都只作用于临时目录，不碰仓库里的 upload/。
 * 配置目录 CONF_PATH 与运行目录 RUNTIME_PATH 同样指向临时目录（database.php 指向一次性库 maccms_it_*）。
 */

$repo = realpath(__DIR__ . '/../../../..');
$site = rtrim(getenv('GOLDEN_SITE_ROOT'), '/\\');
chdir($site);
header('Content-Type:text/html;charset=utf-8');
@ini_set('max_execution_time', '0');
@ini_set('memory_limit', '-1');
define('ROOT_PATH', $site . '/');
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
