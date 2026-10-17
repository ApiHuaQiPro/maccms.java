<?php
/*
 * 文章/专题/演员/角色/网址/剧情的筛选页（show）与搜索页（search）的 PHP 黄金样本生成脚本（B05，MACC-29，队长裁定 D-B05-5）。
 *
 * 流程：用外部提供的管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入仓库 application/install/sql/install.sql 的表结构
 * → 导入合成数据 backend/src/test/resources/site/module-search/seed.sql → 只读加载仓库内的 thinkphp/ 与 application/，
 * 以旧页面的方式调用 listCacheData（paging=yes：条件经 mac_param_url 从请求参数读取；筛选页 type=current、$GLOBALS['type_id']），
 * 记录 ID 序列 → 删除该一次性库。不修改任何旧 PHP 文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，不访问网络。
 *
 * 管理账号只经环境变量传入（与 Java 集成测试相同）：MACCMS_IT_ADMIN_URL（如 jdbc:mysql://127.0.0.1:3306/）、
 * MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD。
 *
 * 用法（仓库根目录）：
 *   php backend/src/test/php/module_search_golden.php > backend/src/test/resources/site/module-search/php-golden.json
 * 每个用例同时记录新版 SSR 页面的规范地址（path），ModuleSearchGoldenMySqlIT 在导入同一份数据的一次性库上逐条请求真实页面，
 * 从页面卡片的详情链接取 ID 序列比对。说明见 docs/architecture/15-b05-readonly-pages.md 第 7.2 节。
 */

error_reporting(E_ERROR | E_PARSE);
$repo = realpath(__DIR__ . '/../../../..');

function env_required($name)
{
    $v = getenv($name);
    if ($v === false || $v === '') {
        fwrite(STDERR, "缺少环境变量 $name\n");
        exit(2);
    }
    return $v;
}

$adminUrl = env_required('MACCMS_IT_ADMIN_URL');
$adminUser = env_required('MACCMS_IT_ADMIN_USERNAME');
$adminPassword = env_required('MACCMS_IT_ADMIN_PASSWORD');
if (!preg_match('#^jdbc:mysql://([^:/?]+)(?::(\d+))?#', $adminUrl, $m)) {
    fwrite(STDERR, "MACCMS_IT_ADMIN_URL 不是 jdbc:mysql://host[:port]/ 形式\n");
    exit(2);
}
$host = $m[1];
$port = isset($m[2]) && $m[2] !== '' ? $m[2] : '3306';
$dbName = 'maccms_it_' . date('YmdHis') . '_' . bin2hex(random_bytes(4));
if (!preg_match('/^maccms_it_[0-9]{14}_[0-9a-f]{8}$/', $dbName)) {
    fwrite(STDERR, "一次性库名不合法\n");
    exit(2);
}

/** 与 ModuleSearchGoldenMySqlIT 相同的拆分：去掉以 -- 开头的行，按"分号 + 空白 + 换行"切分。 */
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

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$cacheDir = sys_get_temp_dir() . '/maccms-module-search-golden-' . bin2hex(random_bytes(4));
$exit = 0;
try {
    $pdo->exec("USE `$dbName`");
    foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
        $pdo->exec($sql);
    }
    foreach (sql_statements($repo . '/backend/src/test/resources/site/module-search/seed.sql') as $sql) {
        $pdo->exec($sql);
    }
    echo generate($repo, $host, $port, $dbName, $adminUser, $adminPassword, $cacheDir);
} catch (\Throwable $e) {
    // 不输出 message（可能含连接信息），只给类型与位置
    fwrite(STDERR, 'failed: ' . get_class($e) . ' at ' . $e->getFile() . ':' . $e->getLine() . "\n");
    $exit = 1;
} finally {
    $pdo->exec("DROP DATABASE `$dbName`");
    if (is_dir($cacheDir)) {
        $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($cacheDir, FilesystemIterator::SKIP_DOTS),
            RecursiveIteratorIterator::CHILD_FIRST);
        foreach ($it as $f) {
            $f->isDir() ? rmdir($f->getPathname()) : unlink($f->getPathname());
        }
        rmdir($cacheDir);
    }
}
exit($exit);

function generate($repo, $host, $port, $dbName, $user, $password, $cacheDir)
{
    // ---- 引导（与 index.php、thinkphp/start.php 相同的常量，不执行 App::run）
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
    $config = include APP_PATH . 'extra/maccms.php';
    $config['app']['cache_core'] = 0;
    $config['app']['popedom_filter'] = 0;
    $config['app']['cache_flag'] = 'golden';
    // Init.php:78-79：search_len 小于 1 时取 10（仓库值为空串），与 vod_list_golden.php 相同
    if (intval($config['app']['search_len']) < 1) {
        $config['app']['search_len'] = 10;
    }
    $GLOBALS['config'] = $config;
    define('MAC_PATH', '/');

    $models = ['art' => ['Art', 'art_id'], 'topic' => ['Topic', 'topic_id'], 'actor' => ['Actor', 'actor_id'],
        'role' => ['Role', 'role_id'], 'website' => ['Website', 'website_id'], 'plot' => ['Vod', 'vod_id']];
    // [模块, 分类（筛选页）或 null（搜索页）, 请求参数]
    $specs = [
        ['art', null, ['wd' => '星']], ['art', null, ['wd' => 'star']], ['art', null, ['wd' => '_']],
        ['art', null, ['name' => '星际,城市']], ['art', null, ['tag' => '科幻']], ['art', null, ['tag' => '科幻,综艺']],
        ['art', null, ['tag' => 'a_b']], ['art', null, ['class' => '纪实']], ['art', null, ['ids' => '1,3,9']],
        ['art', null, ['tid' => '21']], ['art', null, ['tid' => '25']], ['art', null, ['wd' => '星', 'class' => '纪实']],
        ['art', null, ['letter' => 'X']], ['art', null, ['level' => '1']], ['art', null, ['wd' => '星', 'order' => 'asc']],
        ['art', null, ['wd' => '乐园', 'by' => 'level']],
        ['art', 21, []], ['art', 25, []], ['art', 21, ['letter' => 'X']], ['art', 21, ['wd' => '星']],
        ['topic', null, ['wd' => '星']], ['topic', null, ['wd' => 'xingji']], ['topic', null, ['tag' => '科幻']],
        ['topic', null, ['class' => '影视']], ['topic', null, ['ids' => '2,3']], ['topic', null, ['letter' => 'X']],
        ['actor', null, ['wd' => '星']], ['actor', null, ['wd' => 'star']], ['actor', null, ['sex' => '女']],
        ['actor', null, ['area' => '香港', 'sex' => '女']], ['actor', null, ['starsign' => '白羊座']],
        ['actor', null, ['blood' => 'O']], ['actor', null, ['ids' => '1,4']],
        ['actor', 31, []], ['actor', 32, []], ['actor', 31, ['sex' => '女']],
        ['role', null, ['wd' => '星']], ['role', null, ['wd' => 'xing']], ['role', null, ['rid' => '101']],
        ['role', null, ['ids' => '2,3']],
        ['website', null, ['wd' => '星']], ['website', null, ['name' => '影视站']], ['website', null, ['name' => '影视']],
        ['website', null, ['tag' => '星']], ['website', null, ['class' => '科普']],
        ['website', null, ['area' => '大陆', 'lang' => '国语']],
        ['website', 41, []], ['website', 42, []], ['website', 41, ['class' => '科普']],
        ['plot', null, ['wd' => '星']], ['plot', null, ['letter' => 'X']], ['plot', null, ['ids' => '101,105']],
    ];
    $cases = [];
    foreach ($specs as $spec) {
        [$module, $type, $params] = $spec;
        [$modelName, $pk] = $models[$module];
        // 每个用例一次新请求：Request 会缓存第一次读到的 GET 参数（thinkphp/library/think/Request.php）
        \think\Request::destroy();
        $_GET = $params;
        $_POST = [];
        $_REQUEST = $params;
        \think\Request::instance()->module('index');
        $GLOBALS['type_id'] = $type === null ? 0 : $type;
        $lp = ['num' => 999, 'paging' => 'yes', 'by' => 'time', 'order' => 'desc',
            'pageurl' => $module . '/' . ($type === null ? 'search' : 'show')];
        if ($type !== null) {
            $lp['type'] = 'current';
        }
        if ($module === 'plot') {
            $lp['plot'] = '1';
        }
        $res = model($modelName)->listCacheData($lp);
        $ids = [];
        foreach ($res['list'] as $row) {
            $ids[] = (int)$row[$pk];
        }
        ksort($params);
        $q = [];
        foreach ($params as $k => $v) {
            $q[] = $k . '=' . urlencode($v);
        }
        $path = '/index.php/' . $module . ($type === null ? '/search.html' : '/show/id/' . $type . '.html')
            . (count($q) ? '?' . implode('&', $q) : '');
        $id = $module . ($type === null ? '.search' : '.show' . $type) . (count($q) ? '.' . implode('&', $q) : '');
        $cases[] = ['id' => $id, 'path' => $path, 'request' => (object)$params, 'lp' => $lp, 'ids' => $ids];
    }
    $_GET = $_REQUEST = [];

    $flags = JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES;
    $lines = ['{'];
    $lines[] = '  "description": ' . json_encode('MacCMS 模块筛选页（show）与搜索页（search）的 PHP 黄金样本：由 '
            . 'backend/src/test/php/module_search_golden.php 在一次性库（install.sql + site/module-search/seed.sql）上以 paging=yes '
            . '执行旧模型 listCacheData 生成。ids 为旧版返回的完整 ID 序列；path 为新版 SSR 页面的规范地址。不要手工修改，修改脚本或数据后重新生成。',
            $flags) . ',';
    $lines[] = '  "php": ' . json_encode(PHP_VERSION, $flags) . ',';
    $lines[] = '  "sources": ' . json_encode([
            'listCacheData' => 'application/common/model/{Art,Topic,Actor,Role,Website,Vod}.php（listCacheData，paging=yes，条件经 mac_param_url）',
            'show' => 'type=current 与 $GLOBALS[type_id]（Art.php:271-287 等：分类本身与直接子分类）',
            'plot' => 'Vod::listCacheData 的 plot=1（Vod.php:484-486）',
        ], $flags) . ',';
    $lines[] = '  "cases": [';
    foreach ($cases as $i => $row) {
        $lines[] = '    ' . json_encode($row, $flags) . ($i < count($cases) - 1 ? ',' : '');
    }
    $lines[] = '  ]';
    $lines[] = '}';
    return implode("\n", $lines) . "\n";
}
