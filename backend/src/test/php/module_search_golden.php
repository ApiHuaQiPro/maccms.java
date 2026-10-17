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
    $GLOBALS['goldenPdo'] = $pdo;
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
    // [模块, 操作 search|show, 分类（带分类的筛选页）或 null, 请求参数, 选项]
    // 选项：canon = 新版规范地址的条件（请求经旧逐键归一后与请求不同时给出，如 level=1,2 经 mac_param_url 的 intval 为 1）；
    //       rnd = 随机排序要取的随机页（旧算法一：用 mt_srand 选一个使 mt_rand(1, floor(c/20)+1) 恰为该页的种子）或
    //       'candidates'（剧情经视频链计数 > 2000 时的算法二，重放 array_rand 的候选下标）；phase = 导入哪一份附加数据之后执行。
    $specs = [
        // ---- 文章（Art.php:164-227 读 URL；:229-346 条件；:357-376 rnd 与 by 白名单）
        ['art', 'search', null, ['wd' => '星']], ['art', 'search', null, ['wd' => 'star']], ['art', 'search', null, ['wd' => '_']],
        ['art', 'search', null, ['name' => '星际,城市']], ['art', 'search', null, ['tag' => '科幻']],
        ['art', 'search', null, ['tag' => '科幻,综艺']], ['art', 'search', null, ['tag' => 'a_b']],
        ['art', 'search', null, ['class' => '纪实']], ['art', 'search', null, ['ids' => '1,3,9']],
        ['art', 'search', null, ['tid' => '21']], ['art', 'search', null, ['tid' => '25']],
        ['art', 'search', null, ['wd' => '星', 'class' => '纪实']], ['art', 'search', null, ['letter' => 'X']],
        ['art', 'search', null, ['level' => '1']], ['art', 'search', null, ['wd' => '星', 'order' => 'asc']],
        ['art', 'search', null, ['wd' => '乐园', 'by' => 'level']],
        ['art', 'show', 21, []], ['art', 'show', 25, []], ['art', 'show', 21, ['letter' => 'X']],
        ['art', 'show', 21, ['wd' => '星']],
        // level 经 mac_param_url 的 intval（common.php:1577）：多值取首段、all 为 0（不筛选）、负数与越界照常比较（无结果）
        ['art', 'search', null, ['level' => '1,2'], ['canon' => ['level' => '1']]],
        ['art', 'search', null, ['level' => '2abc'], ['canon' => ['level' => '2']]],
        ['art', 'search', null, ['wd' => '星', 'level' => 'all'], ['canon' => ['wd' => '星']]],
        ['art', 'search', null, ['wd' => '星', 'level' => '-1']], ['art', 'search', null, ['wd' => '星', 'level' => '10']],
        ['art', 'show', 25, ['level' => 'all'], ['canon' => []]],
        // letter：htmlspecialchars(trim) 后 explode(',')（保留空项），0?9 展开为数字（Art.php:253-258）
        ['art', 'search', null, ['letter' => 'X,C']], ['art', 'search', null, ['letter' => 'C,']],
        ['art', 'search', null, ['letter' => 'x']], ['art', 'search', null, ['letter' => '0-9']],
        ['art', 'search', null, ['letter' => '0,9']], ['art', 'search', null, ['letter' => 'X，C']],
        ['art', 'search', null, ['tid' => '-21']], ['art', 'search', null, ['tid' => '21abc'], ['canon' => ['tid' => '21']]],
        ['art', 'search', null, ['letter' => 'X,C,H,L', 'by' => 'score']],
        ['art', 'search', null, ['letter' => 'X,C,H,L', 'by' => 'hits_week', 'order' => 'asc']],
        ['art', 'search', null, ['letter' => 'X,C,H', 'by' => 'rnd'], ['rnd' => 1]],
        ['art', 'show', 21, ['by' => 'rnd'], ['rnd' => 1]],
        // ---- 专题（Topic.php 的 paging 分支：id → ids = intval(id)，ids 覆盖；level=all 经 intval 为 0）
        ['topic', 'search', null, ['wd' => '星']], ['topic', 'search', null, ['wd' => 'xingji']],
        ['topic', 'search', null, ['tag' => '科幻']], ['topic', 'search', null, ['class' => '影视']],
        ['topic', 'search', null, ['ids' => '2,3']], ['topic', 'search', null, ['letter' => 'X']],
        ['topic', 'search', null, ['letter' => 'K,Z']], ['topic', 'search', null, ['id' => '2']],
        ['topic', 'search', null, ['id' => '2', 'ids' => '3']],
        ['topic', 'search', null, ['id' => 'abc', 'letter' => 'X'], ['canon' => ['letter' => 'X']]],
        ['topic', 'search', null, ['id' => '-2']],
        ['topic', 'search', null, ['letter' => 'K,X,Z', 'level' => 'all'], ['canon' => ['letter' => 'K,X,Z']]],
        ['topic', 'search', null, ['letter' => 'K,X,Z', 'by' => 'hits_week', 'order' => 'asc']],
        ['topic', 'search', null, ['letter' => 'K,X,Z', 'by' => 'rnd'], ['rnd' => 1]],
        // ---- 演员（Actor.php：sex 精确；area/starsign/blood 为 explode IN；by=in 时 name 只来自模板，URL 下等同 time）
        ['actor', 'search', null, ['wd' => '星']], ['actor', 'search', null, ['wd' => 'star']],
        ['actor', 'search', null, ['sex' => '女']], ['actor', 'search', null, ['area' => '香港', 'sex' => '女']],
        ['actor', 'search', null, ['starsign' => '白羊座']], ['actor', 'search', null, ['blood' => 'O']],
        ['actor', 'search', null, ['ids' => '1,4']],
        ['actor', 'show', 31, []], ['actor', 'show', 32, []], ['actor', 'show', 31, ['sex' => '女']],
        ['actor', 'search', null, ['area' => '日本,香港']], ['actor', 'search', null, ['starsign' => '白羊座,双子座']],
        ['actor', 'search', null, ['blood' => 'A,O']], ['actor', 'search', null, ['blood' => 'A,']],
        ['actor', 'search', null, ['area' => '大陆，香港']],
        ['actor', 'search', null, ['level' => '1,3'], ['canon' => ['level' => '1']]],
        ['actor', 'search', null, ['letter' => 'X,Z,L,S', 'by' => 'in']],
        ['actor', 'search', null, ['letter' => 'X,Z,L,S', 'by' => 'in', 'order' => 'asc']],
        ['actor', 'show', 31, ['area' => '香港,日本']], ['actor', 'search', null, ['sex' => '女', 'by' => 'rnd'], ['rnd' => 1]],
        // ---- 角色（Role.php：rid 经 intval；无分类）；筛选页 role/show 与搜索页读同一组 URL 参数（Role.php:18-31、All.php:236-245）
        ['role', 'search', null, ['wd' => '星']], ['role', 'search', null, ['wd' => 'xing']],
        ['role', 'search', null, ['rid' => '101']], ['role', 'search', null, ['ids' => '2,3']],
        ['role', 'search', null, ['rid' => '-1']], ['role', 'search', null, ['rid' => '101abc'], ['canon' => ['rid' => '101']]],
        ['role', 'search', null, ['letter' => 'X,F']], ['role', 'search', null, ['level' => '1,2'], ['canon' => ['level' => '1']]],
        ['role', 'search', null, ['letter' => 'R']], ['role', 'search', null, ['letter' => 'R', 'by' => 'id', 'order' => 'asc']],
        ['role', 'search', null, ['letter' => 'R', 'by' => 'rnd'], ['rnd' => 2]],
        ['role', 'search', null, ['letter' => 'R', 'by' => 'rnd', 'page' => '2'], ['rnd' => 3]],
        ['role', 'search', null, ['letter' => 'R,S', 'by' => 'rnd'], ['rnd' => 3]],
        ['role', 'search', null, ['letter' => 'Q', 'by' => 'rnd'], ['rnd' => 1]],
        ['role', 'show', null, []], ['role', 'show', null, ['rid' => '101']], ['role', 'show', null, ['letter' => 'X']],
        ['role', 'show', null, ['wd' => '星']], ['role', 'show', null, ['level' => '9', 'by' => 'hits_week']],
        ['role', 'show', null, ['rid' => '101', 'by' => 'rnd'], ['rnd' => 1]],
        // ---- 网址（Website.php：area/lang 为 explode IN；name 精确 IN；by=in 且有 name 时按 find_in_set 排序、忽略 order；referer*）
        //      name 等键在旧版按 search_len（仓库值空 → 10）截断，新版超出码点上限才 400（已登记差异），用例取不超过 10 字的值
        ['website', 'search', null, ['wd' => '星']], ['website', 'search', null, ['name' => '影视站']],
        ['website', 'search', null, ['name' => '影视']], ['website', 'search', null, ['tag' => '星']],
        ['website', 'search', null, ['class' => '科普']], ['website', 'search', null, ['area' => '大陆', 'lang' => '国语']],
        ['website', 'show', 41, []], ['website', 'show', 42, []], ['website', 'show', 41, ['class' => '科普']],
        ['website', 'search', null, ['area' => '大陆,香港']], ['website', 'search', null, ['lang' => '粤语,国语']],
        ['website', 'search', null, ['area' => '香港,']],
        ['website', 'search', null, ['name' => '资讯站,影视站', 'by' => 'in']],
        ['website', 'search', null, ['name' => '资讯站,星空网', 'by' => 'in', 'order' => 'desc']],
        ['website', 'search', null, ['letter' => 'X,Y,Z', 'by' => 'in']],
        ['website', 'search', null, ['letter' => 'X,Y,Z', 'by' => 'referer']],
        ['website', 'search', null, ['letter' => 'X,Y,Z', 'by' => 'referer_week', 'order' => 'asc']],
        ['website', 'search', null, ['class' => '科普', 'level' => 'all'], ['canon' => ['class' => '科普']]],
        ['website', 'search', null, ['letter' => 'X,Y,Z', 'by' => 'rnd'], ['rnd' => 1]],
        // ---- 剧情（Plot::search → 旧视频标签 plot=1，条件经 Vod::listCacheData 的 paging 分支读取，与 vod/search 同一组键）
        ['plot', 'search', null, ['wd' => '星']], ['plot', 'search', null, ['letter' => 'X']],
        ['plot', 'search', null, ['ids' => '101,105']], ['plot', 'search', null, ['tid' => '1']],
        ['plot', 'search', null, ['name' => '星际']], ['plot', 'search', null, ['letter' => 'X,H']],
        ['plot', 'search', null, ['letter' => 'X,H,C', 'by' => 'hits']], ['plot', 'search', null, ['level' => '1']],
        ['plot', 'search', null, ['letter' => 'X,H,C', 'by' => 'rnd'], ['rnd' => 1]],
        ['plot', 'search', null, ['letter' => 'P', 'by' => 'rnd'], ['rnd' => 37, 'phase' => 'plot-bulk']],
        ['plot', 'search', null, ['letter' => 'P,Q', 'by' => 'rnd'], ['rnd' => 'candidates', 'phase' => 'plot-bulk']],
        ['plot', 'search', null, ['letter' => 'P,Q', 'by' => 'rnd', 'page' => '2'], ['rnd' => 'candidates', 'phase' => 'plot-bulk']],
        ['plot', 'search', null, ['letter' => 'Z', 'by' => 'rnd'], ['rnd' => 1, 'phase' => 'plot-bulk']],
    ];
    $pdo = $GLOBALS['goldenPdo'];
    $cases = [];
    $phase = 'base';
    foreach ($specs as $spec) {
        [$module, $action, $type, $params] = $spec;
        $opts = isset($spec[4]) ? $spec[4] : [];
        $want = isset($opts['phase']) ? $opts['phase'] : 'base';
        if ($want !== $phase) {
            foreach (sql_statements($repo . '/backend/src/test/resources/site/module-search/phase-' . $want . '.sql') as $sql) {
                $pdo->exec($sql);
            }
            $phase = $want;
        }
        [$modelName, $pk] = $models[$module];
        $rnd = isset($opts['rnd']) ? $opts['rnd'] : null;
        $lp = ['num' => $rnd === null ? 999 : 20, 'paging' => 'yes', 'by' => 'time', 'order' => 'desc',
            'pageurl' => $module . '/' . $action];
        if ($type !== null) {
            $lp['type'] = 'current';
        }
        if ($module === 'plot') {
            $lp['plot'] = '1';
        }
        $run = function (array $request) use ($modelName, $lp, $type) {
            // 每次一个新请求：Request 会缓存第一次读到的 GET 参数（thinkphp/library/think/Request.php）
            \think\Request::destroy();
            $_GET = $request;
            $_POST = [];
            $_REQUEST = $request;
            \think\Request::instance()->module('index');
            $GLOBALS['type_id'] = $type === null ? 0 : $type;
            return model($modelName)->listCacheData($lp);
        };
        $case = [];
        if ($rnd === null) {
            $res = $run($params);
        } else {
            $seed = crc32($module . json_encode($params));
            $plain = $params;
            unset($plain['by'], $plain['page']);
            $count = (int)$run($plain)['total'];
            $case['count'] = $count;
            if ($rnd === 'candidates') {
                if ($count <= 2000) {
                    throw new RuntimeException('算法二需要计数 > 2000');
                }
                $row = $pdo->query('SELECT MIN(vod_id), MAX(vod_id) FROM mac_vod')->fetch(PDO::FETCH_NUM);
                mt_srand($seed);
                $case['candidates'] = array_values(array_rand(range((int)$row[0], (int)$row[1]), 1000));
                $case['idRange'] = [(int)$row[0], (int)$row[1]];
                $case['algorithm'] = 'candidates';
            } else {
                $pageTotal = (int)floor($count / 20) + 1;
                if ($rnd > $pageTotal || ($module === 'plot' && $count > 2000)) {
                    throw new RuntimeException('随机页超出范围');
                }
                for ($seed = 1; ; $seed++) {
                    mt_srand($seed);
                    if (mt_rand(1, $pageTotal) === $rnd) {
                        break;
                    }
                }
                $case['pageTotal'] = $pageTotal;
                $case['algorithm'] = 'page';
            }
            mt_srand($seed);
            $res = $run($params);
            if ($rnd !== 'candidates' && (int)$res['page'] !== $rnd) {
                throw new RuntimeException('随机页重放与旧代码不一致');
            }
            $case['page'] = (int)$res['page'];
        }
        $ids = [];
        foreach ($res['list'] as $row) {
            $ids[] = (int)$row[$pk];
        }
        $path = function (array $p) use ($module, $action, $type) {
            ksort($p);
            $q = [];
            foreach ($p as $k => $v) {
                $q[] = $k . '=' . urlencode($v);
            }
            return '/index.php/' . $module . '/' . $action . ($type === null ? '.html' : '/id/' . $type . '.html')
                . (count($q) ? '?' . implode('&', $q) : '');
        };
        $sorted = $params;
        ksort($sorted);
        $q = [];
        foreach ($sorted as $k => $v) {
            $q[] = $k . '=' . urlencode($v);
        }
        $id = $module . '.' . $action . ($type === null ? '' : $type) . (count($q) ? '.' . implode('&', $q) : '');
        $cases[] = ['id' => $id, 'phase' => $phase, 'path' => $path($params),
            'canonical' => $path(isset($opts['canon']) ? $opts['canon'] : $params),
            'request' => (object)$params, 'lp' => $lp, 'total' => (int)$res['total'], 'ids' => $ids] + $case;
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
