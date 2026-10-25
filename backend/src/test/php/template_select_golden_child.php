<?php
/*
 * template_select_golden.php 的子进程：一次只跑一个用例。按仓库的 thinkphp/ 与 application/ 初始化 ThinkPHP（不连库），把用例的 site 配置、
 * 站群配置、app.lang 放进 config()，设置 $_SERVER 的 HTTP_USER_AGENT 与 HTTP_HOST，执行旧 app\common\behavior\Init::run，
 * 再按 ThinkPHP App::run 的顺序以 default_lang 加载语言包，输出 JSON。
 */

$repo = realpath(__DIR__ . '/../../../..');
chdir($repo);
$args = json_decode((string)getenv('GOLDEN_ARGS'), true);
define('APP_PATH', $repo . '/application/');
define('ROOT_PATH', $repo . '/');
define('RUNTIME_PATH', sys_get_temp_dir() . '/maccms-b12-tpl-golden/');
define('ENTRANCE', 'index');
require $repo . '/thinkphp/base.php';
\think\Config::set(include APP_PATH . 'config.php');
require THINK_PATH . 'helper.php';
\think\Loader::addNamespace('app', APP_PATH);

$maccms = include APP_PATH . 'extra/maccms.php';
foreach ($args['site'] as $k => $v) {
    $maccms['site'][$k] = $v;
}
if (array_key_exists('lang', $args)) {
    $maccms['app']['lang'] = $args['lang'];
}
\think\Config::set($maccms, 'maccms');
\think\Config::set(array_key_exists('domain', $args) ? $args['domain'] : [], 'domain');
if ($args['ua'] !== null) {
    $_SERVER['HTTP_USER_AGENT'] = $args['ua'];
} else {
    unset($_SERVER['HTTP_USER_AGENT']);
}
$_SERVER['HTTP_HOST'] = $args['host'];

$p = null;
try {
    (new \app\common\behavior\Init())->run($p);
} catch (\Throwable $e) {
    // 旧版的错误处理把 Notice/Warning 也转成异常：请求没有 User-Agent 时整页报错
    echo json_encode(['error' => get_class($e)]);
    exit;
}

$lang = \think\Config::get('default_lang');
\think\Lang::range($lang);
\think\Lang::load([APP_PATH . 'lang' . DIRECTORY_SEPARATOR . $lang . EXT]);

echo json_encode([
    'mob' => MAC_MOB,
    'path_tpl' => MAC_PATH_TPL,
    'path_ads' => MAC_PATH_ADS,
    'path_template' => MAC_PATH_TEMPLATE,
    'root_template' => substr(MAC_ROOT_TEMPLATE, strlen(ROOT_PATH)),
    'view_path' => \think\Config::get('template.view_path'),
    'site' => $GLOBALS['config']['site'],
    'default_lang' => $lang,
    'lang_sample' => [lang('permission_denied'), lang('menu/admin'), lang('vod')],
], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
