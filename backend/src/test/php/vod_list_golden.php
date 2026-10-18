<?php
/*
 * 视频列表筛选参数与随机排序的 PHP 黄金样本生成脚本（路线图 B04，契约 1.3.0）。
 *
 * 流程：用外部提供的管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入仓库 application/install/sql/install.sql 的表结构
 * → 导入合成数据 backend/src/test/resources/content/vodlist/seed.sql → 只读加载仓库内的 thinkphp/ 与 application/，
 * 以 GET 参数调用旧模型 Vod::listCacheData（paging=yes，即旧搜索/筛选页的分页标签，参数经 mac_param_url 与
 * mac_search_len_check 处理），记录 ID 序列与总数 → 依次执行 phase-sparse.sql、phase-high.sql（只有 DML）后再记录随机排序
 * → 打开路由（rewrite.route_status=1，路由表为仓库 application/route.php），记录旧位置参数地址（vodsearch/vodshow）与
 * 查询串经 App::routeCheck + mac_param_url + mac_search_len_check 后得到的参数 → 删除该一次性库。
 * 不修改任何旧 PHP 文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，不访问网络。缓存目录为本次运行新建的
 * 临时目录，结束时删除。
 *
 * 随机排序：每个用例先 mt_srand(固定种子) 再调用旧代码；随机 ID 段用同一种子、同一全表 MIN/MAX 重放
 * array_rand(range(min, max), 1000) 得到旧代码实际使用的候选（并核对旧代码返回的每个 ID 都在候选中）。
 * Java 集成测试把同一随机页或同一组候选交给新代码，比对 ID 序列。
 *
 * 管理账号只经环境变量传入（与 Java 集成测试相同）：MACCMS_IT_ADMIN_URL（如 jdbc:mysql://127.0.0.1:3306/）、
 * MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD。
 *
 * 用法（仓库根目录）：
 *   php backend/src/test/php/vod_list_golden.php > backend/src/test/resources/content/vodlist/php-golden.json
 * 说明见 docs/architecture/14-video-list-filter-completion.md 第 6 节。
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

/** 与 VideoListGoldenMySqlIT 相同的拆分：去掉以 -- 开头的行，按"分号 + 空白 + 换行"切分。 */
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
$cacheDir = sys_get_temp_dir() . '/maccms-vodlist-golden-' . bin2hex(random_bytes(4));
$exit = 0;
try {
    $pdo->exec("USE `$dbName`");
    foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
        $pdo->exec($sql);
    }
    foreach (sql_statements($repo . '/backend/src/test/resources/content/vodlist/seed.sql') as $sql) {
        $pdo->exec($sql);
    }
    echo generate($repo, $pdo, $host, $port, $dbName, $adminUser, $adminPassword, $cacheDir);
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

/** 一次请求的上下文：GET 参数与 PATH_INFO（CLI 下 Request::pathinfo() 读 argv[1]，thinkphp/library/think/Request.php:407-409）。 */
function set_request(array $query, $pathInfo = '/vod/search.html')
{
    \think\Request::destroy();
    $_GET = $query;
    $_POST = [];
    $_REQUEST = $query;
    $_SERVER = ['SCRIPT_NAME' => '/index.php', 'REQUEST_METHOD' => 'GET', 'HTTP_HOST' => 'maccms.local.test',
        'argv' => ['index.php', $pathInfo]];
    \think\Request::instance()->module('index');
}

/** 旧搜索/筛选页的分页标签（{maccms:vod paging="yes" ...}）：标签属性 + 当前请求的 URL 参数。 */
function legacy_list(array $query, array $lp = [])
{
    set_request($query);
    $lp += ['order' => 'desc', 'by' => 'time', 'paging' => 'yes', 'pageurl' => 'vod/search', 'num' => 100];
    return model('Vod')->listCacheData($lp);
}

function ids_of($res)
{
    $ids = [];
    foreach ($res['list'] as $row) {
        $ids[] = (int)$row['vod_id'];
    }
    return $ids;
}

/** 旧 URL 参数名 → 新接口参数名（wd→keyword、class→classes、by→sort，其余同名）。 */
function api_query(array $query, array $extra)
{
    $map = ['wd' => 'keyword', 'class' => 'classes', 'by' => 'sort'];
    $parts = [];
    foreach ($query as $k => $v) {
        $parts[] = (isset($map[$k]) ? $map[$k] : $k) . '=' . $v;
    }
    foreach ($extra as $k => $v) {
        $parts[] = $k . '=' . $v;
    }
    return '/api/v1/videos?' . implode('&', $parts);
}

function generate($repo, $pdo, $host, $port, $dbName, $user, $password, $cacheDir)
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
    $config['app']['vod_search_optimise'] = '';
    // Init.php:78-79：search_len 小于 1 时取 10（仓库值为空串）
    if (intval($config['app']['search_len']) < 1) {
        $config['app']['search_len'] = 10;
    }
    $GLOBALS['config'] = $config;
    define('MAC_PATH', '/');
    define('MAC_PAGE_SP', $config['path']['page_sp'] . '');

    $cases = [];

    // ---- 普通筛选：[编号, 旧 URL 参数, 比较方式, 说明]；equal = 新接口同一请求的结果应逐项相同
    $hundred = implode(',', range(1, 100));
    $filters = [
        ['default', [], 'equal', ''],
        ['letter.A', ['letter' => 'A'], 'equal', ''],
        ['letter.a', ['letter' => 'a'], 'equal', '大小写不敏感由 utf8_general_ci 决定'],
        ['letter.0-9', ['letter' => '0-9'], 'equal', ''],
        ['letter.0x9', ['letter' => '0x9'], 'equal', '首字节 0、第 3 个字节 9 即展开'],
        ['letter.0,9', ['letter' => '0,9'], 'equal', '首字节 0、第 3 个字节 9 即展开'],
        ['letter.0-9,A', ['letter' => '0-9,A'], 'equal', '展开后其余部分被忽略'],
        ['letter.A,B', ['letter' => 'A,B'], 'equal', ''],
        ['letter.A,', ['letter' => 'A,'], 'equal', '空项命中首字母为空的行'],
        ['letter.comma', ['letter' => ','], 'equal', ''],
        ['letter.1-9', ['letter' => '1-9'], 'equal', '不展开，按字面'],
        ['letter.09', ['letter' => '09'], 'equal', '只有 2 个字节，不展开'],
        ['letter.0', ['letter' => '0'], 'legacy-only', '旧版 empty("0") 视为未传；新接口 0 是字面值'],
        ['letter.truncated', ['letter' => 'A,B,C,D,E,F'], 'legacy-only', '旧版按 search_len=10 截断为 A,B,C,D,E,（多出空项）'],
        ['state.正片', ['state' => '正片'], 'equal', ''],
        ['state.multi', ['state' => '正片,预告片'], 'equal', ''],
        ['state.trailing-empty', ['state' => '正片,'], 'equal', '空项命中该列为空的行'],
        ['state.comma', ['state' => ','], 'equal', ''],
        ['version.高清版', ['version' => '高清版'], 'equal', ''],
        ['version.multi', ['version' => '高清版,剧场版'], 'equal', ''],
        ['year.2020', ['year' => '2020'], 'equal', ''],
        ['year.2018-2020', ['year' => '2018-2020'], 'equal', ''],
        ['year.2020-2018', ['year' => '2020-2018'], 'equal', '前大后小时交换'],
        ['year.2018~2020', ['year' => '2018~2020'], 'equal', '第 5 个字符任意'],
        ['year.2018,2020', ['year' => '2018,2020'], 'equal', '逗号同样只是第 5 个字符'],
        ['year.2019-2019', ['year' => '2019-2019'], 'equal', ''],
        ['year.0000-0003', ['year' => '0000-0003'], 'equal', '旧版 intval 后以字符串 0..3 比较（ThinkPHP 按 varchar 列以字符串绑定），不命中空年份'],
        ['year.0003-0006', ['year' => '0003-0006'], 'legacy-only', '旧版展开为 3..6（不补 0），命中年份为 5 的行；新版展开为 0003..0006'],
        ['year.0005', ['year' => '0005'], 'legacy-only', '旧版 intval 为 5；新版按 0005 比较'],
        ['year.2020年', ['year' => '2020年'], 'legacy-only', '旧版长度既不是 4 也不是 9（按字节）时不筛选；新版 400'],
        ['ids.list', ['ids' => '3,1,2'], 'equal', '结果仍按排序字段，不按传入顺序'],
        ['ids.all', ['ids' => 'all'], 'equal', 'all 不筛选'],
        ['ids.empty-item', ['ids' => '1,,2'], 'equal', '空项按 0'],
        ['ids.comma', ['ids' => ','], 'equal', '全是空项，命中不了任何行'],
        ['ids.00', ['ids' => '00'], 'equal', ''],
        ['ids.0', ['ids' => '0'], 'legacy-only', '旧版 empty("0") 视为未传；新接口 0 是字面值'],
        ['ids.long', ['ids' => '1,2,3,4,5,6,7,8,9'], 'equal', 'ids 不在 search_len 截断名单中（17 个字符未截断）'],
        ['ids.hundred', ['ids' => $hundred], 'equal', '100 项（新接口上限）'],
        ['actor.one', ['actor' => '演员甲'], 'equal', ''],
        ['actor.multi', ['actor' => '演员甲,演员丁'], 'equal', ''],
        ['actor.trailing-empty', ['actor' => '演员丙,'], 'equal', '空项生成 %%，命中全部'],
        ['actor.entity', ['actor' => 'm & J'], 'legacy-only', '旧版只匹配 htmlspecialchars 后的形式（m &amp; J）；新版原值与实体形式都匹配'],
        ['actor.truncated', ['actor' => '演员甲演员乙演员丙演员丁'], 'legacy-only', '旧版按 search_len=10 截断后匹配'],
        ['director.one', ['director' => '导演乙'], 'equal', ''],
        ['director.multi', ['director' => '导演甲,导演丙'], 'equal', ''],
        ['tag.multi', ['tag' => '标签甲,标签丙'], 'equal', ''],
        ['tag.one', ['tag' => '标签乙'], 'equal', ''],
        ['name.prefix', ['name' => '影片1'], 'equal', '包含匹配：影片1、影片10..19'],
        ['name.multi', ['name' => '影片2,影片3'], 'equal', ''],
        ['combo.letter-state-year', ['letter' => 'A,B,a', 'state' => '正片,', 'year' => '2018-2021'], 'equal', ''],
        ['combo.ids-actor', ['ids' => '1,2,3,4,5,6,7,8,9,10,11,12', 'actor' => '演员甲'], 'equal', ''],
        ['combo.area-tag', ['area' => '大陆', 'tag' => '标签丙'], 'equal', ''],
        // 契约 1.3.0 补充（MACC-28 Q1）：area/lang 的 IN explode 与 class 的 mac_like_arr（Vod.php:299-304、475-477）
        ['area.one', ['area' => '大陆'], 'equal', '单值回归'],
        ['area.multi', ['area' => '大陆,香港'], 'equal', 'IN 并集'],
        ['area.trailing-empty', ['area' => '香港,'], 'equal', '空项命中地区为空的行（批量行）'],
        ['area.comma', ['area' => ','], 'equal', '只有空项'],
        ['area.fullwidth', ['area' => '大陆，香港'], 'equal', '全角逗号不拆分，整串无命中'],
        ['area.inner-space', ['area' => '大陆, 香港'], 'equal', '各项不裁剪：第二项为 " 香港"，不命中'],
        ['lang.multi', ['lang' => '国语,粤语'], 'equal', ''],
        ['lang.trailing-empty', ['lang' => '粤语,'], 'equal', '空项命中语言为空的行（批量行）'],
        ['class.one', ['class' => '动作'], 'equal', '包含匹配：动作、动作,喜剧、动作冒险、动作，喜剧'],
        ['class.multi', ['class' => '动作,喜剧'], 'equal', 'mac_like_arr：各项 LIKE 之间 OR'],
        ['class.trailing-empty', ['class' => '爱情,'], 'equal', '空项生成 %%，命中全部'],
        ['class.comma', ['class' => ','], 'equal', '只有空项，命中全部'],
        ['class.fullwidth', ['class' => '动作，喜剧'], 'equal', '全角逗号不拆分，按整串包含匹配'],
        ['combo.area-lang-class', ['area' => '大陆,香港', 'lang' => '国语,', 'class' => '喜剧,爱情'], 'equal', ''],
        ['sort.area-multi-hits-asc', ['area' => '大陆,香港', 'by' => 'hits', 'order' => 'asc'], 'equal', ''],
        ['sort.hits_week-asc', ['letter' => '0-9', 'by' => 'hits_week', 'order' => 'asc'], 'equal', ''],
        ['sort.id-asc', ['state' => '正片,', 'by' => 'id', 'order' => 'asc'], 'equal', ''],
        ['sort.hits-desc', ['version' => '高清版,', 'by' => 'hits'], 'equal', ''],
        ['bulk.letter.R', ['letter' => 'R'], 'equal', '批量行 2000 行（只比较第 1 页与总数）'],
    ];
    foreach ($filters as $f) {
        list($id, $query, $compare, $note) = $f;
        $full = legacy_list($query);
        $q2 = $query + ['page' => '2'];
        $p2 = legacy_list($q2, ['num' => 7]);
        $cases[] = ['id' => $id, 'phase' => 'base', 'query' => (object)$query, 'compare' => $compare, 'note' => $note,
            'api' => api_query($query, ['pageSize' => '100']),
            'apiPage2' => api_query($query, ['pageSize' => '7', 'page' => '2']),
            'total' => (int)$full['total'], 'ids' => ids_of($full), 'page2Ids' => ids_of($p2)];
    }

    // ---- 随机排序
    $rnd = function ($id, $phase, array $query, $num, $note) use ($pdo, &$cases) {
        $seed = crc32($id);
        // 旧代码的计数（与 listCacheData 相同的条件，这里只用于判断走哪个算法并写入样本）
        mt_srand($seed);
        $res = legacy_list($query + ['by' => 'rnd'], ['num' => $num]);
        $ids = ids_of($res);
        $row = $pdo->query('SELECT MIN(vod_id), MAX(vod_id) FROM mac_vod')->fetch(PDO::FETCH_NUM);
        $min = (int)$row[0];
        $max = (int)$row[1];
        $case = ['id' => $id, 'phase' => $phase, 'query' => (object)$query, 'compare' => 'equal', 'note' => $note,
            'api' => api_query($query + ['by' => 'rnd'], ['pageSize' => (string)$num]),
            'total' => (int)$res['total'], 'ids' => $ids, 'page' => (int)$res['page'],
            'idRange' => [$min, $max]];
        // 与旧代码相同的随机调用重放：data_count > 2000 且 MIN/MAX 有效时取 array_rand 的键
        $count = (int)model('Vod')->countData(array_merge(['vod_status' => ['eq', 1]], rnd_where($query)));
        $case['count'] = $count;
        if ($count > 2000 && $min > 0 && $max > $min) {
            mt_srand($seed);
            $keys = array_rand(range($min, $max), 1000);
            $set = array_flip($keys);
            foreach ($ids as $v) {
                if (!isset($set[$v])) {
                    throw new RuntimeException('候选重放与旧代码不一致');
                }
            }
            $case['algorithm'] = 'candidates';
            $case['candidates'] = array_values($keys);
        } else {
            $case['algorithm'] = 'page';
        }
        $cases[] = $case;
    };
    $rnd('rnd.small', 'base', ['state' => '正片'], 3, '算法一：c=14（≤2000），随机页 1..floor(14/3)+1=5');
    $rnd('rnd.threshold-2000', 'base', ['letter' => 'R'], 100, '算法一：c 恰为 2000');
    $rnd('rnd.threshold-2001', 'base', ['letter' => 'R,S'], 100, '算法二：c=2001');
    $rnd('rnd.all-page2', 'base', ['page' => '2'], 20, '算法二：c=2039，使用请求的 page=2');
    $rnd('rnd.empty-filter', 'base', ['letter' => 'Q'], 20, '算法一：c=0，唯一的随机页 1，结果为空');
    foreach (sql_statements($repo . '/backend/src/test/resources/content/vodlist/phase-sparse.sql') as $sql) {
        $pdo->exec($sql);
    }
    $rnd('rnd.sparse', 'sparse', [], 100, '算法二：批量行 ID 稀疏（步长 10），全表 MIN/MAX = 1/30010');
    $rnd('rnd.sparse-filter', 'sparse', ['letter' => 'R,S'], 100, '算法二：同上，只看批量行');
    foreach (sql_statements($repo . '/backend/src/test/resources/content/vodlist/phase-high.sql') as $sql) {
        $pdo->exec($sql);
    }
    $rnd('rnd.high-ids', 'high', [], 100, '算法二：最小 ID 1010010，候选下标 0..20000 全部命不中，结果为空');

    // ---- 旧 URL：查询串（路由关闭，仓库默认）与位置参数地址（路由打开）→ 控制器得到的参数
    $keys = ['wd', 'actor', 'area', 'by', 'class', 'director', 'lang', 'letter', 'level', 'order', 'page', 'state',
        'tag', 'year', 'id', 'ids', 'name', 'version'];
    $parsed = [];
    // App.php:85-86：入口绑定模块 index（index.php:20）
    \think\Route::bind(BIND_MODULE);
    $queryUrls = [];
    $queryUrls[] = ['query.truncation', '/vod/search.html', ['actor' => '演员甲演员乙演员丙演员丁', 'director' => '导演导演导演导演导演导演',
        'tag' => 'abcdefghijkl', 'name' => '影片影片影片影片影片影片', 'state' => 'aaaaaaaaaaaa', 'version' => 'bbbbbbbbbbbb',
        'letter' => 'A,B,C,D,E,F', 'ids' => '1,2,3,4,5,6,7,8,9', 'year' => '2018-2020', 'wd' => 'cccccccccccc',
        'class' => 'dddddddddddd', 'area' => 'eeeeeeeeeeee', 'lang' => 'ffffffffffff']];
    $queryUrls[] = ['query.decode', '/vod/search.html', ['actor' => '%E7%94%B2', 'letter' => '%41', 'ids' => '1%2C2',
        'name' => 'a+b', 'state' => ' s ']];
    $queryUrls[] = ['query.zero', '/vod/search.html', ['letter' => '0', 'ids' => '0', 'actor' => '0', 'state' => '0',
        'year' => '0']];
    foreach ($queryUrls as $u) {
        $parsed[] = parse_url_vector($u, $keys);
    }
    \think\Config::set('url_route_on', true);
    \think\Config::set('pathinfo_depr', '/');
    \think\Route::import(include APP_PATH . 'route.php');
    $urls = [];
    $search = ['wd', 'actor', 'area', 'by', 'class', 'director', 'lang', 'letter', 'level', 'order', 'page', 'state',
        'tag', 'year'];
    $sample = ['wd' => '关键词', 'actor' => '演员甲', 'area' => '大陆', 'by' => 'hits', 'class' => '剧情',
        'director' => '导演乙', 'lang' => '国语', 'letter' => 'A', 'level' => '3', 'order' => 'asc', 'page' => '2',
        'state' => '正片', 'tag' => '标签甲', 'year' => '2020'];
    $urls[] = ['vodsearch.full', '/vodsearch/' . implode('-', array_map(function ($k) use ($sample) {
            return $sample[$k];
        }, $search)) . '.html', []];
    foreach ($search as $i => $k) {
        $slots = array_fill(0, count($search), '');
        $slots[$i] = $sample[$k];
        $urls[] = ['vodsearch.slot' . ($i + 1) . '.' . $k, '/vodsearch/' . implode('-', $slots) . '.html', []];
    }
    $urls[] = ['vodsearch.dash-in-value', '/vodsearch/a-b-------------.html', []];
    $urls[] = ['vodsearch.year-range', '/vodsearch/-------------2018-2020.html', []];
    $zero = array_fill(0, count($search), '');
    foreach (['wd', 'actor', 'letter', 'tag'] as $k) {
        $zero[array_search($k, $search)] = '0';
    }
    $urls[] = ['vodsearch.zero', '/vodsearch/' . implode('-', $zero) . '.html', []];
    $show = ['id', 'area', 'by', 'class', 'lang', 'letter', 'level', 'order', 'page', 'state', 'tag', 'year'];
    $showSample = $sample + ['id' => '1'];
    $urls[] = ['vodshow.full', '/vodshow/' . implode('-', array_map(function ($k) use ($showSample) {
            return $showSample[$k];
        }, $show)) . '.html', []];
    foreach ($show as $i => $k) {
        if ($k === 'id') {
            continue;
        }
        $slots = array_fill(0, count($show), '');
        $slots[0] = '1';
        $slots[$i] = $showSample[$k];
        $urls[] = ['vodshow.slot' . ($i + 1) . '.' . $k, '/vodshow/' . implode('-', $slots) . '.html', []];
    }
    foreach ($urls as $u) {
        $parsed[] = parse_url_vector($u, $keys);
    }

    // ---- mac_substring（search_len 截断）的纯函数结果：行为随 PHP 版本变化（见 14 文档第 5 节）
    $substring = [];
    foreach (['Tom &amp; Jerry', 'abcdefghijkl', 'A,B,C,D,E,F', '演员甲演员乙演员丙演员丁', '0123456789ab', 'a1b2c3d4e5f6'] as $in) {
        $substring[] = ['input' => $in, 'length' => 10, 'output' => mac_substring($in, 10)];
    }

    $flags = JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES;
    $lines = ['{'];
    $lines[] = '  "description": ' . json_encode('MacCMS 视频列表筛选参数与随机排序的 PHP 黄金样本：由 '
            . 'backend/src/test/php/vod_list_golden.php 在一次性库（install.sql + content/vodlist/seed.sql，随后依次执行 '
            . 'phase-sparse.sql、phase-high.sql）上执行旧 Vod::listCacheData（paging=yes，参数来自 URL）生成；urls 为旧路由与 '
            . 'mac_param_url/mac_search_len_check 的结果。compare=equal 的用例新接口应逐项相同，legacy-only 只记录旧行为（差异见 '
            . '14 文档）。不要手工修改，修改脚本或数据后重新生成。', $flags) . ',';
    $lines[] = '  "php": ' . json_encode(PHP_VERSION, $flags) . ',';
    $lines[] = '  "searchLen": ' . json_encode((int)$GLOBALS['config']['app']['search_len']) . ',';
    $lines[] = '  "sources": ' . json_encode([
            'listCacheData' => 'application/common/model/Vod.php:108-567（paging=yes 分支 167-271；条件 272-499；rnd 500-537）',
            'mac_param_url' => 'application/common.php:1560-1606',
            'mac_search_len_check' => 'application/common.php:2879-2888',
            'route' => 'application/route.php:321-334（vodshow、vodsearch）',
        ], $flags) . ',';
    foreach (['cases' => $cases, 'urls' => $parsed, 'substring' => $substring] as $name => $rows) {
        $lines[] = '  "' . $name . '": [';
        foreach ($rows as $i => $row) {
            $lines[] = '    ' . json_encode($row, $flags) . ($i < count($rows) - 1 ? ',' : '');
        }
        $lines[] = '  ]' . ($name === 'substring' ? '' : ',');
    }
    $lines[] = '}';
    return implode("\n", $lines) . "\n";
}

/** 用旧代码解析一个请求：App::routeCheck 得到调度，再取 mac_param_url() 与 mac_search_len_check()。 */
function parse_url_vector(array $u, array $keys)
{
    list($id, $path, $query) = $u;
    set_request($query, $path);
    $entry = ['id' => $id, 'pathInfo' => $path, 'query' => (object)$query];
    try {
        $dispatch = \think\App::routeCheck(\think\Request::instance(), \think\Config::get());
        if ($dispatch['type'] === 'module') {
            $r = $dispatch['module'];
            $entry['dispatch'] = strtolower(($r[1] ?: 'index') . '/' . ($r[2] ?: 'index'));
        } else {
            $entry['dispatch'] = $dispatch['type'];
        }
        $p = mac_param_url();
        $t = mac_search_len_check($p);
        $entry['param'] = [];
        $entry['afterLenCheck'] = [];
        foreach ($keys as $k) {
            if (isset($p[$k]) && $p[$k] !== '' && $p[$k] !== 0) {
                $entry['param'][$k] = (string)$p[$k];
            }
            if (isset($t[$k]) && $t[$k] !== '' && $t[$k] !== 0) {
                $entry['afterLenCheck'][$k] = (string)$t[$k];
            }
        }
        $entry['param'] = (object)$entry['param'];
        $entry['afterLenCheck'] = (object)$entry['afterLenCheck'];
    } catch (\think\exception\HttpException $e) {
        $entry['error'] = 'HTTP ' . $e->getStatusCode();
    }
    return $entry;
}

/** 随机排序用例的筛选条件（与 listCacheData 对同一组 URL 参数生成的 where 相同，只覆盖用例中出现的参数）。 */
function rnd_where(array $query)
{
    $w = [];
    if (isset($query['state'])) {
        $w['vod_state'] = ['in', explode(',', $query['state'])];
    }
    if (isset($query['letter'])) {
        $w['vod_letter'] = ['in', explode(',', $query['letter'])];
    }
    return $w;
}
