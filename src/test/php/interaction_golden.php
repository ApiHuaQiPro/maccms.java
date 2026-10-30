<?php
/*
 * B11（MACC-35）评论、留言与内容计数写入的 PHP 黄金样本生成脚本（契约见 docs/architecture/20-b11-comment-gbook-counters.md）。
 *
 * 每个 HTTP 用例是一次真实的前台请求：经 legacy_http_child.php 跑完整的 ThinkPHP 路由、index/controller/Base 的构造函数与
 * Comment、Gbook、Ajax 控制器（或 api 入口的 Provide::vod）本身，记录状态码、Content-Type、全部 Set-Cookie 与正文字节，以及请求后
 * mac_comment、mac_gbook、mac_visit 的全部行与 6 张内容表的计数列；需要会话的用例（验证码）预先写入会话文件，请求后记录会话内容。
 * 另有 search 段：经 interaction_golden_child.php 直接调用 model('Vod')->listCacheData（旧搜索页模板里的 {maccms:vod} 标签就是这样
 * 调用的，仓库缺模板，HTTP 走不到），记录 vod_search_optimise 打开时的结果 id 与 mac_vod_search 的行。
 *
 * 流程：外部管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入 install.sql 与 B06 的合成内容 seed.sql → 每个用例先重置上述表并写入
 * 用例自己的行，再执行请求 → 输出 JSON → 删除一次性库与临时目录。
 * 不修改任何旧 PHP 文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，不访问网络。需要仓库根目录下的 vendor/。
 *
 * 管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；另需 PHP_CGI。
 * 用法（仓库根目录，PHP 8.0 与 7.3 各一份）：
 *   PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe src/test/php/interaction_golden.php \
 *       > src/test/resources/interaction/php80-golden.json
 */

error_reporting(E_ERROR | E_PARSE);
date_default_timezone_set('PRC');
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

$adminUrl = env_required('MACCMS_IT_ADMIN_URL');
$adminUser = env_required('MACCMS_IT_ADMIN_USERNAME');
$adminPassword = env_required('MACCMS_IT_ADMIN_PASSWORD');
$phpCgi = env_required('PHP_CGI');
if (!is_file($repo . '/vendor/topthink/think-captcha/src/Captcha.php')) {
    fwrite(STDERR, "缺少 vendor/topthink/think-captcha（按 AGENTS.md 从备份恢复 vendor/）\n");
    exit(2);
}
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

/** 每个用例开始前清空、请求后整表记录的表（表 => 主键）。 */
const RESET_TABLES = ['mac_comment' => 'comment_id', 'mac_gbook' => 'gbook_id', 'mac_visit' => 'visit_id',
    'mac_vod_search' => 'search_key', 'mac_user' => 'user_id'];
/** 内容表：只记录计数列（每个用例开始前把计数列重置为 0，再按用例写入）。 */
const COUNTER_TABLES = [
    'mac_vod' => ['vod', ['hits', 'hits_day', 'hits_week', 'hits_month', 'time_hits', 'up', 'down', 'score', 'score_all', 'score_num']],
    'mac_art' => ['art', ['hits', 'hits_day', 'hits_week', 'hits_month', 'time_hits', 'up', 'down', 'score', 'score_all', 'score_num']],
    'mac_topic' => ['topic', ['hits', 'hits_day', 'hits_week', 'hits_month', 'time_hits', 'up', 'down', 'score', 'score_all', 'score_num']],
    'mac_actor' => ['actor', ['hits', 'hits_day', 'hits_week', 'hits_month', 'time_hits', 'up', 'down', 'score', 'score_all', 'score_num']],
    'mac_role' => ['role', ['hits', 'hits_day', 'hits_week', 'hits_month', 'time_hits', 'up', 'down', 'score', 'score_all', 'score_num']],
    'mac_website' => ['website', ['hits', 'hits_day', 'hits_week', 'hits_month', 'time_hits', 'up', 'down', 'score', 'score_all', 'score_num',
        'referer', 'referer_day', 'referer_week', 'referer_month', 'time_referer']],
];
const USER_COLUMNS = 'user_id, user_name, user_nick_name';
const SEED_SQL = 'src/test/resources/openapi/provide/seed.sql';

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$work = sys_get_temp_dir() . '/maccms-b11-golden-' . bin2hex(random_bytes(4));
$exit = 0;
try {
    $pdo->exec("USE `$dbName`");
    foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
        $pdo->exec($sql);
    }
    foreach (sql_statements($repo . '/' . SEED_SQL) as $sql) {
        $pdo->exec($sql);
    }
    foreach (fixture_sql() as $sql) {
        $pdo->exec($sql);
    }
    @mkdir($work, 0777, true);
    echo generate($repo, $pdo, $phpCgi, $work, $host, $port, $dbName, $adminUser, $adminPassword);
} catch (\Throwable $e) {
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

/**
 * 所有用例共用的固定设定（Java 侧导入同样的语句）：第二个网址（同一域名前缀，用来看多行匹配）、子域名网址与 https 网址。
 */
function fixture_sql()
{
    return [
        "INSERT INTO mac_website (website_id, type_id, website_name, website_en, website_status, website_jumpurl, website_time,"
        . " website_time_add, website_content) VALUES (2, 41, '同域名网址', 'tongyuming', 1, 'http://site.example.test/other/', 1700001700, 1690001700, '')",
        "INSERT INTO mac_website (website_id, type_id, website_name, website_en, website_status, website_jumpurl, website_time,"
        . " website_time_add, website_content) VALUES (3, 41, '安全网址', 'anquan', 1, 'https://secure.example.test/', 1700001800, 1690001800, '')",
        "INSERT INTO mac_website (website_id, type_id, website_name, website_en, website_status, website_jumpurl, website_time,"
        . " website_time_add, website_content) VALUES (4, 41, '下划线网址', 'xiahuaxian', 1, 'http://a_b.example.test/', 1700001900, 1690001900, '')",
    ];
}

/** 每个用例都带上的配置（仓库值之上）。 */
function base_config()
{
    return [
        'site' => ['site_name' => '示例站点', 'site_url' => 'www.example.test', 'site_status' => '1'],
        'app' => ['cache_flag' => 'golden', 'cache_core' => '0', 'cache_page' => '0', 'compress' => '0',
            'filter_words' => 'www,http,com,net', 'search' => '1', 'search_timespan' => '0', 'search_len' => '64',
            'vod_search_optimise' => '', 'show' => '1', 'show_verify' => '0', 'search_verify' => '0'],
        'user' => ['status' => '1'],
        'comment' => ['status' => '1', 'audit' => '0', 'login' => '0', 'verify' => '0', 'pagesize' => '20', 'timespan' => '3'],
        'gbook' => ['status' => '1', 'audit' => '0', 'login' => '0', 'verify' => '0', 'pagesize' => '20', 'timespan' => '3'],
    ];
}

function default_users()
{
    $u = function ($id, $name, $extra = []) {
        return array_merge(['user_id' => $id, 'group_id' => 2, 'user_name' => $name, 'user_pwd' => md5('secret'),
            'user_status' => 1, 'user_random' => 'rnd' . $id, 'user_points' => 0, 'user_end_time' => 0,
            'user_reg_time' => 1700000000, 'user_nick_name' => '', 'user_question' => '', 'user_answer' => ''], $extra);
    };
    return [
        7 => $u(7, 'member007', ['user_nick_name' => '小七<b>&']),
        8 => $u(8, 'member008'),
    ];
}

function user_cookie($user)
{
    $check = md5($user['user_random'] . '-' . $user['user_name'] . '-' . $user['user_id'] . '-');
    return 'user_id=' . $user['user_id'] . '; user_name=' . rawurlencode($user['user_name']) . '; user_check=' . $check;
}

/**
 * 用例：[id, 入口路径, 方法, 参数, 选项]。
 * 选项：as（以哪个会员登录，默认 null 游客）、config（配置覆盖）、users（会员行覆盖）、rows（初始行：表名 => 行列表）、
 * counters（内容表计数：表名 => [id => [列 => 值]]，列名不带前缀）、ajax（默认 true）、headers、cookie（额外 Cookie）、
 * remote（客户端 IP）、captcha（会话里预置的验证码；值为码）、session（会话里 think 前缀下预置的键 => 值）、entry（默认 index）。
 * 时间值可写成相对形式："now-N"、"today-N"、"weekstart(+|-)N"、"weekend(+|-)N"、"monthstart(+|-)N"、"yearstart(+|-)N"，
 * 按旧 Ajax::hits 的算法（getdate/mktime，PRC）在请求前一刻求值，求得的语句记在用例的 setup 里。
 */
function cases()
{
    $c = [];
    $add = function ($id, $path, $method, $params, $opt = []) use (&$c) {
        $c[] = [$id, $path, $method, $params, $opt];
    };
    $member = ['as' => 7];

    // ---------------------------------------------------------------- 评论发表（Comment.php:37-117，common/model/Comment.php:212-244）
    $cs = '/comment/saveData';
    $base = ['comment_content' => '好看', 'comment_mid' => '1', 'comment_rid' => '1'];
    $add('cs.guest', $cs, 'POST', $base);
    $add('cs.member-nick', $cs, 'POST', $base, $member);
    $add('cs.member-no-nick', $cs, 'POST', $base, ['as' => 8]);
    $add('cs.via-index-post', '/comment/index', 'POST', $base);
    $add('cs.get', $cs, 'GET', $base);
    $add('cs.non-ajax', $cs, 'POST', $base, ['ajax' => false]);
    $add('cs.forged-cookie', $cs, 'POST', $base, ['cookie' => 'user_id=8; user_name=forged']);
    $add('cs.forged-id-only', $cs, 'POST', $base, ['cookie' => 'user_id=7']);
    $add('cs.content-empty', $cs, 'POST', ['comment_content' => ''] + $base);
    $add('cs.content-zero', $cs, 'POST', ['comment_content' => '0'] + $base);
    $add('cs.content-missing', $cs, 'POST', ['comment_mid' => '1', 'comment_rid' => '1']);
    $add('cs.content-space', $cs, 'POST', ['comment_content' => '   '] + $base);
    $add('cs.timespan-cookie', $cs, 'POST', $base, ['cookie' => 'comment_timespan=t']);
    $add('cs.timespan-cookie-zero', $cs, 'POST', $base, ['cookie' => 'comment_timespan=0']);
    $add('cs.mid-missing', $cs, 'POST', ['comment_content' => '好看', 'comment_rid' => '1']);
    foreach (['1', '2', '3', '8', '9', '11'] as $mid) {
        $add('cs.mid' . $mid, $cs, 'POST', ['comment_mid' => $mid] + $base);
    }
    foreach (['4', '5', '01', '1.0', ' 1', 'abc'] as $mid) {
        $add('cs.mid-bad-' . bin2hex($mid), $cs, 'POST', ['comment_mid' => $mid] + $base);
    }
    $add('cs.rid-text', $cs, 'POST', ['comment_rid' => '7abc'] + $base);
    $add('cs.rid-missing', $cs, 'POST', ['comment_content' => '好看', 'comment_mid' => '1']);
    $add('cs.rid-negative', $cs, 'POST', ['comment_rid' => '-5'] + $base);
    $add('cs.rid-nonexistent', $cs, 'POST', ['comment_rid' => '999'] + $base);
    $add('cs.pid', $cs, 'POST', ['comment_pid' => '3'] + $base);
    $add('cs.pid-text', $cs, 'POST', ['comment_pid' => 'x9'] + $base);
    $add('cs.filter-words', $cs, 'POST', ['comment_content' => '去www.a.com看http'] + $base);
    $add('cs.filter-words-off', $cs, 'POST', ['comment_content' => '去www.a.com看'] + $base,
        ['config' => ['app' => ['filter_words' => '']]]);
    $add('cs.filter-words-case', $cs, 'POST', ['comment_content' => 'WWW,Com'] + $base);
    $add('cs.html', $cs, 'POST', ['comment_content' => '<b>粗</b> & "引" \'单\' <script>x</script>'] + $base);
    $add('cs.entity', $cs, 'POST', ['comment_content' => '&amp; &lt;b&gt; &#39; é'] + $base);
    $add('cs.trim', $cs, 'POST', ['comment_content' => "  首尾空白\t\n "] + $base);
    $add('cs.long-ok', $cs, 'POST', ['comment_content' => str_repeat('好', 255)] + $base);
    $add('cs.long-over', $cs, 'POST', ['comment_content' => str_repeat('好', 256)] + $base);
    $add('cs.long-entity-over', $cs, 'POST', ['comment_content' => str_repeat('&', 60)] + $base);
    $add('cs.emoji', $cs, 'POST', ['comment_content' => "好\xF0\x9F\x98\x80"] + $base);
    $add('cs.invalid-utf8', $cs, 'POST', ['comment_content' => "好\xC3\x28"] + $base);
    $add('cs.mass-assign', $cs, 'POST', $base + ['comment_id' => '5', 'comment_status' => '0', 'comment_up' => '9',
            'comment_down' => '9', 'comment_reply' => '7', 'comment_report' => '8', 'comment_time' => '1',
            'comment_ip' => '1', 'comment_name' => '冒名', 'user_id' => '8'], $member);
    $add('cs.mass-assign-guest', $cs, 'POST', $base + ['user_id' => '8', 'comment_name' => '冒名'], []);
    $add('cs.existing-id', $cs, 'POST', $base + ['comment_id' => '1'], ['rows' => ['mac_comment' => [
        ['comment_id' => 1, 'comment_mid' => 1, 'comment_rid' => 1, 'comment_name' => '原作者', 'comment_content' => '原文',
            'comment_time' => 1700000000]]]]);
    $add('cs.audit', $cs, 'POST', $base, ['config' => ['comment' => ['audit' => '1']]]);
    $add('cs.login-guest', $cs, 'POST', $base, ['config' => ['comment' => ['login' => '1']]]);
    $add('cs.login-member', $cs, 'POST', $base, ['as' => 7, 'config' => ['comment' => ['login' => '1']]]);
    $add('cs.login-forged', $cs, 'POST', $base, ['cookie' => 'user_id=8; user_name=member008; user_check=bad',
        'config' => ['comment' => ['login' => '1']]]);
    $add('cs.verify-missing', $cs, 'POST', $base, ['config' => ['comment' => ['verify' => '1']]]);
    $add('cs.verify-wrong', $cs, 'POST', $base + ['verify' => '9999'], ['captcha' => '1234',
        'config' => ['comment' => ['verify' => '1']]]);
    $add('cs.verify-ok', $cs, 'POST', $base + ['verify' => '1234'], ['captcha' => '1234',
        'config' => ['comment' => ['verify' => '1']]]);
    $add('cs.verify-before-login', $cs, 'POST', $base, ['config' => ['comment' => ['verify' => '1', 'login' => '1']]]);
    $add('cs.closed', $cs, 'POST', $base, ['config' => ['comment' => ['status' => '0']]]);
    $add('cs.timespan0', $cs, 'POST', $base, ['config' => ['comment' => ['timespan' => '0']]]);
    $add('cs.timespan-big', $cs, 'POST', $base, ['config' => ['comment' => ['timespan' => '86400']]]);
    $add('cs.ip', $cs, 'POST', $base, ['remote' => '10.1.2.3']);
    $add('cs.ip-forwarded', $cs, 'POST', $base, ['remote' => '10.1.2.3', 'headers' => ['X-Forwarded-For' => '1.2.3.4',
        'Client-IP' => '5.6.7.8']]);
    $add('cs.ipv6', $cs, 'POST', $base, ['remote' => '::1']);
    $add('cs.array-content', $cs, 'POST', ['comment_content' => ['a'], 'comment_mid' => '1', 'comment_rid' => '1']);
    $add('cs.nick-trim', $cs, 'POST', $base, ['as' => 8, 'users' => [8 => ['user_nick_name' => '  空白昵称  ']]]);
    $add('cs.nick-long', $cs, 'POST', $base, ['as' => 8, 'users' => [8 => ['user_nick_name' => str_repeat('&', 20)]]]);

    // ---------------------------------------------------------------- 评论举报（Comment.php:119-138）
    $crow = ['mac_comment' => [
        ['comment_id' => 1, 'comment_mid' => 1, 'comment_rid' => 1, 'comment_name' => '甲', 'comment_content' => '一',
            'comment_time' => 1700000001, 'comment_up' => 3, 'comment_down' => 4, 'comment_report' => 5],
        ['comment_id' => 2, 'comment_mid' => 2, 'comment_rid' => 1, 'comment_name' => '乙', 'comment_content' => '二',
            'comment_time' => 1700000002, 'comment_status' => 0],
    ]];
    $cr = '/comment/report';
    $add('cr.ok', $cr, 'GET', ['id' => '1'], ['rows' => $crow]);
    $add('cr.html-suffix', $cr . '.html', 'GET', ['id' => '1'], ['rows' => $crow]);
    $add('cr.path-param', $cr . '/id/1.html', 'GET', [], ['rows' => $crow]);
    $add('cr.unpublished', $cr, 'GET', ['id' => '2'], ['rows' => $crow]);
    $add('cr.missing-row', $cr, 'GET', ['id' => '99'], ['rows' => $crow]);
    $add('cr.no-id', $cr, 'GET', [], ['rows' => $crow]);
    $add('cr.id0', $cr, 'GET', ['id' => '0'], ['rows' => $crow]);
    $add('cr.id-text', $cr, 'GET', ['id' => '1abc'], ['rows' => $crow]);
    $add('cr.id-negative', $cr, 'GET', ['id' => '-1'], ['rows' => $crow]);
    $add('cr.cookie', $cr, 'GET', ['id' => '1'], ['rows' => $crow, 'cookie' => 'comment-report-1=t']);
    $add('cr.cookie-other', $cr, 'GET', ['id' => '1'], ['rows' => $crow, 'cookie' => 'comment-report-2=t']);
    $add('cr.post', $cr, 'POST', ['id' => '1'], ['rows' => $crow]);
    $add('cr.closed', $cr, 'GET', ['id' => '1'], ['rows' => $crow, 'config' => ['comment' => ['status' => '0']]]);
    $add('cr.timespan0', $cr, 'GET', ['id' => '1'], ['rows' => $crow, 'config' => ['comment' => ['timespan' => '0']]]);
    $add('cr.max', $cr, 'GET', ['id' => '1'], ['rows' => ['mac_comment' => [
        ['comment_id' => 1, 'comment_mid' => 1, 'comment_rid' => 1, 'comment_name' => '甲', 'comment_content' => '一',
            'comment_time' => 1700000001, 'comment_report' => 16777215]]]]);

    // ---------------------------------------------------------------- 评论顶踩（Comment.php:140-182）
    $cd = '/comment/digg';
    $add('cd.up', $cd, 'GET', ['id' => '1', 'type' => 'up'], ['rows' => $crow]);
    $add('cd.down', $cd, 'GET', ['id' => '1', 'type' => 'down'], ['rows' => $crow]);
    $add('cd.other-type', $cd, 'GET', ['id' => '1', 'type' => 'x'], ['rows' => $crow]);
    $add('cd.type-UP', $cd, 'GET', ['id' => '1', 'type' => 'UP'], ['rows' => $crow]);
    $add('cd.type-zero', $cd, 'GET', ['id' => '1', 'type' => '0'], ['rows' => $crow]);
    $add('cd.no-type', $cd, 'GET', ['id' => '1'], ['rows' => $crow]);
    $add('cd.no-id', $cd, 'GET', ['type' => 'up'], ['rows' => $crow]);
    $add('cd.missing-row', $cd, 'GET', ['id' => '99', 'type' => 'up'], ['rows' => $crow]);
    $add('cd.unpublished', $cd, 'GET', ['id' => '2', 'type' => 'up'], ['rows' => $crow]);
    $add('cd.cookie', $cd, 'GET', ['id' => '1', 'type' => 'up'], ['rows' => $crow, 'cookie' => 'comment-digg-1=t']);
    $add('cd.id-text', $cd, 'GET', ['id' => '1x', 'type' => 'down'], ['rows' => $crow]);
    $add('cd.max', $cd, 'GET', ['id' => '1', 'type' => 'up'], ['rows' => ['mac_comment' => [
        ['comment_id' => 1, 'comment_mid' => 1, 'comment_rid' => 1, 'comment_name' => '甲', 'comment_content' => '一',
            'comment_time' => 1700000001, 'comment_up' => 16777215]]]]);
    $add('cd.timespan-config', $cd, 'GET', ['id' => '1', 'type' => 'up'], ['rows' => $crow,
        'config' => ['comment' => ['timespan' => '60']]]);

    // ---------------------------------------------------------------- 留言发表（Gbook.php:43-117，common/model/Gbook.php:212-240）
    $gs = '/gbook/saveData';
    $gbase = ['gbook_content' => '建议增加片源'];
    $add('gs.guest', $gs, 'POST', $gbase);
    $add('gs.member-nick', $gs, 'POST', $gbase, $member);
    $add('gs.member-no-nick', $gs, 'POST', $gbase, ['as' => 8]);
    $add('gs.via-index-post', '/gbook/index', 'POST', $gbase);
    $add('gs.index-post-page', '/gbook/index', 'POST', ['page' => '1']);
    $add('gs.get', $gs, 'GET', $gbase);
    $add('gs.non-ajax', $gs, 'POST', $gbase, ['ajax' => false]);
    $add('gs.forged-cookie', $gs, 'POST', $gbase, ['cookie' => 'user_id=8; user_name=forged']);
    $add('gs.content-empty', $gs, 'POST', ['gbook_content' => '']);
    $add('gs.content-zero', $gs, 'POST', ['gbook_content' => '0']);
    $add('gs.content-space', $gs, 'POST', ['gbook_content' => ' ']);
    $add('gs.timespan-cookie', $gs, 'POST', $gbase, ['cookie' => 'gbook_timespan=t']);
    $add('gs.rid', $gs, 'POST', $gbase + ['gbook_rid' => '3']);
    $add('gs.rid-text', $gs, 'POST', $gbase + ['gbook_rid' => '3abc']);
    $add('gs.rid-negative', $gs, 'POST', $gbase + ['gbook_rid' => '-3']);
    $add('gs.filter-words', $gs, 'POST', ['gbook_content' => '去www.a.com看http']);
    $add('gs.html', $gs, 'POST', ['gbook_content' => '<b>粗</b> & "引" \'单\' <script>x</script>']);
    $add('gs.long-over', $gs, 'POST', ['gbook_content' => str_repeat('好', 256)]);
    $add('gs.emoji', $gs, 'POST', ['gbook_content' => "好\xF0\x9F\x98\x80"]);
    $add('gs.mass-assign', $gs, 'POST', $gbase + ['gbook_id' => '5', 'gbook_status' => '0', 'gbook_reply' => '已回',
            'gbook_reply_time' => '9', 'gbook_time' => '1', 'gbook_ip' => '1', 'gbook_name' => '冒名', 'user_id' => '8'],
        $member);
    $add('gs.mass-assign-guest', $gs, 'POST', $gbase + ['user_id' => '8', 'gbook_reply_time' => '9']);
    $add('gs.audit', $gs, 'POST', $gbase, ['config' => ['gbook' => ['audit' => '1']]]);
    $add('gs.login-guest', $gs, 'POST', $gbase, ['config' => ['gbook' => ['login' => '1']]]);
    $add('gs.login-member', $gs, 'POST', $gbase, ['as' => 7, 'config' => ['gbook' => ['login' => '1']]]);
    $add('gs.verify-missing', $gs, 'POST', $gbase, ['config' => ['gbook' => ['verify' => '1']]]);
    $add('gs.verify-ok', $gs, 'POST', $gbase + ['verify' => '1234'], ['captcha' => '1234',
        'config' => ['gbook' => ['verify' => '1']]]);
    $add('gs.closed', $gs, 'POST', $gbase, ['config' => ['gbook' => ['status' => '0']]]);
    $add('gs.ip', $gs, 'POST', $gbase, ['remote' => '10.1.2.3']);

    // ---------------------------------------------------------------- ajax/hits（Ajax.php:152-217）
    $h = '/ajax/hits';
    $tables = ['1' => 'mac_vod', '2' => 'mac_art', '3' => 'mac_topic', '8' => 'mac_actor', '9' => 'mac_role',
        '11' => 'mac_website'];
    $cnt = function ($table, $id, $vals) {
        return ['counters' => [$table => [$id => $vals]]];
    };
    $sameDay = ['hits' => 10, 'hits_day' => 3, 'hits_week' => 5, 'hits_month' => 7, 'time_hits' => 'now-60'];
    foreach ($tables as $mid => $t) {
        $add('hits.read.mid' . $mid, $h, 'GET', ['mid' => $mid, 'id' => '1'], $cnt($t, 1, $sameDay));
        $add('hits.update.mid' . $mid, $h, 'GET', ['mid' => $mid, 'id' => '1', 'type' => 'update'], $cnt($t, 1, $sameDay));
    }
    $add('hits.update.zero', $h, 'GET', ['mid' => '1', 'id' => '1', 'type' => 'update']);
    $add('hits.update.prev-day-same-week', $h, 'GET', ['mid' => '1', 'id' => '1', 'type' => 'update'],
        $cnt('mac_vod', 1, ['hits' => 10, 'hits_day' => 3, 'hits_week' => 5, 'hits_month' => 7, 'time_hits' => 'today-1']));
    $add('hits.update.today-start', $h, 'GET', ['mid' => '1', 'id' => '1', 'type' => 'update'],
        $cnt('mac_vod', 1, ['hits' => 10, 'hits_day' => 3, 'hits_week' => 5, 'hits_month' => 7, 'time_hits' => 'today-0']));
    $add('hits.update.week-start', $h, 'GET', ['mid' => '1', 'id' => '1', 'type' => 'update'],
        $cnt('mac_vod', 1, ['hits' => 10, 'hits_day' => 3, 'hits_week' => 5, 'hits_month' => 7, 'time_hits' => 'weekstart+0']));
    $add('hits.update.before-week', $h, 'GET', ['mid' => '1', 'id' => '1', 'type' => 'update'],
        $cnt('mac_vod', 1, ['hits' => 10, 'hits_day' => 3, 'hits_week' => 5, 'hits_month' => 7, 'time_hits' => 'weekstart-1']));
    $add('hits.update.week-end', $h, 'GET', ['mid' => '1', 'id' => '1', 'type' => 'update'],
        $cnt('mac_vod', 1, ['hits' => 10, 'hits_day' => 3, 'hits_week' => 5, 'hits_month' => 7, 'time_hits' => 'weekend+0']));
    $add('hits.update.after-week', $h, 'GET', ['mid' => '1', 'id' => '1', 'type' => 'update'],
        $cnt('mac_vod', 1, ['hits' => 10, 'hits_day' => 3, 'hits_week' => 5, 'hits_month' => 7, 'time_hits' => 'weekend+1']));
    $add('hits.update.month-start', $h, 'GET', ['mid' => '1', 'id' => '1', 'type' => 'update'],
        $cnt('mac_vod', 1, ['hits' => 10, 'hits_day' => 3, 'hits_week' => 5, 'hits_month' => 7, 'time_hits' => 'monthstart+0']));
    $add('hits.update.before-month', $h, 'GET', ['mid' => '1', 'id' => '1', 'type' => 'update'],
        $cnt('mac_vod', 1, ['hits' => 10, 'hits_day' => 3, 'hits_week' => 5, 'hits_month' => 7, 'time_hits' => 'monthstart-1']));
    $add('hits.update.last-year', $h, 'GET', ['mid' => '1', 'id' => '1', 'type' => 'update'],
        $cnt('mac_vod', 1, ['hits' => 10, 'hits_day' => 3, 'hits_week' => 5, 'hits_month' => 7, 'time_hits' => 'yearstart-1']));
    $add('hits.update.future', $h, 'GET', ['mid' => '1', 'id' => '1', 'type' => 'update'],
        $cnt('mac_vod', 1, ['hits' => 10, 'hits_day' => 3, 'hits_week' => 5, 'hits_month' => 7, 'time_hits' => 'now+400000']));
    $add('hits.update.max', $h, 'GET', ['mid' => '1', 'id' => '1', 'type' => 'update'],
        $cnt('mac_vod', 1, ['hits' => 16777215, 'hits_day' => 3, 'hits_week' => 5, 'hits_month' => 7, 'time_hits' => 'now-60']));
    $add('hits.update.unpublished', $h, 'GET', ['mid' => '1', 'id' => '5', 'type' => 'update']);
    $add('hits.update.type-UPDATE', $h, 'GET', ['mid' => '1', 'id' => '1', 'type' => 'UPDATE'], $cnt('mac_vod', 1, $sameDay));
    $add('hits.missing-row', $h, 'GET', ['mid' => '1', 'id' => '999', 'type' => 'update']);
    $add('hits.no-id', $h, 'GET', ['mid' => '1', 'type' => 'update']);
    $add('hits.id0', $h, 'GET', ['mid' => '1', 'id' => '0', 'type' => 'update']);
    $add('hits.id-text', $h, 'GET', ['mid' => '1', 'id' => 'abc', 'type' => 'update']);
    $add('hits.id-prefix', $h, 'GET', ['mid' => '1', 'id' => '1abc', 'type' => 'update'], $cnt('mac_vod', 1, $sameDay));
    $add('hits.id-float', $h, 'GET', ['mid' => '1', 'id' => '1.0', 'type' => 'update'], $cnt('mac_vod', 1, $sameDay));
    $add('hits.id-negative', $h, 'GET', ['mid' => '1', 'id' => '-1', 'type' => 'update']);
    $add('hits.mid-missing', $h, 'GET', ['id' => '1', 'type' => 'update']);
    $add('hits.mid4', $h, 'GET', ['mid' => '4', 'id' => '1', 'type' => 'update']);
    $add('hits.mid01', $h, 'GET', ['mid' => '01', 'id' => '1', 'type' => 'update'], $cnt('mac_vod', 1, $sameDay));
    $add('hits.post', $h, 'POST', ['mid' => '1', 'id' => '1', 'type' => 'update'], $cnt('mac_vod', 1, $sameDay));
    $add('hits.path', $h . '/mid/1/id/1/type/update.html', 'GET', [], $cnt('mac_vod', 1, $sameDay));
    $add('hits.non-ajax', $h, 'GET', ['mid' => '1', 'id' => '1', 'type' => 'update'], $cnt('mac_vod', 1, $sameDay)
        + ['ajax' => false]);

    // ---------------------------------------------------------------- ajax/digg（Ajax.php:302-345）
    $d = '/ajax/digg';
    $diggRows = $crow;
    foreach ($tables + ['4' => 'mac_comment'] as $mid => $t) {
        $opt = $t === 'mac_comment' ? ['rows' => $diggRows] : $cnt($t, 1, ['up' => 3, 'down' => 4]);
        $add('digg.up.mid' . $mid, $d, 'GET', ['mid' => $mid, 'id' => '1', 'type' => 'up'], $opt);
        $add('digg.down.mid' . $mid, $d, 'GET', ['mid' => $mid, 'id' => '1', 'type' => 'down'], $opt);
        $add('digg.read.mid' . $mid, $d, 'GET', ['mid' => $mid, 'id' => '1'], $opt);
    }
    $up34 = $cnt('mac_vod', 1, ['up' => 3, 'down' => 4]);
    $add('digg.other-type', $d, 'GET', ['mid' => '1', 'id' => '1', 'type' => 'x'], $up34);
    $add('digg.type-zero', $d, 'GET', ['mid' => '1', 'id' => '1', 'type' => '0'], $up34);
    $add('digg.cookie', $d, 'GET', ['mid' => '1', 'id' => '1', 'type' => 'up'], $up34 + ['cookie' => 'vod-digg-1=t']);
    $add('digg.cookie-read', $d, 'GET', ['mid' => '1', 'id' => '1'], $up34 + ['cookie' => 'vod-digg-1=t']);
    $add('digg.cookie-other-type', $d, 'GET', ['mid' => '1', 'id' => '1', 'type' => 'x'], $up34
        + ['cookie' => 'vod-digg-1=t']);
    $add('digg.missing-row', $d, 'GET', ['mid' => '1', 'id' => '999', 'type' => 'up']);
    $add('digg.missing-row-read', $d, 'GET', ['mid' => '2', 'id' => '999']);
    $add('digg.unpublished', $d, 'GET', ['mid' => '1', 'id' => '5', 'type' => 'up']);
    $add('digg.no-id', $d, 'GET', ['mid' => '1', 'type' => 'up']);
    $add('digg.mid5', $d, 'GET', ['mid' => '5', 'id' => '1', 'type' => 'up']);
    $add('digg.mid10', $d, 'GET', ['mid' => '10', 'id' => '1', 'type' => 'up']);
    $add('digg.id-text', $d, 'GET', ['mid' => '1', 'id' => '1abc', 'type' => 'up'], $up34);
    $add('digg.max', $d, 'GET', ['mid' => '1', 'id' => '1', 'type' => 'up'], $cnt('mac_vod', 1, ['up' => 16777215]));

    // ---------------------------------------------------------------- ajax/score（Ajax.php:347-398）
    $s = '/ajax/score';
    $sc = function ($t, $score, $all, $num) use ($cnt) {
        return $cnt($t, 1, ['score' => $score, 'score_all' => $all, 'score_num' => $num]);
    };
    foreach ($tables as $mid => $t) {
        $add('score.set.mid' . $mid, $s, 'GET', ['mid' => $mid, 'id' => '1', 'score' => '8'], $sc($t, '7.0', 14, 2));
        $add('score.read.mid' . $mid, $s, 'GET', ['mid' => $mid, 'id' => '1'], $sc($t, '7.0', 14, 2));
    }
    $add('score.first', $s, 'GET', ['mid' => '1', 'id' => '1', 'score' => '9']);
    foreach (['1', '10', '7', '3'] as $v) {
        $add('score.round-' . $v, $s, 'GET', ['mid' => '1', 'id' => '1', 'score' => $v], $sc('mac_vod', '3.3', 10, 3));
    }
    $add('score.round-half-up', $s, 'GET', ['mid' => '1', 'id' => '1', 'score' => '1'], $sc('mac_vod', '0.0', 0, 19));
    $add('score.round-half-up-2', $s, 'GET', ['mid' => '1', 'id' => '1', 'score' => '2'], $sc('mac_vod', '0.0', 0, 39));
    $add('score.round-quarter', $s, 'GET', ['mid' => '1', 'id' => '1', 'score' => '1'], $sc('mac_vod', '0.0', 0, 7));
    $add('score.zero', $s, 'GET', ['mid' => '1', 'id' => '1', 'score' => '0'], $sc('mac_vod', '7.0', 14, 2));
    $add('score.empty', $s, 'GET', ['mid' => '1', 'id' => '1', 'score' => ''], $sc('mac_vod', '7.0', 14, 2));
    $add('score.float', $s, 'GET', ['mid' => '1', 'id' => '1', 'score' => '7.5'], $sc('mac_vod', '7.0', 14, 2));
    $add('score.text', $s, 'GET', ['mid' => '1', 'id' => '1', 'score' => 'abc'], $sc('mac_vod', '7.0', 14, 2));
    $add('score.prefix', $s, 'GET', ['mid' => '1', 'id' => '1', 'score' => '8abc'], $sc('mac_vod', '7.0', 14, 2));
    $add('score.negative', $s, 'GET', ['mid' => '1', 'id' => '1', 'score' => '-3'], $sc('mac_vod', '7.0', 14, 2));
    $add('score.negative-big', $s, 'GET', ['mid' => '1', 'id' => '1', 'score' => '-30'], $sc('mac_vod', '7.0', 14, 2));
    $add('score.big', $s, 'GET', ['mid' => '1', 'id' => '1', 'score' => '1000'], $sc('mac_vod', '7.0', 14, 2));
    $add('score.over-decimal', $s, 'GET', ['mid' => '1', 'id' => '1', 'score' => '200'], $sc('mac_vod', '7.0', 14, 2));
    $add('score.hundred', $s, 'GET', ['mid' => '1', 'id' => '1', 'score' => '100'], $sc('mac_vod', '0.0', 0, 0));
    $add('score.max-all', $s, 'GET', ['mid' => '1', 'id' => '1', 'score' => '10'], $sc('mac_vod', '9.0', 16777210, 1864135));
    $add('score.exp', $s, 'GET', ['mid' => '1', 'id' => '1', 'score' => '1e1'], $sc('mac_vod', '7.0', 14, 2));
    $add('score.space', $s, 'GET', ['mid' => '1', 'id' => '1', 'score' => ' 8'], $sc('mac_vod', '7.0', 14, 2));
    $add('score.cookie', $s, 'GET', ['mid' => '1', 'id' => '1', 'score' => '8'], $sc('mac_vod', '7.0', 14, 2)
        + ['cookie' => 'vod-score-1=t']);
    $add('score.cookie-read', $s, 'GET', ['mid' => '1', 'id' => '1'], $sc('mac_vod', '7.0', 14, 2)
        + ['cookie' => 'vod-score-1=t']);
    $add('score.missing-row', $s, 'GET', ['mid' => '1', 'id' => '999', 'score' => '8']);
    $add('score.unpublished', $s, 'GET', ['mid' => '1', 'id' => '5', 'score' => '8']);
    $add('score.no-id', $s, 'GET', ['mid' => '1', 'score' => '8']);
    $add('score.mid4', $s, 'GET', ['mid' => '4', 'id' => '1', 'score' => '8']);
    $add('score.post', $s, 'POST', ['mid' => '1', 'id' => '1', 'score' => '8'], $sc('mac_vod', '7.0', 14, 2));
    $add('score.post-query', $s . '?mid=1&id=1', 'POST', [], $sc('mac_vod', '7.0', 14, 2));
    $add('score.id-text', $s, 'GET', ['mid' => '1', 'id' => '1abc', 'score' => '8'], $sc('mac_vod', '7.0', 14, 2));

    // ---------------------------------------------------------------- ajax/referer（Ajax.php:219-300，common/model/Website.php:613-642）
    $r = '/ajax/referer';
    $ref = ['headers' => ['Referer' => 'http://www.example.test/index.php/website/detail/id/1.html']];
    $refCnt = ['counters' => ['mac_website' => [1 => ['referer' => 10, 'referer_day' => 3, 'referer_week' => 5,
        'referer_month' => 7, 'time_referer' => 'now-60'], 2 => ['referer' => 20, 'referer_day' => 1, 'referer_week' => 1,
        'referer_month' => 1, 'time_referer' => 'today-1']]]];
    $rp = ['url' => 'http://site.example.test/page', 'domain' => 'site.example.test', 'type' => 'update'];
    $add('ref.update', $r, 'GET', $rp, $ref + $refCnt);
    $add('ref.read', $r, 'GET', ['type' => ''] + $rp, $ref + $refCnt);
    $add('ref.https', $r, 'GET', ['url' => 'https://secure.example.test/x', 'domain' => 'secure.example.test',
        'type' => 'update'], $ref);
    $add('ref.no-url', $r, 'GET', ['domain' => 'site.example.test', 'type' => 'update'], $ref);
    $add('ref.no-referer', $r, 'GET', $rp, []);
    $add('ref.foreign-referer', $r, 'GET', $rp, ['headers' => ['Referer' => 'http://evil.test/']]);
    $add('ref.referer-contains-host', $r, 'GET', $rp, ['headers' => ['Referer' => 'http://evil.test/?www.example.test']]
        + $refCnt);
    $add('ref.domain-not-in-url', $r, 'GET', ['url' => 'http://other.test/', 'domain' => 'site.example.test',
        'type' => 'update'], $ref);
    $add('ref.no-domain', $r, 'GET', ['url' => 'http://site.example.test/', 'type' => 'update'], $ref + $refCnt);
    $add('ref.unknown-site', $r, 'GET', ['url' => 'http://none.example.test/', 'domain' => 'none.example.test',
        'type' => 'update'], $ref);
    $add('ref.wildcard-percent', $r, 'GET', ['url' => 'http://x%/', 'domain' => '%', 'type' => 'update'], $ref + $refCnt);
    $add('ref.wildcard-underscore', $r, 'GET', ['url' => 'http://aXb.example.test/', 'domain' => 'a_b.example.test',
        'type' => 'update'], $ref);
    $add('ref.underscore-literal', $r, 'GET', ['url' => 'http://a_b.example.test/', 'domain' => 'a_b.example.test',
        'type' => 'update'], $ref);
    $add('ref.visit-limit', $r, 'GET', $rp, $ref + $refCnt + ['rows' => ['mac_visit' => [
        ['visit_id' => 1, 'user_id' => 0, 'visit_ip' => 2130706433, 'visit_ly' => 'x', 'visit_time' => 'now-10']]]]);
    $add('ref.visit-other-ip', $r, 'GET', $rp, $ref + $refCnt + ['remote' => '10.1.2.3', 'rows' => ['mac_visit' => [
        ['visit_id' => 1, 'user_id' => 0, 'visit_ip' => 2130706433, 'visit_ly' => 'x', 'visit_time' => 'now-10']]]]);
    $add('ref.visit-yesterday', $r, 'GET', $rp, $ref + $refCnt + ['rows' => ['mac_visit' => [
        ['visit_id' => 1, 'user_id' => 0, 'visit_ip' => 2130706433, 'visit_ly' => 'x', 'visit_time' => 'today-1']]]]);
    $add('ref.visit-member-row', $r, 'GET', $rp, $ref + $refCnt + ['rows' => ['mac_visit' => [
        ['visit_id' => 1, 'user_id' => 7, 'visit_ip' => 2130706433, 'visit_ly' => 'x', 'visit_time' => 'now-10']]]]);
    $add('ref.visit-limit-config', $r, 'GET', $rp, $ref + $refCnt + ['config' => ['website' => ['refer_visit_num' => '2']],
        'rows' => ['mac_visit' => [['visit_id' => 1, 'user_id' => 0, 'visit_ip' => 2130706433, 'visit_ly' => 'x',
            'visit_time' => 'now-10']]]]);
    $add('ref.url-escape', $r, 'GET', ['url' => 'http://site.example.test/?a=1&b=<x>"', 'domain' => 'site.example.test',
        'type' => 'update'], $ref);
    $add('ref.url-long', $r, 'GET', ['url' => 'http://site.example.test/' . str_repeat('a', 200),
        'domain' => 'site.example.test', 'type' => 'update'], $ref);
    $add('ref.prev-week', $r, 'GET', $rp, $ref + ['counters' => ['mac_website' => [1 => ['referer' => 10,
        'referer_day' => 3, 'referer_week' => 5, 'referer_month' => 7, 'time_referer' => 'weekstart-1']]]]);
    $add('ref.prev-month', $r, 'GET', $rp, $ref + ['counters' => ['mac_website' => [1 => ['referer' => 10,
        'referer_day' => 3, 'referer_week' => 5, 'referer_month' => 7, 'time_referer' => 'monthstart-1']]]]);

    // ---------------------------------------------------------------- ajax/verify_check（Ajax.php:460-472）
    $v = '/ajax/verify_check';
    $add('vc.search-ok', $v, 'POST', ['type' => 'search', 'verify' => '1234'], ['captcha' => '1234']);
    $add('vc.show-ok', $v, 'POST', ['type' => 'show', 'verify' => '1234'], ['captcha' => '1234']);
    $add('vc.get-ok', $v, 'GET', ['type' => 'show', 'verify' => '1234'], ['captcha' => '1234']);
    $add('vc.wrong', $v, 'POST', ['type' => 'search', 'verify' => '9999'], ['captcha' => '1234']);
    $add('vc.lowercase', $v, 'POST', ['type' => 'search', 'verify' => 'abcd'], ['captcha' => 'ABCD']);
    $add('vc.no-session', $v, 'POST', ['type' => 'search', 'verify' => '1234']);
    $add('vc.no-verify', $v, 'POST', ['type' => 'search'], ['captcha' => '1234']);
    $add('vc.bad-type', $v, 'POST', ['type' => 'user', 'verify' => '1234'], ['captcha' => '1234']);
    $add('vc.no-type', $v, 'POST', ['verify' => '1234'], ['captcha' => '1234']);
    $add('vc.bad-type-non-ajax', $v, 'POST', ['type' => 'user', 'verify' => '1234'], ['captcha' => '1234',
        'ajax' => false]);
    $add('vc.ok-non-ajax', $v, 'POST', ['type' => 'show', 'verify' => '1234'], ['captcha' => '1234', 'ajax' => false]);
    $add('vc.wrong-non-ajax', $v, 'POST', ['type' => 'show', 'verify' => '9'], ['captcha' => '1234', 'ajax' => false]);
    $add('vc.already', $v, 'POST', ['type' => 'show', 'verify' => '1234'], ['captcha' => '1234',
        'session' => ['show_verify' => '1']]);

    // ---------------------------------------------------------------- Provide detail_inc_hits（api/controller/Provide.php:142-150）
    $pv = '/provide/vod';
    $api = ['entry' => 'api', 'ajax' => false, 'config' => ['api' => ['vod' => ['status' => 1, 'detail_inc_hits' => '1']]]]
        + $cnt('mac_vod', 1, ['hits' => 10]);
    foreach (['1', '01', '1.0', ' 1', '1 ', '1abc', '1,2', '0', '', '999', '5', '-1', '+1', '1e0', '%31'] as $ids) {
        $add('provide.ids-' . bin2hex($ids), $pv, 'GET', ['ac' => 'detail', 'ids' => $ids], $api);
    }
    $add('provide.raw-percent', $pv . '?ac=detail&ids=%2531', 'GET', [], $api);
    $add('provide.ac-list', $pv, 'GET', ['ac' => 'list', 'ids' => '1'], $api);
    $add('provide.ac-videolist', $pv, 'GET', ['ac' => 'videolist', 'ids' => '1'], $api);
    $add('provide.off', $pv, 'GET', ['ac' => 'detail', 'ids' => '1'], ['entry' => 'api', 'ajax' => false,
        'config' => ['api' => ['vod' => ['status' => 1, 'detail_inc_hits' => '0']]]] + $cnt('mac_vod', 1, ['hits' => 10]));
    $add('provide.closed', $pv, 'GET', ['ac' => 'detail', 'ids' => '1'], ['entry' => 'api', 'ajax' => false,
        'config' => ['api' => ['vod' => ['status' => 0, 'detail_inc_hits' => '1']]]] + $cnt('mac_vod', 1, ['hits' => 10]));
    $add('provide.max', $pv, 'GET', ['ac' => 'detail', 'ids' => '1'], ['entry' => 'api', 'ajax' => false,
        'config' => ['api' => ['vod' => ['status' => 1, 'detail_inc_hits' => '1']]]]
        + $cnt('mac_vod', 1, ['hits' => 16777215]));
    return $c;
}

/**
 * search 段：旧搜索页 {maccms:vod paging="yes"} 标签对 Vod::listCacheData 的调用（common/model/Vod.php:108-568）。
 * 每个用例：[标签, 请求参数（mac_param_url 的输入）, 用例前的 mac_vod_search 行, 其他选项]；按顺序执行，
 * reset=true 的用例先清空 mac_vod_search（否则沿用上一个用例写下的行，用来看"第二次命中"）。
 */
function search_cases()
{
    $row = function ($word, $field, $ids, $hits = 3) {
        $list = $ids === '' ? [] : explode(',', $ids);
        return ['search_key' => md5($word . '@' . $field), 'search_word' => $word, 'search_field' => $field,
            'search_hit_count' => $hits, 'search_last_hit_time' => 1700000000, 'search_update_time' => 1700000000,
            'search_result_count' => count($list), 'search_result_ids' => $ids];
    };
    $wdField = 'vod_name|vod_en|vod_sub';
    return [
        ['wd.first', ['wd' => '星际'], [], ['reset' => true]],
        ['wd.second', ['wd' => '星际'], null, []],
        ['wd.no-match', ['wd' => '不存在的词'], [], ['reset' => true]],
        ['wd.no-match-second', ['wd' => '不存在的词'], null, []],
        ['wd.en', ['wd' => 'inter'], [], ['reset' => true]],
        ['wd.unpublished', ['wd' => '未审核'], [], ['reset' => true]],
        ['wd.cached-stale', ['wd' => '星际'], [$row('星际', $wdField, '2,3')], ['reset' => true]],
        ['wd.cached-empty-ids', ['wd' => '星际'], [$row('星际', $wdField, '')], ['reset' => true]],
        ['wd.cached-junk-ids', ['wd' => '星际'], [$row('星际', $wdField, ',1,,x,2')], ['reset' => true]],
        ['wd.html-entity', ['wd' => '<b>'], [], ['reset' => true]],
        ['wd.percent', ['wd' => '%'], [], ['reset' => true]],
        ['wd.underscore', ['wd' => '_'], [], ['reset' => true]],
        ['wd.double-comma', ['wd' => '星,,际'], [], ['reset' => true]],
        ['wd.spaces', ['wd' => '  星际  '], [], ['reset' => true]],
        ['wd.comma', ['wd' => '星际,喜剧'], [], ['reset' => true]],
        ['wd.case', ['wd' => 'INTERSTELLAR'], [], ['reset' => true]],
        ['wd.long', ['wd' => str_repeat('长', 140)], [], ['reset' => true, 'config' => ['app' => ['search_len' => '200']]]],
        ['wd.over-1000', ['wd' => '片'], [$row('片', $wdField, implode(',', range(1, 1001)))], ['reset' => true]],
        ['wd.exactly-1000', ['wd' => '片'], [$row('片', $wdField, implode(',', range(1, 1000)))], ['reset' => true]],
        ['actor.multi', ['actor' => '演员甲,演员丙'], [], ['reset' => true]],
        ['actor.multi-second', ['actor' => '演员甲,演员丙'], null, []],
        ['director', ['director' => '导演乙'], [], ['reset' => true]],
        ['tag', ['tag' => '科幻'], [], ['reset' => true]],
        ['class', ['class' => '喜剧,科幻'], [], ['reset' => true]],
        ['name', ['name' => '星际穿越'], [], ['reset' => true]],
        ['wd-and-actor', ['wd' => '星际', 'actor' => '演员丙'], [], ['reset' => true]],
        ['wd-and-actor-union', ['wd' => '喜剧', 'actor' => '演员甲'], [$row('喜剧', $wdField, '2'),
            $row('演员甲', 'vod_actor', '1,6')], ['reset' => true]],
        ['actor-key-union', ['actor' => '甲,丙'], [$row('甲', 'vod_actor', '1'), $row('丙', 'vod_actor', '2,6')],
            ['reset' => true]],
        ['wd-and-level', ['wd' => '星际', 'level' => '1'], [], ['reset' => true]],
        ['disabled', ['wd' => '星际'], [], ['reset' => true, 'config' => ['app' => ['vod_search_optimise' => '']]]],
        ['collect-only', ['wd' => '星际'], [], ['reset' => true, 'config' => ['app' => ['vod_search_optimise' => 'collect']]]],
        ['frontend-and-collect', ['wd' => '星际'], [], ['reset' => true,
            'config' => ['app' => ['vod_search_optimise' => 'collect|frontend']]]],
        ['no-search-param', ['page' => '1'], [], ['reset' => true]],
    ];
}

function generate($repo, $pdo, $phpCgi, $work, $host, $port, $db, $user, $password)
{
    $conf = $work . '/conf';
    $runtime = $work . '/runtime';
    $out = [];
    $lastConf = null;
    $only = getenv('GOLDEN_ONLY');
    $base = deep_merge(include $repo . '/application/extra/maccms.php', base_config());
    foreach (cases() as $case) {
        list($id, $path, $method, $params, $opt) = $case;
        if ($only !== false && $only !== '' && strpos($id, $only) !== 0) {
            continue;
        }
        $over = isset($opt['config']) ? $opt['config'] : [];
        $maccms = deep_merge($base, $over);
        $serialized = serialize($maccms);
        if ($serialized !== $lastConf) {
            rm_tree($conf);
            write_conf($repo, $conf, $maccms, $host, $port, $db, $user, $password);
            $lastConf = $serialized;
        }
        rm_tree($runtime);
        @mkdir($runtime, 0777, true);
        $now = time();
        $setup = setup_sql($opt, $now);
        foreach ($setup as $sql) {
            $pdo->exec($sql);
        }
        $as = array_key_exists('as', $opt) ? $opt['as'] : null;
        $users = users_for($opt);
        $headers = ['X-Requested-With' => 'XMLHttpRequest'];
        if (isset($opt['ajax']) && $opt['ajax'] === false) {
            $headers = [];
        }
        $cookies = [];
        if ($as !== null) {
            $cookies[] = user_cookie($users[$as]);
        }
        if (isset($opt['cookie'])) {
            $cookies[] = $opt['cookie'];
        }
        $sessionId = null;
        $sessionBefore = null;
        if (isset($opt['captcha']) || isset($opt['session'])) {
            $sessionId = 'golden' . bin2hex(random_bytes(8));
            $data = [];
            if (isset($opt['captcha'])) {
                $data[captcha_authcode('ThinkPHP.CN')] = ['verify_code' => captcha_authcode(strtoupper($opt['captcha'])),
                    'verify_time' => $now];
            }
            // ThinkPHP 的 session() 带前缀 think（application/config.php:210），验证码记录不带前缀
            if (isset($opt['session'])) {
                $data['think'] = $opt['session'];
            }
            $sessionBefore = session_encode_data($data);
            file_put_contents($runtime . '/sess_' . $sessionId, $sessionBefore);
            $cookies[] = 'PHPSESSID=' . $sessionId;
        }
        if ($cookies) {
            $headers['Cookie'] = implode('; ', $cookies);
        }
        if (isset($opt['headers'])) {
            $headers = array_merge($headers, $opt['headers']);
        }
        $query = '';
        $post = '';
        $pathOnly = $path;
        if (strpos($path, '?') !== false) {
            list($pathOnly, $query) = explode('?', $path, 2);
        }
        if ($method === 'GET') {
            $query = ltrim($query . '&' . http_build_query($params), '&');
        } else {
            $post = http_build_query($params);
        }
        $entry = isset($opt['entry']) ? $opt['entry'] : 'index';
        $req = ['entry' => $entry, 'path' => $pathOnly, 'query' => $query, 'method' => $method, 'post' => $post,
            'headers' => $headers, 'remote' => isset($opt['remote']) ? $opt['remote'] : '127.0.0.1'];
        $started = time();
        list($status, $respHeaders, $setCookies, $body) = run_request($repo, $phpCgi, $conf, $runtime, $req);
        $after = time();
        $sessionAfter = null;
        if ($sessionId !== null && is_file($runtime . '/sess_' . $sessionId)) {
            $sessionAfter = session_decode_keys(file_get_contents($runtime . '/sess_' . $sessionId));
        }
        $rows = read_rows($pdo);
        if ($status >= 500) {
            // 旧版的异常页（ThinkPHP 的 System Error 模板）只记状态码，不记正文
            $body = '';
        }
        $out[] = ['id' => $id, 'request' => ['entry' => $entry, 'path' => $pathOnly, 'method' => $method,
            'query' => $query, 'post' => $post, 'as' => $as, 'cookie' => isset($opt['cookie']) ? $opt['cookie'] : null,
            'ajax' => isset($headers['X-Requested-With']), 'remote' => $req['remote'],
            'headers' => isset($opt['headers']) ? $opt['headers'] : (object)[],
            'captcha' => isset($opt['captcha']) ? $opt['captcha'] : null,
            'session' => isset($opt['session']) ? $opt['session'] : (object)[]],
            'config' => (object)$over, 'setup' => $setup, 'now' => $now, 'started' => $started, 'after' => $after,
            'status' => $status, 'contentType' => isset($respHeaders['content-type']) ? $respHeaders['content-type'] : null,
            'location' => isset($respHeaders['location']) ? $respHeaders['location'] : null,
            'setCookies' => $setCookies, 'body' => base64_encode($body), 'sessionAfter' => $sessionAfter, 'rows' => $rows];
    }
    $search = search_golden($repo, $pdo, $phpCgi, $work, $host, $port, $db, $user, $password, $base);
    return json_encode(['generator' => 'backend/src/test/php/interaction_golden.php', 'php' => PHP_VERSION,
            'timezone' => 'PRC', 'fixture' => fixture_sql(), 'seed' => SEED_SQL, 'baseConfig' => base_config(),
            'cases' => $out, 'search' => $search],
            JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT | JSON_INVALID_UTF8_SUBSTITUTE) . "\n";
}

function read_rows($pdo)
{
    $rows = [];
    foreach (RESET_TABLES as $t => $pk) {
        $cols = $t === 'mac_user' ? USER_COLUMNS : '*';
        $rows[$t] = $pdo->query("SELECT $cols FROM $t ORDER BY $pk")->fetchAll(PDO::FETCH_ASSOC);
    }
    foreach (COUNTER_TABLES as $t => $spec) {
        list($pre, $cols) = $spec;
        $list = [$pre . '_id'];
        foreach ($cols as $c) {
            $list[] = $pre . '_' . $c;
        }
        $rows[$t] = $pdo->query('SELECT ' . implode(', ', $list) . " FROM $t ORDER BY {$pre}_id")
            ->fetchAll(PDO::FETCH_ASSOC);
    }
    return $rows;
}

function search_golden($repo, $pdo, $phpCgi, $work, $host, $port, $db, $user, $password, $base)
{
    $only = getenv('GOLDEN_ONLY');
    if ($only !== false && $only !== '' && strpos('search', $only) !== 0) {
        return [];
    }
    $pdo->exec('DELETE FROM mac_vod_search');
    $ctx = ['repo' => $repo, 'php_cgi' => $phpCgi, 'work' => $work . '/search', 'db' => [$host, $port, $db, $user,
        $password]];
    @mkdir($work . '/search/runtime', 0777, true);
    $out = [];
    foreach (search_cases() as $c) {
        list($label, $param, $rows, $opt) = $c;
        $setup = [];
        if (!empty($opt['reset'])) {
            $setup[] = 'DELETE FROM mac_vod_search';
        }
        foreach ((array)$rows as $row) {
            $cols = [];
            $vals = [];
            foreach ($row as $k => $v) {
                $cols[] = $k;
                $vals[] = is_int($v) ? (string)$v : "'" . str_replace(["\\", "'"], ["\\\\", "\\'"], (string)$v) . "'";
            }
            $setup[] = 'INSERT INTO mac_vod_search (' . implode(', ', $cols) . ') VALUES (' . implode(', ', $vals) . ')';
        }
        foreach ($setup as $sql) {
            $pdo->exec($sql);
        }
        $config = deep_merge(['app' => ['vod_search_optimise' => 'frontend']],
            isset($opt['config']) ? $opt['config'] : []);
        $started = time();
        $r = run_child($ctx, ['op' => 'vodsearch', 'label' => $label, 'param' => $param,
            'config' => deep_merge($base, $config)]);
        $after = time();
        $out[] = ['label' => $label, 'param' => $param, 'config' => $config, 'setup' => $setup, 'started' => $started,
            'after' => $after, 'ids' => isset($r['ids']) ? $r['ids'] : null, 'total' => isset($r['total']) ? $r['total'] : null,
            'error' => isset($r['error']) ? $r['error'] : null,
            'rows' => $pdo->query('SELECT * FROM mac_vod_search ORDER BY search_key')->fetchAll(PDO::FETCH_ASSOC)];
    }
    $pdo->exec('DELETE FROM mac_vod_search');
    return $out;
}

/** 以 php-cgi 执行 interaction_golden_child.php 的一个用例。 */
function run_child($ctx, $args)
{
    list($host, $port, $db, $user, $password) = $ctx['db'];
    $child = __DIR__ . DIRECTORY_SEPARATOR . 'interaction_golden_child.php';
    $env = [
        'GOLDEN_ARGS' => json_encode($args, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE),
        'GOLDEN_RUNTIME_PATH' => $ctx['work'],
        'GOLDEN_DB_HOST' => $host, 'GOLDEN_DB_PORT' => $port, 'GOLDEN_DB_NAME' => $db, 'GOLDEN_DB_USER' => $user,
        'GOLDEN_DB_PASSWORD' => $password,
        'REDIRECT_STATUS' => '200',
        'GATEWAY_INTERFACE' => 'CGI/1.1',
        'SERVER_PROTOCOL' => 'HTTP/1.1',
        'SERVER_SOFTWARE' => 'golden',
        'SERVER_NAME' => 'www.example.test',
        'SERVER_PORT' => '80',
        'HTTP_HOST' => 'www.example.test',
        'REQUEST_METHOD' => 'GET',
        'SCRIPT_FILENAME' => $child,
        'SCRIPT_NAME' => '/index.php',
        'PHP_SELF' => '/index.php',
        'REQUEST_URI' => '/index.php',
        'QUERY_STRING' => http_build_query($args['param']),
        'REMOTE_ADDR' => '127.0.0.1',
        'REMOTE_PORT' => '50000',
        'DOCUMENT_ROOT' => dirname($child),
        'SystemRoot' => getenv('SystemRoot') ?: 'C:\\Windows',
        'TEMP' => $ctx['work'],
        'TMP' => $ctx['work'],
    ];
    if (getenv('GOLDEN_DEBUG') === '1') {
        $env['GOLDEN_DEBUG'] = '1';
    }
    $cmd = escapeshellarg($ctx['php_cgi']) . ' -d session.save_path=' . escapeshellarg($ctx['work'] . '/runtime')
        . ' -d display_errors=0 -d error_reporting=0 -d session.gc_probability=0';
    $proc = proc_open($cmd, [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['pipe', 'w']], $pipes, $ctx['repo'], $env,
        ['bypass_shell' => true]);
    if (!is_resource($proc)) {
        throw new RuntimeException('php-cgi');
    }
    fclose($pipes[0]);
    $raw = stream_get_contents($pipes[1]);
    $err = stream_get_contents($pipes[2]);
    fclose($pipes[1]);
    fclose($pipes[2]);
    proc_close($proc);
    $sep = strpos($raw, "\r\n\r\n");
    $json = json_decode($sep === false ? $raw : substr($raw, $sep + 4), true);
    if (!is_array($json)) {
        if (getenv('GOLDEN_DEBUG') === '1') {
            fwrite(STDERR, $raw . "\n" . $err . "\n");
        }
        throw new RuntimeException('child output is not JSON for ' . $args['label']);
    }
    return $json;
}

function users_for($opt)
{
    $users = default_users();
    foreach ((isset($opt['users']) ? $opt['users'] : []) as $uid => $over) {
        if ($over === null) {
            unset($users[$uid]);
            continue;
        }
        $users[$uid] = array_merge(isset($users[$uid]) ? $users[$uid] : ['user_id' => $uid], $over);
    }
    return $users;
}

/** 旧 Ajax::hits 的周起止（Ajax.php:187-188）与月、年起点，按 PRC。 */
function period_points($now)
{
    $new = getdate($now);
    return [
        'today' => mktime(0, 0, 0, $new['mon'], $new['mday'], $new['year']),
        'weekstart' => mktime(0, 0, 0, $new['mon'], $new['mday'], $new['year']) - ($new['wday'] * 86400),
        'weekend' => mktime(23, 59, 59, $new['mon'], $new['mday'], $new['year']) + ((6 - $new['wday']) * 86400),
        'monthstart' => mktime(0, 0, 0, $new['mon'], 1, $new['year']),
        'yearstart' => mktime(0, 0, 0, 1, 1, $new['year']),
    ];
}

/** 用例开始前的语句：清空记录表、计数列归零，写入会员、其他表的行与计数（时间值见 cases() 的说明）。 */
function setup_sql($opt, $now)
{
    $points = period_points($now);
    $value = function ($v) use ($now, $points) {
        if (is_string($v) && preg_match('/^now([+-])(\d+)$/', $v, $m)) {
            return $m[1] === '+' ? $now + (int)$m[2] : $now - (int)$m[2];
        }
        if (is_string($v) && preg_match('/^(today|weekstart|weekend|monthstart|yearstart)([+-])(\d+)$/', $v, $m)) {
            return $m[2] === '+' ? $points[$m[1]] + (int)$m[3] : $points[$m[1]] - (int)$m[3];
        }
        return $v;
    };
    $quote = function ($v) {
        return is_int($v) ? (string)$v : "'" . str_replace(["\\", "'"], ["\\\\", "\\'"], (string)$v) . "'";
    };
    $sql = [];
    foreach (array_keys(RESET_TABLES) as $t) {
        $sql[] = "DELETE FROM $t";
    }
    foreach (COUNTER_TABLES as $t => $spec) {
        list($pre, $cols) = $spec;
        $set = [];
        foreach ($cols as $c) {
            $set[] = $pre . '_' . $c . ($c === 'score' ? " = '0.0'" : ' = 0');
        }
        $sql[] = "UPDATE $t SET " . implode(', ', $set) . " WHERE {$pre}_id > 0";
    }
    $insert = function ($table, $row) use ($value, $quote) {
        $cols = [];
        $vals = [];
        foreach ($row as $k => $v) {
            $cols[] = $k;
            $vals[] = $quote($value($v));
        }
        return "INSERT INTO $table (" . implode(', ', $cols) . ') VALUES (' . implode(', ', $vals) . ')';
    };
    foreach (users_for($opt) as $u) {
        $sql[] = $insert('mac_user', $u);
    }
    foreach ((isset($opt['rows']) ? $opt['rows'] : []) as $table => $rows) {
        foreach ($rows as $row) {
            $sql[] = $insert($table, $row);
        }
    }
    foreach ((isset($opt['counters']) ? $opt['counters'] : []) as $table => $byId) {
        $pre = COUNTER_TABLES[$table][0];
        foreach ($byId as $id => $vals) {
            $set = [];
            foreach ($vals as $c => $v) {
                $set[] = $pre . '_' . $c . ' = ' . $quote($value($v));
            }
            $sql[] = "UPDATE $table SET " . implode(', ', $set) . " WHERE {$pre}_id = " . (int)$id;
        }
    }
    return $sql;
}

/** think-captcha 的私有 authcode（Captcha.php），用于构造会话里的验证码记录（同 account_golden_child.php）。 */
function captcha_authcode($str)
{
    $key = substr(md5('ThinkPHP.CN'), 5, 8);
    $str = substr(md5($str), 8, 10);
    return md5($key . $str);
}

/** PHP 默认会话序列化格式（session.serialize_handler=php）：键|serialize(值)。 */
function session_encode_data($data)
{
    $s = '';
    foreach ($data as $k => $v) {
        $s .= $k . '|' . serialize($v);
    }
    return $s;
}

/** 会话文件里的顶层键与值；验证码记录只记 "captcha"（是否还在）。 */
function session_decode_keys($raw)
{
    $out = [];
    $offset = 0;
    $len = strlen($raw);
    while ($offset < $len) {
        $bar = strpos($raw, '|', $offset);
        if ($bar === false) {
            break;
        }
        $key = substr($raw, $offset, $bar - $offset);
        $rest = substr($raw, $bar + 1);
        $value = null;
        $consumed = 0;
        for ($i = 1; $i <= strlen($rest); $i++) {
            $try = @unserialize(substr($rest, 0, $i));
            if ($try !== false || substr($rest, 0, $i) === 'b:0;') {
                $value = $try;
                $consumed = $i;
                break;
            }
        }
        if ($consumed === 0) {
            break;
        }
        $out[$key] = $key === captcha_authcode('ThinkPHP.CN') ? 'captcha' : $value;
        $offset = $bar + 1 + $consumed;
    }
    return (object)$out;
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
 * 以 php-cgi 执行一个请求（同 finance_golden.php 的 run_request）。返回 [status, headers(小写名 => 值), setCookies, body]。
 */
function run_request($repo, $phpCgi, $conf, $runtime, $req)
{
    $entry = $req['entry'];
    $query = $req['query'];
    $method = $req['method'];
    $post = $req['post'];
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
        'GOLDEN_SESSION_PATH' => $runtime,
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
        'REMOTE_ADDR' => $req['remote'],
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
    foreach ($req['headers'] as $name => $value) {
        $env['HTTP_' . strtoupper(str_replace('-', '_', $name))] = $value;
    }
    if (getenv('GOLDEN_DEBUG') === '1') {
        $env['GOLDEN_DEBUG'] = '1';
    }
    $cmd = escapeshellarg($phpCgi) . ' -d session.save_path=' . escapeshellarg($runtime)
        . ' -d display_errors=0 -d error_reporting=0 -d session.gc_probability=0';
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
    $setCookies = [];
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
        if ($name === 'set-cookie') {
            $setCookies[] = $value;
            continue;
        }
        $headers[$name] = $value;
    }
    return [$status, $headers, $setCookies, substr($out, $sep + 4)];
}
