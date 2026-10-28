<?php
/*
 * B27（MACC-50）微信公众号入站接口的 PHP 黄金样本生成脚本（契约见 docs/architecture/29-b27-wechat.md 第 6 节）。
 *
 * 每个场景是一串真实的入站请求：经 legacy_http_child.php（api 入口）跑完整的 ThinkPHP 路由、api/controller/Wechat::index 与
 * common/util/WechatPublic（接入验证 valid、消息回复 responseMsg，文本消息的搜索走旧 Vod::listCacheData）。每个请求记录状态码、
 * Content-Type、正文与请求前后的秒级时间（started/after，比较回复里的 CreateTime 用），以及请求后 mac_vod 的全部行（文本列十六进制），
 * 证明接口不写库。输出里带上分类语句与视频种子（typesSql、seed），新版复放（WechatGoldenMySqlIT）照此建库。
 *
 * 流程：外部管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入 install.sql → 写入分类与视频种子 → 逐个场景执行 → 输出 JSON →
 * 删除一次性库与临时目录。不修改任何旧 PHP 文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，不访问网络。
 *
 * 管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；另需 PHP_CGI。
 * 用法（仓库根目录，PHP 7.3 与 8.0 各一份）：
 *   PHP_CGI=D:/Tools/php/7.3/php-cgi.exe D:/Tools/php/7.3/php.exe backend/src/test/php/wechat_golden.php \
 *       > backend/src/test/resources/openapi/wechat/php73-golden.json
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

/** 接入验证用的令牌（黄金样本自己的假值）。 */
const TOKEN = 'golden-wechat-token';
/** XXE 用例读取的本地文件里的标记（出现在回复里即说明外部实体被展开）。 */
const XXE_MARKER = 'XXE-LEAKED-0f3a';
/** 分类（与 WechatGoldenMySqlIT 相同）。 */
const TYPES_SQL = "INSERT INTO mac_type (type_id, type_name, type_en, type_sort, type_mid, type_pid, type_status) VALUES "
    . "(1, '电影', 'dianying', 1, 1, 0, 1), (6, '动作片', 'dongzuopian', 2, 1, 1, 1), (7, '喜剧片', 'xijupian', 3, 1, 1, 1)";

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$work = sys_get_temp_dir() . DIRECTORY_SEPARATOR . 'maccms-b27-golden-' . bin2hex(random_bytes(4));
$exit = 0;
try {
    $pdo->exec("USE `$dbName`");
    foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
        $pdo->exec($sql);
    }
    // 与 WechatGoldenMySqlIT 相同的分类与视频（sql_mode 为空：TEXT 列没有默认值）
    $pdo->exec("SET SESSION sql_mode = ''");
    $pdo->exec(TYPES_SQL);
    foreach (seed_vods() as $row) {
        $cols = array_keys($row);
        $pdo->prepare("INSERT INTO mac_vod (`" . implode('`, `', $cols) . "`) VALUES ("
            . implode(', ', array_fill(0, count($cols), '?')) . ")")->execute(array_values($row));
    }
    $pdo->exec("SET SESSION sql_mode = DEFAULT");
    @mkdir($work, 0777, true);
    $xxeFile = $work . DIRECTORY_SEPARATOR . 'xxe.txt';
    file_put_contents($xxeFile, XXE_MARKER);
    echo generate($repo, $pdo, $phpCgi, $work, $host, $port, $dbName, $adminUser, $adminPassword, $xxeFile);
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

/** 视频种子：名称都含"星际"的 9 部（按 vod_time 倒序取前 7 部），另有未审核、英文名/副名命中各一部。 */
function seed_vods()
{
    $rows = [];
    $pics = ['http://img.example.test/a.jpg', 'mac://img.example.test/b.jpg', 'upload/vod/c.jpg', '', '//cdn.example.test/e.jpg',
        '/upload/vod/f.jpg', 'https://img.example.test/g.jpg', 'http://img.example.test/h.jpg', 'upload/vod/i.jpg'];
    $contents = ['<p>一个关于星际旅行的故事，<b>穿越</b>虫洞寻找新的家园。</p>', 'plain ascii content that is long enough',
        '短', '', '<script>alert(1)</script>正文&amp;实体', '第一行' . "\n" . '第二行', '中英mixed内容123456789012345', 'x',
        '<img src="a.jpg">图片后的文字很长很长很长很长很长很长'];
    for ($i = 1; $i <= 9; $i++) {
        $rows[] = vod_row($i, [
            'vod_name' => '星际' . $i . ($i === 3 ? '<i>标签</i>' : ''),
            'vod_remarks' => $i === 4 ? '' : '第' . $i . '集',
            'vod_pic' => $pics[$i - 1],
            'vod_content' => $contents[$i - 1],
            'vod_time' => 1700000000 + $i * 100,
            'type_id' => $i % 2 === 0 ? 7 : 6,
            'vod_en' => 'xingji' . $i,
        ]);
    }
    $rows[] = vod_row(10, ['vod_name' => '星际未审核', 'vod_status' => 0, 'vod_time' => 1800000000]);
    $rows[] = vod_row(11, ['vod_name' => '英文名命中', 'vod_en' => 'interstellar', 'vod_time' => 1600000000]);
    $rows[] = vod_row(12, ['vod_name' => '副名命中', 'vod_sub' => '另一个名字', 'vod_time' => 1600000100]);
    return $rows;
}

function vod_row($id, $over)
{
    return array_merge(['vod_id' => $id, 'type_id' => 6, 'type_id_1' => 1, 'vod_name' => 'v', 'vod_sub' => '', 'vod_en' => '',
        'vod_actor' => '', 'vod_director' => '', 'vod_play_from' => 'dplayer', 'vod_play_url' => '第1集$http://v.example.test/1.m3u8',
        'vod_play_server' => 'no', 'vod_play_note' => '', 'vod_down_from' => '', 'vod_down_url' => '', 'vod_down_server' => '',
        'vod_down_note' => '', 'vod_content' => '', 'vod_blurb' => '', 'vod_remarks' => '', 'vod_pic' => '', 'vod_class' => '',
        'vod_time' => 1700000000, 'vod_time_add' => 1700000000, 'vod_plot_name' => '', 'vod_plot_detail' => '',
        'vod_pic_screenshot' => '', 'vod_status' => 1, 'vod_lock' => 0], $over);
}

/** 公众号推送的消息 XML（字段顺序与微信文档一致）。 */
function msg($type, $fields)
{
    $x = '<xml><ToUserName><![CDATA[gh_golden]]></ToUserName><FromUserName><![CDATA[o_user_golden]]></FromUserName>'
        . '<CreateTime>1700000000</CreateTime><MsgType><![CDATA[' . $type . ']]></MsgType>';
    foreach ($fields as $k => $v) {
        $x .= '<' . $k . '><![CDATA[' . $v . ']]></' . $k . '>';
    }
    return $x . '<MsgId>1234567890</MsgId></xml>';
}

function text_msg($content)
{
    return msg('text', ['Content' => $content]);
}

function event_msg($event, $key = null)
{
    return msg('event', $key === null ? ['Event' => $event] : ['Event' => $event, 'EventKey' => $key]);
}

/** 接入验证的查询串（signature 按旧版算法用给定令牌计算）。 */
function valid_query($token, $echostr, $timestamp = '1700000000', $nonce = 'n0nce')
{
    $a = [$token, $timestamp, $nonce];
    sort($a);
    return 'signature=' . sha1(implode($a)) . '&timestamp=' . $timestamp . '&nonce=' . $nonce . '&echostr=' . rawurlencode($echostr);
}

/** 场景表：[标签, 配置覆盖（weixin 组等）, 步骤[[标签, 方法, 路径, 查询串, 正文（null 为不带正文）]]]。 */
function scenarios($xxeFile)
{
    $W = '/wechat/index';
    $xxeUri = 'file:///' . str_replace('\\', '/', $xxeFile);
    $q = valid_query(TOKEN, 'echo-123');
    return [
        ['closed', ['weixin' => ['status' => '0']], [
            ['get', 'GET', $W, $q, null],
            ['post', 'POST', $W, '', text_msg('关键词1')]]],
        ['valid', [], [
            ['ok', 'GET', $W, $q, null],
            ['ok-short-path', 'GET', '/wechat', $q, null],
            ['ok-html', 'GET', '/wechat/index.html', $q, null],
            ['ok-tags', 'GET', $W, valid_query(TOKEN, '<b>a</b>"\'&<x y="1">z'), null],
            ['ok-empty-echostr', 'GET', $W, valid_query(TOKEN, ''), null],
            ['bad-signature', 'GET', $W, 'signature=0000&timestamp=1700000000&nonce=n0nce&echostr=e1', null],
            ['missing-params', 'GET', $W, 'echostr=e2', null],
            ['upper-signature', 'GET', $W, preg_replace_callback('/^signature=([0-9a-f]+)/', function ($m) {
                return 'signature=' . strtoupper($m[1]);
            }, $q), null],
            ['post-with-echostr', 'POST', $W, $q, text_msg('关键词1')]]],
        ['valid-empty-token', ['weixin' => ['token' => '']], [
            ['empty-token', 'GET', $W, valid_query('', 'echo-empty'), null],
            ['empty-token-no-params', 'GET', $W, 'echostr=e3', null]]],
        ['message', [], [
            ['no-body', 'GET', $W, '', null],
            ['empty-post', 'POST', $W, '', ''],
            ['keyword1', 'POST', $W, '', text_msg('我想看关键词1的内容')],
            ['keyword2', 'POST', $W, '', text_msg('关键词2')],
            ['keyword3', 'POST', $W, '', text_msg('  关键词3  ')],
            ['keyword4', 'POST', $W, '', text_msg('关键词4关键词1')],
            ['search-hit', 'POST', $W, '', text_msg('星际')],
            ['search-en', 'POST', $W, '', text_msg('interstellar')],
            ['search-sub', 'POST', $W, '', text_msg('另一个')],
            ['search-miss', 'POST', $W, '', text_msg('没有这个片')],
            ['search-empty', 'POST', $W, '', text_msg('')],
            ['search-percent', 'POST', $W, '', text_msg('星%')],
            ['subscribe', 'POST', $W, '', event_msg('subscribe')],
            ['unsubscribe', 'POST', $W, '', event_msg('unsubscribe')],
            ['click', 'POST', $W, '', event_msg('CLICK', 'menu_1')],
            ['view', 'POST', $W, '', event_msg('VIEW', 'http://www.example.test/')],
            ['link', 'POST', $W, '', msg('link', ['Title' => 't', 'Description' => 'd', 'Url' => 'http://x.example.test/'])],
            ['unknown', 'POST', $W, '', msg('shortvideo', ['MediaId' => 'm'])],
            ['image', 'POST', $W, '', msg('image', ['PicUrl' => 'http://x.example.test/a.jpg', 'MediaId' => 'm'])],
            ['voice', 'POST', $W, '', msg('voice', ['MediaId' => 'm', 'Format' => 'amr'])],
            ['location', 'POST', $W, '', msg('location', ['Location_X' => '1', 'Location_Y' => '2', 'Scale' => '3', 'Label' => 'l'])],
            ['video', 'POST', $W, '', msg('video', ['MediaId' => 'm', 'ThumbMediaId' => 't'])],
            ['no-msgtype', 'POST', $W, '', '<xml><ToUserName><![CDATA[a]]></ToUserName><FromUserName><![CDATA[b]]></FromUserName></xml>'],
            ['plain-text-node', 'POST', $W, '', '<xml><ToUserName>a&amp;b</ToUserName><FromUserName>c</FromUserName>'
                . '<MsgType>text</MsgType><Content>关键词2</Content></xml>'],
            ['cdata-split', 'POST', $W, '', '<xml><ToUserName><![CDATA[a]]]]><![CDATA[>b]]></ToUserName><FromUserName><![CDATA[c]]></FromUserName>'
                . '<MsgType><![CDATA[event]]></MsgType><Event><![CDATA[subscribe]]></Event></xml>'],
            ['malformed', 'POST', $W, '', '<xml><MsgType>text'],
            ['not-xml', 'POST', $W, '', 'hello'],
            ['internal-entity', 'POST', $W, '', '<?xml version="1.0"?><!DOCTYPE xml [<!ENTITY e "关键词2">]><xml>'
                . '<ToUserName>a</ToUserName><FromUserName>b</FromUserName><MsgType>text</MsgType><Content>&e;</Content></xml>'],
            ['xxe', 'POST', $W, '', '<?xml version="1.0"?><!DOCTYPE xml [<!ENTITY e SYSTEM "' . $xxeUri . '">]><xml>'
                . '<ToUserName>a</ToUserName><FromUserName>b</FromUserName><MsgType>text</MsgType><Content>&e;</Content></xml>'],
            ['xxe-param', 'POST', $W, '', '<?xml version="1.0"?><!DOCTYPE xml [<!ENTITY % p SYSTEM "' . $xxeUri . '"> %p;]><xml>'
                . '<ToUserName>a</ToUserName><FromUserName>b</FromUserName><MsgType>event</MsgType><Event>subscribe</Event></xml>'],
            ['form-post', 'POST', $W, '', 'a=1&b=2', 'application/x-www-form-urlencoded']]],
        ['msgtype-text', ['weixin' => ['msgtype' => '1']], [
            ['search-hit', 'POST', $W, '', text_msg('星际')],
            ['search-miss', 'POST', $W, '', text_msg('没有这个片')],
            ['keyword1', 'POST', $W, '', text_msg('关键词1')]]],
        ['bofang-play', ['weixin' => ['bofang' => '1']], [
            ['news', 'POST', $W, '', text_msg('星际')]]],
        ['bofang-play-text', ['weixin' => ['bofang' => '1', 'msgtype' => '1']], [
            ['text', 'POST', $W, '', text_msg('星际')]]],
        ['bofang-search', ['weixin' => ['bofang' => '2']], [
            ['news', 'POST', $W, '', text_msg('星际 1')],
            ['news-hit', 'POST', $W, '', text_msg('星际1')],
            ['miss', 'POST', $W, '', text_msg('没有这个片')]]],
        ['bofang-search-text', ['weixin' => ['bofang' => '2', 'msgtype' => '1']], [
            ['text', 'POST', $W, '', text_msg('星际')]]],
        ['sousuo-http', ['weixin' => ['sousuo' => 'https://wx.example.test', 'gjc1' => '', 'gjc3' => '星际']], [
            ['keyword-empty-skip', 'POST', $W, '', text_msg('关键词1')],
            ['keyword3-over-search', 'POST', $W, '', text_msg('星际')],
            ['search', 'POST', $W, '', text_msg('英文名')]]],
        ['rewrite', ['rewrite' => ['status' => '1', 'suffix_hide' => '1'], 'weixin' => ['msgtype' => '1']], [
            ['text', 'POST', $W, '', text_msg('星际')]]],
        ['subscribe-empty', ['weixin' => ['guanzhu' => '']], [
            ['subscribe', 'POST', $W, '', event_msg('subscribe')]]],
    ];
}

function generate($repo, $pdo, $phpCgi, $work, $host, $port, $db, $user, $password, $xxeFile)
{
    $base = include $repo . '/application/extra/maccms.php';
    $base = deep_merge($base, [
        'site' => ['site_name' => '示例站点', 'site_url' => 'www.example.test'],
        'app' => ['cache_flag' => 'golden', 'cache_core' => '0', 'vod_search_optimise' => ''],
        'weixin' => ['status' => '1', 'duijie' => 'wx.example.test', 'sousuo' => 'wx.example.test', 'token' => TOKEN,
            'guanzhu' => '欢迎关注', 'wuziyuan' => '没找到资源，请更换关键词或等待更新', 'wuziyuanlink' => 'demo.example.test',
            'bofang' => '0', 'msgtype' => '0',
            'gjc1' => '关键词1', 'gjcm1' => '长城', 'gjci1' => 'http://img.example.test/1.jpg', 'gjcl1' => 'http://www.example.test/1/',
            'gjc2' => '关键词2', 'gjcm2' => '名称"引号&', 'gjci2' => 'http://img.example.test/2.jpg', 'gjcl2' => 'http://www.example.test/2/?a=1&b=2',
            'gjc3' => '关键词3', 'gjcm3' => '湄公河行动', 'gjci3' => '', 'gjcl3' => 'www.example.test/3/',
            'gjc4' => '关键词4', 'gjcm4' => '王牌<b>逗</b>王牌', 'gjci4' => 'http://img.example.test/4.jpg', 'gjcl4' => 'http://www.example.test/4/'],
    ]);
    $only = getenv('GOLDEN_ONLY');
    $out = ['php' => PHP_VERSION, 'token' => TOKEN, 'xxeMarker' => XXE_MARKER, 'typesSql' => TYPES_SQL,
        'seed' => seed_vods(), 'scenarios' => []];
    $n = 0;
    foreach (scenarios($xxeFile) as $sc) {
        list($label, $over, $steps) = $sc;
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
        $before = table_rows($pdo, 'mac_vod', 'vod_id');
        $rec = ['label' => $label, 'maccmsPhp' => base64_encode(file_get_contents($conf . '/extra/maccms.php')), 'steps' => []];
        foreach ($steps as $st) {
            list($slabel, $method, $path, $query, $body) = $st;
            $type = isset($st[5]) ? $st[5] : 'text/xml';
            $started = time();
            list($status, $headers, $resp) = run_request($repo, $phpCgi, $conf, $runtime, ['entry' => 'api', 'path' => $path,
                'query' => $query, 'method' => $method, 'body' => $body, 'contentType' => $type]);
            $after = time();
            // 回复里出现本地文件的路径时换成占位符（路径随运行变化）
            $resp = str_replace(str_replace('\\', '/', $xxeFile), '{XXE_FILE}', $resp);
            $rec['steps'][] = ['label' => $slabel, 'method' => $method, 'path' => $path, 'query' => $query,
                'body' => $body === null ? null : str_replace(str_replace('\\', '/', $xxeFile), '{XXE_FILE}', $body),
                'requestContentType' => $body === null ? null : $type,
                'status' => $status, 'contentType' => isset($headers['content-type']) ? $headers['content-type'] : null,
                'response' => base64_encode($resp), 'started' => $started, 'after' => $after,
                'vodUnchanged' => table_rows($pdo, 'mac_vod', 'vod_id') === $before];
        }
        $out['scenarios'][] = $rec;
    }
    return json_encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT) . "\n";
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
 * $req: entry（api|index）、path（PATH_INFO）、query、method、body（原始正文，null 为不带）、contentType。
 */
function run_request($repo, $phpCgi, $conf, $runtime, $req)
{
    $entry = $req['entry'];
    $query = $req['query'];
    $method = $req['method'];
    $body = $req['body'] === null ? '' : $req['body'];
    // 入口文件名须与 SCRIPT_NAME 相同（ThinkPHP 的 Request::baseFile/root 据此推算站点根，与生产的 /api.php 一致）
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
        'REMOTE_ADDR' => '127.0.0.1',
        'REMOTE_PORT' => '50000',
        'DOCUMENT_ROOT' => $entryDir,
        'SystemRoot' => getenv('SystemRoot') ?: 'C:\\Windows',
        'TEMP' => $runtime,
        'TMP' => $runtime,
    ];
    if ($req['body'] !== null) {
        $env['CONTENT_TYPE'] = $req['contentType'];
        $env['CONTENT_LENGTH'] = (string)strlen($body);
    }
    $cmd = escapeshellarg($phpCgi) . ' -d session.save_path=' . escapeshellarg($runtime)
        . ' -d display_errors=0 -d error_reporting=0';
    $proc = proc_open($cmd, [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['pipe', 'w']], $pipes, $repo, $env,
        ['bypass_shell' => true]);
    if (!is_resource($proc)) {
        throw new RuntimeException('php-cgi');
    }
    fwrite($pipes[0], $body);
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
