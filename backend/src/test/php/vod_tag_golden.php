<?php
/*
 * 旧模板标签专属的视频列表条件、SSR tid 与 PHP intval 的黄金样本生成脚本（路线图 B05，MACC-29）。
 *
 * 流程：用外部提供的管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入仓库 application/install/sql/install.sql 的表结构
 * → 导入合成数据 backend/src/test/resources/content/vodtag/seed.sql → 只读加载仓库内的 thinkphp/ 与 application/，
 * 调用旧模型 Vod::listCacheData：
 *   - 标签专属条件（isend、plot、tv、weekday、timeadd/timehits/time、hits*、not、rel、type、typenot、level、start、num、by/order）
 *     以标签属性（$lp，paging=no）传入；
 *   - tid 以 URL 参数传入（paging=yes，pageurl=vod/search；与当前分类并存时 pageurl=vod/type、$GLOBALS['type_id'] 为该分类），
 *     经 mac_param_url 的 intval；
 * 记录 ID 序列（num=999 时的完整结果）与每行的 type_id（Java 按新版可见范围过滤旧结果后比对）；旧代码抛出异常时记录异常类型。
 * 另记录 PHP intval / in_array / strtotime 对一组输入的结果 → 删除该一次性库。
 * 不修改任何旧 PHP 文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，不访问网络。缓存目录为本次运行新建的临时目录，结束时删除。
 *
 * 管理账号只经环境变量传入（与 Java 集成测试相同）：MACCMS_IT_ADMIN_URL（如 jdbc:mysql://127.0.0.1:3306/）、
 * MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD。
 *
 * 用法（仓库根目录）：
 *   php backend/src/test/php/vod_tag_golden.php > backend/src/test/resources/content/vodtag/php-golden.json
 * 说明见 docs/architecture/15-b05-readonly-pages.md 第 2、8 节。
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

/** 与 Java 集成测试相同的拆分：去掉以 -- 开头的行，按"分号 + 空白 + 换行"切分。 */
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
$cacheDir = sys_get_temp_dir() . '/maccms-vodtag-golden-' . bin2hex(random_bytes(4));
$exit = 0;
try {
    $pdo->exec("USE `$dbName`");
    foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
        $pdo->exec($sql);
    }
    foreach (sql_statements($repo . '/backend/src/test/resources/content/vodtag/seed.sql') as $sql) {
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

/** 一次请求的上下文：GET 参数与 PATH_INFO（CLI 下 Request::pathinfo() 读 argv[1]）。 */
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

/**
 * 调用一次旧 listCacheData。$lp 为标签属性；$query 为 URL 参数；$currentType 为 $GLOBALS['type_id']（null 表示没有当前分类）。
 * 返回 ['ids'=>…] 或 ['error'=>异常类型]。
 */
function legacy(array $lp, array $query = [], $currentType = null)
{
    set_request($query);
    unset($GLOBALS['type_id'], $GLOBALS['type_pid']);
    if ($currentType !== null) {
        $GLOBALS['type_id'] = $currentType;
    }
    $lp += ['order' => 'desc', 'by' => 'time', 'paging' => 'no', 'num' => 999];
    try {
        $res = model('Vod')->listCacheData($lp);
    } catch (\Throwable $e) {
        return ['error' => get_class($e)];
    }
    $ids = [];
    foreach ($res['list'] as $row) {
        $ids[] = (int)$row['vod_id'];
    }
    $out = ['ids' => $ids];
    if (isset($res['total']) && $res['total'] !== null) {
        $out['total'] = (int)$res['total'];
    }
    return $out;
}

function generate($repo, $pdo, $host, $port, $dbName, $user, $password, $cacheDir)
{
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
    if (intval($config['app']['search_len']) < 1) {
        $config['app']['search_len'] = 10;
    }
    $GLOBALS['config'] = $config;
    define('MAC_PATH', '/');
    define('MAC_PAGE_SP', $config['path']['page_sp'] . '');

    $rowTypes = [];
    foreach ($pdo->query('SELECT vod_id, type_id, vod_status FROM mac_vod ORDER BY vod_id') as $r) {
        $rowTypes[(string)$r[0]] = (int)$r[1];
    }

    $cases = [];
    $add = function ($id, $kind, array $lp, array $query, $currentType, $note) use (&$cases) {
        $res = legacy($lp, $query, $currentType);
        $case = ['id' => $id, 'kind' => $kind, 'lp' => (object)$lp, 'query' => (object)$query,
            'currentType' => $currentType, 'note' => $note] + $res;
        if (isset($lp['start']) || (isset($lp['num']) && $lp['num'] != 999)) {
            // 取窗口的用例另记同一排序下的完整结果（start=0、num=999），Java 按新版可见范围过滤后再取同一窗口
            $full = $lp;
            unset($full['start']);
            $full['num'] = 999;
            $case['fullIds'] = legacy($full, $query, $currentType)['ids'];
        }
        $cases[] = $case;
    };

    // ---- 标签专属条件（paging=no，标签属性）
    foreach (['0', '1', ' 1', '01', '1.0', '2', '', 'a'] as $v) {
        $add('isend.' . $v, 'tag', ['isend' => $v], [], null, "in_array(\$isend, ['0','1']) 宽松比较");
    }
    foreach (['0', '1', '01', '2'] as $v) {
        $add('plot.' . $v, 'tag', ['plot' => $v], [], null, '');
    }
    foreach (['CCTV1', 'CCTV1,湖南卫视', ',', '湖南卫视,', '0'] as $v) {
        $add('tv.' . $v, 'tag', ['tv' => $v], [], null, 'vod_tv IN explode');
    }
    foreach (['一', '一,五', '三', ','] as $v) {
        $add('weekday.' . $v, 'tag', ['weekday' => $v], [], null, 'mac_like_arr，空项为 %%');
    }
    $times = ['2023-11-15 00:00:00', '2023-11-16 12:00:00', '@1700020000', 'abc'];
    foreach (['timeadd', 'timehits', 'time'] as $k) {
        foreach ($times as $t) {
            $add($k . '.' . $t, 'tag', [$k => $t], [], null, 'intval(strtotime()) 后 gt；时间点见 strtotime 向量');
        }
    }
    $ranges = ['gt 300', 'lt 100', 'between 100,500', 'egt 302', 'elt 51', 'eq 302', 'neq 302', 'in 13,23,302',
        'notin 13,23', 'notbetween 100,400', '> 300', 'GT 300', 'Between 100,500', '300', 'gt', 'like 3%', 'exp 1=1'];
    foreach (['hits', 'hitsday', 'hitsweek', 'hitsmonth'] as $k) {
        foreach ($ranges as $r) {
            $add($k . '.' . $r, 'tag', [$k => $r], [], null, 'explode(" ") 两段；一段或不支持的运算符见 error');
        }
    }
    foreach (['1,2,3', '1,,2', 'abc', ' 5', '5x', '0'] as $v) {
        $add('not.' . $v, 'tag', ['not' => $v], [], null, 'vod_id NOT IN，按整数绑定');
    }
    foreach (['1,2', '3', 'abc', '1,a', ',', '1.0,2'] as $v) {
        $add('rel.' . $v, 'tag', ['rel' => $v], [], null, '全为数字时 vod_id IN，否则 vod_rel_vod 包含匹配');
    }
    foreach (['6', '1,6', 'abc', '9', '2,8'] as $v) {
        $add('typenot.' . $v, 'tag', ['typenot' => $v], [], null, 'type_id NOT IN');
    }
    foreach (['1', '1,2', '6', '9', '11', '12', 'all', 'abc', ' 6', '06', '6.0', '6abc', '0'] as $v) {
        $add('type.' . $v, 'tag', ['type' => $v], [], null, '本身与 type_pid 命中的分类（in_array 宽松比较）');
    }
    $add('type.current-6', 'tag', ['type' => 'current'], [], 6, '当前分类 6');
    $add('type.current-none', 'tag', ['type' => 'current'], [], null, '没有当前分类时 intval 为 0：全部顶级分类');
    foreach (['all', '1,2', '0', 'abc', '10', '3'] as $v) {
        $add('level.' . $v, 'tag', ['level' => $v], [], null, 'vod_level IN，按整数绑定；all 为 1..9');
    }
    foreach (['0', '1', '2', '3', '-3'] as $v) {
        $add('start.' . $v, 'tag', ['start' => $v, 'num' => '5', 'by' => 'id', 'order' => 'asc'], [], null,
            'start>1 时减 1 后作为偏移');
    }
    $add('num.3', 'tag', ['num' => '3', 'by' => 'hits', 'order' => 'asc'], [], null, '');
    $add('order.hits_month-asc', 'tag', ['by' => 'hits_month', 'order' => 'asc', 'isend' => '1'], [], null, '');
    // 覆盖关系（同一个 where 键）
    $add('override.ids-not', 'tag', ['ids' => '1,2,3,4', 'not' => '2'], [], null, 'not 覆盖 ids');
    $add('override.ids-rel', 'tag', ['ids' => '1,2,3,4', 'rel' => '5,6'], [], null, '数字 rel 覆盖 ids');
    $add('override.not-rel', 'tag', ['not' => '5', 'rel' => '5,6'], [], null, '数字 rel 覆盖 not');
    $add('override.not-reltext', 'tag', ['not' => '5', 'rel' => 'abc'], [], null, '文本 rel 是另一个键，与 not 同时生效');
    $add('override.type-typenot', 'tag', ['type' => '1', 'typenot' => '6'], [], null, 'typenot 覆盖 type');
    $add('combo.isend-level-tv', 'tag', ['isend' => '1', 'level' => '1,3,5,7,9', 'tv' => 'CCTV1'], [], null, '');
    $add('combo.hits-time', 'tag', ['hits' => 'between 50,400', 'timeadd' => '2023-11-15 00:00:00', 'plot' => '1'],
        [], null, '');

    // ---- tid（URL 参数，paging=yes，旧 mac_param_url 的 intval）
    $tids = ['1', '6', '7', '10', '2', '8', '9', '11', '12', '0', '', 'abc', '01', '6abc', '1,2', '-1', ' 6', '6 ',
        '1e1', '+6', '0x6', '65536', '99999999999999999999', '1.9', '６'];
    foreach ($tids as $v) {
        $add('tid.' . $v, 'url', ['paging' => 'yes', 'pageurl' => 'vod/search', 'num' => 999], ['tid' => $v], null,
            'type_id = tid OR type_id_1 = tid（intval 为 0 时不筛选）');
    }
    // 与当前分类并存（旧分类/筛选页 vod/type、vod/show 的分页标签：$type = 当前分类，两个条件 AND）
    foreach ([[1, '6'], [1, '2'], [6, '1'], [2, '8'], [6, '10'], [1, '0'], [1, '-1']] as $p) {
        $add('tid.current-' . $p[0] . '.' . $p[1], 'url', ['paging' => 'yes', 'pageurl' => 'vod/type', 'num' => 999],
            ['tid' => $p[1]], $p[0], '当前分类（本身与子分类）AND tid');
    }
    $add('tid.with-isend', 'url', ['paging' => 'yes', 'pageurl' => 'vod/search', 'num' => 999, 'isend' => '1'],
        ['tid' => '1'], null, 'tid 与标签属性 isend 同时生效');

    // ---- 纯函数向量
    $intval = [];
    foreach (array_merge($tids, ['00', '-0', ' -5', "\t7", "\n8", '+', '-', '.5', '-.5e1', '1.', '1e', '1e3abc',
        '9223372036854775807', '9223372036854775808', '-9223372036854775809', '1e999', '-1e999', '12 34', '0b11',
        '0777', '1_000', '١', '0,1', '1,2,3', '3abc', '10']) as $v) {
        $intval[] = ['input' => $v, 'intval' => intval($v)];
    }
    $inArray = [];
    foreach (['0', '1', ' 1', '01', '1.0', '1e0', '-0', '0.0', '2', '', 'a', '1 ', ' 0'] as $v) {
        $inArray[] = ['input' => $v, 'inZeroOne' => in_array($v, ['0', '1'])];
    }
    $strtotime = [];
    foreach ($times as $t) {
        $strtotime[] = ['input' => $t, 'value' => intval(strtotime($t))];
    }

    $flags = JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES;
    $lines = ['{'];
    $lines[] = '  "description": ' . json_encode('MacCMS 旧模板标签专属的视频列表条件、SSR tid 与 PHP intval 的黄金样本：由 '
            . 'backend/src/test/php/vod_tag_golden.php 在一次性库（install.sql + content/vodtag/seed.sql）上执行旧 '
            . 'Vod::listCacheData 生成（popedom_filter=0，不按可见性过滤；Java 按 rowTypes 过滤后比对）。不要手工修改，修改脚本或数据后重新生成。',
            $flags) . ',';
    $lines[] = '  "php": ' . json_encode(PHP_VERSION, $flags) . ',';
    $lines[] = '  "sources": ' . json_encode([
            'listCacheData' => 'application/common/model/Vod.php:108-567（tid 180-182、356-358；not/rel 278-288；level 285-290；'
                . 'weekday/tv/time* 311-329；type/typenot 337-354；hits* 363-394；isend 396-398；plot 484-486；start 157-159）',
            'mac_param_url' => 'application/common.php:1560-1606（tid、level 为 intval）',
        ], $flags) . ',';
    $lines[] = '  "rowTypes": ' . json_encode((object)$rowTypes, $flags) . ',';
    foreach (['cases' => $cases, 'intval' => $intval, 'inArray' => $inArray, 'strtotime' => $strtotime] as $name => $rows) {
        $lines[] = '  "' . $name . '": [';
        foreach ($rows as $i => $row) {
            $lines[] = '    ' . json_encode($row, $flags) . ($i < count($rows) - 1 ? ',' : '');
        }
        $lines[] = '  ]' . ($name === 'strtotime' ? '' : ',');
    }
    $lines[] = '}';
    return implode("\n", $lines) . "\n";
}
