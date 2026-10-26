<?php
/*
 * B14（MACC-38）后台会员与财务管理的 PHP 黄金样本生成脚本（契约见 docs/architecture/23-b14-admin-member.md）。
 *
 * 每个场景是一串真实的后台请求：经 admin_content_child.php（B13 的子进程入口，与控制器无关，原样复用）跑完整的 ThinkPHP 路由、
 * admin/controller/Base 的构造函数与 B14 范围内的控制器（User、Group、Card、Order、Cash、Plog、Ulog、Visit）；同一场景内按浏览器的
 * 方式保存并回送 cookie（PHP 会话）。每个请求记录状态码、Location、Content-Type、Content-Disposition、全部 Set-Cookie 与正文字节
 * （base64），以及请求后发生变化的表的全部行（只记变化的表；场景开始时各表的行在 rows0）。文本列一律十六进制。请求前后的秒级时间
 * （started/after）用于比较写入的时间列。
 *
 * 场景之间互不依赖：每个场景先清空跟踪的各表并把自增计数复位（一次性库上的测试夹具：DELETE 后 ALTER TABLE … AUTO_INCREMENT = 1，
 * 新版的复放测试做同样的事，使新增行的主键相同），写入自己的行，使用新的 cookie、会话与配置目录。与"今天"有关的种子时间（卡密、访问
 * 记录的时间筛选）按生成时的 PRC 零点计算，写进黄金后就是固定值；复放时新版的时钟拨到 PHP 请求开始的秒，结果可比。
 *
 * 流程：外部管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入 install.sql → 逐个场景执行 → 输出 JSON → 删除一次性库与临时目录。
 * 不修改任何旧 PHP 文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，不访问外部网络。
 *
 * 管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；另需 PHP_CGI。
 * 用法（仓库根目录，PHP 8.0 与 7.3 各一份）：
 *   PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe backend/src/test/php/admin_member_golden.php \
 *       > backend/src/test/resources/admin/member/php80-golden.json
 * 只跑部分场景：GOLDEN_ONLY=<标签前缀>（逗号分隔多个）。
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

const ENTRY = '/_admin.php';
/** 跟踪的表与主键（顺序即输出顺序）。内容表只作为会员访问日志的关联数据，B14 不写它们。 */
const TABLES = ['mac_user' => 'user_id', 'mac_group' => 'group_id', 'mac_card' => 'card_id', 'mac_order' => 'order_id',
    'mac_cash' => 'cash_id', 'mac_plog' => 'plog_id', 'mac_ulog' => 'ulog_id', 'mac_visit' => 'visit_id',
    'mac_type' => 'type_id', 'mac_vod' => 'vod_id', 'mac_art' => 'art_id', 'mac_topic' => 'topic_id', 'mac_actor' => 'actor_id'];

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$work = sys_get_temp_dir() . '/maccms-b14-golden-' . bin2hex(random_bytes(4));
$exit = 0;
try {
    $pdo->exec("USE `$dbName`");
    foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
        $pdo->exec($sql);
    }
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

// ====================================================================== 场景数据

/** 每个场景都带上的配置（仓库值之上）：后台登录不要验证码，语言简体，每页 20。 */
function base_config()
{
    return ['app' => ['admin_login_verify' => '0', 'lang' => 'zh-cn', 'pagesize' => '20'],
        'view' => ['vod_detail' => '0', 'art_detail' => '0', 'topic_detail' => '0', 'actor_detail' => '0'],
        'upload' => ['protocol' => 'http', 'mode' => 'local', 'watermark' => '0', 'thumb' => '0']];
}

function admins()
{
    return [
        ['admin_id' => 1, 'admin_name' => 'admin', 'admin_pwd' => md5('admin888'), 'admin_status' => 1, 'admin_auth' => ''],
        ['admin_id' => 2, 'admin_name' => 'editor', 'admin_pwd' => md5('ed pass'), 'admin_status' => 1,
            'admin_auth' => ',user/data,user/info,group/index,'],
    ];
}

/** 今天（PRC）零点，生成时计算（写进黄金后是固定值）。 */
function today0()
{
    static $t = null;
    if ($t === null) {
        $t = strtotime('today');
    }
    return $t;
}

function popedom_json(array $map)
{
    return json_encode($map);
}

function groups()
{
    $all = popedom_json(['1' => ['1' => '1', '2' => '2', '3' => '3', '4' => '4', '5' => '5'], '2' => ['1' => '1', '2' => '2'],
        '5' => ['1' => '1', '2' => '2']]);
    return [
        ['group_id' => 1, 'group_name' => '游客', 'group_status' => 1, 'group_type' => ',1,2,5,', 'group_popedom' => $all,
            'group_points_day' => 0, 'group_points_week' => 0, 'group_points_month' => 0, 'group_points_year' => 0],
        ['group_id' => 2, 'group_name' => '默认会员', 'group_status' => 1, 'group_type' => ',1,2,5,', 'group_popedom' => $all,
            'group_points_day' => 0, 'group_points_week' => 0, 'group_points_month' => 0, 'group_points_year' => 0],
        ['group_id' => 3, 'group_name' => 'VIP会员', 'group_status' => 1, 'group_type' => ',1,2,',
            'group_popedom' => popedom_json(['1' => ['1' => '1', '3' => '3'], '2' => ['2' => '2', '5' => '5']]),
            'group_points_day' => 10, 'group_points_week' => 70, 'group_points_month' => 300, 'group_points_year' => 3600],
        ['group_id' => 4, 'group_name' => '金牌<b>会员</b>', 'group_status' => 0, 'group_type' => '', 'group_popedom' => '',
            'group_points_day' => 1, 'group_points_week' => 2, 'group_points_month' => 3, 'group_points_year' => 4],
        ['group_id' => 5, 'group_name' => '空组', 'group_status' => 1, 'group_type' => ',5,', 'group_popedom' => popedom_json([]),
            'group_points_day' => 0, 'group_points_week' => 0, 'group_points_month' => 0, 'group_points_year' => 0],
    ];
}

function u($id, $name, $group, array $extra = [])
{
    return array_merge(['user_id' => $id, 'group_id' => $group, 'user_name' => $name, 'user_pwd' => md5('pw' . $id),
        'user_nick_name' => '', 'user_qq' => '', 'user_email' => '', 'user_phone' => '', 'user_status' => 1, 'user_portrait' => '',
        'user_portrait_thumb' => '', 'user_openid_qq' => '', 'user_openid_weixin' => '', 'user_question' => '', 'user_answer' => '',
        'user_points' => 0, 'user_points_froze' => 0, 'user_reg_time' => 1690000000 + $id, 'user_reg_ip' => 2130706433,
        'user_login_time' => 1700000000 + $id, 'user_login_ip' => 3232235777, 'user_last_login_time' => 0, 'user_last_login_ip' => 0,
        'user_login_num' => $id, 'user_extend' => 0, 'user_random' => '', 'user_end_time' => 0, 'user_pid' => 0, 'user_pid_2' => 0,
        'user_pid_3' => 0], $extra);
}

function users()
{
    return [
        u(1, 'alice01', 2, ['user_points' => 100, 'user_nick_name' => '爱丽丝', 'user_email' => 'a@x.test', 'user_qq' => '10001',
            'user_question' => '问题', 'user_answer' => '答案', 'user_portrait' => 'upload/user/1/1.jpg']),
        u(2, 'bobvip', 3, ['user_points' => 50, 'user_points_froze' => 30, 'user_end_time' => 1700000000, 'user_pid' => 1,
            'user_login_time' => today0() + 30]),
        u(3, 'carolvip', 3, ['user_points' => 7, 'user_end_time' => 4000000000, 'user_pid' => 2, 'user_pid_2' => 1]),
        u(4, 'davegold', 4, ['user_end_time' => 1600000000, 'user_points_froze' => 5]),
        u(5, 'eve_off', 2, ['user_status' => 0, 'user_pid' => 3, 'user_pid_2' => 2, 'user_pid_3' => 1]),
        u(6, '<b>"x"&amp;', 9, ['user_nick_name' => 'a"b<c>&amp;d', 'user_pid' => 1]),
    ];
}

function cards()
{
    $t = today0();
    return [
        ['card_id' => 1, 'card_no' => 'AAAA2222BBBB3333', 'card_pwd' => 'PWD12345', 'card_money' => 10, 'card_points' => 100,
            'card_use_status' => 1, 'card_sale_status' => 1, 'user_id' => 1, 'card_add_time' => 1600000000, 'card_use_time' => $t + 10],
        ['card_id' => 2, 'card_no' => 'CCCC4444DDDD5555', 'card_pwd' => 'QWERTYUI', 'card_money' => 20, 'card_points' => 200,
            'card_use_status' => 0, 'card_sale_status' => 1, 'user_id' => 0, 'card_add_time' => $t - 3 * 86400 + 60, 'card_use_time' => 0],
        ['card_id' => 3, 'card_no' => '1234567890123456', 'card_pwd' => '87654321', 'card_money' => 5, 'card_points' => 50,
            'card_use_status' => 0, 'card_sale_status' => 0, 'user_id' => 0, 'card_add_time' => $t + 60, 'card_use_time' => 0],
        ['card_id' => 4, 'card_no' => 'ZZZZ<&>"9999XXXX', 'card_pwd' => 'a"b<c', 'card_money' => 1, 'card_points' => 1,
            'card_use_status' => 1, 'card_sale_status' => 1, 'user_id' => 99, 'card_add_time' => $t - 40 * 86400, 'card_use_time' => 1650000000],
    ];
}

function orders()
{
    return [
        ['order_id' => 1, 'user_id' => 1, 'order_status' => 0, 'order_code' => 'PAY20260101120000123456', 'order_price' => '10.00',
            'order_time' => 1700000100, 'order_points' => 100, 'order_pay_type' => '', 'order_pay_time' => 0, 'order_remarks' => ''],
        ['order_id' => 2, 'user_id' => 2, 'order_status' => 1, 'order_code' => 'PAY20260102120000654321', 'order_price' => '5.50',
            'order_time' => 1700000200, 'order_points' => 55, 'order_pay_type' => 'alipay', 'order_pay_time' => today0() + 5,
            'order_remarks' => '备注'],
        ['order_id' => 3, 'user_id' => 99, 'order_status' => 0, 'order_code' => 'PAY<i>9</i>', 'order_price' => '1.00',
            'order_time' => 1700000300, 'order_points' => 1, 'order_pay_type' => '', 'order_pay_time' => 0, 'order_remarks' => ''],
    ];
}

function cashes()
{
    return [
        ['cash_id' => 1, 'user_id' => 2, 'cash_status' => 0, 'cash_points' => 20, 'cash_money' => '0.20', 'cash_bank_name' => '银行A',
            'cash_bank_no' => '6222000011112222', 'cash_payee_name' => '鲍勃', 'cash_time' => 1700000100, 'cash_time_audit' => 0],
        ['cash_id' => 2, 'user_id' => 2, 'cash_status' => 1, 'cash_points' => 10, 'cash_money' => '0.10', 'cash_bank_name' => '银行B',
            'cash_bank_no' => '6222000033334444', 'cash_payee_name' => '鲍勃', 'cash_time' => 1700000200, 'cash_time_audit' => 1700000300],
        ['cash_id' => 3, 'user_id' => 4, 'cash_status' => 0, 'cash_points' => 5, 'cash_money' => '0.05', 'cash_bank_name' => '<b>行</b>',
            'cash_bank_no' => '99<&>', 'cash_payee_name' => '戴夫', 'cash_time' => today0() + 1, 'cash_time_audit' => 0],
        ['cash_id' => 4, 'user_id' => 1, 'cash_status' => 0, 'cash_points' => 40, 'cash_money' => '0.40', 'cash_bank_name' => '银行C',
            'cash_bank_no' => '6222000055556666', 'cash_payee_name' => '爱丽丝', 'cash_time' => 1700000400, 'cash_time_audit' => 0],
        ['cash_id' => 5, 'user_id' => 77, 'cash_status' => 0, 'cash_points' => 3, 'cash_money' => '0.03', 'cash_bank_name' => '无人',
            'cash_bank_no' => '1', 'cash_payee_name' => '无', 'cash_time' => 1700000500, 'cash_time_audit' => 0],
    ];
}

function plogs()
{
    return [
        ['plog_id' => 1, 'user_id' => 1, 'user_id_1' => 0, 'plog_type' => 4, 'plog_points' => 5, 'plog_time' => 1700000100,
            'plog_remarks' => '用户【2、bobvip】消费50积分，获得奖励5积分'],
        ['plog_id' => 2, 'user_id' => 1, 'user_id_1' => 0, 'plog_type' => 5, 'plog_points' => 3, 'plog_time' => 1700000200, 'plog_remarks' => ''],
        ['plog_id' => 3, 'user_id' => 1, 'user_id_1' => 0, 'plog_type' => 4, 'plog_points' => 2, 'plog_time' => today0() + 2, 'plog_remarks' => '<b>x</b>'],
        ['plog_id' => 4, 'user_id' => 2, 'user_id_1' => 0, 'plog_type' => 8, 'plog_points' => 10, 'plog_time' => 1700000400, 'plog_remarks' => ''],
        ['plog_id' => 5, 'user_id' => 1, 'user_id_1' => 0, 'plog_type' => 6, 'plog_points' => 1, 'plog_time' => 1700000500, 'plog_remarks' => ''],
        ['plog_id' => 6, 'user_id' => 99, 'user_id_1' => 0, 'plog_type' => 9, 'plog_points' => 7, 'plog_time' => 1700000600, 'plog_remarks' => ''],
    ];
}

function ulogs()
{
    return [
        ['ulog_id' => 1, 'user_id' => 1, 'ulog_mid' => 1, 'ulog_type' => 4, 'ulog_rid' => 1, 'ulog_sid' => 1, 'ulog_nid' => 2,
            'ulog_points' => 5, 'ulog_time' => 1700000100],
        ['ulog_id' => 2, 'user_id' => 1, 'ulog_mid' => 2, 'ulog_type' => 1, 'ulog_rid' => 1, 'ulog_sid' => 0, 'ulog_nid' => 0,
            'ulog_points' => 0, 'ulog_time' => 1700000200],
        ['ulog_id' => 3, 'user_id' => 2, 'ulog_mid' => 3, 'ulog_type' => 2, 'ulog_rid' => 1, 'ulog_sid' => 0, 'ulog_nid' => 0,
            'ulog_points' => 0, 'ulog_time' => today0() + 3],
        ['ulog_id' => 4, 'user_id' => 2, 'ulog_mid' => 8, 'ulog_type' => 2, 'ulog_rid' => 1, 'ulog_sid' => 0, 'ulog_nid' => 0,
            'ulog_points' => 0, 'ulog_time' => 1700000400],
        ['ulog_id' => 5, 'user_id' => 3, 'ulog_mid' => 1, 'ulog_type' => 5, 'ulog_rid' => 9, 'ulog_sid' => 0, 'ulog_nid' => 0,
            'ulog_points' => 0, 'ulog_time' => 1700000500],
        ['ulog_id' => 6, 'user_id' => 99, 'ulog_mid' => 1, 'ulog_type' => 2, 'ulog_rid' => 1, 'ulog_sid' => 0, 'ulog_nid' => 0,
            'ulog_points' => 0, 'ulog_time' => 1700000600],
    ];
}

function visits()
{
    $t = today0();
    return [
        ['visit_id' => 1, 'user_id' => 1, 'visit_ip' => 2130706433, 'visit_ly' => 'http://a.test/page?x=1', 'visit_time' => $t + 100],
        ['visit_id' => 2, 'user_id' => 1, 'visit_ip' => 2130706434, 'visit_ly' => 'https://a.test/', 'visit_time' => $t - 3 * 86400],
        ['visit_id' => 3, 'user_id' => 0, 'visit_ip' => 0, 'visit_ly' => 'http://b.test/<b>', 'visit_time' => $t - 40 * 86400],
        ['visit_id' => 4, 'user_id' => 2, 'visit_ip' => 3232235777, 'visit_ly' => 'http://a_test/', 'visit_time' => 1700000000],
    ];
}

function types()
{
    $t = function ($id, $name, $mid, $pid, $sort, array $extra = []) {
        return array_merge(['type_id' => $id, 'type_name' => $name, 'type_en' => 'en' . $id, 'type_sort' => $sort,
            'type_mid' => $mid, 'type_pid' => $pid, 'type_status' => 1, 'type_tpl' => 'type.html', 'type_tpl_list' => 'show.html',
            'type_tpl_detail' => 'detail.html', 'type_tpl_play' => 'play.html', 'type_tpl_down' => 'down.html', 'type_extend' => ''],
            $extra);
    };
    return [
        $t(1, '电影', 1, 0, 1),
        $t(2, '动作片', 1, 1, 1),
        $t(3, '喜剧<片>', 1, 1, 2, ['type_status' => 0]),
        $t(5, '资讯', 2, 0, 2),
        $t(6, '头条', 2, 5, 1),
    ];
}

function vods()
{
    return [['vod_id' => 1, 'vod_name' => '星际穿越', 'type_id' => 2, 'type_id_1' => 1, 'vod_status' => 1, 'vod_en' => 'xingji',
        'vod_pic' => 'upload/vod/a.jpg', 'vod_content' => '', 'vod_play_from' => 'dplayer', 'vod_play_server' => 'no',
        'vod_play_note' => '', 'vod_play_url' => '第1集$http://a.test/1.m3u8#第2集$http://a.test/2.m3u8', 'vod_down_from' => '',
        'vod_down_server' => '', 'vod_down_note' => '', 'vod_down_url' => '', 'vod_plot_name' => '', 'vod_plot_detail' => '',
        'vod_time' => 1700000000, 'vod_time_add' => 1690000000]];
}

function arts()
{
    return [['art_id' => 1, 'art_name' => '新闻<一>', 'type_id' => 6, 'type_id_1' => 5, 'art_status' => 1, 'art_en' => 'xinwen',
        'art_title' => '', 'art_note' => '', 'art_content' => '<p>x</p>', 'art_time' => 1700000000]];
}

function topics()
{
    return [['topic_id' => 1, 'topic_name' => '专题一', 'topic_en' => 'zhuanti', 'topic_status' => 1, 'topic_tpl' => 'detail.html',
        'topic_time' => 1700000000, 'topic_rel_vod' => '', 'topic_rel_art' => '', 'topic_content' => '', 'topic_extend' => '']];
}

function actors()
{
    return [['actor_id' => 1, 'actor_name' => '马修', 'actor_en' => 'maxiu', 'type_id' => 0, 'type_id_1' => 0, 'actor_status' => 1,
        'actor_time' => 1700000000, 'actor_content' => '']];
}

function base_seed()
{
    return ['mac_user' => users(), 'mac_group' => groups(), 'mac_card' => cards(), 'mac_order' => orders(),
        'mac_cash' => cashes(), 'mac_plog' => plogs(), 'mac_ulog' => ulogs(), 'mac_visit' => visits(), 'mac_type' => types(),
        'mac_vod' => vods(), 'mac_art' => arts(), 'mac_topic' => topics(), 'mac_actor' => actors()];
}

// ====================================================================== 请求构造

/** 一个请求步骤。$post 为数组时按表单编码，为字符串时原样作为正文。 */
function req($label, $method, $path, $query = '', $post = '', array $opt = [])
{
    return ['label' => $label, 'method' => $method, 'path' => $path, 'query' => $query, 'post' => $post,
        'ajax' => !isset($opt['ajax']) || $opt['ajax'], 'headers' => isset($opt['headers']) ? $opt['headers'] : [],
        'body' => !isset($opt['body']) || $opt['body'], 'multipart' => null];
}

function get($label, $path, $query = '', array $opt = [])
{
    return req($label, 'GET', $path, $query, '', $opt + ['ajax' => false]);
}

function post($label, $path, $post, array $opt = [])
{
    return req($label, 'POST', $path, '', $post, $opt);
}

/** 原始正文（不经 http_build_query，用于数组名、重复字段与特殊字节）。 */
function raw($label, $path, $raw, array $opt = [])
{
    return req($label, 'POST', $path, '', ['__raw__' => $raw], $opt);
}

function login($name = 'admin', $pwd = 'admin888')
{
    return req('login', 'POST', '/admin/index/login', '', ['admin_name' => $name, 'admin_pwd' => $pwd], ['body' => false]);
}

function sc($label, array $steps, array $opt = [])
{
    return array_merge(['label' => $label, 'seed' => base_seed(), 'files' => [], 'config' => [],
        'steps' => array_merge([login()], $steps)], $opt);
}

// ====================================================================== 场景

function scenarios($repo)
{
    return array_merge(scenarios_user(), scenarios_group(), scenarios_card(), scenarios_order(), scenarios_cash(),
        scenarios_logs());
}

/** 会员编辑表单（旧 admin/view/user/info.html 提交的字段）。 */
function user_form(array $over = [])
{
    return array_merge(['user_id' => '1', 'user_name' => 'alice01', 'user_pwd' => '', 'user_status' => '1', 'group_id' => '2',
        'user_portrait' => 'upload/user/1/1.jpg', 'user_nick_name' => '爱丽丝', 'user_end_time' => '1970-01-01 08:00:00',
        'user_points' => '100', 'user_phone' => '', 'user_qq' => '10001', 'user_email' => 'a@x.test', 'user_question' => '问题',
        'user_answer' => '答案'], $over);
}

function scenarios_user()
{
    $s = [];
    $s[] = sc('user-list', [
        // 第 2 页不触发到期降级（会员 2、4 已到期仍在 VIP/金牌组）
        get('page2', '/admin/user/data', 'page=2&limit=2'),
        get('page2-loose', '/admin/user/data', 'page=2.0&limit=50'),
        // 第 1 页（缺省）触发 expire()：group_id > 2 且 user_end_time <= now 降为 2
        get('data', '/admin/user/data'),
        get('status-0', '/admin/user/data', 'status=0'),
        get('status-loose', '/admin/user/data', 'status=01'),
        get('group', '/admin/user/data', 'group=3'),
        get('group-missing-name', '/admin/user/data', 'group=9'),
        get('wd', '/admin/user/data', 'wd=' . rawurlencode('vip')),
        get('wd-entity', '/admin/user/data', 'wd=' . rawurlencode('<b>"x"&')),
        get('wd-like', '/admin/user/data', 'wd=' . rawurlencode('_o')),
        get('limit-bad', '/admin/user/data', 'page=abc&limit=0'),
        get('ajax', '/admin/user/data', 'page=1', ['ajax' => true]),
    ]);
    $s[] = sc('user-expire-page1', [
        get('page1-loose', '/admin/user/data', 'page=1.0'),
    ]);
    $s[] = sc('user-info', [
        get('new', '/admin/user/info'),
        get('edit', '/admin/user/info', 'id=1'),
        get('edit-vip', '/admin/user/info', 'id=3'),
        get('edit-escape', '/admin/user/info', 'id=6'),
        get('missing', '/admin/user/info', 'id=999'),
        get('path-id', '/admin/user/info/id/2'),
    ]);
    $s[] = sc('user-save', [
        post('edit', '/admin/user/info', user_form(['user_nick_name' => '新昵称<b>', 'user_points' => '123'])),
        post('edit-pwd', '/admin/user/info', user_form(['user_pwd' => ' pa ss '])),
        post('edit-pwd-like', '/admin/user/info', user_form(['user_pwd' => 'like'])),
        post('edit-end-time', '/admin/user/info', user_form(['user_id' => '3', 'user_name' => 'carolvip', 'group_id' => '3',
            'user_end_time' => '2030-01-02 03:04:05'])),
        post('edit-end-time-date', '/admin/user/info', user_form(['user_id' => '3', 'user_name' => 'carolvip', 'group_id' => '3',
            'user_end_time' => '2031-05-06'])),
        post('edit-end-time-numeric', '/admin/user/info', user_form(['user_id' => '3', 'user_name' => 'carolvip', 'group_id' => '3',
            'user_end_time' => '1800000000'])),
        post('edit-end-time-empty', '/admin/user/info', user_form(['user_id' => '3', 'user_name' => 'carolvip', 'group_id' => '3',
            'user_end_time' => ''])),
        post('edit-end-time-hm', '/admin/user/info', user_form(['user_id' => '3', 'user_name' => 'carolvip', 'group_id' => '3',
            'user_end_time' => '2030-01-02 03:04'])),
        post('edit-extra-columns', '/admin/user/info', user_form(['user_points_froze' => '9', 'user_pid' => '5',
            'user_login_num' => '77', 'nosuch' => 'x'])),
        post('name-short', '/admin/user/info', user_form(['user_name' => 'abcde'])),
        post('name-empty', '/admin/user/info', user_form(['user_name' => ''])),
        post('name-six-mb', '/admin/user/info', user_form(['user_name' => '六个汉字名字'])),
        post('name-too-long', '/admin/user/info', user_form(['user_name' => str_repeat('n', 31)])),
        post('points-bad', '/admin/user/info', user_form(['user_points' => '12abc'])),
        post('points-negative', '/admin/user/info', user_form(['user_points' => '-1'])),
        post('new', '/admin/user/info', ['user_id' => '', 'user_name' => 'newuser1', 'user_pwd' => 'secret', 'user_status' => '1',
            'group_id' => '3', 'user_nick_name' => '新人', 'user_end_time' => '1970-01-01 08:00:00', 'user_points' => '5',
            'user_phone' => '13800000000', 'user_qq' => '', 'user_email' => '', 'user_question' => '', 'user_answer' => '']),
        post('new-no-pwd', '/admin/user/info', ['user_id' => '', 'user_name' => 'newuser2', 'user_pwd' => '', 'user_status' => '0',
            'group_id' => '2', 'user_end_time' => '1970-01-01 08:00:00', 'user_points' => '']),
        post('new-short', '/admin/user/info', ['user_id' => '', 'user_name' => 'abc', 'user_pwd' => 'x']),
        post('html', '/admin/user/info', user_form(['user_nick_name' => 'h']), ['ajax' => false]),
    ]);
    $s[] = sc('user-del', [
        get('one', '/admin/user/del', 'ids=5', ['ajax' => true]),
        raw('array', '/admin/user/del', 'ids%5B%5D=4&ids%5B%5D=99'),
        get('empty', '/admin/user/del', '', ['ajax' => true]),
        get('bad', '/admin/user/del', 'ids=abc', ['ajax' => true]),
        get('csv', '/admin/user/del', 'ids=2,3', ['ajax' => true]),
    ]);
    $s[] = sc('user-field', [
        get('off', '/admin/user/field', 'ids=1,2&col=user_status&val=0', ['ajax' => true]),
        get('on-loose', '/admin/user/field', 'ids=1&col=user_status&val=1.0', ['ajax' => true]),
        get('bad-col', '/admin/user/field', 'ids=1&col=user_points&val=1', ['ajax' => true]),
        get('bad-val', '/admin/user/field', 'ids=1&col=user_status&val=2', ['ajax' => true]),
        get('no-ids', '/admin/user/field', 'col=user_status&val=1', ['ajax' => true]),
    ]);
    $s[] = sc('user-reward', [
        get('all', '/admin/user/reward', 'uid=1'),
        get('level-1', '/admin/user/reward', 'uid=1&level=1'),
        get('level-2', '/admin/user/reward', 'uid=1&level=2'),
        get('level-3', '/admin/user/reward', 'uid=1&level=3'),
        get('level-bad', '/admin/user/reward', 'uid=1&level=4'),
        get('wd', '/admin/user/reward', 'uid=1&wd=' . rawurlencode('vip')),
        get('uid-2', '/admin/user/reward', 'uid=2'),
        get('no-uid', '/admin/user/reward'),
        get('uid-text', '/admin/user/reward', 'uid=' . rawurlencode('1<x>')),
    ]);
    $s[] = sc('user-denied', [
        login('editor', 'ed pass'),
        get('data', '/admin/user/data'),
        get('del', '/admin/user/del', 'ids=1', ['ajax' => true]),
        get('reward', '/admin/user/reward', 'uid=1'),
        get('card', '/admin/card/index'),
    ]);
    return $s;
}

function group_form(array $over = [])
{
    return array_merge(['group_id' => '3', 'group_name' => 'VIP会员', 'group_points_day' => '10', 'group_points_week' => '70',
        'group_points_month' => '300', 'group_points_year' => '3600', 'group_status' => '1'], $over);
}

function scenarios_group()
{
    $s = [];
    $s[] = sc('group-list', [
        get('index', '/admin/group/index'),
        get('status-0', '/admin/group/index', 'status=0'),
        get('status-loose', '/admin/group/index', 'status=1.0'),
        get('wd', '/admin/group/index', 'wd=' . rawurlencode('会员')),
        get('wd-entity', '/admin/group/index', 'wd=' . rawurlencode('<b>')),
    ]);
    $s[] = sc('group-info', [
        get('new', '/admin/group/info'),
        get('edit', '/admin/group/info', 'id=3'),
        get('edit-guest', '/admin/group/info', 'id=1'),
        get('edit-empty-popedom', '/admin/group/info', 'id=4'),
        get('missing', '/admin/group/info', 'id=99'),
    ]);
    $base = 'group_id=3&group_name=VIP%E4%BC%9A%E5%91%98&group_points_day=10&group_points_week=70&group_points_month=300'
        . '&group_points_year=3600&group_status=1';
    $s[] = sc('group-save', [
        raw('edit', '/admin/group/info', $base . '&group_type%5B%5D=1&group_type%5B%5D=2&group_type%5B%5D=6'
            . '&group_popedom%5B1%5D%5B1%5D=1&group_popedom%5B1%5D%5B3%5D=3&group_popedom%5B6%5D%5B2%5D=2'
            . '&group_popedom%5B2%5D%5B5%5D=5'),
        raw('edit-no-type', '/admin/group/info', $base),
        raw('edit-points', '/admin/group/info', str_replace('group_points_day=10', 'group_points_day=1.5', $base)
            . '&group_type%5B%5D=1'),
        raw('edit-points-overflow', '/admin/group/info', str_replace('group_points_day=10', 'group_points_day=70000', $base)),
        raw('edit-week-negative', '/admin/group/info', str_replace('group_points_week=70', 'group_points_week=-5', $base)),
        raw('name-empty', '/admin/group/info', str_replace('group_name=VIP%E4%BC%9A%E5%91%98', 'group_name=', $base)),
        raw('name-zero', '/admin/group/info', str_replace('group_name=VIP%E4%BC%9A%E5%91%98', 'group_name=0', $base)),
        raw('popedom-scalar', '/admin/group/info', $base . '&group_popedom=abc'),
        raw('new', '/admin/group/info', 'group_id=&group_name=%E6%96%B0%E7%BB%84&group_type%5B%5D=5'
            . '&group_popedom%5B5%5D%5B1%5D=1&group_popedom%5B5%5D%5B2%5D=2'),
        raw('new-no-id', '/admin/group/info', 'group_name=%E6%97%A0id%E7%BB%84&group_status=0&group_points_day=3'),
        raw('new-status-0', '/admin/group/info', 'group_id=&group_name=%E5%81%9C%E7%94%A8%E7%BB%84&group_status=0'),
        raw('edit-guest', '/admin/group/info', 'group_id=1&group_name=%E6%B8%B8%E5%AE%A2%E6%94%B9&group_type%5B%5D=1'
            . '&group_popedom%5B1%5D%5B2%5D=2'),
        raw('html', '/admin/group/info', $base . '&group_type%5B%5D=2', ['ajax' => false]),
        // group_type 不是数组（PHP 8 join() 报错 500；PHP 7.3 告警后写成 ',,'）；放在最后，之后新旧的行不同
        raw('type-scalar', '/admin/group/info', $base . '&group_type=7'),
    ]);
    $s[] = sc('group-del', [
        get('has-user', '/admin/group/del', 'ids=3', ['ajax' => true]),
        get('reg-group-blank', '/admin/group/del', 'ids=5,', ['ajax' => true]),
        get('empty', '/admin/group/del', '', ['ajax' => true]),
        get('bad', '/admin/group/del', 'ids=abc', ['ajax' => true]),
        get('no-user', '/admin/group/del', 'ids=5', ['ajax' => true]),
        raw('array', '/admin/group/del', 'ids%5B%5D=4&ids%5B%5D=98', ['ajax' => true]),
        get('guest', '/admin/group/del', 'ids=1', ['ajax' => true]),
    ]);
    $s[] = sc('group-field', [
        get('off', '/admin/group/field', 'ids=3&col=group_status&val=0', ['ajax' => true]),
        get('guest-off', '/admin/group/field', 'ids=1,2&col=group_status&val=0', ['ajax' => true]),
        get('bad-col', '/admin/group/field', 'ids=3&col=group_name&val=1', ['ajax' => true]),
        get('bad-val', '/admin/group/field', 'ids=3&col=group_status&val=x', ['ajax' => true]),
    ]);
    return $s;
}

function scenarios_card()
{
    $s = [];
    $s[] = sc('card-list', [
        get('index', '/admin/card/index'),
        get('sale-0', '/admin/card/index', 'sale_status=0'),
        get('use-1', '/admin/card/index', 'use_status=1'),
        get('use-loose', '/admin/card/index', 'use_status=1.0'),
        get('wd', '/admin/card/index', 'wd=' . rawurlencode('4444')),
        get('wd-like', '/admin/card/index', 'wd=' . rawurlencode('_')),
        get('time-empty', '/admin/card/index', 'time='),
        get('time-last', '/admin/card/index', 'time=1'),
        get('time-today', '/admin/card/index', 'time=0'),
        get('time-week', '/admin/card/index', 'time=7'),
        get('time-month', '/admin/card/index', 'time=30'),
        get('time-bad', '/admin/card/index', 'time=abc'),
        get('search-form', '/admin/card/index', 'sale_status=&use_status=&time=&wd='),
        get('page2', '/admin/card/index', 'page=2&limit=3'),
        get('export', '/admin/card/index', 'sale_status=&use_status=&time=&wd=&export=1'),
        get('export-filter', '/admin/card/index', 'use_status=0&export=1'),
        get('export-loose', '/admin/card/index', 'export=1.0&page=3&limit=1'),
    ]);
    $s[] = sc('card-last-empty', [
        get('time-last', '/admin/card/index', 'time=1'),
    ], ['seed' => array_merge(base_seed(), ['mac_card' => []])]);
    $s[] = sc('card-info', [
        get('form', '/admin/card/info'),
        get('form-id', '/admin/card/info', 'id=1'),
        post('no-num', '/admin/card/info', ['num' => '', 'money' => '10', 'point' => '100']),
        post('no-money', '/admin/card/info', ['num' => '3', 'money' => '0', 'point' => '100']),
        post('no-point', '/admin/card/info', ['num' => '3', 'money' => '10']),
        post('negative', '/admin/card/info', ['num' => '-2', 'money' => '10', 'point' => '100']),
        post('overflow', '/admin/card/info', ['num' => '1', 'money' => '70000', 'point' => '100']),
        post('html', '/admin/card/info', ['num' => '', 'money' => '1', 'point' => '1'], ['ajax' => false]),
    ]);
    // 生成卡密（随机，复放时只比较条数、长度与字符集）
    $s[] = sc('card-make', [
        post('num-letter', '/admin/card/info', ['num' => '3', 'money' => '10', 'point' => '100', 'role_no' => 'num',
            'role_pwd' => 'letter']),
        post('mixed', '/admin/card/info', ['num' => '2abc', 'money' => '5.9', 'point' => '50', 'role_no' => '', 'role_pwd' => '']),
        post('role-other', '/admin/card/info', ['num' => '1', 'money' => '1', 'point' => '1', 'role_no' => 'xyz']),
    ]);
    $s[] = sc('card-del', [
        get('one', '/admin/card/del', 'ids=2', ['ajax' => true]),
        raw('array', '/admin/card/del', 'ids%5B%5D=3&ids%5B%5D=99'),
        get('empty', '/admin/card/del', '', ['ajax' => true]),
        get('bad', '/admin/card/del', 'ids=abc', ['ajax' => true]),
        get('all-2', '/admin/card/del', 'ids=1&all=2', ['ajax' => true]),
        get('all-no-ids', '/admin/card/del', 'all=1', ['ajax' => true]),
        get('all', '/admin/card/del', 'ids=1&all=1', ['ajax' => true]),
    ]);
    return $s;
}

function scenarios_order()
{
    $s = [];
    $s[] = sc('order-list', [
        get('index', '/admin/order/index'),
        get('status-0', '/admin/order/index', 'status=0'),
        get('status-1', '/admin/order/index', 'status=1'),
        get('status-empty', '/admin/order/index', 'status='),
        get('status-loose', '/admin/order/index', 'status=1.0'),
        get('uid', '/admin/order/index', 'uid=2'),
        get('wd', '/admin/order/index', 'wd=' . rawurlencode('0101')),
        get('wd-entity', '/admin/order/index', 'wd=' . rawurlencode('<i>')),
        get('page2', '/admin/order/index', 'page=2&limit=2'),
    ]);
    $s[] = sc('order-del', [
        get('one', '/admin/order/del', 'ids=1', ['ajax' => true]),
        get('empty', '/admin/order/del', '', ['ajax' => true]),
        get('bad', '/admin/order/del', 'ids=1x', ['ajax' => true]),
        get('all', '/admin/order/del', 'ids=1&all=1', ['ajax' => true]),
    ]);
    return $s;
}

function scenarios_cash()
{
    $s = [];
    $s[] = sc('cash-list', [
        get('index', '/admin/cash/index'),
        get('status-0', '/admin/cash/index', 'status=0'),
        get('status-1', '/admin/cash/index', 'status=1'),
        get('uid', '/admin/cash/index', 'uid=2'),
        get('wd', '/admin/cash/index', 'wd=' . rawurlencode('3333')),
        get('wd-entity', '/admin/cash/index', 'wd=' . rawurlencode('<&>')),
        get('page2', '/admin/cash/index', 'page=2&limit=2'),
    ]);
    $s[] = sc('cash-audit', [
        get('one', '/admin/cash/audit', 'ids=1', ['ajax' => true]),
        get('empty', '/admin/cash/audit', '', ['ajax' => true]),
        get('bad', '/admin/cash/audit', 'ids=a', ['ajax' => true]),
        get('no-user', '/admin/cash/audit', 'ids=5', ['ajax' => true]),
        // 会员 1 的冻结积分（0）不够扣提现 4 的 40（旧版先把提现标为已审核，再扣成负数时 MySQL 报错）；放在最后，之后新旧的行不同
        raw('array', '/admin/cash/audit', 'ids%5B%5D=4&ids%5B%5D=98', ['ajax' => true]),
    ]);
    $s[] = sc('cash-audit-again', [
        // 已审核的提现再次审核（旧版对选中的全部行再写审核时间、并再次扣减冻结积分）
        get('audited', '/admin/cash/audit', 'ids=2', ['ajax' => true]),
    ]);
    $s[] = sc('cash-audit-froze-short', [
        // 会员 4 的冻结积分（5）够扣提现 3 的 5；先在后台把冻结积分改成 1 再审核（旧版扣成负数，MySQL 报错）
        ['label' => 'froze-1', 'sql' => 'UPDATE mac_user SET user_points_froze = 1 WHERE user_id = 4'],
        get('short', '/admin/cash/audit', 'ids=3', ['ajax' => true]),
    ]);
    $s[] = sc('cash-del', [
        get('pending', '/admin/cash/del', 'ids=1', ['ajax' => true]),
        get('audited', '/admin/cash/del', 'ids=2', ['ajax' => true]),
        get('empty', '/admin/cash/del', '', ['ajax' => true]),
        get('bad', '/admin/cash/del', 'ids=x', ['ajax' => true]),
        get('no-user', '/admin/cash/del', 'ids=5', ['ajax' => true]),
        get('all', '/admin/cash/del', 'ids=1&all=1', ['ajax' => true]),
    ]);
    $s[] = sc('cash-del-froze-short', [
        ['label' => 'froze-0', 'sql' => 'UPDATE mac_user SET user_points_froze = 0 WHERE user_id = 1'],
        get('short', '/admin/cash/del', 'ids=4', ['ajax' => true]),
    ]);
    return $s;
}

function scenarios_logs()
{
    $s = [];
    $s[] = sc('plog-list', [
        get('index', '/admin/plog/index'),
        get('type', '/admin/plog/index', 'type=4'),
        get('type-loose', '/admin/plog/index', 'type=04'),
        get('uid', '/admin/plog/index', 'uid=1'),
        get('page2', '/admin/plog/index', 'page=2&limit=4'),
    ]);
    $s[] = sc('plog-del', [
        get('one', '/admin/plog/del', 'ids=1', ['ajax' => true]),
        // 列表复选框的值是未定义的 ulog_id（旧模板缺陷）：提交的是空串
        raw('checkbox-blank', '/admin/plog/del', 'ids%5B%5D=&ids%5B%5D=', ['ajax' => true]),
        get('empty', '/admin/plog/del', '', ['ajax' => true]),
        get('all', '/admin/plog/del', 'ids=1&all=1', ['ajax' => true]),
    ]);
    $s[] = sc('ulog-list', [
        get('index', '/admin/ulog/index'),
        get('mid', '/admin/ulog/index', 'mid=1'),
        get('type', '/admin/ulog/index', 'type=2'),
        get('uid', '/admin/ulog/index', 'uid=2'),
        get('page2', '/admin/ulog/index', 'page=2&limit=4'),
    ]);
    $s[] = sc('ulog-del', [
        get('one', '/admin/ulog/del', 'ids=1', ['ajax' => true]),
        raw('array', '/admin/ulog/del', 'ids%5B%5D=2&ids%5B%5D=3', ['ajax' => true]),
        get('bad', '/admin/ulog/del', 'ids=-1', ['ajax' => true]),
        get('all', '/admin/ulog/del', 'ids=1&all=1', ['ajax' => true]),
    ]);
    $s[] = sc('visit-list', [
        get('index', '/admin/visit/index'),
        get('uid', '/admin/visit/index', 'uid=1'),
        get('time-empty', '/admin/visit/index', 'time='),
        get('time-today', '/admin/visit/index', 'time=0'),
        get('time-week', '/admin/visit/index', 'time=7'),
        get('time-bad', '/admin/visit/index', 'time=x'),
        get('wd', '/admin/visit/index', 'wd=' . rawurlencode('a.test')),
        get('wd-like', '/admin/visit/index', 'wd=' . rawurlencode('a_t')),
        get('wd-http', '/admin/visit/index', 'wd=' . rawurlencode('http://a.test')),
        get('page2', '/admin/visit/index', 'page=2&limit=3'),
    ]);
    $s[] = sc('visit-del', [
        get('one', '/admin/visit/del', 'ids=1', ['ajax' => true]),
        raw('checkbox-blank', '/admin/visit/del', 'ids%5B%5D=', ['ajax' => true]),
        get('all', '/admin/visit/del', 'ids=1&all=1', ['ajax' => true]),
    ]);
    return $s;
}

// ====================================================================== 引擎
function generate($repo, $pdo, $phpCgi, $work, $host, $port, $db, $user, $password)
{
    $out = [];
    $only = getenv('GOLDEN_ONLY');
    $prefixes = $only !== false && $only !== '' ? explode(',', $only) : null;
    $base = deep_merge(include $repo . '/application/extra/maccms.php', base_config());
    $types = column_types($pdo);
    foreach (scenarios($repo) as $sc) {
        if ($prefixes !== null) {
            $hit = false;
            foreach ($prefixes as $p) {
                $hit = $hit || strpos($sc['label'], $p) === 0;
            }
            if (!$hit) {
                continue;
            }
        }
        $conf = $work . '/conf';
        $runtime = $work . '/runtime';
        $site = $work . '/site';
        rm_tree($conf);
        rm_tree($runtime);
        rm_tree($site);
        @mkdir($runtime, 0777, true);
        @mkdir($site . '/static/data', 0777, true);
        @mkdir($site . '/static/ueditor', 0777, true);
        copy($repo . '/static/data/pinyin.dat', $site . '/static/data/pinyin.dat');
        copy($repo . '/static/ueditor/config.json', $site . '/static/ueditor/config.json');
        @mkdir($site . '/upload', 0777, true);
        foreach ($sc['files'] as $rel => $content) {
            @mkdir(dirname($site . '/' . $rel), 0777, true);
            file_put_contents($site . '/' . $rel, $content);
            touch($site . '/' . $rel, 1700000000);
        }
        $over = $sc['config'];
        $maccms = deep_merge($base, $over);
        write_conf($repo, $conf, $maccms, $host, $port, $db, $user, $password);
        seed($pdo, $sc['seed']);
        $rows0 = read_all($pdo, $types);
        $files0 = list_files($site . '/upload');
        $last = $rows0;
        $lastFiles = $files0;
        $jar = [];
        $token = null;
        $steps = [];
        foreach ($sc['steps'] as $st) {
            if (isset($st['sql'])) {
                $pdo->exec($st['sql']);
                $now = read_all($pdo, $types);
                $steps[] = ['label' => $st['label'], 'sql' => $st['sql'], 'tables' => (object)changed($last, $now)];
                $last = $now;
                continue;
            }
            $headers = $st['headers'];
            if ($st['ajax']) {
                $headers['X-Requested-With'] = 'XMLHttpRequest';
            }
            if ($jar) {
                $pairs = [];
                foreach ($jar as $k => $v) {
                    $pairs[] = $k . '=' . $v;
                }
                $headers['Cookie'] = implode('; ', $pairs);
            }
            $contentType = 'application/x-www-form-urlencoded';
            if ($st['multipart'] !== null) {
                list($post, $contentType) = multipart($st['multipart']);
            } elseif (is_array($st['post'])) {
                $post = isset($st['post']['__raw__']) ? $st['post']['__raw__'] : http_build_query($st['post']);
            } else {
                $post = $st['post'];
            }
            $post = str_replace(['{token}', '%7Btoken%7D'], rawurlencode((string)$token), $post);
            $req = ['path' => $st['path'], 'query' => $st['query'], 'method' => $st['method'], 'post' => $post,
                'contentType' => $contentType, 'headers' => $headers, 'remote' => '127.0.0.1'];
            $started = time();
            list($status, $respHeaders, $setCookies, $body) = run_request($repo, $phpCgi, $conf, $runtime, $site, $req);
            $after = time();
            $now = read_all($pdo, $types);
            $files = list_files($site . '/upload');
            $tables = changed($last, $now);
            $last = $now;
            foreach ($setCookies as $c) {
                $pair = explode(';', $c, 2)[0];
                list($k, $v) = array_pad(explode('=', $pair, 2), 2, '');
                if ($v === 'deleted' || stripos($c, 'Max-Age=0') !== false) {
                    unset($jar[$k]);
                } else {
                    $jar[$k] = $v;
                }
            }
            if (preg_match('/name="__token__"(?: type="hidden")? value="([0-9a-f]+)"/', $body, $tm)) {
                $token = $tm[1];
            }
            $steps[] = ['label' => $st['label'], 'request' => ['method' => $st['method'], 'path' => $st['path'],
                'query' => $st['query'], 'post' => $st['multipart'] !== null ? null : $post,
                'multipart' => $st['multipart'] !== null ? multipart_describe($st['multipart']) : null,
                'ajax' => $st['ajax'], 'headers' => (object)$st['headers']],
                'started' => $started, 'after' => $after, 'status' => $status,
                'contentType' => isset($respHeaders['content-type']) ? $respHeaders['content-type'] : null,
                'location' => isset($respHeaders['location']) ? $respHeaders['location'] : null,
                'disposition' => isset($respHeaders['content-disposition']) ? $respHeaders['content-disposition'] : null,
                'setCookies' => $setCookies, 'body' => !$st['body'] ? null : base64_encode($body),
                'tables' => (object)$tables, 'files' => $files === $lastFiles ? null : (object)$files];
            $lastFiles = $files;
        }
        $out[] = ['label' => $sc['label'], 'config' => (object)$over,
            'maccmsPhp' => '<?php return ' . var_export($maccms, true) . ';', 'seed' => encode_seed($sc['seed'], $types),
            'filesSeed' => (object)array_map('base64_encode', $sc['files']), 'files0' => (object)$files0, 'rows0' => $rows0,
            'steps' => $steps];
    }
    return json_encode(['generator' => 'backend/src/test/php/admin_member_golden.php', 'php' => PHP_VERSION,
            'timezone' => 'PRC', 'entry' => ENTRY, 'host' => 'www.example.test', 'remote' => '127.0.0.1',
            'baseConfig' => base_config(), 'admins' => admins(), 'scenarios' => $out],
            JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT | JSON_INVALID_UTF8_SUBSTITUTE) . "\n";
}

/** 每张表的列名 → 是否数值列（information_schema，一次性库）。 */
function column_types($pdo)
{
    $out = [];
    $rows = $pdo->query("SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
        . "ORDER BY TABLE_NAME, ORDINAL_POSITION")->fetchAll(PDO::FETCH_ASSOC);
    foreach ($rows as $r) {
        $out[$r['TABLE_NAME']][$r['COLUMN_NAME']] = in_array($r['DATA_TYPE'], ['tinyint', 'smallint', 'mediumint', 'int', 'bigint'], true)
            ? 'int' : ($r['DATA_TYPE'] === 'decimal' ? 'decimal' : 'text');
    }
    return $out;
}

/** 清空跟踪的表并复位自增计数（一次性库上的测试夹具），写入场景的行与管理员。 */
function seed($pdo, array $seed)
{
    foreach (array_merge(array_keys(TABLES), ['mac_admin']) as $t) {
        $pdo->exec("DELETE FROM $t");
        $pdo->exec("ALTER TABLE $t AUTO_INCREMENT = 1");
    }
    $all = $seed;
    $all['mac_admin'] = admins();
    foreach ($all as $table => $rows) {
        foreach ($rows as $row) {
            $cols = array_keys($row);
            $stmt = $pdo->prepare("INSERT INTO $table (" . implode(', ', $cols) . ') VALUES ('
                . implode(', ', array_fill(0, count($cols), '?')) . ')');
            $stmt->execute(array_values($row));
        }
    }
}

function encode_seed(array $seed, $types)
{
    $out = [];
    foreach ($seed as $table => $rows) {
        $out[$table] = [];
        foreach ($rows as $row) {
            $out[$table][] = encode_row($table, $row, $types);
        }
    }
    return (object)$out;
}

function encode_row($table, array $row, $types)
{
    $r = [];
    foreach ($row as $c => $v) {
        $kind = $types[$table][$c];
        $r[$c] = $kind === 'int' ? (int)$v : ($kind === 'decimal' ? (string)$v : bin2hex((string)$v));
    }
    return $r;
}

/** 跟踪的各表的全部行（主键顺序；整数列为整数，decimal 为字符串，其余十六进制）。 */
function read_all($pdo, $types)
{
    $out = [];
    foreach (TABLES as $t => $pk) {
        $rows = [];
        foreach ($pdo->query("SELECT * FROM $t ORDER BY $pk")->fetchAll(PDO::FETCH_ASSOC) as $row) {
            $rows[] = encode_row($t, $row, $types);
        }
        $out[$t] = $rows;
    }
    return $out;
}

function changed($before, $after)
{
    $out = [];
    foreach ($after as $t => $rows) {
        if ($before[$t] !== $rows) {
            $out[$t] = $rows;
        }
    }
    return $out;
}

/** upload/ 下的文件（相对站点根的路径 → 大小与 sha256）。 */
function list_files($dir)
{
    $out = [];
    if (!is_dir($dir)) {
        return $out;
    }
    $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($dir, FilesystemIterator::SKIP_DOTS));
    $base = dirname($dir);
    foreach ($it as $f) {
        if ($f->isFile()) {
            $rel = str_replace('\\', '/', substr($f->getPathname(), strlen($base) + 1));
            $out[$rel] = ['size' => $f->getSize(), 'sha256' => hash_file('sha256', $f->getPathname())];
        }
    }
    ksort($out);
    return $out;
}

/** multipart/form-data 正文：$parts = [[name, value] 或 [name, filename, content, type]]。 */
function multipart(array $parts)
{
    $boundary = '----goldenboundary7d3a';
    $body = '';
    foreach ($parts as $p) {
        $body .= "--$boundary\r\n";
        if (count($p) === 2) {
            $body .= "Content-Disposition: form-data; name=\"{$p[0]}\"\r\n\r\n{$p[1]}\r\n";
        } else {
            $body .= "Content-Disposition: form-data; name=\"{$p[0]}\"; filename=\"{$p[1]}\"\r\nContent-Type: {$p[3]}\r\n\r\n{$p[2]}\r\n";
        }
    }
    $body .= "--$boundary--\r\n";
    return [$body, 'multipart/form-data; boundary=' . $boundary];
}

function multipart_describe(array $parts)
{
    $out = [];
    foreach ($parts as $p) {
        $out[] = count($p) === 2 ? ['name' => $p[0], 'value' => $p[1]]
            : ['name' => $p[0], 'filename' => $p[1], 'content' => base64_encode($p[2]), 'type' => $p[3]];
    }
    return $out;
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

/** 以 php-cgi 执行一个后台请求。返回 [status, headers(小写名 => 值), setCookies, body]。 */
function run_request($repo, $phpCgi, $conf, $runtime, $site, $req)
{
    $query = $req['query'];
    $method = $req['method'];
    $post = $req['post'];
    $entryDir = dirname($runtime) . DIRECTORY_SEPARATOR . 'entry';
    $entryFile = $entryDir . DIRECTORY_SEPARATOR . '_admin.php';
    if (!is_file($entryFile)) {
        @mkdir($entryDir, 0777, true);
        file_put_contents($entryFile, '<?php require ' . var_export(__DIR__ . DIRECTORY_SEPARATOR . 'admin_content_child.php', true) . ';');
    }
    $env = [
        'GOLDEN_CONF_PATH' => $conf,
        'GOLDEN_RUNTIME_PATH' => $runtime,
        'GOLDEN_SESSION_PATH' => $runtime,
        'GOLDEN_SITE_ROOT' => $site,
        'REDIRECT_STATUS' => '200',
        'GATEWAY_INTERFACE' => 'CGI/1.1',
        'SERVER_PROTOCOL' => 'HTTP/1.1',
        'SERVER_SOFTWARE' => 'golden',
        'SERVER_NAME' => 'www.example.test',
        'SERVER_PORT' => '80',
        'HTTP_HOST' => 'www.example.test',
        'REQUEST_METHOD' => $method,
        'SCRIPT_FILENAME' => $entryFile,
        'SCRIPT_NAME' => ENTRY,
        'PHP_SELF' => ENTRY . $req['path'],
        'REQUEST_URI' => ENTRY . $req['path'] . ($query === '' ? '' : '?' . $query),
        'QUERY_STRING' => $query,
        'REMOTE_ADDR' => $req['remote'],
        'REMOTE_PORT' => '50000',
        'DOCUMENT_ROOT' => $entryDir,
        'SystemRoot' => getenv('SystemRoot') ?: 'C:\\Windows',
        'TEMP' => $runtime,
        'TMP' => $runtime,
    ];
    if ($req['path'] !== '') {
        $env['PATH_INFO'] = $req['path'];
    }
    if ($method === 'POST') {
        $env['CONTENT_TYPE'] = $req['contentType'];
        $env['CONTENT_LENGTH'] = (string)strlen($post);
    }
    foreach ($req['headers'] as $name => $value) {
        $env['HTTP_' . strtoupper(str_replace('-', '_', $name))] = $value;
    }
    $cmd = escapeshellarg($phpCgi) . ' -d session.save_path=' . escapeshellarg($runtime)
        . ' -d upload_tmp_dir=' . escapeshellarg($runtime)
        . ' -d display_errors=0 -d error_reporting=0 -d session.gc_probability=0';
    $proc = proc_open($cmd, [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['pipe', 'w']], $pipes, $site, $env,
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
