<?php
/*
 * D-B09-9（B13，MACC-37）头像登记附件黄金样本的子进程入口：由 portrait_annex_golden.php 以 php-cgi 执行，一次只处理一个前台请求。
 *
 * 与仓库的 index.php 相同地经 thinkphp/start.php 跑完整的 ThinkPHP 路由、index/controller/Base 与 User 的构造函数（label_user 按
 * cookie 认出会员）和 User::portrait → common/model/Upload::upload（think-image 缩略图、mac_user 更新、mac_annex 登记）本身，
 * 不修改任何旧 PHP 文件。与 index.php 的不同：
 *   - 工作目录与 ROOT_PATH 指向父进程为本次运行创建的临时站点根目录（GOLDEN_SITE_ROOT），旧代码按 ROOT_PATH 移动上传文件、按
 *     "./upload/…" 读写头像时只作用于临时目录，不碰仓库里的 upload/；
 *   - VENDOR_PATH 指向仓库的 vendor/（不在 git 中，按 AGENTS.md 从备份恢复；用其中的 think-image）；
 *   - CONF_PATH、RUNTIME_PATH 指向临时目录（database.php 指向一次性库 maccms_it_*）；
 *   - 不检查 install.lock（一次性站点没有安装锁文件）。
 */

$repo = realpath(__DIR__ . '/../../../..');
$site = rtrim(getenv('GOLDEN_SITE_ROOT'), '/\\');
chdir($site);
header('Content-Type:text/html;charset=utf-8');
@ini_set('max_execution_time', '0');
@ini_set('memory_limit', '-1');
define('ROOT_PATH', $site . '/');
define('APP_PATH', $repo . '/application/');
define('VENDOR_PATH', $repo . '/vendor/');
define('CONF_PATH', rtrim(getenv('GOLDEN_CONF_PATH'), '/\\') . '/');
define('RUNTIME_PATH', rtrim(getenv('GOLDEN_RUNTIME_PATH'), '/\\') . '/');
define('MAC_COMM', $repo . '/application/common/common/');
define('MAC_HOME_COMM', $repo . '/application/index/common/');
define('MAC_ADMIN_COMM', $repo . '/application/admin/common/');
define('MAC_START_TIME', microtime(true));
define('BIND_MODULE', 'index');
define('ENTRANCE', 'index');
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
