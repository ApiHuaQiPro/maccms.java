<?php
/*
 * 文章/专题/演员/角色/网址/友链列表与详情关联内容的 PHP 黄金样本生成脚本（MACC-27 / B03）。
 *
 * 流程：用外部提供的管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入仓库 application/install/sql/install.sql 的表结构
 * → 导入合成数据 backend/src/test/resources/content/library/seed.sql → 只读加载仓库内的 thinkphp/ 与 application/，
 * 调用旧模型的 listCacheData（paging=no）与 infoData，记录 ID 序列 → 删除该一次性库。不修改任何旧 PHP 文件，
 * 不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，不访问网络。缓存目录为本次运行新建的临时目录，结束时删除。
 *
 * 管理账号只经环境变量传入（与 Java 集成测试相同）：MACCMS_IT_ADMIN_URL（如 jdbc:mysql://127.0.0.1:3306/）、
 * MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD。
 *
 * 用法（仓库根目录）：
 *   php backend/src/test/php/content_library_golden.php > backend/src/test/resources/content/library/php-golden.json
 * 每个用例同时记录新接口的请求地址（api），LibraryMySqlIT 在导入同一份数据的一次性库上逐条请求并比对。
 * 说明见 docs/architecture/11-content-read-completion.md 第 6 节。
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

/** 与 LibraryMySqlIT 相同的拆分：去掉以 -- 开头的行，按"分号 + 空白 + 换行"切分。 */
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
$cacheDir = sys_get_temp_dir() . '/maccms-library-golden-' . bin2hex(random_bytes(4));
$exit = 0;
try {
    $pdo->exec("USE `$dbName`");
    foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
        $pdo->exec($sql);
    }
    foreach (sql_statements($repo . '/backend/src/test/resources/content/library/seed.sql') as $sql) {
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
    // model('Art') 按当前模块解析，找不到时回落到 common 模块（thinkphp/library/think/Loader.php:438-456）
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
    $GLOBALS['config'] = $config;
    define('MAC_PATH', '/');

    $kinds = [
        'art' => ['model' => 'Art', 'api' => '/api/v1/articles', 'pk' => 'art_id',
            'sorts' => ['time', 'time_add', 'id', 'score', 'hits', 'hits_day', 'hits_week', 'hits_month', 'up', 'down', 'level']],
        'topic' => ['model' => 'Topic', 'api' => '/api/v1/topics', 'pk' => 'topic_id',
            'sorts' => ['time', 'time_add', 'id', 'score', 'hits', 'hits_day', 'hits_week', 'hits_month', 'up', 'down', 'level']],
        'actor' => ['model' => 'Actor', 'api' => '/api/v1/actors', 'pk' => 'actor_id',
            'sorts' => ['time', 'time_add', 'id', 'score', 'hits', 'hits_day', 'hits_week', 'hits_month', 'up', 'down', 'level']],
        'role' => ['model' => 'Role', 'api' => '/api/v1/roles', 'pk' => 'role_id',
            'sorts' => ['time', 'time_add', 'id', 'score', 'hits', 'hits_day', 'hits_week', 'hits_month', 'up', 'down', 'level']],
        'website' => ['model' => 'Website', 'api' => '/api/v1/websites', 'pk' => 'website_id',
            'sorts' => ['time', 'time_add', 'id', 'score', 'hits', 'hits_day', 'hits_week', 'hits_month', 'up', 'down', 'level',
                'referer', 'referer_day', 'referer_week', 'referer_month']],
    ];
    // [lp 追加参数, 新接口查询参数]
    $filters = [
        'art' => [[['type' => '21'], 'categoryId=21'], [['type' => '22'], 'categoryId=22'], [['type' => '25'], 'categoryId=25']],
        'topic' => [],
        'actor' => [[['type' => '31'], 'categoryId=31'], [['sex' => '男'], 'sex=男'], [['area' => '大陆'], 'area=大陆'],
            [['starsign' => '白羊座'], 'starSign=白羊座'], [['blood' => 'O'], 'blood=O'],
            [['sex' => '女', 'area' => '香港'], 'sex=女&area=香港']],
        'role' => [[['rid' => '101'], 'videoId=101'], [['rid' => '102'], 'videoId=102'], [['rid' => '999'], 'videoId=999']],
        'website' => [[['type' => '41'], 'categoryId=41'], [['area' => '香港'], 'area=香港'], [['lang' => '国语'], 'lang=国语']],
    ];
    $common = [
        [[], ''],
        [['level' => '3'], 'level=3'],
        [['letter' => 'A'], 'letter=A'],
        [['letter' => 'a'], 'letter=a'],
        [['letter' => '0-9'], 'letter=0-9'],
    ];

    $cases = [];
    foreach ($kinds as $kind => $k) {
        $model = model($k['model']);
        $run = function ($id, $lp, $query) use (&$cases, $model, $k, $kind) {
            $lp['num'] = 999;
            $lp['paging'] = 'no';
            $res = $model->listCacheData($lp);
            $ids = [];
            foreach ($res['list'] as $row) {
                $ids[] = (int)$row[$k['pk']];
            }
            $cases[] = ['id' => $kind . '.' . $id, 'api' => $k['api'] . '?' . ($query === '' ? '' : $query . '&')
                . 'pageSize=100', 'lp' => $lp, 'ids' => $ids];
        };
        foreach ($common as $c) {
            $run($c[1] === '' ? 'default' : $c[1], $c[0], $c[1]);
        }
        foreach ($k['sorts'] as $by) {
            foreach (['desc', 'asc'] as $order) {
                $run("sort=$by&order=$order", ['by' => $by, 'order' => $order], "sort=$by&order=$order");
            }
        }
        foreach ($filters[$kind] as $f) {
            $run($f[1], $f[0], $f[1]);
        }
    }
    // 友链：by id/sort（默认 id）、order（默认 asc）、type font/pic
    $link = model('Link');
    foreach ([
                 [[], ''], [['by' => 'sort'], 'sort=sort'], [['by' => 'sort', 'order' => 'desc'], 'sort=sort&order=desc'],
                 [['by' => 'id', 'order' => 'desc'], 'sort=id&order=desc'], [['type' => 'font'], 'type=font'],
                 [['type' => 'pic', 'by' => 'sort', 'order' => 'desc'], 'type=pic&sort=sort&order=desc'],
             ] as $c) {
        $lp = $c[0] + ['num' => 999];
        $res = $link->listCacheData($lp);
        $ids = [];
        foreach ($res['list'] as $row) {
            $ids[] = (int)$row['link_id'];
        }
        $cases[] = ['id' => 'link.' . ($c[1] === '' ? 'default' : $c[1]), 'api' => '/api/v1/links?'
            . ($c[1] === '' ? '' : $c[1] . '&') . 'pageSize=100', 'lp' => $lp, 'ids' => $ids];
    }

    // 专题详情的关联内容（Topic::infoData 的 vod_list / art_list）
    $topics = [];
    foreach ([1, 2, 3, 4] as $id) {
        $r = model('Topic')->infoData(['topic_id' => ['eq', $id], 'topic_status' => ['eq', 1]]);
        $vods = [];
        foreach ($r['info']['vod_list'] as $v) {
            $vods[] = (int)$v['vod_id'];
        }
        $arts = [];
        foreach ($r['info']['art_list'] as $v) {
            $arts[] = (int)$v['art_id'];
        }
        $topics[] = ['id' => $id, 'api' => '/api/v1/topics/' . $id, 'videoIds' => $vods, 'articleIds' => $arts];
    }

    // 详情中的多值字段（经真实库读出后解码）：文章分页与截图、网址截图、视频分集剧情
    $details = [];
    $art = model('Art')->infoData(['art_id' => ['eq', 1], 'art_status' => ['eq', 1]])['info'];
    $details[] = ['id' => 'art.1', 'api' => '/api/v1/articles/1', 'pages' => array_values($art['art_page_list']),
        'screenshots' => keyed($art['art_pic_screenshot_list'])];
    $web = model('Website')->infoData(['website_id' => ['eq', 1], 'website_status' => ['eq', 1]])['info'];
    $details[] = ['id' => 'website.1', 'api' => '/api/v1/websites/1',
        'screenshots' => keyed($web['website_pic_screenshot_list'])];
    foreach ([101, 102, 103, 104] as $vid) {
        $vod = model('Vod')->infoData(['vod_id' => ['eq', $vid], 'vod_status' => ['eq', 1]])['info'];
        $details[] = ['id' => 'plot.' . $vid, 'api' => '/api/v1/videos/' . $vid . '/plots',
            'plots' => keyed($vod['vod_plot_list'])];
    }

    $flags = JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES;
    $lines = ['{'];
    $lines[] = '  "description": ' . json_encode('MacCMS 文章/专题/演员/角色/网址/友链列表与关联内容的 PHP 黄金样本：由 '
            . 'backend/src/test/php/content_library_golden.php 在一次性库（install.sql + content/library/seed.sql）上执行旧模型生成。'
            . 'ids 为旧版返回的完整 ID 序列；api 为新接口的对应请求。不要手工修改，修改脚本或数据后重新生成。', $flags) . ',';
    $lines[] = '  "php": ' . json_encode(PHP_VERSION, $flags) . ',';
    $lines[] = '  "sources": ' . json_encode([
            'listCacheData' => 'application/common/model/{Art,Topic,Actor,Role,Website,Link}.php（listCacheData，paging=no）',
            'topic' => 'application/common/model/Topic.php:264-339（infoData 的 vod_list/art_list）',
            'details' => 'Art::infoData（art_page_list、art_pic_screenshot_list）、Website::infoData、Vod::infoData（vod_plot_list）',
        ], $flags) . ',';
    foreach (['cases' => $cases, 'topics' => $topics, 'details' => $details] as $name => $rows) {
        $lines[] = '  "' . $name . '": [';
        foreach ($rows as $i => $row) {
            $lines[] = '    ' . json_encode($row, $flags) . ($i < count($rows) - 1 ? ',' : '');
        }
        $lines[] = '  ]' . ($name === 'details' ? '' : ',');
    }
    $lines[] = '}';
    return implode("\n", $lines) . "\n";
}

function keyed($arr)
{
    $out = [];
    foreach ((array)$arr as $k => $v) {
        $out[] = ['key' => $k, 'value' => $v];
    }
    return $out;
}
