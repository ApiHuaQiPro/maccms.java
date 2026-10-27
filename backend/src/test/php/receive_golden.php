<?php
/*
 * B19（MACC-43）采集入库与 Receive 入站接口的 PHP 黄金样本生成脚本（契约见 docs/architecture/27-b19-collect-ingest.md 第 7 节）。
 *
 * 每个场景是一串真实的入站请求：经 legacy_http_child.php（api 入口）跑完整的 ThinkPHP 路由、api/controller/Receive 的构造函数与
 * Receive::vod，后者调用旧 Collect::vod_data($param = [], $data, $show = 0)——就是后台采集与 Receive 共用的入库合并规则。每个请求记录
 * 状态码、Content-Type 与正文，以及请求后 mac_vod、mac_vod_search 的全部行（文本列十六进制）与请求前后的秒级时间（started/after，
 * 比较 vod_time、vod_time_add 等写入时刻用）。
 *
 * 场景之间互不依赖：每个场景先清空 mac_vod、mac_vod_search 并把自增计数复位（一次性库上的测试夹具，新版的复放测试做同样的事，使新增行
 * 的主键相同），写入自己的种子行，使用新的配置与运行目录（旧版把 mac_interface_type 放在运行目录的缓存里）。
 *
 * 流程：外部管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入 install.sql → 写入分类 → 逐个场景执行 → 输出 JSON → 删除一次性库与临时目录。
 * 不修改任何旧 PHP 文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，不访问网络（图片同步保持关闭）。
 *
 * 管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；另需 PHP_CGI。
 * 用法（仓库根目录，PHP 8.0 与 7.3 各一份）：
 *   PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe backend/src/test/php/receive_golden.php \
 *       > backend/src/test/resources/collect/receive/php80-golden.json
 */

error_reporting(E_ERROR | E_PARSE);
date_default_timezone_set('PRC');
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

/** 每个场景开始前清空、请求后整表记录的表（表 => 主键）。 */
const TABLES = ['mac_vod' => 'vod_id', 'mac_vod_search' => 'search_key'];
/** 入站密码（16 位以上）。 */
const PASS = 'golden-pass-0123456789';

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$work = sys_get_temp_dir() . DIRECTORY_SEPARATOR . 'maccms-b19-golden-' . bin2hex(random_bytes(4));
$exit = 0;
try {
    $pdo->exec("USE `$dbName`");
    foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
        $pdo->exec($sql);
    }
    // 与 ReceiveGoldenMySqlIT 相同的分类（sql_mode 为空：TEXT 列没有默认值）
    $pdo->exec("SET SESSION sql_mode = ''");
    $pdo->exec("INSERT INTO mac_type (type_id, type_name, type_en, type_sort, type_mid, type_pid, type_status) VALUES "
        . "(1, '电影', 'dianying', 1, 1, 0, 1), (6, '动作片', 'dongzuopian', 2, 1, 1, 1), (7, '喜剧片', 'xijupian', 3, 1, 1, 1)");
    $pdo->exec("SET SESSION sql_mode = DEFAULT");
    echo generate($repo, $pdo, $phpCgi, $work, $host, $port, $dbName, $adminUser, $adminPassword);
} catch (\Throwable $e) {
    // 不输出 message（可能含连接信息），只给类型与位置
    fwrite(STDERR, 'failed: ' . get_class($e) . ' at ' . $e->getFile() . ':' . $e->getLine() . "\n");
    if (getenv('GOLDEN_DEBUG') === '1') {
        fwrite(STDERR, $e->getMessage() . "\n");
    }
    $exit = 1;
} finally {
    $pdo->exec("DROP DATABASE `$dbName`");
    rm_tree($work);
}
exit($exit);

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

/** 一张表的全部行（按主键排序；值一律十六进制，NULL 为 null）。 */
function table_rows($pdo, $table, $pk)
{
    $out = [];
    foreach ($pdo->query("SELECT * FROM `$table` ORDER BY `$pk`")->fetchAll(PDO::FETCH_ASSOC) as $r) {
        $o = [];
        foreach ($r as $k => $v) {
            $o[$k] = $v === null ? null : bin2hex((string)$v);
        }
        $out[] = $o;
    }
    return $out;
}

/** 场景开始：清空跟踪的表、自增复位、写入种子行（sql_mode 为空，与新版复放相同）。 */
function reset_tables($pdo, $seed)
{
    $pdo->exec("SET SESSION sql_mode = ''");
    foreach (TABLES as $t => $pk) {
        $pdo->exec("DELETE FROM `$t`");
        if ($t !== 'mac_vod_search') {
            $pdo->exec("ALTER TABLE `$t` AUTO_INCREMENT = 1");
        }
    }
    foreach ($seed as $t => $rows) {
        foreach ($rows as $row) {
            $cols = array_keys($row);
            $sql = "INSERT INTO `$t` (`" . implode('`, `', $cols) . "`) VALUES (" . implode(', ', array_fill(0, count($cols), '?')) . ")";
            $pdo->prepare($sql)->execute(array_values($row));
        }
    }
    $pdo->exec("SET SESSION sql_mode = DEFAULT");
}

/**
 * 视频入站请求的字段（带密码；没有给 vod_content 时补一段正文——旧表的 vod_content 是没有默认值的 TEXT，MySQL 严格模式下缺这一列的
 * 新增报错，旧版整页 500，见 require/no-content）。
 */
function vod_post($fields)
{
    return array_merge(['pass' => PASS], $fields, isset($fields['vod_content']) ? [] : ['vod_content' => '正文']);
}

/** 种子视频行。 */
function seed_vod($id, $over)
{
    return array_merge(['vod_id' => $id, 'type_id' => 6, 'type_id_1' => 1, 'vod_name' => '老剧', 'vod_actor' => '甲',
        'vod_director' => '乙', 'vod_play_from' => 'dplayer', 'vod_play_url' => 'a#b', 'vod_play_server' => 'no',
        'vod_play_note' => '', 'vod_down_from' => '', 'vod_down_url' => '', 'vod_down_server' => '', 'vod_down_note' => '',
        'vod_content' => '旧内容', 'vod_blurb' => '旧简介', 'vod_serial' => '10', 'vod_remarks' => '旧备注', 'vod_pic' => '',
        'vod_class' => '剧情', 'vod_time' => 1600000000, 'vod_time_add' => 1600000000, 'vod_plot_name' => '', 'vod_plot_detail' => '',
        'vod_pic_screenshot' => '', 'vod_status' => 1, 'vod_lock' => 0], $over);
}

/** 场景表：[标签, 配置覆盖, 种子, 步骤[[标签, 方法, 路径, 查询串, POST 字段（null 为 GET）]], 选项]。 */
function scenarios()
{
    $full = [
        'vod_id' => '999', 'type_id' => '6', 'type_name' => '动作片', 'vod_name' => '星际 穿越<b>粗体</b>', 'vod_sub' => 'a%2Bb',
        'vod_letter' => 'Z', 'vod_actor' => '张三/李四，王五', 'vod_director' => '导演甲', 'vod_class' => '科幻|冒险',
        'vod_tag' => '<i>t1</i> t2', 'vod_year' => '2014年', 'vod_area' => '美国', 'vod_lang' => '英语', 'vod_remarks' => 'HD',
        'vod_serial' => '12集', 'vod_total' => '24', 'vod_isend' => '1', 'vod_pic' => 'http://img.example.test/a.jpg',
        'vod_content' => '<p>一个关于星际旅行的故事。</p>', 'vod_blurb' => '',
        'vod_play_from' => 'dplayer$$$unknown$$$$$$videojs',
        'vod_play_url' => '第1集$http://v.example.test/1.m3u8#第2集$http://v.example.test/2.m3u8#$$$x$$$y$$$z#',
        'vod_play_server' => 'no$$$no$$$no$$$no', 'vod_play_note' => '$$$$$$$$$', 'vod_down_from' => 'http',
        'vod_down_url' => '下载$http://d.example.test/1.mp4  ', 'vod_plot_name' => '$$$第1集$$$', 'vod_plot_detail' => '$$$剧情$$$',
        'vod_time_add' => '1700000000', 'vod_time_update' => '1700000100', 'vod_status' => '0', 'vod_lock' => '0', 'vod_hits' => '99',
        'vod_score' => '8.5', 'extra_field' => 'ignored'];
    $allUp = ',a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u,v,w';
    $old = ['vod_name' => '老剧', 'type_id' => '6', 'vod_actor' => '甲', 'vod_director' => '乙'];
    $P = '/receive/vod/';
    return [
        ['closed', ['interface' => ['status' => '0']], [], [['post', 'POST', $P, '', vod_post(['vod_name' => 'x', 'type_id' => '6'])]]],
        ['pass', [], [], [
            ['wrong', 'POST', $P, '', ['pass' => 'nope', 'vod_name' => 'x', 'type_id' => '6']],
            ['missing', 'POST', $P, '', ['vod_name' => 'x', 'type_id' => '6']]]],
        ['pass-loose', ['interface' => ['pass' => '1000000000000000']], [], [
            ['numeric-equal', 'POST', $P, '', ['pass' => '1e15', 'vod_name' => '数值密码', 'type_id' => '6', 'vod_content' => 'c']]]],
        ['pass-short', ['interface' => ['pass' => 'short']], [], [['short', 'POST', $P, '', ['pass' => 'short', 'vod_name' => 'x']]]],
        ['pass-empty', ['interface' => ['pass' => '']], [], [['none', 'POST', $P, '', ['vod_name' => 'x']]]],
        ['require', [], [], [
            ['no-name', 'POST', $P, '', vod_post(['type_id' => '6'])],
            ['no-type', 'POST', $P, '', vod_post(['vod_name' => '无分类'])],
            ['type-name-unmapped', 'POST', $P, '', vod_post(['vod_name' => '未映射', 'type_name' => '纪录片'])],
            ['type-name-mapped', 'POST', $P, '', vod_post(['vod_name' => '已映射', 'type_name' => '喜剧'])],
            ['type-zero', 'POST', $P, '', vod_post(['vod_name' => '零分类', 'type_id' => '0', 'type_name' => '喜剧'])],
            ['type-name-orphan', 'POST', $P, '', vod_post(['vod_name' => '孤儿', 'type_name' => '孤儿'])],
            ['no-content', 'POST', $P, '', ['pass' => PASS, 'vod_name' => '没有正文', 'type_id' => '6']],
            ['index', 'GET', '/receive/index/', 'pass=' . rawurlencode(PASS), null]]],
        ['insert-full', [], [], [['full', 'POST', $P, '', vod_post($full)]]],
        ['filter', [], [], [['in-filter', 'POST', $P, '', vod_post(['vod_name' => '色戒 加长版', 'type_id' => '6'])]]],
        ['psename', ['collect' => ['vod' => ['psename' => '1']]], [], [
            ['rename', 'POST', $P, '', vod_post(['vod_name' => '某剧第2季', 'type_id' => '6'])]]],
        ['update-play', [], ['mac_vod' => [seed_vod(1, [])]], [
            ['same-and-new', 'POST', $P, '', vod_post($old + ['vod_play_from' => 'dplayer$$$videojs', 'vod_play_url' => 'a#b$$$c#d',
                'vod_play_server' => 'no$$$no', 'vod_play_note' => '$$$'])],
            ['replace', 'POST', $P, '', vod_post($old + ['vod_play_from' => 'dplayer', 'vod_play_url' => 'a#c'])],
            ['same-group', 'POST', $P, '', vod_post($old + ['vod_play_from' => 'videojs', 'vod_play_url' => 'c#d'])],
            ['no-play', 'POST', $P, '', vod_post($old)]]],
        ['update-urlrole', ['collect' => ['vod' => ['urlrole' => '1', 'uprule' => ',a,b']]], ['mac_vod' => [seed_vod(1,
            ['vod_down_from' => 'http', 'vod_down_url' => 'd1#d2', 'vod_down_server' => 'no', 'vod_down_note' => 'n'])]], [
            ['merge', 'POST', $P, '', vod_post($old + ['vod_play_from' => 'dplayer', 'vod_play_url' => 'b#c#',
                'vod_down_from' => 'http$$$xunlei', 'vod_down_url' => 'd2#d3$$$x1', 'vod_down_server' => '$$$s', 'vod_down_note' => '$$$m'])]]],
        ['update-fields', ['collect' => ['vod' => ['uprule' => $allUp, 'inrule' => ',b']]], ['mac_vod' => [
            seed_vod(1, ['vod_pic' => 'http://old.example.test/p.jpg', 'vod_year' => '2010', 'vod_total' => '5', 'vod_isend' => 0]),
            seed_vod(2, ['vod_name' => '本地图', 'vod_pic' => 'upload/vod/x.jpg', 'vod_serial' => '7.5'])]], [
            ['all', 'POST', $P, '', vod_post(['vod_name' => '老剧', 'type_id' => '6', 'vod_actor' => '丙/丁', 'vod_director' => '戊',
                'vod_serial' => '8', 'vod_remarks' => '新备注', 'vod_year' => '2011', 'vod_area' => '大陆', 'vod_lang' => '国语',
                'vod_pic' => 'http://new.example.test/p.jpg', 'vod_content' => '新内容', 'vod_tag' => '标签', 'vod_sub' => '副名',
                'vod_class' => '悬疑/剧情', 'vod_writer' => '编剧', 'vod_version' => '高清', 'vod_state' => '正片', 'vod_blurb' => '新简介',
                'vod_tv' => 'CCTV', 'vod_weekday' => '一', 'vod_total' => '30', 'vod_isend' => '1', 'vod_plot_name' => '第1集',
                'vod_plot_detail' => '详情'])],
            ['serial-bigger', 'POST', $P, '', vod_post(['vod_name' => '老剧', 'type_id' => '6', 'vod_serial' => '20'])],
            ['local-pic', 'POST', $P, '', vod_post(['vod_name' => '本地图', 'type_id' => '6', 'vod_pic' => 'http://n.example.test/q.jpg',
                'vod_serial' => '3'])]]],
        ['locked', [], ['mac_vod' => [seed_vod(1, ['vod_lock' => 1])]], [['lock', 'POST', $P, '', vod_post($old)]]],
        ['uprule-empty', ['collect' => ['vod' => ['uprule' => '']]], ['mac_vod' => [seed_vod(1, [])]], [
            ['empty', 'POST', $P, '', vod_post($old)]]],
        ['not-need', ['collect' => ['vod' => ['uprule' => ',d']]], ['mac_vod' => [seed_vod(1, [])]], [
            ['same', 'POST', $P, '', vod_post($old + ['vod_remarks' => '旧备注'])]]],
        ['inrule-blend', ['collect' => ['vod' => ['uprule' => ',d']]], ['mac_vod' => [seed_vod(1, ['vod_name' => '同名',
            'vod_actor' => '演员A,演员B', 'vod_director' => '导演X'])]], [
            ['director-match', 'POST', $P, '', vod_post(['vod_name' => '同名', 'type_id' => '6', 'vod_actor' => '演员C',
                'vod_director' => '导演X', 'vod_remarks' => 'r1'])],
            ['actor-match', 'POST', $P, '', vod_post(['vod_name' => '同名', 'type_id' => '6', 'vod_actor' => '演员Z,演员B',
                'vod_director' => '导演Y', 'vod_remarks' => 'r2'])],
            ['no-match', 'POST', $P, '', vod_post(['vod_name' => '同名', 'type_id' => '6', 'vod_actor' => '演员Z',
                'vod_director' => '导演Z', 'vod_remarks' => 'r3'])],
            ['no-director', 'POST', $P, '', vod_post(['vod_name' => '同名', 'type_id' => '6', 'vod_actor' => '演员A',
                'vod_remarks' => 'r4'])]]],
        ['inrule-year', ['collect' => ['vod' => ['inrule' => ',b,c,d,e', 'uprule' => ',d']]], ['mac_vod' => [seed_vod(1,
            ['vod_year' => '2014', 'vod_area' => '美国', 'vod_lang' => '英语'])]], [
            ['match', 'POST', $P, '', vod_post(['vod_name' => '老剧', 'type_id' => '6', 'vod_year' => '2014年', 'vod_area' => '美国',
                'vod_lang' => '英语', 'vod_remarks' => 'y1'])],
            ['other-year', 'POST', $P, '', vod_post(['vod_name' => '老剧', 'type_id' => '6', 'vod_year' => '2015', 'vod_area' => '美国',
                'vod_lang' => '英语', 'vod_remarks' => 'y2'])],
            ['other-type', 'POST', $P, '', vod_post(['vod_name' => '老剧', 'type_id' => '7', 'vod_year' => '2014', 'vod_area' => '美国',
                'vod_lang' => '英语', 'vod_remarks' => 'y3'])]]],
        ['get-route', [], [], [
            ['route-pass', 'GET', '/receive/vod/pass/' . PASS . '/', 'vod_name=' . rawurlencode('路径密码') . '&type_id=6&vod_content=c', null],
            ['query-only', 'GET', $P, 'pass=' . rawurlencode(PASS) . '&vod_name=' . rawurlencode('查询%2541') . '&type_id=6&vod_content=c', null]]],
        ['trim-exp', [], [], [['trim', 'POST', $P, '', vod_post(['vod_name' => "  两边空格\t", 'type_id' => ' 6 ', 'vod_remarks' => 'LIKE',
            'vod_sub' => 'not in', 'vod_lang' => 'Null'])]]],
        ['filter-add', [], [], [['player-filter', 'POST', $P, '', vod_post(['vod_name' => '片名<script>x</script>', 'type_id' => '7',
            'vod_class' => '喜剧', 'type_name' => '喜剧片', 'vod_content' => 'abc', 'vod_blurb' => '自带简介',
            'vod_play_from' => 'iframe$$$link', 'vod_play_url' => 'u1$$$u2'])]]],
        ['search-collect', ['app' => ['vod_search_optimise' => 'collect|frontend'], 'collect' => ['vod' => ['uprule' => ',d']]],
            ['mac_vod' => [seed_vod(1, ['vod_name' => '搜索剧', 'vod_actor' => '主演甲,主演乙', 'vod_director' => '导演丙'])]], [
            ['blend-ids', 'POST', $P, '', vod_post(['vod_name' => '搜索剧', 'type_id' => '6', 'vod_actor' => '主演乙',
                'vod_director' => '别的导演', 'vod_remarks' => 's1'])],
            ['insert-append', 'POST', $P, '', vod_post(['vod_name' => '新搜索剧', 'type_id' => '6', 'vod_actor' => '主演乙',
                'vod_director' => '导演丁'])]]],
        ['random', ['collect' => ['vod' => ['hits_start' => '1', 'hits_end' => '1000', 'updown_start' => '1', 'updown_end' => '1000',
            'score' => '1']]], [], [['random', 'POST', $P, '', vod_post(['vod_name' => '随机', 'type_id' => '6'])]], ['random' => true]],
    ];
}

function generate($repo, $pdo, $phpCgi, $work, $host, $port, $db, $user, $password)
{
    $base = include $repo . '/application/extra/maccms.php';
    // 统一的站点值与入站接口；入库配置关闭随机项（点击、顶踩、评分），随机场景单独打开
    $base = deep_merge($base, [
        'site' => ['site_name' => '示例站点', 'site_url' => 'www.example.test'],
        'app' => ['cache_flag' => 'golden', 'cache_core' => '0', 'vod_search_optimise' => ''],
        'interface' => ['status' => '1', 'pass' => PASS, 'vodtype' => "动作片=动作\r\n喜剧片=喜剧\r\n不存在=孤儿\r\n=空名", 'arttype' => ''],
        'collect' => ['vod' => ['status' => '1', 'hits_start' => '0', 'hits_end' => '0', 'updown_start' => '0', 'updown_end' => '0',
            'score' => '0', 'pic' => '0', 'tag' => '0', 'class_filter' => '1', 'psename' => '0', 'psernd' => '0', 'psesyn' => '0',
            'urlrole' => '0', 'inrule' => ',f,g', 'uprule' => ',a', 'filter' => '色戒,色即是空']],
    ]);
    $only = getenv('GOLDEN_ONLY');
    $out = ['php' => PHP_VERSION, 'pass' => PASS, 'scenarios' => []];
    $n = 0;
    foreach (scenarios() as $sc) {
        list($label, $over, $seed, $steps) = $sc;
        $opt = isset($sc[4]) ? $sc[4] : [];
        if ($only !== false && $only !== '' && count(array_filter(explode(',', $only), function ($o) use ($label) {
                return strpos($label, $o) === 0;
            })) === 0) {
            continue;
        }
        $maccms = deep_merge($base, $over);
        $dir = $work . DIRECTORY_SEPARATOR . 's' . (++$n);
        $conf = $dir . DIRECTORY_SEPARATOR . 'conf';
        $runtime = $dir . DIRECTORY_SEPARATOR . 'runtime';
        @mkdir($runtime, 0777, true);
        write_conf($repo, $conf, $maccms, $host, $port, $db, $user, $password);
        reset_tables($pdo, $seed);
        $rec = ['label' => $label, 'maccmsPhp' => base64_encode(file_get_contents($conf . '/extra/maccms.php')),
            'seed' => $seed, 'random' => !empty($opt['random']), 'steps' => []];
        foreach ($steps as $st) {
            list($slabel, $method, $path, $query, $post) = $st;
            $body = $post === null ? '' : http_build_query($post);
            $started = time();
            list($status, $headers, $resp) = run_request($repo, $phpCgi, $conf, $runtime, ['entry' => 'api', 'path' => $path,
                'query' => $query, 'method' => $method, 'post' => $body]);
            $after = time();
            $tables = [];
            foreach (TABLES as $t => $pk) {
                $tables[$t] = table_rows($pdo, $t, $pk);
            }
            $rec['steps'][] = ['label' => $slabel, 'method' => $method, 'path' => $path, 'query' => $query, 'body' => $body,
                'status' => $status, 'contentType' => isset($headers['content-type']) ? $headers['content-type'] : null,
                'response' => base64_encode($resp), 'started' => $started, 'after' => $after, 'tables' => $tables];
        }
        $out['scenarios'][] = $rec;
    }
    return json_encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT) . "\n";
}
