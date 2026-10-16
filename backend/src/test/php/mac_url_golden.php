<?php
/*
 * 旧 URL 黄金向量生成脚本（MACC-10）。只读加载仓库内的 thinkphp/ 与 application/common.php，
 * 在内存中套用合成的 URL 配置分支，执行旧代码的 URL 生成（mac_url 等）与 PATHINFO 解析（App::routeCheck
 * + mac_param_url），把结果写成 JSON。不修改任何旧 PHP 文件，不连数据库，不读仓库外配置，不访问网络。
 *
 * 用法（仓库根目录）：
 *   php backend/src/test/php/mac_url_golden.php > backend/src/test/resources/site/url-golden/php-vectors.json
 * 内部为每个配置分支启动一个子进程（ThinkPHP 的 Route/Config/Request 是进程级静态状态）。
 * 说明见 docs/frontend/02-url-golden-vectors.md。
 */

error_reporting(E_ALL & ~E_NOTICE & ~E_WARNING & ~E_DEPRECATED);

$repo = realpath(__DIR__ . '/../../../..');

/** 配置分支：只覆盖 URL 相关项，其余取仓库 application/extra/maccms.php。 */
function branches()
{
    return [
        'default' => ['desc' => '仓库默认：pathinfo_depr=/、rewrite.status=0、route_status=0、suffix_hide=0',
            'set' => []],
        'rewrite' => ['desc' => '条件分支：rewrite.status=1（生成不含 index.php；能否到达 PHP 取决于部署转发）',
            'set' => ['rewrite.status' => '1']],
        'suffix_hide' => ['desc' => '条件分支：rewrite.suffix_hide=1',
            'set' => ['rewrite.suffix_hide' => '1']],
        'route' => ['desc' => '条件分支：rewrite.route_status=1，路由表为仓库 application/route.php',
            'set' => ['rewrite.route_status' => '1']],
        'rewrite_route' => ['desc' => '条件分支：rewrite.status=1 + route_status=1',
            'set' => ['rewrite.status' => '1', 'rewrite.route_status' => '1']],
        'depr_dash' => ['desc' => '条件分支：app.pathinfo_depr=-',
            'set' => ['app.pathinfo_depr' => '-']],
    ];
}

if ($argc < 2) {
    // 父进程：逐个分支启动子进程并合并
    $out = [
        'description' => 'MacCMS 旧 URL 黄金向量：由 backend/src/test/php/mac_url_golden.php 执行仓库内旧 PHP 代码生成；'
            . '输入全部为合成数据。不要手工修改，修改生成脚本后重新生成。',
        'sources' => [
            'mac_url' => 'application/common.php:1732-2428',
            'mac_url_type' => 'application/common.php:2467-2487',
            'mac_url_vod_detail' => 'application/common.php:2564-2567',
            'mac_url_vod_play' => 'application/common.php:2572-2592',
            'mac_url_search' => 'application/common.php:2462-2465',
            'mac_url_page' => 'application/common.php:2429-2434',
            'mac_param_url' => 'application/common.php:1560-1606',
            'url()' => 'thinkphp/library/think/Url.php:28-163（build）、:296-319（getRuleUrl）',
            'routeCheck' => 'thinkphp/library/think/App.php:622-660；Route::parseUrl thinkphp/library/think/Route.php:1217-1276',
            'pathinfo' => 'thinkphp/library/think/Request.php:401-445（?s= 兼容、后缀去除）',
            'config' => 'application/config.php:80-92（var_pathinfo、url_html_suffix、url_route_on）；'
                . 'application/common/behavior/Init.php:81-85（route_status→url_route_on、pathinfo_depr）；'
                . 'index.php:20（BIND_MODULE=index）',
        ],
        'branches' => [],
    ];
    foreach (branches() as $name => $b) {
        $cmd = escapeshellarg(PHP_BINARY) . ' ' . escapeshellarg(__FILE__) . ' ' . escapeshellarg($name);
        $json = shell_exec($cmd);
        $data = json_decode($json, true);
        if (!is_array($data)) {
            fwrite(STDERR, "branch $name failed:\n$json\n");
            exit(1);
        }
        $out['branches'][] = $data;
    }
    // 每个向量一行，便于审阅 diff
    $flags = JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES;
    $lines = ["{",
        '  "description": ' . json_encode($out['description'], $flags) . ',',
        '  "sources": ' . json_encode($out['sources'], $flags) . ',',
        '  "branches": ['];
    foreach ($out['branches'] as $bi => $b) {
        $lines[] = '    {"branch": ' . json_encode($b['branch'], $flags) . ', "description": '
            . json_encode($b['description'], $flags) . ',';
        $lines[] = '     "config": ' . json_encode($b['config'], $flags) . ',';
        foreach (['build', 'parse'] as $kind) {
            $lines[] = '     "' . $kind . '": [';
            $n = count($b[$kind]);
            foreach ($b[$kind] as $i => $v) {
                $lines[] = '      ' . json_encode($v, $flags) . ($i < $n - 1 ? ',' : '');
            }
            $lines[] = '     ]' . ($kind === 'build' ? ',' : '');
        }
        $lines[] = '    }' . ($bi < count($out['branches']) - 1 ? ',' : '');
    }
    $lines[] = '  ]';
    $lines[] = '}';
    echo implode("\n", $lines), "\n";
    exit(0);
}

$branchName = $argv[1];
$branch = branches()[$branchName];

// ---- 引导（与 index.php、thinkphp/start.php 相同的常量，不执行 App::run） ----
define('APP_PATH', $repo . '/application/');
define('ROOT_PATH', $repo . '/');
// 运行时目录指向不存在的临时位置：不读仓库 runtime/ 下的路由缓存，也不写任何文件
define('RUNTIME_PATH', sys_get_temp_dir() . '/maccms-url-golden-nonexistent/');
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
foreach ($branch['set'] as $k => $v) {
    list($a, $b) = explode('.', $k);
    $config[$a][$b] = $v;
}
// Init.php:81-85
\think\Config::set('url_route_on', $config['rewrite']['route_status']);
if (empty($config['app']['pathinfo_depr'])) {
    $config['app']['pathinfo_depr'] = '/';
}
\think\Config::set('pathinfo_depr', $config['app']['pathinfo_depr']);
$GLOBALS['config'] = $config;
define('MAC_PATH', $config['site']['install_dir'] . '');
define('MAC_PAGE_SP', $config['path']['page_sp'] . '');
// App.php:85-86
\think\Route::bind(BIND_MODULE);
// App.php:637-642：路由开启时导入 application/route.php（生成 URL 时请求已经过 routeCheck，规则已导入）
if ($config['rewrite']['route_status']) {
    \think\Route::import(include APP_PATH . 'route.php');
}

/** 生成用例对应的旧代码位置（行号基于当前仓库）。 */
function build_source($id)
{
    $map = [
        'home.' => 'application/common/controller/All.php:61（$maccms[\'path\']=MAC_PATH）；application/common/behavior/Init.php:52',
        'index.' => 'application/common.php:1757-1768（mac_url index/index 分支）+ :2376-2426（尾部处理）',
        'type.pagelink.' => 'application/common.php:2429-2434（mac_url_page）+ mac_url_type',
        'type.' => 'application/common.php:2467-2487（mac_url_type）+ :1824-1853（mac_url vod/type）+ :2376-2426',
        'detail' => 'application/common.php:2564-2567（mac_url_vod_detail）+ :1854-1882（mac_url vod/detail）+ :2376-2426',
        'play.' => 'application/common.php:2572-2592（mac_url_vod_play）+ :1883-1924（mac_url vod/play）+ :2376-2426',
        'search.' => 'application/common.php:2462-2465（mac_url_search）+ :2372-2374（mac_url default）+ :2376-2426',
        'show.' => 'application/common.php:2467-2487（mac_url_type flag=show）+ :1803-1823（mac_url vod/show）+ :2376-2426',
        'user.' => 'application/common.php:2372-2374（mac_url default）+ :2376-2426',
    ];
    foreach ($map as $prefix => $src) {
        if (strpos($id, $prefix) === 0) {
            return $src;
        }
    }
    return 'application/common.php（mac_url）';
}

/**
 * 用旧代码解析一个请求：App::routeCheck 得到调度，再取 mac_param_url()（控制器实际使用的参数）。
 * $in: pathInfo（Web 服务器交给 PHP 的 PATH_INFO，已解码）| s（?s= 参数）；query（其余 GET 参数）。
 * CLI 下 Request::pathinfo() 读 argv[1]（Request.php:407-409），与 FPM 读 PATH_INFO 的效果相同。
 */
function php_parse(array $in, array $keys = ['id', 'sid', 'nid', 'wd', 'area', 'year'])
{
    \think\Request::destroy();
    $query = isset($in['query']) ? $in['query'] : [];
    $_GET = $query;
    $_SERVER = ['SCRIPT_NAME' => '/index.php', 'REQUEST_METHOD' => 'GET', 'HTTP_HOST' => 'maccms.local.test',
        'argv' => ['index.php']];
    if (isset($in['s'])) {
        $_GET['s'] = $in['s'];
    } else {
        $_SERVER['argv'][1] = $in['pathInfo'];
    }
    $_REQUEST = $_GET;
    $request = \think\Request::instance();
    $entry = [];
    try {
        $dispatch = \think\App::routeCheck($request, \think\Config::get());
        if ($dispatch['type'] !== 'module') {
            return ['dispatch' => $dispatch['type']];
        }
        // App::module 中的规范化：控制器/操作为空取默认，url_convert 时转小写（thinkphp/library/think/App.php）
        $r = $dispatch['module'];
        $controller = strtolower(strip_tags($r[1] ?: \think\Config::get('default_controller')));
        $action = strtolower(strip_tags($r[2] ?: \think\Config::get('default_action')));
        $entry['dispatch'] = $controller . '/' . $action;
        // 调度目标是否真实存在（控制器文件与公开方法）；不存在时旧站在调度阶段报错
        $file = APP_PATH . 'index/controller/' . \think\Loader::parseName($controller, 1) . '.php';
        $entry['handlerExists'] = is_file($file)
            && preg_match('/public\s+function\s+' . preg_quote($action, '/') . '\s*\(/i', file_get_contents($file)) === 1;
        $p = mac_param_url();
        // 只记录本批相关且非空的参数（page 恒记录）
        $entry['param'] = ['page' => $p['page']];
        foreach ($keys as $k) {
            if ($p[$k] !== '' && $p[$k] !== 0) {
                $entry['param'][$k] = $p[$k];
            }
        }
    } catch (\think\exception\HttpException $e) {
        $entry['error'] = 'HTTP ' . $e->getStatusCode();
    } catch (\Exception $e) {
        $entry['error'] = get_class($e);
    }
    return $entry;
}

$result = [
    'branch' => $branchName,
    'description' => $branch['desc'],
    'config' => [
        'app.pathinfo_depr' => $config['app']['pathinfo_depr'],
        'rewrite.status' => $config['rewrite']['status'],
        'rewrite.route_status' => $config['rewrite']['route_status'],
        'rewrite.suffix_hide' => $config['rewrite']['suffix_hide'],
        'rewrite.type_id' => $config['rewrite']['type_id'],
        'rewrite.vod_id' => $config['rewrite']['vod_id'],
        'path.page_sp' => $config['path']['page_sp'],
        'site.install_dir' => $config['site']['install_dir'],
        'view.vod_type' => $config['view']['vod_type'],
        'view.vod_detail' => $config['view']['vod_detail'],
        'view.vod_play' => $config['view']['vod_play'],
        'url_html_suffix' => \think\Config::get('url_html_suffix'),
    ],
    'build' => [],
    'parse' => [],
];

// ---- 合成数据 ----
$type = ['type_id' => 1, 'type_en' => 'hechengfenlei', 'type_mid' => 1, 'type_pid' => 0,
    'type' => ['type_en' => 'hechengfenlei'], 'type_1' => ['type_id' => 0, 'type_en' => '']];
$typeMax = array_merge($type, ['type_id' => 65535, 'type_en' => 'zuidafenlei']);
$vod = ['vod_id' => 123, 'vod_en' => 'hechengshipin', 'vod_time' => 1700000000, 'type_id' => 6,
    'type' => ['type_en' => 'hechengzilei'], 'type_1' => ['type_id' => 1, 'type_en' => 'hechengfenlei']];
$vodMin = array_merge($vod, ['vod_id' => 1]);
$vodMax = array_merge($vod, ['vod_id' => 4294967295]);

/**
 * 生成用例。call 是传给旧函数的调用（只用于说明），fn 实际执行。
 * 每个用例在两种请求上下文中执行：经 /index.php 访问（Request root=/index.php）与经站点根访问（root=''），
 * 并改变 Host、X-Forwarded-Host、HTTPS、X-Forwarded-Proto，记录输出是否随上下文变化。
 */
$cases = [
    ['home.mac_path', 'MAC_PATH（模板首页链接 {$maccms.path}，All.php:61）', function () { return MAC_PATH; }],
    ['index.p1', "mac_url('index/index')", function () { return mac_url('index/index'); }],
    ['index.p2', "mac_url('index/index',['page'=>2])", function () { return mac_url('index/index', ['page' => 2]); }],
    ['index.pagelink', "mac_url('index/index',['page'=>'PAGELINK'])（common.php:1765-1768 的特殊分支）",
        function () { return mac_url('index/index', ['page' => 'PAGELINK']); }],
    ['type.p1', 'mac_url_type($type)', function () use ($type) { return mac_url_type($type); }],
    ['type.p1.explicit', "mac_url_type(\$type,['page'=>1])", function () use ($type) { return mac_url_type($type, ['page' => 1]); }],
    ['type.p2', "mac_url_type(\$type,['page'=>2])", function () use ($type) { return mac_url_type($type, ['page' => 2]); }],
    ['type.p1000', "mac_url_type(\$type,['page'=>1000])", function () use ($type) { return mac_url_type($type, ['page' => 1000]); }],
    ['type.p0', "mac_url_type(\$type,['page'=>0])", function () use ($type) { return mac_url_type($type, ['page' => 0]); }],
    ['type.pneg', "mac_url_type(\$type,['page'=>-1])", function () use ($type) { return mac_url_type($type, ['page' => -1]); }],
    ['type.max', 'mac_url_type($typeMax)', function () use ($typeMax) { return mac_url_type($typeMax); }],
    ['type.pagelink', "mac_url_type(\$type,['page'=>'PAGELINK'])（分页模板，Vod.php model:256）",
        function () use ($type) { return mac_url_type($type, ['page' => 'PAGELINK']); }],
    ['type.pagelink.n1', "mac_url_page(mac_url_type(\$type,['page'=>'PAGELINK']),1)",
        function () use ($type) { return mac_url_page(mac_url_type($type, ['page' => 'PAGELINK']), 1); }],
    ['type.pagelink.n3', "mac_url_page(mac_url_type(\$type,['page'=>'PAGELINK']),3)",
        function () use ($type) { return mac_url_page(mac_url_type($type, ['page' => 'PAGELINK']), 3); }],
    ['detail', 'mac_url_vod_detail($vod)', function () use ($vod) { return mac_url_vod_detail($vod); }],
    ['detail.min', 'mac_url_vod_detail($vodMin)', function () use ($vodMin) { return mac_url_vod_detail($vodMin); }],
    ['detail.max', 'mac_url_vod_detail($vodMax)', function () use ($vodMax) { return mac_url_vod_detail($vodMax); }],
    ['play.1-1', "mac_url_vod_play(\$vod,['sid'=>1,'nid'=>1])", function () use ($vod) { return mac_url_vod_play($vod, ['sid' => 1, 'nid' => 1]); }],
    ['play.2-10', "mac_url_vod_play(\$vod,['sid'=>2,'nid'=>10])", function () use ($vod) { return mac_url_vod_play($vod, ['sid' => 2, 'nid' => 10]); }],
    ['play.0-0', "mac_url_vod_play(\$vod,['sid'=>0,'nid'=>0])", function () use ($vod) { return mac_url_vod_play($vod, ['sid' => 0, 'nid' => 0]); }],
    ['search.empty', 'mac_url_search([])', function () { return mac_url_search([]); }],
    ['search.wd', "mac_url_search(['wd'=>'合成关键词'])", function () { return mac_url_search(['wd' => '合成关键词']); }],
    ['search.wd.p2', "mac_url_search(['wd'=>'合成关键词','page'=>2])", function () { return mac_url_search(['wd' => '合成关键词', 'page' => 2]); }],
    ['search.wd.zero', "mac_url_search(['wd'=>'0'])", function () { return mac_url_search(['wd' => '0']); }],
    ['search.wd.space', "mac_url_search(['wd'=>' a b '])", function () { return mac_url_search(['wd' => ' a b ']); }],
    ['search.wd.reserved', "mac_url_search(['wd'=>'a+b/c-d.e&f=g?h#i%j~k*l'])", function () { return mac_url_search(['wd' => 'a+b/c-d.e&f=g?h#i%j~k*l']); }],
    ['search.wd.html', "mac_url_search(['wd'=>'<b>\"x\\'</b>'])", function () { return mac_url_search(['wd' => '<b>"x\'</b>']); }],
    ['search.wd.js', "mac_url_search(['wd'=>'javascript:alert(1)'])", function () { return mac_url_search(['wd' => 'javascript:alert(1)']); }],
    ['search.wd.dots', "mac_url_search(['wd'=>'..'])", function () { return mac_url_search(['wd' => '..']); }],
    ['search.wd.dothtml', "mac_url_search(['wd'=>'x.html'])", function () { return mac_url_search(['wd' => 'x.html']); }],
    ['search.wd.dash', "mac_url_search(['wd'=>'a-'])", function () { return mac_url_search(['wd' => 'a-']); }],
    ['search.wd.indexphp', "mac_url_search(['wd'=>'index.php','actor'=>'x'])", function () { return mac_url_search(['wd' => 'index.php', 'actor' => 'x']); }],
    ['search.wd.ctrl', "mac_url_search(['wd'=>\"a\\tb\\nc\"])", function () { return mac_url_search(['wd' => "a\tb\nc"]); }],
    ['search.multi', "mac_url_search(['wd'=>'合成','year'=>'2020','area'=>'大陆','actor'=>'演员甲'])",
        function () { return mac_url_search(['wd' => '合成', 'year' => '2020', 'area' => '大陆', 'actor' => '演员甲']); }],
    ['show.none', "mac_url_type(\$type,[],'show')", function () use ($type) { return mac_url_type($type, [], 'show'); }],
    ['show.filters', "mac_url_type(\$type,['area'=>'大陆','year'=>'2020','page'=>2],'show')",
        function () use ($type) { return mac_url_type($type, ['area' => '大陆', 'year' => '2020', 'page' => 2], 'show'); }],
    ['show.order', "mac_url_type(\$type,['by'=>'hits','order'=>'asc','class'=>'动作'],'show')",
        function () use ($type) { return mac_url_type($type, ['by' => 'hits', 'order' => 'asc', 'class' => '动作'], 'show'); }],
    ['user.login', "mac_url('user/login')", function () { return mac_url('user/login'); }],
    ['user.index', "mac_url('user/index')", function () { return mac_url('user/index'); }],
];

$contexts = [
    'via_index_php' => ['root' => '/index.php', 'server' => ['HTTP_HOST' => 'maccms.local.test']],
    'via_site_root' => ['root' => '', 'server' => ['HTTP_HOST' => 'maccms.local.test']],
    'hostile_headers' => ['root' => '/index.php', 'server' => ['HTTP_HOST' => 'evil.invalid',
        'HTTP_X_FORWARDED_HOST' => 'evil.invalid', 'HTTP_X_FORWARDED_PROTO' => 'https', 'HTTPS' => 'on']],
];

foreach ($cases as $c) {
    list($id, $call, $fn) = $c;
    $outs = [];
    foreach ($contexts as $cname => $ctx) {
        \think\Request::destroy();
        $_SERVER = array_merge(['SCRIPT_NAME' => '/index.php', 'REQUEST_METHOD' => 'GET'], $ctx['server']);
        $_GET = [];
        $req = \think\Request::instance();
        $req->root($ctx['root']);
        $req->module('index');
        $req->controller('Index');
        $req->action('index');
        $outs[$cname] = $fn();
    }
    $entry = ['id' => $id, 'call' => $call, 'source' => build_source($id), 'check' => 'php-executed',
        'output' => $outs['via_index_php']];
    if ($outs['via_site_root'] !== $outs['via_index_php']) {
        $entry['outputViaSiteRoot'] = $outs['via_site_root'];
    }
    $entry['hostIndependent'] = $outs['hostile_headers'] === $outs['via_index_php'];
    $result['build'][] = $entry;
}

// ---- 生成结果回送旧代码解析（往返）：生成的 URL 在同一配置下实际调度到哪里 ----
// 含 /index.php 的地址取其后部分作 PATH_INFO；不含时（rewrite.status=1）假定部署把整段路径作为 PATH_INFO
// 交给 index.php（常见伪静态规则，未验证），记 viaRewrite=true。Web 服务器交给 PHP 的 PATH_INFO 是解码后的，
// 这里用 rawurldecode 模拟（路径中的 + 不解码）。
foreach ($result['build'] as $i => $entry) {
    $url = $entry['output'];
    if ($url === '' || strpos($url, 'PAGELINK') !== false) {
        continue;
    }
    if ($url === '/') {
        $pathInfo = '';
        $viaRewrite = false;
    } elseif (strpos($url, '/index.php') === 0) {
        $pathInfo = (string) substr($url, strlen('/index.php'));
        $viaRewrite = false;
    } else {
        $pathInfo = $url;
        $viaRewrite = true;
    }
    $rt = php_parse(['pathInfo' => rawurldecode($pathInfo)]);
    $rt['viaRewrite'] = $viaRewrite;
    $result['build'][$i]['roundTrip'] = $rt;
}

// ---- 解析用例 ----
// pathInfo：Web 服务器交给 PHP 的 PATH_INFO（已解码）；s：?s= 兼容参数；query：其余 GET 参数。
// CLI 下 Request::pathinfo() 读 argv[1]（Request.php:407-409），与 FPM 读 PATH_INFO 的效果相同。
$parseCases = [
    ['root', ['pathInfo' => '']],
    ['root.slash', ['pathInfo' => '/']],
    ['index.module', ['pathInfo' => '/index/index.html']],
    ['index.route', ['pathInfo' => '/index-2.html']],
    ['type', ['pathInfo' => '/vod/type/id/1.html']],
    ['type.p2', ['pathInfo' => '/vod/type/id/1/page/2.html']],
    ['type.p1', ['pathInfo' => '/vod/type/id/1/page/1.html']],
    ['type.p0', ['pathInfo' => '/vod/type/id/1/page/0.html']],
    ['type.pabc', ['pathInfo' => '/vod/type/id/1/page/abc.html']],
    ['type.p2x', ['pathInfo' => '/vod/type/id/1/page/2x.html']],
    ['type.order', ['pathInfo' => '/vod/type/page/2/id/1.html']],
    ['type.nosuffix', ['pathInfo' => '/vod/type/id/1']],
    ['type.slash', ['pathInfo' => '/vod/type/id/1/']],
    ['type.htm', ['pathInfo' => '/vod/type/id/1.htm']],
    ['type.upper', ['pathInfo' => '/VOD/TYPE/id/1.HTML']],
    ['type.lead0', ['pathInfo' => '/vod/type/id/01.html']],
    ['type.en', ['pathInfo' => '/vod/type/id/hechengfenlei.html']],
    ['type.extra', ['pathInfo' => '/vod/type/id/1/foo/bar.html']],
    ['type.dup', ['pathInfo' => '/vod/type/id/1/id/2.html']],
    ['type.tags', ['pathInfo' => '/vod/type/id/1<b>.html']],
    ['type.query.page', ['pathInfo' => '/vod/type/id/1.html', 'query' => ['page' => '3']]],
    ['type.query.id', ['pathInfo' => '/vod/type/id/1.html', 'query' => ['id' => '2']]],
    ['type.query.other', ['pathInfo' => '/vod/type/id/1.html', 'query' => ['from' => 'x']]],
    ['type.dash', ['pathInfo' => '/vod-type-id-1-page-2.html']],
    ['type.route', ['pathInfo' => '/vodtype/1.html']],
    ['type.route.p2', ['pathInfo' => '/vodtype/1-2.html']],
    ['type.route.p1', ['pathInfo' => '/vodtype/1-1.html']],
    ['detail', ['pathInfo' => '/vod/detail/id/123.html']],
    ['detail.nosuffix', ['pathInfo' => '/vod/detail/id/123']],
    ['detail.slash', ['pathInfo' => '/vod/detail/id/123/']],
    ['detail.big', ['pathInfo' => '/vod/detail/id/4294967296.html']],
    ['detail.zero', ['pathInfo' => '/vod/detail/id/0.html']],
    ['detail.en', ['pathInfo' => '/vod/detail/id/hechengshipin.html']],
    ['detail.dash', ['pathInfo' => '/vod-detail-id-123.html']],
    ['detail.route', ['pathInfo' => '/voddetail/123.html']],
    ['detail.s.slash', ['s' => '/vod/detail/id/123']],
    ['detail.s.slash.html', ['s' => 'vod/detail/id/123.html']],
    ['detail.s.dash', ['s' => 'vod-detail-id-123']],
    ['detail.s.route', ['s' => 'voddetail/123']],
    ['play', ['pathInfo' => '/vod/play/id/123/sid/1/nid/2.html']],
    ['play.route', ['pathInfo' => '/vodplay/123-1-2.html']],
    ['search', ['pathInfo' => '/vod/search/wd/合成关键词.html']],
    ['search.route', ['pathInfo' => '/vodsearch/合成关键词-------------.html']],
    ['show.route', ['pathInfo' => '/vodshow/1-大陆-------2---2020.html']],
    ['user.login', ['pathInfo' => '/user/login.html']],
    // MACC-20：Java SSR 搜索页承接的"固定路径 + 查询串"形态。query 是 $_GET（Web 服务器已解码一次的值），
    // mac_param_url 会再 urldecode 一次并 htmlspecialchars；含 % + & < > " ' 的值两边不同（09 方案第 9 节），只记录不比对。
    ['search.q.base', ['pathInfo' => '/vod/search.html']],
    ['search.q.wd', ['pathInfo' => '/vod/search.html', 'query' => ['wd' => '合成关键词']]],
    ['search.q.multi', ['pathInfo' => '/vod/search.html', 'query' => ['wd' => '合成', 'area' => '大陆', 'lang' => '国语',
        'year' => '2020', 'level' => '9', 'class' => '剧情', 'by' => 'hits', 'order' => 'asc', 'page' => '2']]],
    ['search.q.slash', ['pathInfo' => '/vod/search/', 'query' => ['wd' => 'A/B']]],
    ['search.q.nosuffix', ['pathInfo' => '/vod/search', 'query' => ['wd' => '..']]],
    ['search.q.dothtml', ['pathInfo' => '/vod/search.html', 'query' => ['wd' => 'a.html']]],
    ['search.q.backslash', ['pathInfo' => '/vod/search.html', 'query' => ['wd' => 'A\B', 'area' => '..']]],
    ['search.q.trim', ['pathInfo' => '/vod/search.html', 'query' => ['wd' => ' a b ', 'year' => ' 2020 ']]],
    ['search.q.zero', ['pathInfo' => '/vod/search.html', 'query' => ['wd' => '0', 'area' => '0', 'level' => '0']]],
    ['search.q.levelpad', ['pathInfo' => '/vod/search.html', 'query' => ['level' => '0000000009', 'by' => 'time']]],
    ['search.q.p1000', ['pathInfo' => '/vod/search.html', 'query' => ['wd' => 'x', 'page' => '1000']]],
    ['search.q.pct', ['pathInfo' => '/vod/search.html', 'query' => ['wd' => '%41']]],
    ['search.q.plus', ['pathInfo' => '/vod/search.html', 'query' => ['wd' => 'C++']]],
    ['search.q.amp', ['pathInfo' => '/vod/search.html', 'query' => ['wd' => 'Tom & Jerry']]],
];

foreach ($parseCases as $pc) {
    list($id, $in) = $pc;
    $result['parse'][] = array_merge(['id' => $id, 'input' => $in,
        'source' => 'App::routeCheck + mac_param_url（见 sources.routeCheck、sources.mac_param_url）',
        'check' => 'php-executed'], strpos($id, 'search.q.') === 0
        ? php_parse($in, ['wd', 'area', 'lang', 'year', 'level', 'class', 'by', 'order']) : php_parse($in));
}

echo json_encode($result, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
