<?php
/*
 * B05 页面 URL 黄金向量生成脚本（路线图 B05，MACC-29）。与 mac_url_golden.php 相同的做法：只读加载仓库内的 thinkphp/ 与
 * application/common.php，在内存中套用合成的 URL 配置分支，执行旧 mac_url_* 生成本批新页面的地址。不修改任何旧 PHP 文件，
 * 不连数据库，不读仓库外配置，不访问网络。
 *
 * 用法（仓库根目录）：
 *   php src/test/php/b05_url_golden.php > src/test/resources/site/url-golden/b05-vectors.json
 * 只生成 site 支持的三种形态（仓库默认、rewrite、suffix_hide）；每个分支一个子进程（ThinkPHP 的静态状态）。
 * 说明见 docs/architecture/15-b05-readonly-pages.md 第 5、6 节。
 */

error_reporting(E_ALL & ~E_NOTICE & ~E_WARNING & ~E_DEPRECATED);

$repo = realpath(__DIR__ . '/../../../php');

function branches()
{
    return [
        'default' => ['rewrite.status' => '0', 'rewrite.suffix_hide' => '0'],
        'rewrite' => ['rewrite.status' => '1', 'rewrite.suffix_hide' => '0'],
        'suffix_hide' => ['rewrite.status' => '0', 'rewrite.suffix_hide' => '1'],
    ];
}

if ($argc < 2) {
    $flags = JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES;
    $lines = ['{',
        '  "description": ' . json_encode('B05 页面 URL 黄金向量：由 src/test/php/b05_url_golden.php 执行仓库内旧 PHP 代码生成；'
            . '输入全部为合成数据。不要手工修改，修改生成脚本后重新生成。', $flags) . ',',
        '  "branches": ['];
    $names = array_keys(branches());
    foreach ($names as $bi => $name) {
        $cmd = escapeshellarg(PHP_BINARY) . ' ' . escapeshellarg(__FILE__) . ' ' . escapeshellarg($name);
        $data = json_decode(shell_exec($cmd), true);
        if (!is_array($data)) {
            fwrite(STDERR, "branch $name failed\n");
            exit(1);
        }
        $lines[] = '    {"branch": ' . json_encode($name, $flags) . ', "config": ' . json_encode($data['config'], $flags)
            . ', "build": [';
        $n = count($data['build']);
        foreach ($data['build'] as $i => $v) {
            $lines[] = '      ' . json_encode($v, $flags) . ($i < $n - 1 ? ',' : '');
        }
        $lines[] = '    ]}' . ($bi < count($names) - 1 ? ',' : '');
    }
    $lines[] = '  ]';
    $lines[] = '}';
    echo implode("\n", $lines), "\n";
    exit(0);
}

$branchName = $argv[1];
define('APP_PATH', $repo . '/application/');
define('ROOT_PATH', $repo . '/');
define('RUNTIME_PATH', sys_get_temp_dir() . '/maccms-b05-url-golden-nonexistent/');
define('BIND_MODULE', 'index');
define('ENTRANCE', 'index');
define('IN_FILE', '/index.php');
require $repo . '/thinkphp/base.php';
\think\Config::set(include APP_PATH . 'config.php');
require THINK_PATH . 'helper.php';
require APP_PATH . 'common.php';
error_reporting(E_ALL & ~E_NOTICE & ~E_WARNING & ~E_DEPRECATED);
date_default_timezone_set('UTC');

$config = include APP_PATH . 'extra/maccms.php';
foreach (branches()[$branchName] as $k => $v) {
    list($a, $b) = explode('.', $k);
    $config[$a][$b] = $v;
}
\think\Config::set('url_route_on', $config['rewrite']['route_status']);
if (empty($config['app']['pathinfo_depr'])) {
    $config['app']['pathinfo_depr'] = '/';
}
\think\Config::set('pathinfo_depr', $config['app']['pathinfo_depr']);
$GLOBALS['config'] = $config;
define('MAC_PATH', $config['site']['install_dir'] . '');
define('MAC_PAGE_SP', $config['path']['page_sp'] . '');
\think\Route::bind(BIND_MODULE);

\think\Request::destroy();
$_SERVER = ['SCRIPT_NAME' => '/index.php', 'REQUEST_METHOD' => 'GET', 'HTTP_HOST' => 'maccms.local.test'];
$_GET = [];
$req = \think\Request::instance();
$req->root('/index.php');
$req->module('index');
$req->controller('Index');
$req->action('index');

// ---- 合成数据 ----
$vod = ['vod_id' => 123, 'vod_en' => 'hechengshipin', 'vod_time' => 1700000000, 'type_id' => 6,
    'type' => ['type_en' => 'hechengzilei'], 'type_1' => ['type_id' => 1, 'type_en' => 'hechengfenlei']];
$artType = ['type_id' => 21, 'type_en' => 'wenzhang', 'type_mid' => 2, 'type_pid' => 0,
    'type' => ['type_en' => 'wenzhang'], 'type_1' => ['type_id' => 0, 'type_en' => '']];
$actorType = ['type_id' => 31, 'type_en' => 'yanyuan', 'type_mid' => 8, 'type_pid' => 0,
    'type' => ['type_en' => 'yanyuan'], 'type_1' => ['type_id' => 0, 'type_en' => '']];
$websiteType = ['type_id' => 41, 'type_en' => 'wangzhi', 'type_mid' => 11, 'type_pid' => 0,
    'type' => ['type_en' => 'wangzhi'], 'type_1' => ['type_id' => 0, 'type_en' => '']];
$vodType = ['type_id' => 1, 'type_en' => 'dianying', 'type_mid' => 1, 'type_pid' => 0,
    'type' => ['type_en' => 'dianying'], 'type_1' => ['type_id' => 0, 'type_en' => '']];
$art = ['art_id' => 55, 'art_en' => 'wenzhang55', 'art_time' => 1700000000, 'type_id' => 21,
    'type' => ['type_en' => 'wenzhang'], 'type_1' => ['type_id' => 0, 'type_en' => '']];
$topic = ['topic_id' => 7, 'topic_en' => 'zhuanti7'];
$actor = ['actor_id' => 8, 'actor_en' => 'yanyuan8'];
$role = ['role_id' => 9, 'role_en' => 'juese9', 'actor_en' => ''];
$website = ['website_id' => 10, 'website_en' => 'wangzhi10'];

$cases = [
    ['vod.play.1-1', function () use ($vod) { return mac_url_vod_play($vod, ['sid' => 1, 'nid' => 1]); }],
    ['vod.play.2-10', function () use ($vod) { return mac_url_vod_play($vod, ['sid' => 2, 'nid' => 10]); }],
    ['vod.play.link', function () use ($vod) { return mac_url_vod_play($vod, ['sid' => '{sid}', 'nid' => '{nid}']); }],
    ['vod.player.1-2', function () { return mac_url('index/vod/player', ['id' => 123, 'sid' => 1, 'nid' => 2]); }],
    ['vod.player.2-10', function () { return mac_url('index/vod/player', ['id' => 123, 'sid' => 2, 'nid' => 10]); }],
    ['vod.down.1-1', function () use ($vod) { return mac_url_vod_down($vod, ['sid' => 1, 'nid' => 1]); }],
    ['vod.down.3-4', function () use ($vod) { return mac_url_vod_down($vod, ['sid' => 3, 'nid' => 4]); }],
    ['vod.downer.1-2', function () { return mac_url('index/vod/downer', ['id' => 123, 'sid' => 1, 'nid' => 2]); }],
    ['vod.plot', function () use ($vod) { return mac_url_vod_plot($vod); }],
    ['vod.plot.p2', function () use ($vod) { return mac_url_vod_plot($vod, ['page' => 2]); }],
    ['vod.role', function () use ($vod) { return mac_url_vod_role($vod); }],
    ['vod.rss', function () use ($vod) { return mac_url('vod/rss', ['id' => 123]); }],
    ['vod.ajax_detail', function () { return mac_url('vod/ajax_detail', ['id' => 123]); }],
    ['vod.ajax_show', function () { return mac_url('vod/ajax_show', ['id' => 1, 'area' => '大陆', 'page' => 2]); }],
    ['vod.ajax_search', function () { return mac_url('vod/ajax_search', ['wd' => '合成', 'page' => 2]); }],
    ['vod.show.none', function () use ($vodType) { return mac_url_type($vodType, [], 'show'); }],
    ['vod.show.filters', function () use ($vodType) {
        return mac_url_type($vodType, ['area' => '大陆', 'year' => '2020', 'page' => 2], 'show'); }],
    ['art.index', function () { return mac_url_art_index(); }],
    ['art.index.p2', function () { return mac_url_art_index(['page' => 2]); }],
    ['art.type', function () use ($artType) { return mac_url_type($artType); }],
    ['art.type.p2', function () use ($artType) { return mac_url_type($artType, ['page' => 2]); }],
    ['art.show', function () use ($artType) { return mac_url_type($artType, ['letter' => 'A', 'page' => 2], 'show'); }],
    ['art.detail', function () use ($art) { return mac_url_art_detail($art); }],
    ['art.detail.p1', function () use ($art) { return mac_url_art_detail($art, ['page' => 1]); }],
    ['art.detail.p3', function () use ($art) { return mac_url_art_detail($art, ['page' => 3]); }],
    ['art.search', function () { return mac_url_art_search(['wd' => '合成']); }],
    ['art.rss', function () { return mac_url('art/rss', ['id' => 55]); }],
    ['topic.index', function () { return mac_url_topic_index(); }],
    ['topic.index.p2', function () { return mac_url_topic_index(['page' => 2]); }],
    ['topic.detail', function () use ($topic) { return mac_url_topic_detail($topic); }],
    ['topic.search', function () { return mac_url('topic/search', ['wd' => '合成']); }],
    ['actor.index', function () { return mac_url_actor_index(); }],
    ['actor.index.p2', function () { return mac_url_actor_index(['page' => 2]); }],
    ['actor.type', function () use ($actorType) { return mac_url_type($actorType); }],
    ['actor.type.p2', function () use ($actorType) { return mac_url_type($actorType, ['page' => 2]); }],
    ['actor.show', function () use ($actorType) { return mac_url_type($actorType, ['sex' => '女', 'area' => '大陆'], 'show'); }],
    ['actor.detail', function () use ($actor) { return mac_url_actor_detail($actor); }],
    ['actor.search', function () { return mac_url_actor_search(['wd' => '合成']); }],
    ['role.index', function () { return mac_url_role_index(); }],
    ['role.index.p2', function () { return mac_url_role_index(['page' => 2]); }],
    ['role.detail', function () use ($role) { return mac_url_role_detail($role); }],
    ['role.show', function () { return mac_url('role/show', ['letter' => 'B', 'page' => 2]); }],
    ['role.search', function () { return mac_url('role/search', ['wd' => '合成']); }],
    ['website.index', function () { return mac_url_website_index(); }],
    ['website.index.p2', function () { return mac_url_website_index(['page' => 2]); }],
    ['website.type', function () use ($websiteType) { return mac_url_type($websiteType); }],
    ['website.type.p2', function () use ($websiteType) { return mac_url_type($websiteType, ['page' => 2]); }],
    ['website.show', function () use ($websiteType) { return mac_url_type($websiteType, ['area' => '大陆'], 'show'); }],
    ['website.detail', function () use ($website) { return mac_url_website_detail($website); }],
    ['website.search', function () { return mac_url_website_search(['wd' => '合成']); }],
    ['plot.index', function () { return mac_url_plot_index(); }],
    ['plot.index.p2', function () { return mac_url_plot_index(['page' => 2]); }],
    ['plot.detail', function () use ($vod) { return mac_url_plot_detail($vod); }],
    ['plot.detail.p2', function () use ($vod) { return mac_url_plot_detail($vod, ['page' => 2]); }],
    ['plot.search', function () { return mac_url('plot/search', ['wd' => '合成']); }],
];

$out = ['config' => ['rewrite.status' => $config['rewrite']['status'], 'rewrite.suffix_hide' => $config['rewrite']['suffix_hide'],
    'rewrite.route_status' => $config['rewrite']['route_status'], 'app.pathinfo_depr' => $config['app']['pathinfo_depr'],
    'site.install_dir' => $config['site']['install_dir']], 'build' => []];
foreach ($cases as $c) {
    $out['build'][] = ['id' => $c[0], 'output' => $c[1]()];
}
echo json_encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
