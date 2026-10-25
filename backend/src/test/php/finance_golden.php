<?php
/*
 * B10（MACC-34）会员权益与财务的 PHP 黄金样本生成脚本（契约见 docs/architecture/19-b10-member-finance.md 第 4 节）。
 *
 * 每个用例是一次真实的前台请求：经 legacy_http_child.php 跑完整的 ThinkPHP 路由、Base 构造函数（label_user、登录检查）与
 * index/controller/User.php 的方法本身，记录状态码、Content-Type、Location、全部 Set-Cookie 与正文字节，以及请求后
 * mac_user/mac_ulog/mac_plog/mac_card/mac_cash/mac_order/mac_visit 的全部行。覆盖：ajax_buy_popedom、ajax_ulog（set 与列表）、
 * ulog_del、plog_del、cash（申请）、cash_del、buy（卡密与建单）、upgrade、pay/gopay（未配置支付）、order_info、visit、
 * 推广注册（D-B09-7）与分销奖励。请求一律带 X-Requested-With（旧模板的脚本就是这样请求的），所以 error() 的结果是 JSON。
 *
 * 流程：外部管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入 install.sql 与 B06 的合成内容 seed.sql、本脚本的会员组与积分设定 →
 * 每个用例先清空上面 7 张表并写入用例自己的行，再执行请求 → 输出 JSON → 删除一次性库与临时目录。
 * 不修改任何旧 PHP 文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，不访问网络。需要仓库根目录下的 vendor/。
 *
 * 管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；另需 PHP_CGI。
 * 用法（仓库根目录，PHP 8.0 与 7.3 各一份）：
 *   PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe backend/src/test/php/finance_golden.php \
 *       > backend/src/test/resources/member/finance-php80-golden.json
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

const TABLES = ['mac_user' => 'user_id', 'mac_ulog' => 'ulog_id', 'mac_plog' => 'plog_id', 'mac_card' => 'card_id',
    'mac_cash' => 'cash_id', 'mac_order' => 'order_id', 'mac_visit' => 'visit_id'];
/** 记录的会员列（本批会改动的列；其余列由 B09 黄金覆盖）。 */
const USER_COLUMNS = 'user_id, group_id, user_name, user_points, user_points_froze, user_end_time, user_pid, user_pid_2, user_pid_3';
const SEED_SQL = 'backend/src/test/resources/openapi/provide/seed.sql';

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$work = sys_get_temp_dir() . '/maccms-b10-golden-' . bin2hex(random_bytes(4));
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
 * 内容与会员组的固定设定（所有用例共用，Java 侧导入同样的语句）：视频 1（整片 5、播放 3、下载 4）、视频 2（不收费）、
 * 视频 3（50）、未审核的视频 5（7）、文章 1（整篇 6、按页 2）；VIP 组 3 的日/周/月/年价格；停用的组 4；组 2、3 的分类权限与组 1 相同。
 */
function fixture_sql()
{
    return [
        'UPDATE mac_vod SET vod_points=5, vod_points_play=3, vod_points_down=4 WHERE vod_id=1',
        'UPDATE mac_vod SET vod_points=0, vod_points_play=0, vod_points_down=0 WHERE vod_id=2',
        'UPDATE mac_vod SET vod_points=50, vod_points_play=50, vod_points_down=50 WHERE vod_id=3',
        'UPDATE mac_vod SET vod_points=7, vod_points_play=7, vod_points_down=7 WHERE vod_id=5',
        'UPDATE mac_art SET art_points=6, art_points_detail=2 WHERE art_id=1',
        'UPDATE mac_group g, (SELECT group_type, group_popedom FROM mac_group WHERE group_id=1) s'
        . ' SET g.group_type=s.group_type, g.group_popedom=s.group_popedom WHERE g.group_id IN (2,3)',
        'UPDATE mac_group SET group_points_day=1, group_points_week=5, group_points_month=20, group_points_year=200'
        . ' WHERE group_id=3',
        "INSERT INTO mac_group (group_id, group_name, group_status, group_type, group_popedom, group_points_day,"
        . " group_points_week, group_points_month, group_points_year) VALUES (4, '关闭的组', 0, ',1,', '{}', 1, 1, 1, 1)",
    ];
}

/** 每个用例都带上的配置（仓库值之上）。 */
function base_config()
{
    return [
        'site' => ['site_name' => '示例站点', 'site_url' => 'www.example.test'],
        'app' => ['cache_flag' => 'golden', 'cache_core' => '0'],
        'user' => ['status' => '1', 'reg_open' => '1', 'reg_status' => '1', 'reg_verify' => '0', 'login_verify' => '0',
            'reg_points' => '10', 'reg_num' => '0', 'reg_phone_sms' => '0', 'reg_email_sms' => '0',
            'invite_reg_points' => '10', 'invite_visit_points' => '1', 'invite_visit_num' => '1',
            'reward_status' => '1', 'reward_ratio' => '10', 'reward_ratio_2' => '30', 'reward_ratio_3' => '50',
            'cash_status' => '1', 'cash_ratio' => '100', 'cash_min' => '1', 'vod_points_type' => '1',
            'art_points_type' => '1', 'trysee' => '0'],
        'pay' => ['min' => '10', 'scale' => '1'],
    ];
}

/** 用例的默认会员（user_id => 行）；用例里的 users 按 user_id 覆盖或增加列，值为 null 时不写这一行。 */
function default_users()
{
    $u = function ($id, $name, $extra = []) {
        return array_merge(['user_id' => $id, 'group_id' => 2, 'user_name' => $name, 'user_pwd' => md5('secret'),
            'user_status' => 1, 'user_random' => 'rnd' . $id, 'user_points' => 0, 'user_points_froze' => 0,
            'user_end_time' => 0, 'user_reg_time' => 1700000000, 'user_pid' => 0, 'user_pid_2' => 0,
            'user_pid_3' => 0, 'user_nick_name' => '', 'user_question' => '', 'user_answer' => ''], $extra);
    };
    return [
        2 => $u(2, 'refer002'),
        3 => $u(3, 'refer003'),
        4 => $u(4, 'refer004'),
        7 => $u(7, 'buyer007', ['user_points' => 100, 'user_pid' => 2, 'user_pid_2' => 3, 'user_pid_3' => 4]),
        8 => $u(8, 'other008', ['user_points' => 50]),
    ];
}

function user_cookie($user)
{
    $check = md5($user['user_random'] . '-' . $user['user_name'] . '-' . $user['user_id'] . '-');
    return 'user_id=' . $user['user_id'] . '; user_name=' . rawurlencode($user['user_name']) . '; user_check=' . $check;
}

/**
 * 用例：[id, 路径, 方法, 参数（查询串或正文的键值）, 选项]。
 * 选项：as（以哪个会员登录，默认 7；null 为游客）、config（配置覆盖）、users（会员行覆盖）、rows（其他表的初始行：表名 => 行列表）、
 * ajax（默认 true）、headers（其他请求头）、remote（客户端 IP）、raw（原样的查询串/正文，不经 http_build_query）。
 */
function cases()
{
    $c = [];
    $add = function ($id, $path, $method, $params, $opt = []) use (&$c) {
        $c[] = [$id, $path, $method, $params, $opt];
    };
    $vipFuture = ['users' => [7 => ['group_id' => 3, 'user_end_time' => '+86400']]];

    // ---------------------------------------------------------------- ajax_buy_popedom（User.php:101-173）
    $buy = '/user/ajax_buy_popedom.html';
    $add('buy.param.mid-missing', $buy, 'GET', ['id' => '1', 'type' => '4']);
    $add('buy.param.mid3', $buy, 'GET', ['mid' => '3', 'id' => '1', 'type' => '4']);
    $add('buy.param.type2', $buy, 'GET', ['mid' => '1', 'id' => '1', 'type' => '2']);
    $add('buy.param.id0', $buy, 'GET', ['mid' => '1', 'id' => '0', 'type' => '4']);
    $add('buy.param.id-text', $buy, 'GET', ['mid' => '1', 'id' => 'abc', 'type' => '4']);
    $add('buy.loose.mid01', $buy, 'GET', ['mid' => '01', 'id' => '1', 'type' => '4']);
    $add('buy.loose.type4.0', $buy, 'GET', ['mid' => '1', 'id' => '1', 'type' => '4.0']);
    $add('buy.vod-missing', $buy, 'GET', ['mid' => '1', 'id' => '999', 'type' => '4']);
    $add('buy.art-missing', $buy, 'GET', ['mid' => '2', 'id' => '999', 'type' => '1']);
    $add('buy.vod.play.whole', $buy, 'GET', ['mid' => '1', 'id' => '1', 'sid' => '1', 'nid' => '2', 'type' => '4']);
    $add('buy.vod.down.whole', $buy, 'GET', ['mid' => '1', 'id' => '1', 'type' => '5']);
    $add('buy.vod.play.episode', $buy, 'GET', ['mid' => '1', 'id' => '1', 'sid' => '1', 'nid' => '2', 'type' => '4'],
        ['config' => ['user' => ['vod_points_type' => '0']]]);
    $add('buy.vod.down.episode', $buy, 'GET', ['mid' => '1', 'id' => '1', 'sid' => '2', 'nid' => '1', 'type' => '5'],
        ['config' => ['user' => ['vod_points_type' => '0']]]);
    $add('buy.vod.already', $buy, 'GET', ['mid' => '1', 'id' => '1', 'type' => '4'],
        ['rows' => ['mac_ulog' => [['ulog_id' => 1, 'user_id' => 7, 'ulog_mid' => 1, 'ulog_type' => 4, 'ulog_rid' => 1,
            'ulog_sid' => 0, 'ulog_nid' => 0, 'ulog_points' => 5, 'ulog_time' => 1700000000]]]]);
    $add('buy.vod.price-changed', $buy, 'GET', ['mid' => '1', 'id' => '1', 'type' => '4'],
        ['rows' => ['mac_ulog' => [['ulog_id' => 1, 'user_id' => 7, 'ulog_mid' => 1, 'ulog_type' => 4, 'ulog_rid' => 1,
            'ulog_sid' => 0, 'ulog_nid' => 0, 'ulog_points' => 4, 'ulog_time' => 1700000000]]]]);
    $add('buy.vod.not-enough', $buy, 'GET', ['mid' => '1', 'id' => '1', 'type' => '4'],
        ['users' => [7 => ['user_points' => 4]]]);
    $add('buy.vod.exact-points', $buy, 'GET', ['mid' => '1', 'id' => '1', 'type' => '4'],
        ['users' => [7 => ['user_points' => 5]]]);
    $add('buy.vod.free', $buy, 'GET', ['mid' => '1', 'id' => '2', 'type' => '4']);
    $add('buy.vod.mid2-type4', $buy, 'GET', ['mid' => '2', 'id' => '1', 'type' => '4']);
    $add('buy.art.whole', $buy, 'GET', ['mid' => '2', 'id' => '1', 'sid' => '3', 'type' => '1']);
    $add('buy.art.page', $buy, 'GET', ['mid' => '2', 'id' => '1', 'sid' => '3', 'type' => '1'],
        ['config' => ['user' => ['art_points_type' => '0']]]);
    $add('buy.reward.three-levels', $buy, 'GET', ['mid' => '1', 'id' => '3', 'type' => '4']);
    $add('buy.reward.missing-level2', $buy, 'GET', ['mid' => '1', 'id' => '3', 'type' => '4'],
        ['users' => [3 => null]]);
    $add('buy.reward.off', $buy, 'GET', ['mid' => '1', 'id' => '3', 'type' => '4'],
        ['config' => ['user' => ['reward_status' => '0']]]);
    $add('buy.reward.ratio-empty', $buy, 'GET', ['mid' => '1', 'id' => '3', 'type' => '4'],
        ['config' => ['user' => ['reward_ratio' => '', 'reward_ratio_2' => '0', 'reward_ratio_3' => '7']]]);
    $add('buy.reward.small-fee', $buy, 'GET', ['mid' => '1', 'id' => '1', 'type' => '4']);
    $add('buy.sid-overflow', $buy, 'GET', ['mid' => '1', 'id' => '1', 'sid' => '300', 'nid' => '1', 'type' => '4'],
        ['config' => ['user' => ['vod_points_type' => '0']]]);
    $add('buy.guest', $buy, 'GET', ['mid' => '1', 'id' => '1', 'type' => '4'], ['as' => null]);
    $add('buy.vip-buys-too', $buy, 'GET', ['mid' => '1', 'id' => '1', 'type' => '4'], $vipFuture);
    $add('buy.vod.unpublished', $buy, 'GET', ['mid' => '1', 'id' => '5', 'type' => '4']);
    $add('buy.vod.down-points-whole', $buy, 'GET', ['mid' => '1', 'id' => '3', 'type' => '5'],
        ['users' => [7 => ['user_points' => 49]]]);

    // ---------------------------------------------------------------- ajax_ulog（User.php:47-99）
    $ulog = '/user/ajax_ulog.html';
    $add('ulog.set.fav-vod', $ulog, 'GET', ['ac' => 'set', 'mid' => '1', 'id' => '1', 'type' => '2']);
    $add('ulog.set.fav-again', $ulog, 'GET', ['ac' => 'set', 'mid' => '1', 'id' => '1', 'type' => '2'],
        ['rows' => ['mac_ulog' => [['ulog_id' => 5, 'user_id' => 7, 'ulog_mid' => 1, 'ulog_type' => 2, 'ulog_rid' => 1,
            'ulog_sid' => 0, 'ulog_nid' => 0, 'ulog_points' => 0, 'ulog_time' => 1700000000],
            ['ulog_id' => 6, 'user_id' => 7, 'ulog_mid' => 1, 'ulog_type' => 2, 'ulog_rid' => 1,
                'ulog_sid' => 0, 'ulog_nid' => 0, 'ulog_points' => 0, 'ulog_time' => 1700000001]]]]);
    $add('ulog.set.fav-art', $ulog, 'POST', ['ac' => 'set', 'mid' => '2', 'id' => '1', 'type' => '2']);
    $add('ulog.set.fav-topic', $ulog, 'GET', ['ac' => 'set', 'mid' => '3', 'id' => '1', 'type' => '2']);
    $add('ulog.set.fav-actor', $ulog, 'GET', ['ac' => 'set', 'mid' => '8', 'id' => '1', 'type' => '2']);
    $add('ulog.set.mid5', $ulog, 'GET', ['ac' => 'set', 'mid' => '5', 'id' => '1', 'type' => '2']);
    $add('ulog.set.type6', $ulog, 'GET', ['ac' => 'set', 'mid' => '1', 'id' => '1', 'type' => '6']);
    $add('ulog.set.type0', $ulog, 'GET', ['ac' => 'set', 'mid' => '2', 'id' => '1', 'type' => '0']);
    $add('ulog.set.id0', $ulog, 'GET', ['ac' => 'set', 'mid' => '2', 'id' => '0', 'type' => '2']);
    $add('ulog.set.play-fee', $ulog, 'GET', ['ac' => 'set', 'mid' => '1', 'id' => '1', 'sid' => '1', 'nid' => '1',
        'type' => '4']);
    $add('ulog.set.play-free', $ulog, 'GET', ['ac' => 'set', 'mid' => '1', 'id' => '2', 'sid' => '1', 'nid' => '1',
        'type' => '4']);
    $add('ulog.set.down-free', $ulog, 'GET', ['ac' => 'set', 'mid' => '1', 'id' => '2', 'sid' => '1', 'nid' => '1',
        'type' => '5']);
    $add('ulog.set.play-missing', $ulog, 'GET', ['ac' => 'set', 'mid' => '1', 'id' => '999', 'type' => '4']);
    $add('ulog.set.play-purchased', $ulog, 'GET', ['ac' => 'set', 'mid' => '1', 'id' => '1', 'sid' => '1',
        'nid' => '2', 'type' => '4'],
        ['rows' => ['mac_ulog' => [['ulog_id' => 9, 'user_id' => 7, 'ulog_mid' => 1, 'ulog_type' => 4, 'ulog_rid' => 1,
            'ulog_sid' => 1, 'ulog_nid' => 2, 'ulog_points' => 3, 'ulog_time' => 1700000000]]]]);
    $add('ulog.set.play-unpublished', $ulog, 'GET', ['ac' => 'set', 'mid' => '1', 'id' => '5', 'type' => '4']);
    $add('ulog.set.mid-text', $ulog, 'GET', ['ac' => 'set', 'mid' => '1x', 'id' => '2y', 'type' => '2z']);
    $add('ulog.set.guest', $ulog, 'GET', ['ac' => 'set', 'mid' => '1', 'id' => '1', 'type' => '2'], ['as' => null]);
    $listRows = ['mac_ulog' => [
        ['ulog_id' => 1, 'user_id' => 7, 'ulog_mid' => 1, 'ulog_type' => 2, 'ulog_rid' => 1, 'ulog_sid' => 0,
            'ulog_nid' => 0, 'ulog_points' => 0, 'ulog_time' => 1700000010],
        ['ulog_id' => 2, 'user_id' => 7, 'ulog_mid' => 1, 'ulog_type' => 4, 'ulog_rid' => 1, 'ulog_sid' => 1,
            'ulog_nid' => 2, 'ulog_points' => 0, 'ulog_time' => 1700000020],
        ['ulog_id' => 3, 'user_id' => 7, 'ulog_mid' => 1, 'ulog_type' => 5, 'ulog_rid' => 1, 'ulog_sid' => 1,
            'ulog_nid' => 1, 'ulog_points' => 0, 'ulog_time' => 1700000030],
        ['ulog_id' => 4, 'user_id' => 7, 'ulog_mid' => 2, 'ulog_type' => 2, 'ulog_rid' => 1, 'ulog_sid' => 0,
            'ulog_nid' => 0, 'ulog_points' => 0, 'ulog_time' => 1700000040],
        ['ulog_id' => 5, 'user_id' => 7, 'ulog_mid' => 3, 'ulog_type' => 2, 'ulog_rid' => 1, 'ulog_sid' => 0,
            'ulog_nid' => 0, 'ulog_points' => 0, 'ulog_time' => 1700000050],
        ['ulog_id' => 6, 'user_id' => 7, 'ulog_mid' => 8, 'ulog_type' => 2, 'ulog_rid' => 1, 'ulog_sid' => 0,
            'ulog_nid' => 0, 'ulog_points' => 0, 'ulog_time' => 1700000060],
        ['ulog_id' => 7, 'user_id' => 8, 'ulog_mid' => 1, 'ulog_type' => 2, 'ulog_rid' => 2, 'ulog_sid' => 0,
            'ulog_nid' => 0, 'ulog_points' => 0, 'ulog_time' => 1700000070],
        ['ulog_id' => 8, 'user_id' => 7, 'ulog_mid' => 1, 'ulog_type' => 2, 'ulog_rid' => 3, 'ulog_sid' => 0,
            'ulog_nid' => 0, 'ulog_points' => 0, 'ulog_time' => 1700000005],
    ]];
    $add('ulog.list.all', $ulog, 'GET', ['ac' => 'list'], ['rows' => $listRows]);
    $add('ulog.list.default-ac', $ulog, 'GET', [], ['rows' => $listRows]);
    $add('ulog.list.mid1', $ulog, 'GET', ['ac' => 'list', 'mid' => '1'], ['rows' => $listRows]);
    $add('ulog.list.type2-page2', $ulog, 'GET', ['ac' => 'list', 'type' => '2', 'limit' => '2', 'page' => '2'],
        ['rows' => $listRows]);
    $add('ulog.list.id1', $ulog, 'GET', ['ac' => 'list', 'id' => '1', 'mid' => '1', 'type' => '4'],
        ['rows' => $listRows]);
    $add('ulog.list.empty', $ulog, 'GET', ['ac' => 'list', 'mid' => '9'], ['rows' => $listRows]);
    $add('ulog.list.page0-limit-negative', $ulog, 'GET', ['ac' => 'list', 'page' => '0', 'limit' => '-5'],
        ['rows' => $listRows]);
    $add('ulog.list.limit-big', $ulog, 'GET', ['ac' => 'list', 'limit' => '500'], ['rows' => $listRows]);
    $gone = $listRows;
    $gone['mac_ulog'][] = ['ulog_id' => 20, 'user_id' => 7, 'ulog_mid' => 1, 'ulog_type' => 2, 'ulog_rid' => 999,
        'ulog_sid' => 0, 'ulog_nid' => 0, 'ulog_points' => 0, 'ulog_time' => 1700000100];
    $gone['mac_ulog'][] = ['ulog_id' => 21, 'user_id' => 7, 'ulog_mid' => 1, 'ulog_type' => 2, 'ulog_rid' => 5,
        'ulog_sid' => 0, 'ulog_nid' => 0, 'ulog_points' => 0, 'ulog_time' => 1700000101];
    $gone['mac_ulog'][] = ['ulog_id' => 22, 'user_id' => 7, 'ulog_mid' => 2, 'ulog_type' => 2, 'ulog_rid' => 999,
        'ulog_sid' => 0, 'ulog_nid' => 0, 'ulog_points' => 0, 'ulog_time' => 1700000102];
    $gone['mac_ulog'][] = ['ulog_id' => 23, 'user_id' => 7, 'ulog_mid' => 9, 'ulog_type' => 2, 'ulog_rid' => 1,
        'ulog_sid' => 0, 'ulog_nid' => 0, 'ulog_points' => 0, 'ulog_time' => 1700000103];
    $add('ulog.list.missing-content', $ulog, 'GET', ['ac' => 'list', 'limit' => '4'], ['rows' => $gone]);

    // ---------------------------------------------------------------- ulog_del / plog_del（User.php:683-758）
    $delRows = ['mac_ulog' => $listRows['mac_ulog'], 'mac_plog' => [
        ['plog_id' => 1, 'user_id' => 7, 'plog_type' => 8, 'plog_points' => 5, 'plog_time' => 1700000001],
        ['plog_id' => 2, 'user_id' => 7, 'plog_type' => 2, 'plog_points' => 10, 'plog_time' => 1700000002],
        ['plog_id' => 3, 'user_id' => 8, 'plog_type' => 2, 'plog_points' => 10, 'plog_time' => 1700000003],
    ]];
    $add('ulogdel.type-missing', '/user/ulog_del.html', 'POST', ['ids' => '1'], ['rows' => $delRows]);
    $add('ulogdel.empty', '/user/ulog_del.html', 'POST', ['type' => '2'], ['rows' => $delRows]);
    $add('ulogdel.ids', '/user/ulog_del.html', 'POST', ['type' => '2', 'ids' => '1,4,7'], ['rows' => $delRows]);
    $add('ulogdel.all', '/user/ulog_del.html', 'POST', ['type' => '2', 'all' => '1'], ['rows' => $delRows]);
    $add('ulogdel.all-with-ids', '/user/ulog_del.html', 'POST', ['type' => '2', 'all' => '1', 'ids' => '5'],
        ['rows' => $delRows]);
    $add('ulogdel.all-not-1', '/user/ulog_del.html', 'POST', ['type' => '2', 'all' => '2'], ['rows' => $delRows]);
    $add('ulogdel.negative', '/user/ulog_del.html', 'POST', ['type' => '2', 'ids' => '-1,-4'], ['rows' => $delRows]);
    $add('ulogdel.text-id', '/user/ulog_del.html', 'POST', ['type' => '2', 'ids' => '1,abc'], ['rows' => $delRows]);
    $add('ulogdel.loose-type', '/user/ulog_del.html', 'POST', ['type' => '02', 'ids' => '1'], ['rows' => $delRows]);
    $add('plogdel.ids', '/user/plog_del.html', 'POST', ['ids' => '1,3'], ['rows' => $delRows]);
    $add('plogdel.all', '/user/plog_del.html', 'POST', ['all' => '1'], ['rows' => $delRows]);
    $add('plogdel.empty', '/user/plog_del.html', 'POST', [], ['rows' => $delRows]);

    // ---------------------------------------------------------------- cash（Cash.php:66-115）与 cash_del（:117-146）
    $cash = '/user/cash.html';
    $bank = ['cash_bank_name' => '示例银行', 'cash_bank_no' => '6222000011112222', 'cash_payee_name' => '张三'];
    $add('cash.closed', $cash, 'POST', ['cash_money' => '1'] + $bank, ['config' => ['user' => ['cash_status' => '0']]]);
    $add('cash.below-min', $cash, 'POST', ['cash_money' => '0.5'] + $bank);
    $add('cash.too-much', $cash, 'POST', ['cash_money' => '1.01'] + $bank);
    $add('cash.ok', $cash, 'POST', ['cash_money' => '0.5'] + $bank, ['config' => ['user' => ['cash_min' => '0.5']]]);
    $add('cash.ok-1', $cash, 'POST', ['cash_money' => '1'] + $bank);
    $add('cash.float-money', $cash, 'POST', ['cash_money' => '0.29'] + $bank,
        ['config' => ['user' => ['cash_min' => '0.1']]]);
    $add('cash.money-prefix', $cash, 'POST', ['cash_money' => ' 1abc'] + $bank);
    $add('cash.money-rounding', $cash, 'POST', ['cash_money' => '0.125'] + $bank,
        ['config' => ['user' => ['cash_min' => '0.1', 'cash_ratio' => '8']]]);
    $add('cash.no-bank-name', $cash, 'POST', ['cash_money' => '1', 'cash_bank_name' => ' ', 'cash_bank_no' => '1',
        'cash_payee_name' => 'a']);
    $add('cash.no-bank-no', $cash, 'POST', ['cash_money' => '1', 'cash_bank_name' => 'b', 'cash_bank_no' => '',
        'cash_payee_name' => 'a']);
    $add('cash.no-payee', $cash, 'POST', ['cash_money' => '1', 'cash_bank_name' => 'b', 'cash_bank_no' => '1']);
    $add('cash.escape', $cash, 'POST', ['cash_money' => '1', 'cash_bank_name' => '<b>银行</b>%41',
        'cash_bank_no' => ' 1&2 ', 'cash_payee_name' => '"李四"']);
    $add('cash.points-overflow', $cash, 'POST', ['cash_money' => '700'] + $bank,
        ['users' => [7 => ['user_points' => 100000]]]);
    $add('cash.with-froze', $cash, 'POST', ['cash_money' => '1'] + $bank,
        ['users' => [7 => ['user_points_froze' => 30]]]);
    $cashRows = ['mac_cash' => [
        ['cash_id' => 1, 'user_id' => 7, 'cash_status' => 0, 'cash_points' => 20, 'cash_money' => '0.20',
            'cash_bank_name' => 'b', 'cash_bank_no' => '1', 'cash_payee_name' => 'a', 'cash_time' => 1700000001],
        ['cash_id' => 2, 'user_id' => 7, 'cash_status' => 1, 'cash_points' => 30, 'cash_money' => '0.30',
            'cash_bank_name' => 'b', 'cash_bank_no' => '1', 'cash_payee_name' => 'a', 'cash_time' => 1700000002,
            'cash_time_audit' => 1700000100],
        ['cash_id' => 3, 'user_id' => 8, 'cash_status' => 0, 'cash_points' => 40, 'cash_money' => '0.40',
            'cash_bank_name' => 'b', 'cash_bank_no' => '1', 'cash_payee_name' => 'a', 'cash_time' => 1700000003],
        ['cash_id' => 4, 'user_id' => 7, 'cash_status' => 0, 'cash_points' => 10, 'cash_money' => '0.10',
            'cash_bank_name' => 'b', 'cash_bank_no' => '1', 'cash_payee_name' => 'a', 'cash_time' => 1700000004],
    ]];
    $froze = ['rows' => $cashRows, 'users' => [7 => ['user_points' => 70, 'user_points_froze' => 30],
        8 => ['user_points_froze' => 40]]];
    $add('cashdel.pending', '/user/cash_del.html', 'POST', ['ids' => '1'], $froze);
    $add('cashdel.audited', '/user/cash_del.html', 'POST', ['ids' => '2'], $froze);
    $add('cashdel.other-user', '/user/cash_del.html', 'POST', ['ids' => '3'], $froze);
    $add('cashdel.all', '/user/cash_del.html', 'POST', ['all' => '1'], $froze);
    $add('cashdel.empty', '/user/cash_del.html', 'POST', [], $froze);
    $add('cashdel.mixed', '/user/cash_del.html', 'POST', ['ids' => '1,2,3,4,99'], $froze);

    // ---------------------------------------------------------------- buy：卡密（Card.php:123-157）与建单（User.php:444-461）
    $card = function ($id, $no, $pwd, $points, $extra = []) {
        return array_merge(['card_id' => $id, 'card_no' => $no, 'card_pwd' => $pwd, 'card_money' => 10,
            'card_points' => $points, 'card_use_status' => 0, 'card_sale_status' => 0, 'user_id' => 0,
            'card_add_time' => 1700000000, 'card_use_time' => 0], $extra);
    };
    $cards = ['mac_card' => [$card(1, 'CARDNO0000000001', 'PWD00001', 30), $card(2, 'CARDNO0000000002', 'PWD00002', 40,
        ['card_use_status' => 1, 'card_sale_status' => 1, 'user_id' => 8, 'card_use_time' => 1700000500]),
        $card(3, 'DUPNO00000000003', 'DUPPWD03', 15), $card(4, 'DUPNO00000000003', 'DUPPWD03', 25),
        $card(5, 'CARDNO0000000005', 'PWD00005', 0, ['card_sale_status' => 1])]];
    $buyUrl = '/user/buy.html';
    $add('card.empty', $buyUrl, 'POST', ['flag' => 'card', 'card_no' => '', 'card_pwd' => 'x'], ['rows' => $cards]);
    $add('card.wrong', $buyUrl, 'POST', ['flag' => 'card', 'card_no' => 'CARDNO0000000001', 'card_pwd' => 'nope'],
        ['rows' => $cards]);
    $add('card.ok', $buyUrl, 'POST', ['flag' => 'card', 'card_no' => 'CARDNO0000000001', 'card_pwd' => 'PWD00001'],
        ['rows' => $cards]);
    $add('card.case-trailing', $buyUrl, 'POST', ['flag' => 'card', 'card_no' => 'cardno0000000001 ',
        'card_pwd' => 'pwd00001'], ['rows' => $cards]);
    $add('card.used', $buyUrl, 'POST', ['flag' => 'card', 'card_no' => 'CARDNO0000000002', 'card_pwd' => 'PWD00002'],
        ['rows' => $cards]);
    $add('card.duplicate', $buyUrl, 'POST', ['flag' => 'card', 'card_no' => 'DUPNO00000000003',
        'card_pwd' => 'DUPPWD03'], ['rows' => $cards]);
    $add('card.zero-points', $buyUrl, 'POST', ['flag' => 'card', 'card_no' => 'CARDNO0000000005',
        'card_pwd' => 'PWD00005'], ['rows' => $cards]);
    $add('card.flag-in-query', $buyUrl . '?flag=card', 'POST', ['card_no' => 'CARDNO0000000001',
        'card_pwd' => 'PWD00001'], ['rows' => $cards]);
    $add('order.empty', $buyUrl, 'POST', ['price' => '']);
    $add('order.zero', $buyUrl, 'POST', ['price' => '0']);
    $add('order.below-min', $buyUrl, 'POST', ['price' => '9.99']);
    $add('order.ok', $buyUrl, 'POST', ['price' => '10']);
    $add('order.decimal-scale', $buyUrl, 'POST', ['price' => '12.5'], ['config' => ['pay' => ['scale' => '1.5']]]);
    $add('order.rounding', $buyUrl, 'POST', ['price' => '10.005']);
    $add('order.exponent', $buyUrl, 'POST', ['price' => '1e2']);
    $add('order.spaces', $buyUrl, 'POST', ['price' => ' 20']);
    $add('order.text', $buyUrl, 'POST', ['price' => 'abc']);
    $add('order.negative', $buyUrl, 'POST', ['price' => '-50']);
    $add('order.points-overflow', $buyUrl, 'POST', ['price' => '20000000']);

    // ---------------------------------------------------------------- upgrade（User.php:539-600）
    $up = '/user/upgrade.html';
    $add('upgrade.long-invalid', $up, 'POST', ['group_id' => '3', 'long' => 'hour']);
    $add('upgrade.long-missing', $up, 'POST', ['group_id' => '3']);
    $add('upgrade.group2', $up, 'POST', ['group_id' => '2', 'long' => 'day']);
    $add('upgrade.group-missing', $up, 'POST', ['group_id' => '9', 'long' => 'day']);
    $add('upgrade.group-closed', $up, 'POST', ['group_id' => '4', 'long' => 'day']);
    $add('upgrade.not-enough', $up, 'POST', ['group_id' => '3', 'long' => 'year']);
    $add('upgrade.month-from-now', $up, 'POST', ['group_id' => '3', 'long' => 'month']);
    $add('upgrade.extend', $up, 'POST', ['group_id' => '3', 'long' => 'week'], $vipFuture);
    $add('upgrade.expired-base', $up, 'POST', ['group_id' => '3', 'long' => 'day'],
        ['users' => [7 => ['group_id' => 3, 'user_end_time' => '-100']]]);
    $add('upgrade.exact-points', $up, 'POST', ['group_id' => '3', 'long' => 'month'],
        ['users' => [7 => ['user_points' => 20]]]);
    $add('upgrade.reward-off', $up, 'POST', ['group_id' => '3', 'long' => 'month'],
        ['config' => ['user' => ['reward_status' => '0']]]);

    // ---------------------------------------------------------------- pay / gopay（User.php:469-533）
    $orders = ['mac_order' => [
        ['order_id' => 1, 'user_id' => 7, 'order_status' => 0, 'order_code' => 'PAY20260101000000123456',
            'order_price' => '10.00', 'order_time' => 1700000000, 'order_points' => 10],
        ['order_id' => 2, 'user_id' => 8, 'order_status' => 1, 'order_code' => 'PAY20260101000000654321',
            'order_price' => '20.00', 'order_time' => 1700000100, 'order_points' => 20, 'order_pay_type' => 'alipay',
            'order_pay_time' => 1700000200],
    ]];
    $add('pay.missing', '/user/pay.html', 'GET', ['order_code' => 'NOPE'], ['rows' => $orders]);
    $add('pay.other-user', '/user/pay.html', 'GET', ['order_code' => 'PAY20260101000000654321'], ['rows' => $orders]);
    $add('gopay.empty', '/user/gopay.html', 'GET', [], ['rows' => $orders]);
    $add('gopay.alipay', '/user/gopay.html', 'GET', ['order_code' => 'PAY20260101000000123456', 'order_id' => '1',
        'payment' => 'alipay'], ['rows' => $orders]);
    $add('gopay.weixin-upper', '/user/gopay.html', 'GET', ['order_code' => 'PAY20260101000000123456',
        'order_id' => '1', 'payment' => 'WeiXin'], ['rows' => $orders]);
    $add('gopay.unknown', '/user/gopay.html', 'GET', ['payment' => 'paypal'], ['rows' => $orders]);
    $add('gopay.only-id', '/user/gopay.html', 'GET', ['order_id' => '1'], ['rows' => $orders]);

    // ---------------------------------------------------------------- order_info（User.php:858-869）
    $add('orderinfo.own', '/user/order_info.html', 'GET', ['order_id' => '1'], ['rows' => $orders]);
    $add('orderinfo.other-user', '/user/order_info.html', 'GET', ['order_id' => '2'], ['rows' => $orders]);
    $add('orderinfo.missing', '/user/order_info.html', 'GET', ['order_id' => '99'], ['rows' => $orders]);
    $add('orderinfo.zero', '/user/order_info.html', 'GET', [], ['rows' => $orders]);

    // ---------------------------------------------------------------- visit（User.php:906-918、common/model/User.php:848-892）
    $visit = '/user/visit.html';
    $ref = ['headers' => ['Referer' => 'http://ref.example.test/a?b=1&c=<x>']];
    $add('visit.uid0', $visit, 'GET', ['uid' => '0'], ['as' => null]);
    $add('visit.first', $visit, 'GET', ['uid' => '7'], ['as' => null] + $ref);
    $add('visit.limit', $visit, 'GET', ['uid' => '7'], ['as' => null, 'rows' => ['mac_visit' => [
        ['visit_id' => 1, 'user_id' => 7, 'visit_ip' => 2130706433, 'visit_ly' => '', 'visit_time' => 'now-10']]]]);
    $add('visit.yesterday', $visit, 'GET', ['uid' => '7'], ['as' => null, 'rows' => ['mac_visit' => [
        ['visit_id' => 1, 'user_id' => 7, 'visit_ip' => 2130706433, 'visit_ly' => '', 'visit_time' => 'today-1']]]]);
    $add('visit.other-ip', $visit, 'GET', ['uid' => '7'], ['as' => null, 'remote' => '10.1.2.3', 'rows' => ['mac_visit' => [
        ['visit_id' => 1, 'user_id' => 7, 'visit_ip' => 2130706433, 'visit_ly' => '', 'visit_time' => 'now-10']]]]);
    $add('visit.num2', $visit, 'GET', ['uid' => '7'], ['as' => null, 'config' => ['user' => ['invite_visit_num' => '2']],
        'rows' => ['mac_visit' => [['visit_id' => 1, 'user_id' => 7, 'visit_ip' => 2130706433, 'visit_ly' => '',
            'visit_time' => 'now-10']]]]);
    $add('visit.negative-uid', $visit, 'GET', ['uid' => '-7'], ['as' => null]);
    $add('visit.missing-user', $visit, 'GET', ['uid' => '999'], ['as' => null]);
    $add('visit.points0', $visit, 'GET', ['uid' => '7'], ['as' => null,
        'config' => ['user' => ['invite_visit_points' => '0']]]);
    $add('visit.url-same-host', $visit, 'GET', ['uid' => '7', 'url' => 'http://www.example.test/index.php/vod/detail/id/1.html'],
        ['as' => null]);
    $add('visit.url-other-host', $visit, 'GET', ['uid' => '7', 'url' => 'http://evil.example.test/'], ['as' => null]);
    $add('visit.url-relative', $visit, 'GET', ['uid' => '7', 'url' => '/index.php'], ['as' => null]);
    $add('visit.logged-in', $visit, 'GET', ['uid' => '8']);

    // ---------------------------------------------------------------- 推广注册（D-B09-7，common/model/User.php:236-264）
    $reg = ['user_name' => 'newuser01', 'user_pwd' => 'pass1234', 'user_pwd2' => 'pass1234'];
    $add('reg.invite', '/user/reg.html', 'POST', $reg + ['uid' => '7'], ['as' => null]);
    $add('reg.invite-points0', '/user/reg.html', 'POST', $reg + ['uid' => '7'], ['as' => null,
        'config' => ['user' => ['invite_reg_points' => '0']]]);
    $add('reg.invite-missing', '/user/reg.html', 'POST', $reg + ['uid' => '999'], ['as' => null]);
    $add('reg.invite-cookie', '/user/reg.html', 'POST', $reg, ['as' => null, 'cookie' => 'uid=8']);
    $add('reg.no-invite', '/user/reg.html', 'POST', $reg, ['as' => null]);
    return $c;
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
            rm_tree($runtime);
            @mkdir($runtime, 0777, true);
            $lastConf = $serialized;
        }
        $now = time();
        $setup = setup_sql($opt, $now);
        foreach ($setup as $sql) {
            $pdo->exec($sql);
        }
        $as = array_key_exists('as', $opt) ? $opt['as'] : 7;
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
        $req = ['entry' => 'index', 'path' => $pathOnly, 'query' => $query, 'method' => $method, 'post' => $post,
            'headers' => $headers, 'remote' => isset($opt['remote']) ? $opt['remote'] : '127.0.0.1'];
        $started = time();
        list($status, $respHeaders, $setCookies, $body) = run_request($repo, $phpCgi, $conf, $runtime, $req);
        $after = time();
        $rows = [];
        foreach (TABLES as $t => $pk) {
            $cols = $t === 'mac_user' ? USER_COLUMNS : '*';
            $rows[$t] = $pdo->query("SELECT $cols FROM $t ORDER BY $pk")->fetchAll(PDO::FETCH_ASSOC);
        }
        if ($status >= 500) {
            // 旧版的异常页（ThinkPHP 的 System Error 模板）只记状态码，不记正文
            $body = '';
        }
        $out[] = ['id' => $id, 'request' => ['path' => $pathOnly, 'method' => $method, 'query' => $query,
            'post' => $post, 'as' => $as, 'cookie' => isset($opt['cookie']) ? $opt['cookie'] : null,
            'ajax' => isset($headers['X-Requested-With']), 'remote' => $req['remote'],
            'referer' => isset($headers['Referer']) ? $headers['Referer'] : null],
            'config' => (object)$over, 'setup' => $setup, 'now' => $now, 'started' => $started, 'after' => $after,
            'status' => $status, 'contentType' => isset($respHeaders['content-type']) ? $respHeaders['content-type'] : null,
            'location' => isset($respHeaders['location']) ? $respHeaders['location'] : null,
            'setCookies' => $setCookies, 'body' => base64_encode($body), 'rows' => $rows];
    }
    return json_encode(['generator' => 'backend/src/test/php/finance_golden.php', 'php' => PHP_VERSION,
            'timezone' => 'PRC', 'fixture' => fixture_sql(), 'seed' => SEED_SQL, 'baseConfig' => base_config(),
            'cases' => $out], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT) . "\n";
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

/** 用例开始前的语句：清空 7 张表，写入会员与其他表的行（时间值 "+N"/"-N" 相对现在，"now-N"、"today-N" 同理）。 */
function setup_sql($opt, $now)
{
    $today = (new DateTime('today', new DateTimeZone('PRC')))->getTimestamp();
    $value = function ($v) use ($now, $today) {
        if (is_string($v) && preg_match('/^([+-])(\d+)$/', $v, $m)) {
            return $m[1] === '+' ? $now + (int)$m[2] : $now - (int)$m[2];
        }
        if (is_string($v) && preg_match('/^(now|today)-(\d+)$/', $v, $m)) {
            return ($m[1] === 'now' ? $now : $today) - (int)$m[2];
        }
        return $v;
    };
    $sql = [];
    foreach (array_keys(TABLES) as $t) {
        $sql[] = "DELETE FROM $t";
    }
    $insert = function ($table, $row) use ($value) {
        $cols = [];
        $vals = [];
        foreach ($row as $k => $v) {
            $cols[] = $k;
            $v = $value($v);
            $vals[] = is_int($v) ? (string)$v : "'" . str_replace(["\\", "'"], ["\\\\", "\\'"], (string)$v) . "'";
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
    return $sql;
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
 * 以 php-cgi 执行一个请求（同 provide_golden.php 的 run_request，另外按出现顺序保留全部 Set-Cookie）。
 * 返回 [status, headers(小写名 => 值), setCookies, body]。
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
