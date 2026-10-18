<?php
/*
 * B06（MACC-30）Provide 资源站接口与 ajax/data、ajax/suggest、ajax/desktop、qrcode 的 PHP 黄金样本生成脚本。
 *
 * 流程：用外部提供的管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入仓库 application/install/sql/install.sql 的表结构
 * → 导入合成数据 backend/src/test/resources/openapi/provide/seed.sql → 为每个用例按需改写临时配置目录中的 extra/maccms.php，
 * 以 php-cgi 执行 legacy_http_child.php（完整的 ThinkPHP 路由与旧控制器，入口常量与 api.php/index.php 相同）→ 记录状态码、
 * Content-Type、Content-Disposition 与正文字节 → 删除一次性库与临时目录。
 * 不修改任何旧 PHP 文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，不访问网络（白名单用例不含域名项）。
 *
 * 管理账号只经环境变量传入（与 Java 集成测试相同）：MACCMS_IT_ADMIN_URL（如 jdbc:mysql://127.0.0.1:3306/）、
 * MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD。另需 PHP_CGI（与运行本脚本的 php 同版本的 php-cgi 可执行文件）。
 *
 * 用法（仓库根目录，PHP 8.0 与 7.3 各一份）：
 *   PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe backend/src/test/php/provide_golden.php \
 *       > backend/src/test/resources/openapi/provide/php80-golden.json
 * 说明见 docs/architecture/16-b06-misc-pages-provide.md 第 3、4、6 节。
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
$phpCgi = env_required('PHP_CGI');
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

function rm_tree($dir)
{
    if (!is_dir($dir)) {
        return;
    }
    $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($dir, FilesystemIterator::SKIP_DOTS),
        RecursiveIteratorIterator::CHILD_FIRST);
    foreach ($it as $f) {
        $f->isDir() ? rmdir($f->getPathname()) : unlink($f->getPathname());
    }
    rmdir($dir);
}

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$work = sys_get_temp_dir() . '/maccms-b06-golden-' . bin2hex(random_bytes(4));
$exit = 0;
try {
    $pdo->exec("USE `$dbName`");
    foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
        $pdo->exec($sql);
    }
    foreach (sql_statements($repo . '/backend/src/test/resources/openapi/provide/seed.sql') as $sql) {
        $pdo->exec($sql);
    }
    echo generate($repo, $pdo, $phpCgi, $work, $host, $port, $dbName, $adminUser, $adminPassword);
} catch (\Throwable $e) {
    // 不输出 message（可能含连接信息），只给类型与位置
    fwrite(STDERR, 'failed: ' . get_class($e) . ' at ' . $e->getFile() . ':' . $e->getLine() . "\n");
    $exit = 1;
} finally {
    $pdo->exec("DROP DATABASE `$dbName`");
    rm_tree($work);
}
exit($exit);

/** 递归合并（后者覆盖前者；列表整体替换）。 */
function deep_merge($base, $over)
{
    foreach ($over as $k => $v) {
        if (is_array($v) && isset($base[$k]) && is_array($base[$k])) {
            $base[$k] = deep_merge($base[$k], $v);
        } else {
            $base[$k] = $v;
        }
    }
    return $base;
}

function write_conf($repo, $conf, $maccms, $host, $port, $db, $user, $password)
{
    @mkdir($conf . '/extra', 0777, true);
    foreach (['config.php', 'route.php', 'tags.php', 'command.php'] as $f) {
        copy($repo . '/application/' . $f, $conf . '/' . $f);
    }
    if (getenv('GOLDEN_DEBUG') === '1') {
        // 只用于排查：打开 ThinkPHP 调试页（输出不用于黄金样本）
        $text = file_get_contents($repo . '/application/config.php');
        file_put_contents($conf . '/config.php', preg_replace("/'app_debug'(\s*)=>(\s*)false/", "'app_debug'$1=>$2true", $text));
    }
    foreach (glob($repo . '/application/extra/*.php') as $f) {
        copy($f, $conf . '/extra/' . basename($f));
    }
    $database = include $repo . '/application/database.php';
    $database = array_merge($database, ['hostname' => $host, 'hostport' => $port, 'database' => $db,
        'username' => $user, 'password' => $password, 'debug' => false]);
    file_put_contents($conf . '/database.php', '<?php return ' . var_export($database, true) . ';');
    file_put_contents($conf . '/extra/maccms.php', '<?php return ' . var_export($maccms, true) . ';');
}

/**
 * 以 php-cgi 执行一个请求。返回 [status, headers(小写名 => 值), body]。
 * $req: entry（api|index）、path（PATH_INFO，如 /provide/vod/）、query、method、headers（名 => 值）、remote、post。
 */
function run_request($repo, $phpCgi, $conf, $runtime, $req)
{
    $entry = $req['entry'];
    $query = isset($req['query']) ? $req['query'] : '';
    $method = isset($req['method']) ? $req['method'] : 'GET';
    $post = isset($req['post']) ? $req['post'] : '';
    // 入口文件名须与 SCRIPT_NAME 相同（ThinkPHP 的 Request::baseFile/root 据此推算站点根，与生产的 /index.php、/api.php 一致）
    $entryDir = dirname($runtime) . DIRECTORY_SEPARATOR . 'entry';
    $entryFile = $entryDir . DIRECTORY_SEPARATOR . $entry . '.php';
    if (!is_file($entryFile)) {
        @mkdir($entryDir, 0777, true);
        file_put_contents($entryFile, '<?php require ' . var_export(__DIR__ . DIRECTORY_SEPARATOR . 'legacy_http_child.php', true) . ';');
    }
    $env = [
        'GOLDEN_ENTRY' => $entry,
        'GOLDEN_CONF_PATH' => $conf,
        'GOLDEN_RUNTIME_PATH' => $runtime,
        'REDIRECT_STATUS' => '200',
        'GATEWAY_INTERFACE' => 'CGI/1.1',
        'SERVER_PROTOCOL' => 'HTTP/1.1',
        'SERVER_SOFTWARE' => 'golden',
        'SERVER_NAME' => 'www.example.test',
        'SERVER_PORT' => '80',
        'HTTP_HOST' => 'www.example.test',
        'REQUEST_METHOD' => $method,
        'SCRIPT_FILENAME' => $entryFile,
        'SCRIPT_NAME' => '/' . $entry . '.php',
        'PHP_SELF' => '/' . $entry . '.php' . $req['path'],
        'PATH_INFO' => $req['path'],
        'REQUEST_URI' => '/' . $entry . '.php' . $req['path'] . ($query === '' ? '' : '?' . $query),
        'QUERY_STRING' => $query,
        'REMOTE_ADDR' => isset($req['remote']) ? $req['remote'] : '127.0.0.1',
        'REMOTE_PORT' => '50000',
        'DOCUMENT_ROOT' => $entryDir,
        'SystemRoot' => getenv('SystemRoot') ?: 'C:\\Windows',
        'TEMP' => $runtime,
        'TMP' => $runtime,
    ];
    if ($method === 'POST') {
        $env['CONTENT_TYPE'] = 'application/x-www-form-urlencoded';
        $env['CONTENT_LENGTH'] = (string)strlen($post);
    }
    if (isset($req['headers'])) {
        foreach ($req['headers'] as $name => $value) {
            $env['HTTP_' . strtoupper(str_replace('-', '_', $name))] = $value;
        }
    }
    $cmd = escapeshellarg($phpCgi) . ' -d session.save_path=' . escapeshellarg($runtime)
        . ' -d display_errors=0 -d error_reporting=0';
    $proc = proc_open($cmd, [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['pipe', 'w']], $pipes, $repo, $env,
        ['bypass_shell' => true]);
    if (!is_resource($proc)) {
        throw new RuntimeException('php-cgi');
    }
    fwrite($pipes[0], $post);
    fclose($pipes[0]);
    $out = stream_get_contents($pipes[1]);
    stream_get_contents($pipes[2]);
    fclose($pipes[1]);
    fclose($pipes[2]);
    proc_close($proc);
    $sep = strpos($out, "\r\n\r\n");
    if ($sep === false) {
        throw new RuntimeException('php-cgi 输出没有头部');
    }
    $headers = [];
    $status = 200;
    foreach (explode("\r\n", substr($out, 0, $sep)) as $line) {
        $p = strpos($line, ':');
        if ($p === false) {
            continue;
        }
        $name = strtolower(trim(substr($line, 0, $p)));
        $value = trim(substr($line, $p + 1));
        if ($name === 'status') {
            $status = (int)$value;
            continue;
        }
        $headers[$name] = $value;
    }
    return [$status, $headers, substr($out, $sep + 4)];
}

function generate($repo, $pdo, $phpCgi, $work, $host, $port, $db, $user, $password)
{
    $base = include $repo . '/application/extra/maccms.php';
    // 黄金样本统一的站点值（仓库值 site_name/site_url 为空）；缓存关闭，避免用例互相影响（cachetime 用例单独打开）
    $base = deep_merge($base, ['site' => ['site_name' => '示例站点', 'site_url' => 'www.example.test'],
        'app' => ['cache_flag' => 'golden', 'cache_core' => '0']]);
    $on = ['status' => '1', 'charge' => '0', 'cachetime' => ''];
    $open = ['api' => ['vod' => $on, 'art' => $on, 'actor' => $on, 'role' => $on, 'website' => $on]];

    // [id, 请求, 配置覆盖（null 为仓库值 + 统一站点值）, 选项]
    // 选项：rel = 执行前把这些视频的 vod_time 改为"执行时刻 - 秒数"（h 参数用例；Java 侧按记录的 now 重放）
    $specs = [];
    $vod = function ($id, $query, $conf = null, $path = '/provide/vod/', $opt = []) use (&$specs, $open) {
        $specs[] = [$id, ['entry' => 'api', 'path' => $path, 'query' => $query], $conf === null ? $open : deep_merge($open, $conf), $opt];
    };
    // ---- vod：开关
    $specs[] = ['vod.closed', ['entry' => 'api', 'path' => '/provide/vod/', 'query' => 'ac=list'], [], []];
    // ---- vod：列表/详情/XML
    $vod('vod.list', 'ac=list');
    $vod('vod.noac', '');
    $vod('vod.list.xml', 'ac=list&at=xml');
    $vod('vod.videolist', 'ac=videolist');
    $vod('vod.videolist.xml', 'ac=videolist&at=xml');
    $vod('vod.detail.ids1', 'ac=detail&ids=1');
    $vod('vod.detail.ids1.xml', 'ac=detail&ids=1&at=xml');
    $vod('vod.detail.ids-multi', 'ac=detail&ids=1,2,abc');
    $vod('vod.detail.ids-empty-item', 'ac=detail&ids=1,,3');
    $vod('vod.detail.ids-space', 'ac=detail&ids=%201');
    $vod('vod.detail.ids-unpublished', 'ac=detail&ids=5');
    // ---- vod：分类
    $vod('vod.t2', 'ac=list&t=2');
    $vod('vod.t1', 'ac=list&t=1');
    $vod('vod.t-abc', 'ac=list&t=abc');
    $vod('vod.typefilter', 'ac=list', ['api' => ['vod' => ['typefilter' => '2,3']]]);
    $vod('vod.typefilter.t-sub', 'ac=list&t=1', ['api' => ['vod' => ['typefilter' => '2,11']]]);
    $vod('vod.typefilter.t-miss', 'ac=list&t=4', ['api' => ['vod' => ['typefilter' => '2,3']]]);
    $vod('vod.typefilter.xml', 'ac=list&at=xml', ['api' => ['vod' => ['typefilter' => '2,3']]]);
    // ---- vod：isend
    $vod('vod.isend1', 'ac=list&isend=1');
    $vod('vod.isend0', 'ac=list&isend=0');
    $vod('vod.isend-empty', 'ac=list&isend=');
    $vod('vod.isend-1.0', 'ac=list&isend=1.0');
    $vod('vod.isend-01', 'ac=list&isend=01');
    $vod('vod.isend-abc', 'ac=list&isend=abc');
    // ---- vod：h（vod_time 按执行时刻改写：1 小时前、30 小时前、未来 2 天）
    $rel = ['rel' => [1 => 3600, 2 => 108000, 3 => -172800]];
    $vod('vod.h24', 'ac=list&h=24', null, '/provide/vod/', $rel);
    $vod('vod.h48', 'ac=list&h=48', null, '/provide/vod/', $rel);
    $vod('vod.h0', 'ac=list&h=0', null, '/provide/vod/', $rel);
    $vod('vod.h00', 'ac=list&h=00', null, '/provide/vod/', $rel);
    // ---- vod：wd
    $vod('vod.wd', 'ac=list&wd=' . rawurlencode('星际'));
    $vod('vod.wd-percent', 'ac=list&wd=%25');
    $vod('vod.wd-slash', 'ac=list&wd=' . rawurlencode('Action/'));
    $vod('vod.wd-double-encoded', 'ac=list&wd=%25E6%2598%259F');
    // ---- vod：year
    $vod('vod.year2014', 'ac=list&year=2014');
    $vod('vod.year-range', 'ac=list&year=2010-2014');
    $vod('vod.year-range-swap', 'ac=list&year=2014-2010');
    $vod('vod.year-short', 'ac=list&year=20');
    $vod('vod.year-abcd', 'ac=list&year=abcd');
    $vod('vod.year-old', 'ac=list&year=1800-1850');
    $vod('vod.year-9chars', 'ac=list&year=abcdefghi');
    // ---- vod：from
    $vod('vod.from-param', 'ac=list&from=aaa');
    $vod('vod.from-param-short', 'ac=list&from=aa');
    $vod('vod.from-param-multi', 'ac=list&from=aaa,ccc');
    $vod('vod.from-param.videolist', 'ac=videolist&from=aaa');
    $vod('vod.from-param.xml', 'ac=videolist&from=aaa&at=xml');
    $vod('vod.from-config', 'ac=list', ['api' => ['vod' => ['from' => 'bbb']]]);
    $vod('vod.from-config.videolist', 'ac=videolist', ['api' => ['vod' => ['from' => 'bbb,aaa']]]);
    $vod('vod.from-config.list.xml', 'ac=list&at=xml', ['api' => ['vod' => ['from' => 'bbb']]]);
    $vod('vod.from-config-overrides-param', 'ac=list&from=ccc', ['api' => ['vod' => ['from' => 'bbb']]]);
    // ---- vod：分页
    $vod('vod.pagesize3', 'ac=list&pagesize=3');
    $vod('vod.pagesize3.pg2', 'ac=list&pagesize=3&pg=2');
    $vod('vod.pagesize3.pg9', 'ac=list&pagesize=3&pg=9');
    $vod('vod.pagesize500', 'ac=list&pagesize=500');
    $vod('vod.pagesize0', 'ac=list&pagesize=0');
    $vod('vod.pg0', 'ac=list&pg=0');
    $vod('vod.config-pagesize', 'ac=list', ['api' => ['vod' => ['pagesize' => '4']]]);
    $vod('vod.config-pagesize.pg2', 'ac=list&pg=2', ['api' => ['vod' => ['pagesize' => '4']]]);
    $vod('vod.sort-asc', 'ac=list&sort_direction=asc');
    $vod('vod.sort-other', 'ac=list&sort_direction=ASC');
    // ---- vod：路径参数、后缀、POST
    $vod('vod.path-params', '', null, '/provide/vod/at/xml/ac/list/');
    $vod('vod.path-over-query', 'ac=list', null, '/provide/vod/ac/detail/ids/2');
    $vod('vod.suffix-html', 'ac=list', null, '/provide/vod.html');
    $vod('vod.no-slash', 'ac=list', null, '/provide/vod');
    // ---- vod：图片前缀
    $vod('vod.imgurl', 'ac=detail&ids=1,2,3,4', ['api' => ['vod' => ['imgurl' => 'https://img.example.test/']]]);
    $vod('vod.imgurl-http', 'ac=detail&ids=1,2', ['api' => ['vod' => ['imgurl' => 'http://img.example.test/']], 'upload' => ['protocol' => 'https']]);
    $vod('vod.imgurl-empty', 'ac=detail&ids=1,2', ['api' => ['vod' => ['imgurl' => '']]]);
    // ---- vod：白名单（REMOTE_ADDR 与伪造头；只记录旧行为，新版差异见 16 文档第 2 节）
    $charge = ['api' => ['vod' => ['charge' => '1', 'auth' => '']]];
    $specs[] = ['vod.charge.loopback', ['entry' => 'api', 'path' => '/provide/vod/', 'query' => 'ac=list'], deep_merge($open, $charge), []];
    $specs[] = ['vod.charge.denied', ['entry' => 'api', 'path' => '/provide/vod/', 'query' => 'ac=list', 'remote' => '10.0.0.5'], deep_merge($open, $charge), []];
    $specs[] = ['vod.charge.spoof-cf', ['entry' => 'api', 'path' => '/provide/vod/', 'query' => 'ac=list', 'remote' => '10.0.0.5', 'headers' => ['CF-Connecting-IP' => '127.0.0.1']], deep_merge($open, $charge), ['legacyOnly' => true]];
    $specs[] = ['vod.charge.spoof-xreal', ['entry' => 'api', 'path' => '/provide/vod/', 'query' => 'ac=list', 'remote' => '10.0.0.5', 'headers' => ['X-Real-IP' => '127.0.0.1']], deep_merge($open, $charge), ['legacyOnly' => true]];
    $specs[] = ['vod.charge.auth-ip', ['entry' => 'api', 'path' => '/provide/vod/', 'query' => 'ac=list', 'remote' => '10.0.0.5'], deep_merge($open, ['api' => ['vod' => ['charge' => '1', 'auth' => ' 10.0.0.9 # 10.0.0.5 ']]]), []];
    $specs[] = ['vod.charge.auth-port', ['entry' => 'api', 'path' => '/provide/vod/', 'query' => 'ac=list', 'remote' => '10.0.0.5'], deep_merge($open, ['api' => ['vod' => ['charge' => '1', 'auth' => '10.0.0.5:80']]]), []];
    $specs[] = ['vod.charge.ipv6', ['entry' => 'api', 'path' => '/provide/vod/', 'query' => 'ac=list', 'remote' => '::1'], deep_merge($open, $charge), []];
    // ---- 其他模块
    foreach (['art', 'actor', 'role', 'website'] as $mod) {
        $p = '/provide/' . $mod . '/';
        $specs[] = [$mod . '.closed', ['entry' => 'api', 'path' => $p, 'query' => 'ac=list'], [], []];
        $vod($mod . '.list', 'ac=list', null, $p);
        $vod($mod . '.noac', '', null, $p);
        $vod($mod . '.detail', 'ac=detail', null, $p);
        $vod($mod . '.detail.ids1', 'ac=detail&ids=1', null, $p);
        $vod($mod . '.videolist', 'ac=videolist', null, $p);
        $vod($mod . '.wd', 'ac=list&wd=' . rawurlencode('甲'), null, $p);
        $vod($mod . '.pg2', 'ac=list&pg=2', null, $p);
        $vod($mod . '.pg-abc', 'ac=list&pg=abc', null, $p);
        $vod($mod . '.h24', 'ac=list&h=24', null, $p);
        $vod($mod . '.h-abc', 'ac=list&h=abc', null, $p);
        $vod($mod . '.config-pagesize', 'ac=list', ['api' => [$mod => ['pagesize' => '1']]], $p);
        $vod($mod . '.xml-ignored', 'ac=list&at=xml', null, $p);
    }
    $vod('art.t21', 'ac=list&t=21', null, '/provide/art/');
    $vod('art.typefilter', 'ac=list', ['api' => ['art' => ['typefilter' => '22']]], '/provide/art/');
    $vod('actor.t31', 'ac=list&t=31', null, '/provide/actor/');
    $vod('website.t41', 'ac=list&t=41', null, '/provide/website/');
    $vod('role.t1', 'ac=list&t=1', null, '/provide/role/');
    $specs[] = ['comment.empty', ['entry' => 'api', 'path' => '/provide/comment/', 'query' => 'ac=list'], $open, []];
    $specs[] = ['index.empty', ['entry' => 'api', 'path' => '/provide/index/', 'query' => ''], $open, []];

    // ---- index 入口：ajax/data、ajax/suggest、ajax/desktop、qrcode
    $idx = function ($id, $path, $query, $conf = [], $opt = [], $headers = []) use (&$specs) {
        $req = ['entry' => 'index', 'path' => $path, 'query' => $query];
        if ($headers) {
            $req['headers'] = $headers;
        }
        $specs[] = [$id, $req, $conf, $opt];
    };
    foreach (['1', '2', '3', '8', '9', '11'] as $mid) {
        $idx('data.mid' . $mid, '/ajax/data', 'mid=' . $mid);
    }
    $idx('data.mid7', '/ajax/data', 'mid=7');
    $idx('data.nomid', '/ajax/data', '');
    $idx('data.limit20', '/ajax/data', 'mid=1&limit=20');
    $idx('data.limit15', '/ajax/data', 'mid=1&limit=15');
    $idx('data.page2', '/ajax/data', 'mid=1&page=2');
    $idx('data.page21', '/ajax/data', 'mid=1&page=21');
    $idx('data.page-abc', '/ajax/data', 'mid=1&page=abc');
    $idx('data.tid1', '/ajax/data', 'mid=1&tid=1');
    $idx('data.tid2', '/ajax/data', 'mid=1&tid=2');
    $idx('data.tid99', '/ajax/data', 'mid=1&tid=99');
    $idx('data.art.tid21', '/ajax/data', 'mid=2&tid=21');
    $idx('data.path-params', '/ajax/data/mid/2/limit/10', '');
    $idx('suggest.closed', '/ajax/suggest', 'mid=1&wd=' . rawurlencode('星'), ['app' => ['search' => '0']]);
    foreach (['1', '2', '3', '8', '9', '11'] as $mid) {
        $idx('suggest.mid' . $mid, '/ajax/suggest', 'mid=' . $mid . '&wd=' . rawurlencode('甲'));
    }
    $idx('suggest.vod', '/ajax/suggest', 'mid=1&wd=' . rawurlencode('星'));
    $idx('suggest.en', '/ajax/suggest', 'mid=1&wd=xiju');
    $idx('suggest.limit1', '/ajax/suggest', 'mid=1&wd=a&limit=1');
    $idx('suggest.limit0', '/ajax/suggest', 'mid=1&wd=a&limit=0');
    $idx('suggest.nowd', '/ajax/suggest', 'mid=1');
    $idx('suggest.badmid', '/ajax/suggest', 'mid=4&wd=a');
    $idx('desktop.default', '/ajax/desktop', '');
    $idx('desktop.named', '/ajax/desktop', 'name=' . rawurlencode('我的站') . '&url=' . rawurlencode('www.example.test/a?b=1'));
    $idx('desktop.http', '/ajax/desktop', 'name=abc&url=' . rawurlencode('https://www.example.test/'));
    $idx('desktop.msie', '/ajax/desktop', 'name=' . rawurlencode('我的站') . '&url=x', [], [], ['User-Agent' => 'Mozilla/4.0 (compatible; MSIE 8.0; Windows NT 6.1)']);
    $idx('desktop.html-chars', '/ajax/desktop', 'name=' . rawurlencode('a<b>"c"') . '&url=' . rawurlencode('http://x.test/?a=1&b=2'));
    $idx('qrcode.valid', '/qrcode/index', 'url=' . rawurlencode('http://www.example.test/index.php/vod/detail/id/1.html'));
    $idx('qrcode.invalid', '/qrcode/index', 'url=' . rawurlencode('not a url'));
    $idx('qrcode.empty', '/qrcode/index', '');

    // ---- cachetime：同一请求两次，中间改名；旧版第二次仍返回缓存（新版不做服务端缓存，16 文档 D-B06-4）
    $cache = deep_merge($open, ['api' => ['vod' => ['cachetime' => '60']]]);
    $specs[] = ['vod.cache.first', ['entry' => 'api', 'path' => '/provide/vod/', 'query' => 'ac=list&t=3'], $cache, []];
    $specs[] = ['vod.cache.second', ['entry' => 'api', 'path' => '/provide/vod/', 'query' => 'ac=list&t=3'], $cache,
        ['legacyOnly' => true, 'sqlBefore' => ["UPDATE mac_vod SET vod_name='改名后' WHERE vod_id=2"],
            'sqlAfter' => ["UPDATE mac_vod SET vod_name='欢乐喜剧' WHERE vod_id=2"]]];
    // ---- datafilter 为空：旧版输出未审核行（新版始终只含已审核，D-B06-2）
    $vod('vod.datafilter-empty', 'ac=list', ['api' => ['vod' => ['datafilter' => '']]], '/provide/vod/', ['legacyOnly' => true]);

    // ---- 差异数据（phase-diff.sql）：只记录旧版输出，Java 侧按 16 文档第 3.6、4.9、4.10 节的规则断言
    $d = ['phase' => 'diff', 'legacyOnly' => true];
    $vod('diff.vod.list', 'ac=list', null, '/provide/vod/', $d);
    $vod('diff.vod.list.xml', 'ac=list&at=xml', null, '/provide/vod/', $d);
    $vod('diff.vod.detail', 'ac=detail&ids=30,31,32,33,34,35,36', null, '/provide/vod/', $d);
    $vod('diff.vod.detail.xml', 'ac=detail&ids=33,34,35,36&at=xml', null, '/provide/vod/', $d);
    $vod('diff.art.detail', 'ac=detail&ids=10', null, '/provide/art/', $d);
    $vod('diff.role.list', 'ac=list', null, '/provide/role/', $d);
    $vod('diff.role.detail', 'ac=detail&ids=3,4', null, '/provide/role/', $d);
    $idx('diff.data.mid9', '/ajax/data', 'mid=9', [], $d);
    $idx('diff.suggest.unpublished', '/ajax/suggest', 'mid=1&wd=' . rawurlencode('未审核'), [], $d);
    $idx('diff.suggest.invisible', '/ajax/suggest', 'mid=1&wd=' . rawurlencode('分类视频'), [], $d);

    $conf = $work . '/conf';
    $runtime = $work . '/runtime';
    @mkdir($runtime, 0777, true);
    $cases = [];
    $lastConf = null;
    $only = getenv('GOLDEN_ONLY');
    foreach ($specs as $spec) {
        list($id, $req, $over, $opt) = $spec;
        if ($only !== false && $only !== '' && strpos($id, $only) !== 0) {
            continue;
        }
        $maccms = deep_merge($base, $over);
        $serialized = serialize($maccms);
        if ($serialized !== $lastConf) {
            rm_tree($conf);
            write_conf($repo, $conf, $maccms, $GLOBALS['host'], $GLOBALS['port'], $GLOBALS['dbName'],
                $GLOBALS['adminUser'], $GLOBALS['adminPassword']);
            rm_tree($runtime);
            @mkdir($runtime, 0777, true);
            $lastConf = $serialized;
        }
        if (isset($opt['phase']) && $opt['phase'] === 'diff' && empty($GLOBALS['diffLoaded'])) {
            foreach (sql_statements($repo . '/backend/src/test/resources/openapi/provide/phase-diff.sql') as $sql) {
                $pdo->exec($sql);
            }
            $GLOBALS['diffLoaded'] = true;
            // 分类缓存（type_list）须随数据重建
            rm_tree($runtime);
            @mkdir($runtime, 0777, true);
        }
        if (isset($opt['sqlBefore'])) {
            foreach ($opt['sqlBefore'] as $sql) {
                $pdo->exec($sql);
            }
        }
        $now = time();
        if (isset($opt['rel'])) {
            foreach ($opt['rel'] as $vid => $ago) {
                foreach (['vod' => 'vod', 'art' => 'art', 'actor' => 'actor', 'role' => 'role', 'website' => 'website'] as $t) {
                    $pdo->exec('UPDATE mac_' . $t . ' SET ' . $t . '_time = ' . ($now - $ago) . ' WHERE ' . $t . '_id = ' . (int)$vid);
                }
            }
        }
        list($status, $headers, $body) = run_request($repo, $phpCgi, $conf, $runtime, $req);
        $after = time();
        if (isset($opt['rel'])) {
            // 恢复种子中的时间（与 seed.sql 相同的值），避免影响后续用例
            $seedTimes = ['vod' => [1 => 1700001000, 2 => 1700002000, 3 => 1700003000],
                'art' => [1 => 1700001100, 2 => 1700001200, 3 => 1700009100],
                'actor' => [1 => 1700001300, 2 => 1700009200], 'role' => [1 => 1700001400, 2 => 1700001500],
                'website' => [1 => 1700001600]];
            foreach ($seedTimes as $t => $rows) {
                foreach ($rows as $vid => $ts) {
                    $pdo->exec('UPDATE mac_' . $t . ' SET ' . $t . '_time = ' . $ts . ' WHERE ' . $t . '_id = ' . $vid);
                }
            }
        }
        if (isset($opt['sqlAfter'])) {
            foreach ($opt['sqlAfter'] as $sql) {
                $pdo->exec($sql);
            }
        }
        $kept = [];
        foreach (['content-type', 'content-disposition', 'cache-control', 'location'] as $h) {
            if (isset($headers[$h])) {
                $kept[$h] = $headers[$h];
            }
        }
        $cases[] = [
            'id' => $id,
            'request' => $req,
            'config' => $over,
            'rel' => isset($opt['rel']) ? $opt['rel'] : (object)[],
            'legacyOnly' => !empty($opt['legacyOnly']),
            'phase' => isset($opt['phase']) ? $opt['phase'] : 'main',
            'now' => $now,
            'nowAfter' => $after,
            'status' => $status,
            'headers' => (object)$kept,
            'body' => base64_encode($body),
        ];
    }
    return json_encode(['php' => PHP_VERSION, 'timezone' => 'PRC', 'site' => $base['site'], 'cases' => $cases],
            JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT) . "\n";
}
