<?php
/*
 * 播放/下载入口（player_aaaa、授权判定）与 playerconfig.js 的黄金样本生成脚本（路线图 B05，MACC-29）。
 *
 * 流程（父进程）：用外部提供的管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入仓库 application/install/sql/install.sql 的表结构
 * → 导入合成数据 src/test/resources/site/play/seed.sql → 按 MAC_PLAYER_SORT 的两种取值各启动一个子进程（常量只能定义一次）
 * → 合并结果 → 删除该一次性库。子进程只读加载仓库内的 thinkphp/ 与 application/，对每个用例：
 *   - 按用例改写游客组权限（只写这个一次性库的 mac_group），设置 $GLOBALS['user'] 为游客（group_id=1、积分 0）；
 *   - 调用旧 check_user_popedom 记录权限判定（Base.php:92-231）；会中止的分支（错误页/扣积分确认页）不再调用 label_vod_play；
 *   - 否则调用旧 label_vod_play（common/controller/All.php:442-563），记录 player_info 与外层是 iframe 还是内联播放器；
 *   - 按 Vod::play/player/downer 的顺序记录版权页、密码页（Vod.php:86-120）。
 * 另按后台 _cache_clear 与 configplay 的写法（admin/controller/Base.php:59-103、System.php:675-690）用仓库的旧配置重新生成
 * playerconfig.js 的文本（以仓库 static/js/playerconfig.js 为模板，不写任何文件）。
 * 不修改任何旧 PHP 文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，不访问网络。缓存目录为本次运行新建的临时目录。
 *
 * 管理账号只经环境变量传入（与 Java 集成测试相同）：MACCMS_IT_ADMIN_URL、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD。
 *
 * 用法（仓库根目录）：
 *   php src/test/php/vod_play_golden.php > src/test/resources/site/play/php-golden.json
 * 说明见 docs/architecture/15-b05-readonly-pages.md 第 6 节。
 */

error_reporting(E_ERROR | E_PARSE);
$repo = realpath(__DIR__ . '/../../../php');

function env_required($name)
{
    $v = getenv($name);
    if ($v === false || $v === '') {
        fwrite(STDERR, "缺少环境变量 $name\n");
        exit(2);
    }
    return $v;
}

function sql_statements($file)
{
    $text = file_get_contents($file);
    $kept = [];
    foreach (preg_split('/\r?\n/', $text) as $line) {
        if (strpos($line, '--') !== 0) {
            $kept[] = $line;
        }
    }
    $out = [];
    foreach (preg_split('/;\s*\n/', implode("\n", $kept) . "\n") as $stmt) {
        if (trim($stmt) !== '') {
            $out[] = trim($stmt);
        }
    }
    return $out;
}

/** 游客组权限的几种形态（分类 1、6、2、11）。 */
function popedom_variants()
{
    $all = ['1' => '1', '2' => '2', '3' => '3', '4' => '4', '5' => '5'];
    $listOnly = ['1' => '1', '2' => '2'];
    $trial = ['1' => '1', '2' => '2', '5' => '5'];
    return [
        'full' => [',1,6,2,11,', ['1' => $all, '6' => $all, '2' => $all, '11' => $listOnly]],
        'trial' => [',1,6,2,11,', ['1' => $trial, '6' => $trial, '2' => $trial, '11' => $listOnly]],
        'none' => [',1,6,2,11,', ['1' => $listOnly, '6' => $listOnly, '2' => $listOnly, '11' => $listOnly]],
        'downonly' => [',1,6,2,11,', ['1' => ['1' => '1', '2' => '2', '4' => '4'], '6' => ['1' => '1', '2' => '2', '4' => '4'],
            '2' => $listOnly, '11' => $listOnly]],
    ];
}

/**
 * 用例：[id, 入口 play|player|down|downer, vod, sid, nid, 权限形态, 配置覆盖]。
 * 配置覆盖键：app.encrypt、user.trysee、user.vod_points_type、app.copyright_status、user.status。
 */
function cases()
{
    $c = [];
    foreach (['play', 'player'] as $e) {
        $c[] = ["$e.v1.s1n1", $e, 1, 1, 1, 'full', []];
        $c[] = ["$e.v1.s1n2", $e, 1, 1, 2, 'full', []];
        $c[] = ["$e.v1.s1n3.upload", $e, 1, 1, 3, 'full', []];
        $c[] = ["$e.v1.s2n1.protocolrel", $e, 1, 2, 1, 'full', []];
        $c[] = ["$e.v1.enc1", $e, 1, 1, 2, 'full', ['app.encrypt' => '1']];
        $c[] = ["$e.v1.enc2", $e, 1, 1, 2, 'full', ['app.encrypt' => '2']];
        $c[] = ["$e.v9.enc1.cjk", $e, 9, 1, 1, 'full', ['app.encrypt' => '1']];
        $c[] = ["$e.v9.enc2.cjk", $e, 9, 1, 2, 'full', ['app.encrypt' => '2']];
        $c[] = ["$e.v9.s1n1", $e, 9, 1, 1, 'full', []];
        $c[] = ["$e.v2.points", $e, 2, 1, 1, 'full', []];
        $c[] = ["$e.v2.points.perepisode", $e, 2, 1, 1, 'full', ['user.vod_points_type' => '0']];
        $c[] = ["$e.v2.statusoff", $e, 2, 1, 1, 'none', ['user.status' => '0']];
        $c[] = ["$e.v3.trial.vodtrysee", $e, 3, 1, 1, 'trial', []];
        $c[] = ["$e.v3.trial.n2", $e, 3, 1, 2, 'trial', []];
        $c[] = ["$e.v3.full.trysee", $e, 3, 1, 1, 'full', []];
        $c[] = ["$e.v1.trial.global0", $e, 1, 1, 1, 'trial', []];
        $c[] = ["$e.v1.trial.global3", $e, 1, 1, 1, 'trial', ['user.trysee' => '3']];
        $c[] = ["$e.v10.trial.points", $e, 10, 1, 1, 'trial', ['user.trysee' => '3']];
        $c[] = ["$e.v1.none", $e, 1, 1, 1, 'none', []];
        $c[] = ["$e.v7.nocat", $e, 7, 1, 1, 'full', []];
        $c[] = ["$e.v4.pwd", $e, 4, 1, 1, 'full', []];
        $c[] = ["$e.v5.copyright1", $e, 5, 1, 1, 'full', ['app.copyright_status' => '1']];
        $c[] = ["$e.v5.copyright3", $e, 5, 1, 1, 'full', ['app.copyright_status' => '3']];
        $c[] = ["$e.v5.copyright4", $e, 5, 1, 1, 'full', ['app.copyright_status' => '4']];
        $c[] = ["$e.v6.s2n1.iframe", $e, 6, 2, 1, 'full', []];
        $c[] = ["$e.v6.s3n3.bare", $e, 6, 3, 3, 'full', []];
        $c[] = ["$e.v6.s3n4.ownfrom", $e, 6, 3, 4, 'full', []];
        $c[] = ["$e.v6.s4n2", $e, 6, 4, 2, 'full', []];
        $c[] = ["$e.v6.s4n3.last", $e, 6, 4, 3, 'full', []];
    }
    foreach (['down', 'downer'] as $e) {
        $c[] = ["$e.v1.s1n1", $e, 1, 1, 1, 'full', []];
        $c[] = ["$e.v1.s1n2.upload", $e, 1, 1, 2, 'full', []];
        $c[] = ["$e.v1.s2n1.thunder", $e, 1, 2, 1, 'full', []];
        $c[] = ["$e.v2.points", $e, 2, 1, 1, 'full', []];
        $c[] = ["$e.v2.points.perepisode", $e, 2, 1, 1, 'full', ['user.vod_points_type' => '0']];
        $c[] = ["$e.v1.trialonly", $e, 1, 1, 1, 'trial', ['user.trysee' => '3']];
        $c[] = ["$e.v1.downonly", $e, 1, 1, 1, 'downonly', []];
        $c[] = ["$e.v4.pwd", $e, 4, 1, 1, 'full', []];
        $c[] = ["$e.v5.copyright4", $e, 5, 1, 1, 'full', ['app.copyright_status' => '4']];
        $c[] = ["$e.v1.enc2", $e, 1, 1, 1, 'full', ['app.encrypt' => '2']];
    }
    return $c;
}

if ($argc < 2) {
    $adminUrl = env_required('MACCMS_IT_ADMIN_URL');
    env_required('MACCMS_IT_ADMIN_USERNAME');
    env_required('MACCMS_IT_ADMIN_PASSWORD');
    if (!preg_match('#^jdbc:mysql://([^:/?]+)(?::(\d+))?#', $adminUrl, $m)) {
        fwrite(STDERR, "MACCMS_IT_ADMIN_URL 不是 jdbc:mysql://host[:port]/ 形式\n");
        exit(2);
    }
    $host = $m[1];
    $port = isset($m[2]) && $m[2] !== '' ? $m[2] : '3306';
    $dbName = 'maccms_it_' . date('YmdHis') . '_' . bin2hex(random_bytes(4));
    if (!preg_match('/^maccms_it_[0-9]{14}_[0-9a-f]{8}$/', $dbName)) {
        exit(2);
    }
    $pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", getenv('MACCMS_IT_ADMIN_USERNAME'),
        getenv('MACCMS_IT_ADMIN_PASSWORD'), [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
    $pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
    $exit = 0;
    try {
        $pdo->exec("USE `$dbName`");
        foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
            $pdo->exec($sql);
        }
        foreach (sql_statements(dirname($repo) . '/src/test/resources/site/play/seed.sql') as $sql) {
            $pdo->exec($sql);
        }
        $out = ['description' => '播放/下载入口黄金样本：由 src/test/php/vod_play_golden.php 在一次性库上执行仓库内旧 PHP 代码生成；'
            . '输入全部为合成数据（src/test/resources/site/play/seed.sql）。不要手工修改，修改生成脚本后重新生成。',
            'php' => PHP_VERSION, 'popedom' => [], 'playerConfigJs' => null, 'playerConfigJsSynthetic' => null,
            'cases' => []];
        foreach (popedom_variants() as $name => $v) {
            $out['popedom'][$name] = ['groupType' => $v[0], 'groupPopedom' => json_encode($v[1])];
        }
        foreach (['0', '1'] as $sort) {
            $cmd = escapeshellarg(PHP_BINARY) . ' ' . escapeshellarg(__FILE__) . ' child ' . escapeshellarg($sort) . ' '
                . escapeshellarg($dbName) . ' ' . escapeshellarg($host) . ' ' . escapeshellarg($port);
            $data = json_decode(shell_exec($cmd), true);
            if (!is_array($data)) {
                throw new RuntimeException('child failed');
            }
            if ($sort === '0') {
                $out['playerConfigJs'] = $data['playerConfigJs'];
                $out['playerConfigJsSynthetic'] = $data['playerConfigJsSynthetic'];
            }
            foreach ($data['cases'] as $case) {
                $out['cases'][] = $case;
            }
        }
        $flags = JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES;
        $lines = ['{', '  "description": ' . json_encode($out['description'], $flags) . ',',
            '  "php": ' . json_encode($out['php']) . ',',
            '  "popedom": ' . json_encode($out['popedom'], $flags) . ',',
            '  "playerConfigJs": ' . json_encode($out['playerConfigJs'], $flags) . ',',
            '  "playerConfigJsSynthetic": ' . json_encode($out['playerConfigJsSynthetic'], $flags) . ',',
            '  "cases": ['];
        $n = count($out['cases']);
        foreach ($out['cases'] as $i => $case) {
            $lines[] = '    ' . json_encode($case, $flags) . ($i < $n - 1 ? ',' : '');
        }
        $lines[] = '  ]';
        $lines[] = '}';
        echo implode("\n", $lines), "\n";
    } catch (\Throwable $e) {
        fwrite(STDERR, 'failed: ' . get_class($e) . ' at ' . $e->getFile() . ':' . $e->getLine() . "\n");
        $exit = 1;
    } finally {
        $pdo->exec("DROP DATABASE `$dbName`");
    }
    exit($exit);
}

// ------------------------------------------------------------------ 子进程
list(, , $sort, $dbName, $host, $port) = $argv;
$user = env_required('MACCMS_IT_ADMIN_USERNAME');
$password = env_required('MACCMS_IT_ADMIN_PASSWORD');
if (!preg_match('/^maccms_it_[0-9]{14}_[0-9a-f]{8}$/', $dbName)) {
    exit(2);
}
$cacheDir = sys_get_temp_dir() . '/maccms-vodplay-golden-' . bin2hex(random_bytes(4));
$pdo = new PDO("mysql:host=$host;port=$port;dbname=$dbName;charset=utf8", $user, $password,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);

define('APP_PATH', $repo . '/application/');
define('ROOT_PATH', $repo . '/');
define('RUNTIME_PATH', $cacheDir . '/runtime/');
define('BIND_MODULE', 'index');
define('ENTRANCE', 'index');
define('IN_FILE', '/index.php');
require $repo . '/thinkphp/base.php';
\think\Config::set(include APP_PATH . 'config.php');
require THINK_PATH . 'helper.php';
require APP_PATH . 'common.php';
error_reporting(E_ERROR | E_PARSE);
date_default_timezone_set('UTC');
\think\Loader::addNamespace('app', APP_PATH);
\think\Request::instance()->module('index');
\think\Lang::load(APP_PATH . 'lang/zh-cn.php');
\think\Config::set('database', [
    'type' => 'mysql', 'hostname' => $host, 'hostport' => $port, 'database' => $dbName, 'username' => $user,
    'password' => $password, 'charset' => 'utf8', 'prefix' => 'mac_', 'debug' => false,
    'resultset_type' => 'array', 'fields_strict' => false,
]);
\think\Config::set('cache', ['type' => 'File', 'path' => $cacheDir . '/cache/', 'prefix' => '', 'expire' => 0]);
foreach (['vodplayer', 'voddowner', 'vodserver', 'maccms'] as $name) {
    \think\Config::set(include APP_PATH . 'extra/' . $name . '.php', $name);
}
$baseConfig = include APP_PATH . 'extra/maccms.php';
$baseConfig['app']['cache_core'] = 0;
$baseConfig['app']['cache_page'] = 0;
$baseConfig['app']['cache_flag'] = 'golden';
define('MAC_PATH', '/');
define('MAC_PAGE_SP', $baseConfig['path']['page_sp'] . '');
define('MAC_PLAYER_SORT', $sort);
$GLOBALS['ismake'] = '0';
// Init.php:81-85 与 App.php:85-86：URL 形态按仓库默认（pathinfo_depr=/、不启用路由），模块绑定 index
\think\Config::set('url_route_on', $baseConfig['rewrite']['route_status']);
\think\Config::set('pathinfo_depr', empty($baseConfig['app']['pathinfo_depr']) ? '/' : $baseConfig['app']['pathinfo_depr']);
\think\Route::bind(BIND_MODULE);

/**
 * 后台 configplay（System.php:675-690）与 _cache_clear（admin/controller/Base.php:59-103）对 playerconfig.js 的两次替换，
 * 以仓库 static/js/playerconfig.js 为模板，只返回文本、不写文件。$dir 为旧站 application 目录（含 extra/）。
 */
function player_config_js($repo, $dir)
{
    $maccms = include $dir . '/extra/maccms.php';
    $fc = file_get_contents($repo . '/static/js/playerconfig.js');
    $jsb = mac_get_body($fc, '//参数开始', '//参数结束');
    $fc = str_replace($jsb, "\r\n" . 'MacPlayerConfig=' . json_encode($maccms['play']) . ';' . "\r\n", $fc);
    $lists = [];
    foreach (['vodplayer', 'voddowner'] as $name) {
        $list = [];
        foreach (include $dir . '/extra/' . $name . '.php' as $k => $v) {
            $list[$k] = ['show' => (string)$v['show'], 'des' => (string)$v['des'], 'ps' => (string)$v['ps'],
                'parse' => (string)$v['parse']];
        }
        $lists[] = $list;
    }
    $server = [];
    foreach (include $dir . '/extra/vodserver.php' as $k => $v) {
        $server[$k] = ['show' => (string)$v['show'], 'des' => (string)$v['des']];
    }
    $content = 'MacPlayerConfig.player_list=' . json_encode($lists[0]) . ',MacPlayerConfig.downer_list='
        . json_encode($lists[1]) . ',MacPlayerConfig.server_list=' . json_encode($server) . ';';
    $jsb = mac_get_body($fc, '//缓存开始', '//缓存结束');
    return str_replace($jsb, "\r\n" . $content . "\r\n", $fc);
}

function call_protected($obj, $method, array $args)
{
    $m = new ReflectionMethod($obj, $method);
    $m->setAccessible(true);
    return $m->invokeArgs($obj, $args);
}

$variants = popedom_variants();
$results = [];
foreach (cases() as $case) {
    list($id, $entry, $vodId, $sid, $nid, $variant, $over) = $case;
    $config = $baseConfig;
    foreach ($over as $k => $v) {
        list($a, $b) = explode('.', $k);
        $config[$a][$b] = $v;
    }
    $GLOBALS['config'] = $config;
    $pdo->exec("UPDATE mac_group SET group_type=" . $pdo->quote($variants[$variant][0]) . ", group_popedom="
        . $pdo->quote(json_encode($variants[$variant][1])) . " WHERE group_id=1");
    $GLOBALS['user'] = ['user_id' => 0, 'user_name' => '游客', 'group_id' => 1, 'user_points' => 0,
        'group' => ['group_id' => 1, 'group_type' => $variants[$variant][0], 'group_popedom' => $variants[$variant][1]]];
    \think\Request::destroy();
    $query = ['id' => (string)$vodId, 'sid' => (string)$sid, 'nid' => (string)$nid];
    $_GET = $query;
    $_POST = [];
    $_REQUEST = $query;
    $_SERVER = ['SCRIPT_NAME' => '/index.php', 'REQUEST_METHOD' => 'GET', 'HTTP_HOST' => 'maccms.local.test',
        'argv' => ['index.php', '/vod/' . $entry . '/id/' . $vodId . '.html']];
    $request = \think\Request::instance();
    $request->root('/index.php');
    $request->module('index');
    $request->controller('Vod');
    $request->action($entry);

    $ref = new ReflectionClass('app\index\controller\Vod');
    $c = $ref->newInstanceWithoutConstructor();
    $view = \think\View::instance(\think\Config::get('template'), \think\Config::get('view_replace_str'));
    foreach (['view' => $view, 'request' => $request, '_tsp' => '20260101'] as $prop => $value) {
        $p = new ReflectionProperty($c, $prop);
        $p->setAccessible(true);
        $p->setValue($c, $value);
    }
    $view->assign('player_data', null);
    $view->assign('player_js', null);
    $view->assign('obj', null);

    $flag = ($entry === 'play' || $entry === 'player') ? 'play' : 'down';
    $pe = $entry === 'player' ? 1 : 0;
    $record = ['id' => $id, 'entry' => $entry, 'vod' => $vodId, 'sid' => $sid, 'nid' => $nid, 'popedomVariant' => $variant,
        'config' => (object)$over, 'playerSort' => $sort];

    $param = mac_param_url();
    $res = mac_label_vod_detail($param);
    if ($res['code'] > 1) {
        $record['result'] = 'not_found';
        $results[] = $record;
        continue;
    }
    $info = $res['info'];
    $trysee = 0;
    if ($flag === 'play') {
        $trysee = $config['user']['trysee'];
        if ($info['vod_trysee'] > 0) {
            $trysee = $info['vod_trysee'];
        }
    }
    // 外层页（pe=0）的权限判定：播放位 3、下载位 4；记录码与试看标记
    $outer = call_protected($c, 'check_user_popedom', [$info['type_id'], $flag === 'play' ? 3 : 4, $param, $flag, $info, $trysee]);
    $record['popedom'] = ['code' => $outer['code'], 'trysee' => isset($outer['trysee']) ? $outer['trysee'] : null,
        'confirm' => isset($outer['confirm']) ? $outer['confirm'] : null];
    $aborts = $outer['code'] > 1 && empty($outer['trysee']);
    if ($aborts && $pe === 0) {
        $record['result'] = !empty($outer['confirm']) ? 'confirm' : 'error';
        $results[] = $record;
        continue;
    }
    try {
        $info2 = call_protected($c, 'label_vod_play', [$flag, [], 0, $pe]);
    } catch (\Throwable $e) {
        $record['result'] = 'exception:' . get_class($e);
        $results[] = $record;
        continue;
    }
    $record['playerInfo'] = $info2['player_info'];
    $js = $view->__get('player_js');
    if (preg_match('/<iframe id="player_if" name="player_if" src="([^"]*)"/', (string)$js, $mm)) {
        $record['outer'] = 'iframe';
        $record['iframeSrc'] = $mm[1];
    } else {
        $record['outer'] = 'inline';
        // 内联时旧模板输出 <script>var player_aaaa=json_encode($player_info)</script>（All.php:559）；记录它是否与 playerInfo 同源
        $record['playerDataMatches'] = $view->__get('player_data')
            === '<script type="text/javascript">var player_aaaa=' . json_encode($info2['player_info']) . '</script>';
    }
    // 各入口在 label_vod_play 之后的检查（Vod.php:86-120）
    if ($entry === 'play') {
        $record['result'] = ($info2['vod_copyright'] == 1 && $config['app']['copyright_status'] == 3) ? 'copyright' : 'page';
    } elseif ($entry === 'player') {
        if ($info2['vod_copyright'] == 1 && $config['app']['copyright_status'] == 4) {
            $record['result'] = 'copyright';
        } elseif (!empty($info2['vod_pwd_play'])) {
            $record['result'] = 'password';
        } else {
            $record['result'] = 'page';
        }
    } elseif ($entry === 'downer') {
        $record['result'] = !empty($info2['vod_pwd_down']) ? 'password' : 'page';
    } else {
        $record['result'] = 'page';
    }
    $results[] = $record;
}

$out = ['playerConfigJs' => player_config_js($repo, APP_PATH),
    'playerConfigJsSynthetic' => player_config_js($repo, dirname($repo) . '/src/test/resources/site/play/legacy-config'),
    'cases' => $results];
echo json_encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);

if (is_dir($cacheDir)) {
    $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($cacheDir, FilesystemIterator::SKIP_DOTS),
        RecursiveIteratorIterator::CHILD_FIRST);
    foreach ($it as $f) {
        $f->isDir() ? rmdir($f->getPathname()) : unlink($f->getPathname());
    }
    rmdir($cacheDir);
}
