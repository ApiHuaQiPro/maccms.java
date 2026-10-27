<?php
/*
 * B17（MACC-41）采集配置管理的 PHP 黄金样本（契约见 docs/architecture/25-b17-collect-config.md 第 8 节）。
 *
 * 范围：资源站采集源（admin/controller/Collect.php 的 index、info、del、union、bind、clearbind）与自定义规则节点（admin/controller/Cj.php
 * 的 index、info、del、export、import）。每个场景是一串真实的后台请求：经 admin_system_child.php（B15 的子进程入口，原样复用）以 php-cgi
 * 跑完整的 ThinkPHP 路由、admin/controller/Base 与控制器；站点是本次运行在临时目录里复制出来的副本（application/、thinkphp/、vendor/），
 * 所以 collect/bind 写的 application/extra/bind.php 只落在副本里。每个场景开始前把副本的 application/extra/ 与 runtime/ 还原、清空跟踪的
 * 四张表并复位自增计数（一次性库上的测试夹具：DELETE 后 ALTER TABLE … AUTO_INCREMENT = 1，新版的复放测试做同样的事），再写入场景的行与
 * 预置的 bind.php。每一步记录状态码、Content-Type、Location、Content-Disposition、正文（base64）、这一步之后 bind.php 的变化，以及四张表的
 * 全部行（文本一律十六进制）。表单步骤（form）同 B15：先 GET 页面，按 jQuery serialize() 取第一个表单的字段，改几个字段后 POST。
 *
 * 不调用会访问网络的动作：collect/test（旧版按 collect_url 发起 HTTP 请求，E01 不实施）与 collect/api、cj/col_url 等（B18～B20）。
 *
 * 流程：外部管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入 install.sql → 逐个场景执行 → 输出 JSON → 删除一次性库与临时目录。
 * 不修改任何仓库文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置。
 *
 * 管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；另需 PHP_CGI 与 GOLDEN_VENDOR_DIR
 * （备份 zip 里解出的 vendor/ 目录，见根目录 AGENTS.md "本地运行与测试"第 1 步）。
 * 用法（仓库根目录，PHP 8.0 与 7.3 各一份）：
 *   PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe backend/src/test/php/admin_collect_golden.php \
 *       > backend/src/test/resources/admin/collect/php80-golden.json
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
$vendor = env_required('GOLDEN_VENDOR_DIR');
if (!is_file($vendor . '/autoload.php')) {
    fwrite(STDERR, "GOLDEN_VENDOR_DIR 不是完整的 vendor 目录\n");
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

const ENTRY = '/_admin.php';
const HOST = 'www.example.test';
/** 跟踪的表与主键（顺序即输出顺序）。 */
const TABLES = ['mac_collect' => 'collect_id', 'mac_cj_node' => 'nodeid', 'mac_cj_content' => 'id', 'mac_cj_history' => 'md5'];

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$work = sys_get_temp_dir() . DIRECTORY_SEPARATOR . 'maccms-b17-golden-' . bin2hex(random_bytes(4));
$exit = 0;
try {
    $pdo->exec("USE `$dbName`");
    foreach (sql_statements($repo . '/application/install/sql/install.sql') as $sql) {
        $pdo->exec($sql);
    }
    base_seed($pdo);
    echo generate($repo, $vendor, $pdo, $phpCgi, $work, $host, $port, $dbName, $adminUser, $adminPassword);
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

// ====================================================================== 数据

/** 所有场景共用：管理员（1 超级管理员、2 只有采集源列表权限）与分类（collect/bind 取分类名）。 */
function base_seed($pdo)
{
    $pdo->exec("SET SESSION sql_mode = ''");
    $pdo->exec("INSERT INTO mac_admin (admin_id, admin_name, admin_pwd, admin_status, admin_auth) VALUES "
        . "(1, 'admin', '" . md5('admin888') . "', 1, ''), (2, 'editor', '" . md5('ed pass') . "', 1, ',collect/index,cj/index,')");
    $pdo->exec("INSERT INTO mac_type (type_id, type_name, type_en, type_sort, type_mid, type_pid, type_status) VALUES "
        . "(1, '电影', 'dianying', 1, 1, 0, 1), (2, '连续剧', 'lianxuju', 2, 1, 0, 1), (6, 'A&B<型>\"', 'ab', 3, 2, 0, 0)");
    $pdo->exec("SET SESSION sql_mode = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION'");
}

function collects()
{
    return [
        ['collect_id' => 1, 'collect_name' => '资源一', 'collect_url' => 'http://res.example.test/api.php/provide/vod/',
            'collect_type' => 1, 'collect_mid' => 1, 'collect_appid' => '', 'collect_appkey' => '', 'collect_param' => '&ct=1',
            'collect_filter' => 0, 'collect_filter_from' => '', 'collect_opt' => 0, 'collect_sync_pic_opt' => 0],
        ['collect_id' => 2, 'collect_name' => 'json源<b>', 'collect_url' => 'https://json.example.test/api?ac=list&x=中文 空格',
            'collect_type' => 2, 'collect_mid' => 2, 'collect_appid' => 'app', 'collect_appkey' => 'key', 'collect_param' => 'a=b&c="d"',
            'collect_filter' => 2, 'collect_filter_from' => 'm3u8,mp4', 'collect_opt' => 1, 'collect_sync_pic_opt' => 1],
        ['collect_id' => 3, 'collect_name' => 'Actor"Q"&amp;', 'collect_url' => 'http://a.example.test/provide/?k=<x>',
            'collect_type' => 2, 'collect_mid' => 8, 'collect_appid' => '', 'collect_appkey' => '', 'collect_param' => '',
            'collect_filter' => 0, 'collect_filter_from' => '', 'collect_opt' => 2, 'collect_sync_pic_opt' => 2],
        ['collect_id' => 4, 'collect_name' => '网址', 'collect_url' => 'http://w.example.test/', 'collect_type' => 1,
            'collect_mid' => 11, 'collect_appid' => '', 'collect_appkey' => '', 'collect_param' => '', 'collect_filter' => 3,
            'collect_filter_from' => '', 'collect_opt' => 0, 'collect_sync_pic_opt' => 0],
    ];
}

function node($id, array $over = [])
{
    return array_merge(['nodeid' => $id, 'name' => '节点' . $id, 'lastdate' => 0, 'sourcecharset' => 'UTF-8', 'sourcetype' => 1,
        'urlpage' => 'http://cj.example.test/list_(*).html', 'pagesize_start' => 1, 'pagesize_end' => 3, 'page_base' => '',
        'par_num' => 1, 'url_contain' => 'show', 'url_except' => 'ad', 'url_start' => '<ul class="list">', 'url_end' => '</ul>',
        'title_rule' => '<h1>[内容]</h1>', 'title_html_rule' => '', 'type_rule' => '', 'type_html_rule' => '',
        'content_rule' => '<div id="c">[内容]</div>', 'content_html_rule' => "<a([^>]*)>(.*)</a>[|]\n<script([^>]*)>(.*)</script>[|]",
        'content_page_start' => '', 'content_page_end' => '', 'content_page_rule' => 1, 'content_page' => 1, 'content_nextpage' => '',
        'down_attachment' => 0, 'watermark' => 0, 'coll_order' => 2, 'customize_config' => '', 'program_config' => '', 'mid' => 1], $over);
}

function nodes()
{
    return [
        node(1, ['name' => '电影规则', 'lastdate' => 1700000000,
            'customize_config' => json_encode([['name' => '导演', 'en_name' => 'director', 'rule' => '<p>导演：[内容]</p>',
                'html_rule' => '<a([^>]*)>(.*)</a>[|]'], ['name' => '主演"x"', 'en_name' => 'actor', 'rule' => '', 'html_rule' => '']],
                JSON_FORCE_OBJECT),
            'program_config' => json_encode(['map' => ['vod_name' => 'title', 'vod_director' => 'director'],
                'funcs' => ['vod_name' => '', 'vod_director' => 'trim']])]),
        node(2, ['name' => '文章<b>规则</b>', 'sourcetype' => 2, 'mid' => 2, 'sourcecharset' => 'GBK',
            'urlpage' => "http://cj.example.test/a.html\r\nhttp://cj.example.test/b.html", 'content_page_rule' => 2,
            'content_nextpage' => '下一页', 'coll_order' => 1, 'content_page' => 0]),
        node(3, ['name' => '单页', 'sourcetype' => 3, 'sourcecharset' => 'BIG5', 'urlpage' => 'http://cj.example.test/one.html',
            'lastdate' => 1600000000]),
    ];
}

function contents()
{
    return [
        ['id' => 1, 'nodeid' => 1, 'status' => 1, 'url' => 'http://cj.example.test/show/1.html', 'title' => '一', 'data' => ''],
        ['id' => 2, 'nodeid' => 1, 'status' => 2, 'url' => 'http://cj.example.test/show/2.html', 'title' => '二',
            'data' => '{"title":"二"}'],
        ['id' => 3, 'nodeid' => 2, 'status' => 1, 'url' => 'http://cj.example.test/show/3.html', 'title' => '三', 'data' => ''],
    ];
}

function histories()
{
    $out = [];
    foreach (['http://cj.example.test/show/1.html', 'http://cj.example.test/show/2.html', 'http://cj.example.test/show/3.html',
                 'http://other.example.test/x.html'] as $u) {
        $out[] = ['md5' => md5($u)];
    }
    return $out;
}

function all_seed()
{
    return ['mac_collect' => collects(), 'mac_cj_node' => nodes(), 'mac_cj_content' => contents(), 'mac_cj_history' => histories()];
}

/** 预置的 bind.php：两个资源站（按 md5(采集地址)）各绑定几个远程分类，另有一个数字键与一个非绑定格式的键。 */
function bind_file()
{
    $a = md5('http://res.example.test/api.php/provide/vod/');
    $b = md5('https://json.example.test/api?ac=list&x=中文 空格');
    return "<?php\nreturn " . var_export([$a . '_1' => 1, $a . '_2' => 2, $b . '_5' => 6, $b . '_7' => 0, 15 => 2, 'x_y' => 1], true) . ';';
}

// ====================================================================== 场景

function req($label, $method, $path, $query = '', $post = '', array $opt = [])
{
    return ['label' => $label, 'method' => $method, 'path' => $path, 'query' => $query, 'post' => $post,
        'ajax' => !isset($opt['ajax']) || $opt['ajax'], 'headers' => isset($opt['headers']) ? $opt['headers'] : []];
}

function get_page($label, $path, $query = '')
{
    return req($label, 'GET', $path, $query, '', ['ajax' => false]);
}

function form_step($label, $path, array $mods = [], array $opt = [])
{
    return ['label' => $label, 'form' => $path, 'query' => isset($opt['query']) ? $opt['query'] : '', 'mods' => $mods,
        'action' => isset($opt['action']) ? $opt['action'] : $path, 'formIndex' => isset($opt['formIndex']) ? $opt['formIndex'] : 0];
}

function raw_post($label, $path, $raw, array $opt = [])
{
    return req($label, 'POST', $path, isset($opt['query']) ? $opt['query'] : '', ['__raw__' => $raw], $opt);
}

function upload_step($label, $path, $field, $filename, $content, array $fields = [], $query = '')
{
    return ['label' => $label, 'upload' => $path, 'field' => $field, 'filename' => $filename, 'content' => $content,
        'fields' => $fields, 'query' => $query];
}

function login_steps()
{
    return [req('login', 'POST', '/admin/index/login', '', ['admin_name' => 'admin', 'admin_pwd' => 'admin888'])];
}

function set($name, $value)
{
    return ['set', $name, $value];
}

function remove($name)
{
    return ['remove', $name];
}

function add($name, $value)
{
    return ['add', $name, $value];
}

function sc($label, array $steps, array $opt = [])
{
    return array_merge(['label' => $label, 'steps' => array_merge(login_steps(), $steps), 'seed' => all_seed(),
        'bind' => bind_file()], $opt);
}

function scenarios()
{
    $a = md5('http://res.example.test/api.php/provide/vod/');
    $b = md5('https://json.example.test/api?ac=list&x=中文 空格');
    $node = base64_encode(json_encode(array_merge(node(9), ['name' => '导入的规则', 'lastdate' => 1650000000])));
    $nested = base64_encode(json_encode(['name' => '数组值', 'sourcecharset' => 'UTF-8', 'url_contain' => ['a', 'b']]));
    return [
        // ------------------------------------------------------------------ 资源站采集源（mac_collect）
        sc('collect-list', [
            get_page('index', '/admin/collect/index'),
            get_page('page2', '/admin/collect/index', 'page=2&limit=2'),
            get_page('limit-bad', '/admin/collect/index', 'page=abc&limit=0'),
            get_page('union', '/admin/collect/union'),
        ]),
        sc('collect-list-empty', [get_page('index', '/admin/collect/index')], ['seed' => ['mac_collect' => []]]),
        sc('collect-info', [
            get_page('new', '/admin/collect/info'),
            get_page('edit', '/admin/collect/info', 'id=2'),
            get_page('edit-actor', '/admin/collect/info', 'id=3'),
            get_page('missing', '/admin/collect/info', 'id=99'),
        ]),
        sc('collect-save', [
            form_step('add', '/admin/collect/info', [set('collect_name', '新资源 "站"'), set('collect_url', 'http://new.example.test/api.php'),
                set('collect_param', '&h=24'), set('collect_type', '1'), set('collect_mid', '2')]),
            form_step('edit', '/admin/collect/info', [set('collect_name', '资源一改'), set('collect_opt', '2'),
                set('collect_filter', '1'), set('collect_filter_from', 'youku')], ['query' => 'id=1']),
            form_step('name-empty', '/admin/collect/info', [set('collect_name', '')], ['query' => 'id=2']),
            form_step('url-empty', '/admin/collect/info', [set('collect_name', '有名字'), set('collect_url', '')]),
            raw_post('no-token', '/admin/collect/info', 'collect_id=1&collect_name=x&collect_url=y'),
            form_step('bad-token', '/admin/collect/info', [set('__token__', 'bad')], ['query' => 'id=1']),
            form_step('extra-fields', '/admin/collect/info', [add('collect_appid', 'myapp'), add('unknown_col', 'x'),
                set('collect_sync_pic_opt', '')], ['query' => 'id=4']),
        ]),
        sc('collect-save-bad', [
            form_step('name-too-long', '/admin/collect/info', [set('collect_name', str_repeat('长', 31))], ['query' => 'id=1']),
            form_step('type-bad', '/admin/collect/info', [set('collect_type', 'abc')], ['query' => 'id=1']),
        ]),
        sc('collect-del', [
            req('one', 'GET', '/admin/collect/del', 'ids=1'),
            raw_post('array', '/admin/collect/del', 'ids%5B%5D=2&ids%5B%5D=3'),
            req('missing', 'GET', '/admin/collect/del', 'ids=99'),
            req('empty', 'GET', '/admin/collect/del'),
        ]),
        sc('collect-del-bad', [req('bad', 'GET', '/admin/collect/del', 'ids=abc')]),
        sc('collect-perm', [
            req('login-editor', 'POST', '/admin/index/logout', '', ''),
            req('login2', 'POST', '/admin/index/login', '', ['admin_name' => 'editor', 'admin_pwd' => 'ed pass']),
            get_page('index', '/admin/collect/index'),
            req('info', 'GET', '/admin/collect/info', 'id=1'),
            req('bind', 'GET', '/admin/collect/bind', 'col=' . $a . '_9&val=1'),
        ]),
        // ------------------------------------------------------------------ 分类绑定（extra/bind.php）
        sc('bind', [
            req('bind-new', 'GET', '/admin/collect/bind', 'ids=&col=' . $a . '_3&val=2'),
            req('rebind', 'GET', '/admin/collect/bind', 'col=' . $a . '_1&val=6'),
            req('unbind', 'GET', '/admin/collect/bind', 'col=' . $a . '_2&val=0'),
            req('unknown-type', 'GET', '/admin/collect/bind', 'col=' . $b . '_8&val=99'),
            req('val-text', 'GET', '/admin/collect/bind', 'col=' . $b . '_9&val=abc'),
            req('val-num-text', 'GET', '/admin/collect/bind', 'col=' . $b . '_10&val=2x'),
            req('numeric-col', 'GET', '/admin/collect/bind', 'col=15&val=1'),
            req('chinese-col', 'GET', '/admin/collect/bind', 'col=' . rawurlencode('中文_1') . '&val=1'),
            req('quote-col', 'GET', '/admin/collect/bind', 'col=' . rawurlencode("a'b\\c") . '&val=1'),
            req('no-col', 'GET', '/admin/collect/bind', 'val=1'),
            req('page-post', 'POST', '/admin/collect/bind', '', 'col=' . $b . '_11&val=1'),
        ]),
        sc('bind-empty-file', [
            req('first', 'GET', '/admin/collect/bind', 'col=' . $a . '_1&val=1'),
        ], ['bind' => "<?php\nreturn array (\n);"]),
        sc('bind-no-file', [
            req('first', 'GET', '/admin/collect/bind', 'col=' . $a . '_1&val=2'),
        ], ['bind' => null]),
        sc('clearbind', [
            req('one-site', 'GET', '/admin/collect/clearbind', 'cjflag=' . $a),
            req('partial-flag', 'GET', '/admin/collect/clearbind', 'cjflag=_5'),
            req('all', 'GET', '/admin/collect/clearbind'),
            req('again', 'GET', '/admin/collect/clearbind'),
        ]),
        sc('clearbind-ajax-post', [
            raw_post('all', '/admin/collect/clearbind', ''),
        ]),
        // ------------------------------------------------------------------ 自定义规则节点（mac_cj_node）
        sc('cj-list', [
            get_page('index', '/admin/cj/index'),
            get_page('page2', '/admin/cj/index', 'page=2&limit=2'),
        ]),
        sc('cj-info', [
            get_page('new', '/admin/cj/info'),
            get_page('edit', '/admin/cj/info', 'id=1'),
            get_page('edit-art', '/admin/cj/info', 'id=2'),
            get_page('missing', '/admin/cj/info', 'id=99'),
        ]),
        sc('cj-save', [
            form_step('edit', '/admin/cj/info', [set('data[name]', '电影规则改'), set('data[url_contain]', 'detail')], ['query' => 'id=1']),
            form_step('edit-type2', '/admin/cj/info', [set('data[sourcetype]', '2'), set('urlpage2', "http://x.test/1\r\nhttp://x.test/2")],
                ['query' => 'id=3']),
            form_step('edit-diy-filtered', '/admin/cj/info', [add('data[customize_config][name][]', ''),
                add('data[customize_config][en_name][]', 'skip'), add('data[customize_config][rule][]', 'r'),
                add('data[customize_config][html_rule][]', 'h')], ['query' => 'id=1']),
            raw_post('diy-all-empty', '/admin/cj/info', 'data%5Bnodeid%5D=2&data%5Bname%5D=n2&data%5Bsourcetype%5D=1&urlpage1=u'
                . '&data%5Bcustomize_config%5D%5Bname%5D%5B%5D=&data%5Bcustomize_config%5D%5Ben_name%5D%5B%5D=e'),
            raw_post('partial', '/admin/cj/info', 'data%5Bnodeid%5D=3&data%5Bname%5D=%E5%8D%95%E9%A1%B5%E6%94%B9&data%5Bsourcetype%5D=3'
                . '&urlpage3=http%3A%2F%2Fcj.example.test%2Fone2.html&data%5Bunknown%5D=1'),
            raw_post('no-sourcetype', '/admin/cj/info', 'data%5Bnodeid%5D=3&data%5Bname%5D=x&urlpage=zzz'),
        ]),
        sc('cj-add', [
            form_step('add-form', '/admin/cj/info', [set('data[name]', '新规则'), add('data[sourcecharset]', 'UTF-8'),
                add('data[sourcetype]', '1'), set('urlpage1', 'http://n.example.test/(*).html')]),
            raw_post('add-full', '/admin/cj/info', http_build_query(['data' => ['nodeid' => '', 'name' => '完整规则', 'sourcecharset' => 'GBK',
                'sourcetype' => '3', 'mid' => '2', 'pagesize_start' => '', 'pagesize_end' => '', 'par_num' => '1', 'url_contain' => '',
                'url_except' => '', 'url_start' => '', 'url_end' => '', 'title_rule' => 't', 'title_html_rule' => '', 'type_rule' => '',
                'type_html_rule' => '', 'content_rule' => 'c', 'content_html_rule' => '', 'content_page_rule' => '1', 'content_nextpage' => '',
                'content_page_start' => '', 'content_page_end' => '', 'content_page' => '1', 'coll_order' => '2',
                'customize_config' => ['name' => ['字段'], 'en_name' => ['f'], 'rule' => ['<b>[内容]</b>'], 'html_rule' => ['']],
                'program_config' => '', 'down_attachment' => '0', 'watermark' => '0'], 'urlpage3' => 'http://n.example.test/one.html'])),
        ], ['seed' => ['mac_cj_node' => []]]),
        sc('cj-save-bad', [
            raw_post('name-too-long', '/admin/cj/info', 'data%5Bnodeid%5D=1&data%5Bname%5D=' . rawurlencode(str_repeat('名', 21))
                . '&data%5Bsourcetype%5D=1&urlpage1=u'),
            raw_post('diy-scalar', '/admin/cj/info', 'data%5Bnodeid%5D=1&data%5Bname%5D=x&data%5Bsourcetype%5D=1&urlpage1=u'
                . '&data%5Bcustomize_config%5D=abc'),
            raw_post('array-value', '/admin/cj/info', 'data%5Bnodeid%5D=1&data%5Bname%5D%5B%5D=x&data%5Bsourcetype%5D=1&urlpage1=u'),
            raw_post('no-data', '/admin/cj/info', 'x=1'),
        ]),
        sc('cj-del', [
            req('one', 'GET', '/admin/cj/del', 'ids=1'),
            req('empty', 'GET', '/admin/cj/del'),
            raw_post('array', '/admin/cj/del', 'ids%5B%5D=2&ids%5B%5D=3'),
        ]),
        sc('cj-del-bad', [req('bad', 'GET', '/admin/cj/del', 'ids=1abc')]),
        sc('cj-export', [
            req('one', 'GET', '/admin/cj/export', 'id=1', '', ['ajax' => false]),
            req('art', 'GET', '/admin/cj/export', 'id=2', '', ['ajax' => false]),
            req('missing', 'GET', '/admin/cj/export', 'id=99'),
        ]),
        sc('cj-import', [
            upload_step('valid', '/admin/cj/import', 'file', 'mac_cj_x.txt', $node),
            upload_step('garbage', '/admin/cj/import', 'file', 'bad.txt', '!!!not base64 json!!!'),
            upload_step('jpg-name', '/admin/cj/import', 'file', 'rule.jpg', $node),
            upload_step('nested', '/admin/cj/import', 'file', 'nested.txt', $nested),
        ]),
    ];
}

// ====================================================================== 生成

function generate($repo, $vendor, $pdo, $phpCgi, $work, $host, $port, $db, $user, $password)
{
    @mkdir($work, 0777, true);
    $base = $work . DIRECTORY_SEPARATOR . 'base';
    $site = $work . DIRECTORY_SEPARATOR . 'site';
    copy_tree($repo . '/application', $base . '/application');
    copy_tree($repo . '/thinkphp', $base . '/thinkphp');
    copy_tree($vendor, $base . '/vendor');
    $database = include $repo . '/application/database.php';
    $database = array_merge($database, ['hostname' => $host, 'hostport' => $port, 'database' => $db,
        'username' => $user, 'password' => $password, 'debug' => false]);
    file_put_contents($base . '/application/database.php', '<?php return ' . var_export($database, true) . ';');
    $maccms = include $repo . '/application/extra/maccms.php';
    $maccms['app']['admin_login_verify'] = '0';
    $maccms['app']['lang'] = 'zh-cn';
    $maccms['app']['pagesize'] = '2';
    file_put_contents($base . '/application/extra/maccms.php', "<?php\nreturn " . var_export($maccms, true) . ';');
    foreach (glob($base . '/application/extra/*.php') as $f) {
        file_put_contents($f, str_replace("\r\n", "\n", file_get_contents($f)));
    }
    copy_tree($base, $site);
    $types = column_types($pdo);

    $only = getenv('GOLDEN_ONLY') ? explode(',', getenv('GOLDEN_ONLY')) : [];
    $out = [];
    foreach (scenarios() as $sc) {
        if ($only && !array_filter($only, function ($o) use ($sc) {
                return strpos($sc['label'], $o) === 0;
            })) {
            continue;
        }
        foreach (['application/extra', 'runtime'] as $dir) {
            rm_tree($site . '/' . $dir);
            if (is_dir($base . '/' . $dir)) {
                copy_tree($base . '/' . $dir, $site . '/' . $dir);
            }
        }
        $bindPath = $site . '/application/extra/bind.php';
        if ($sc['bind'] === null) {
            @unlink($bindPath);
        } else {
            file_put_contents($bindPath, $sc['bind']);
        }
        $seed = array_merge(all_seed(), $sc['seed']);
        seed($pdo, $seed);
        $runtime = $site . DIRECTORY_SEPARATOR . 'runtime';
        $jar = [];
        $token = null;
        $lastBind = bind_now($site);
        $steps = [];
        foreach ($sc['steps'] as $st) {
            $page = null;
            if (isset($st['form'])) {
                $page = do_request($site, $phpCgi, $runtime, $jar, ['method' => 'GET', 'path' => $st['form'], 'query' => $st['query'],
                    'post' => '', 'ajax' => false, 'headers' => []]);
                if (preg_match('/name="__token__" value="([0-9a-f]+)"/', $page['body'], $tm)) {
                    $token = $tm[1];
                }
                $fields = apply_mods(serialize_form($page['body'], $st['formIndex']), $st['mods']);
                $req = ['method' => 'POST', 'path' => $st['action'], 'query' => $st['query'], 'post' => encode_fields($fields), 'ajax' => true,
                    'headers' => []];
            } elseif (isset($st['upload'])) {
                $boundary = '----goldenboundary' . bin2hex(random_bytes(6));
                $body = '';
                foreach ($st['fields'] as $k => $v) {
                    $body .= "--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n";
                }
                $body .= "--$boundary\r\nContent-Disposition: form-data; name=\"{$st['field']}\"; filename=\"{$st['filename']}\"\r\n"
                    . "Content-Type: text/plain\r\n\r\n{$st['content']}\r\n--$boundary--\r\n";
                $req = ['method' => 'POST', 'path' => $st['upload'], 'query' => $st['query'], 'post' => $body, 'ajax' => true, 'headers' => [],
                    'contentType' => 'multipart/form-data; boundary=' . $boundary];
            } else {
                if (is_array($st['post'])) {
                    $post = isset($st['post']['__raw__']) ? $st['post']['__raw__'] : http_build_query($st['post']);
                } else {
                    $post = $st['post'];
                }
                $req = ['method' => $st['method'], 'path' => $st['path'], 'query' => $st['query'], 'post' => $post, 'ajax' => $st['ajax'],
                    'headers' => $st['headers']];
            }
            $res = do_request($site, $phpCgi, $runtime, $jar, $req, $token);
            if (preg_match('/name="__token__" value="([0-9a-f]+)"/', $res['body'], $tm)) {
                $token = $tm[1];
            }
            $now = bind_now($site);
            $rec = step_record($st['label'], $req, $res, $page);
            $rec['bind'] = $now === $lastBind ? false : ($now === null ? null : base64_encode($now));
            $rec['tables'] = read_all($pdo, $types);
            if (isset($st['upload'])) {
                $rec['upload'] = ['field' => $st['field'], 'filename' => $st['filename'], 'content' => base64_encode($st['content']),
                    'fields' => (object)$st['fields']];
                unset($rec['request']['post']);
            }
            $steps[] = $rec;
            $lastBind = $now;
        }
        $out[] = ['label' => $sc['label'], 'bind0' => $sc['bind'] === null ? null : base64_encode($sc['bind']),
            'seed' => encode_seed($seed, $types), 'steps' => $steps];
    }
    return json_encode(['generator' => 'backend/src/test/php/admin_collect_golden.php', 'php' => PHP_VERSION, 'timezone' => 'PRC',
            'entry' => ENTRY, 'host' => HOST, 'pagesize' => 2,
            'maccmsPhp' => base64_encode(file_get_contents($base . '/application/extra/maccms.php')), 'scenarios' => $out],
            JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT | JSON_INVALID_UTF8_SUBSTITUTE) . "\n";
}

function bind_now($site)
{
    $p = $site . '/application/extra/bind.php';
    return is_file($p) ? file_get_contents($p) : null;
}

/** 各表的列类型（数值列在种子与结果里写十进制，其余十六进制）。 */
function column_types($pdo)
{
    $out = [];
    foreach (array_keys(TABLES) as $t) {
        foreach ($pdo->query("SHOW COLUMNS FROM $t")->fetchAll(PDO::FETCH_ASSOC) as $c) {
            $out[$t][$c['Field']] = preg_match('/int|decimal/', $c['Type']) ? 'num' : 'text';
        }
    }
    return $out;
}

function seed($pdo, array $seed)
{
    foreach (array_keys(TABLES) as $t) {
        $pdo->exec("DELETE FROM $t");
        if ($t !== 'mac_cj_history') {
            $pdo->exec("ALTER TABLE $t AUTO_INCREMENT = 1");
        }
    }
    foreach ($seed as $t => $rows) {
        foreach ($rows as $r) {
            $cols = array_keys($r);
            $sql = "INSERT INTO $t (" . implode(',', $cols) . ') VALUES (' . implode(',', array_fill(0, count($cols), '?')) . ')';
            $pdo->prepare($sql)->execute(array_map('strval', array_values($r)));
        }
    }
}

function encode_seed(array $seed, $types)
{
    $out = [];
    foreach (array_keys(TABLES) as $t) {
        $out[$t] = [];
        foreach (isset($seed[$t]) ? $seed[$t] : [] as $r) {
            $out[$t][] = encode_row($t, $r, $types);
        }
    }
    return $out;
}

function encode_row($table, array $row, $types)
{
    $o = [];
    foreach ($row as $k => $v) {
        $o[$k] = $v === null ? null : ($types[$table][$k] === 'num' ? (string)$v : bin2hex((string)$v));
    }
    return $o;
}

function read_all($pdo, $types)
{
    $out = [];
    foreach (TABLES as $t => $pk) {
        $rows = [];
        foreach ($pdo->query("SELECT * FROM $t ORDER BY $pk")->fetchAll(PDO::FETCH_ASSOC) as $r) {
            $rows[] = encode_row($t, $r, $types);
        }
        $out[$t] = $rows;
    }
    return $out;
}

function step_record($label, $req, $res, $page)
{
    $r = ['label' => $label, 'request' => ['method' => $req['method'], 'path' => $req['path'], 'query' => $req['query'],
        'post' => $req['post'], 'ajax' => $req['ajax'], 'headers' => (object)$req['headers']],
        'started' => $res['started'], 'after' => $res['after'], 'status' => $res['status'],
        'contentType' => isset($res['headers']['content-type']) ? $res['headers']['content-type'] : null,
        'location' => isset($res['headers']['location']) ? $res['headers']['location'] : null,
        'disposition' => isset($res['headers']['content-disposition']) ? base64_encode($res['headers']['content-disposition']) : null,
        'body' => base64_encode($res['body'])];
    if ($page !== null) {
        $r['page'] = ['status' => $page['status'], 'body' => base64_encode($page['body'])];
    }
    return $r;
}

function do_request($site, $phpCgi, $runtime, &$jar, $req, $token = null)
{
    $headers = $req['headers'];
    if ($req['ajax']) {
        $headers['X-Requested-With'] = 'XMLHttpRequest';
    }
    if ($jar) {
        $pairs = [];
        foreach ($jar as $k => $v) {
            $pairs[] = $k . '=' . $v;
        }
        $headers['Cookie'] = implode('; ', $pairs);
    }
    $post = str_replace('{token}', rawurlencode((string)$token), $req['post']);
    $query = str_replace('{token}', rawurlencode((string)$token), $req['query']);
    $started = time();
    list($status, $respHeaders, $setCookies, $body) = run_request($site, $phpCgi, $runtime, $req['method'], $req['path'],
        $query, $post, $headers, isset($req['contentType']) ? $req['contentType'] : null);
    $after = time();
    foreach ($setCookies as $c) {
        $pair = explode(';', $c, 2)[0];
        list($k, $v) = array_pad(explode('=', $pair, 2), 2, '');
        if ($v === 'deleted' || stripos($c, 'Max-Age=0') !== false) {
            unset($jar[$k]);
        } else {
            $jar[$k] = $v;
        }
    }
    return ['status' => $status, 'headers' => $respHeaders, 'body' => $body, 'started' => $started, 'after' => $after];
}

/** 以 php-cgi 执行一个后台请求。返回 [status, headers(小写名 => 值), setCookies, body]。 */
function run_request($site, $phpCgi, $runtime, $method, $path, $query, $post, $headers, $contentType)
{
    $entryDir = dirname($site) . DIRECTORY_SEPARATOR . 'entry';
    $entryFile = $entryDir . DIRECTORY_SEPARATOR . '_admin.php';
    if (!is_file($entryFile)) {
        @mkdir($entryDir, 0777, true);
        file_put_contents($entryFile, '<?php require ' . var_export(__DIR__ . DIRECTORY_SEPARATOR . 'admin_system_child.php', true) . ';');
    }
    @mkdir($runtime, 0777, true);
    $env = [
        'GOLDEN_SITE_ROOT' => $site,
        'GOLDEN_SESSION_PATH' => $runtime,
        'REDIRECT_STATUS' => '200',
        'GATEWAY_INTERFACE' => 'CGI/1.1',
        'SERVER_PROTOCOL' => 'HTTP/1.1',
        'SERVER_SOFTWARE' => 'golden',
        'SERVER_NAME' => HOST,
        'SERVER_PORT' => '80',
        'HTTP_HOST' => HOST,
        'HTTP_USER_AGENT' => 'Mozilla/5.0 (golden)',
        'REQUEST_METHOD' => $method,
        'SCRIPT_FILENAME' => $entryFile,
        'SCRIPT_NAME' => ENTRY,
        'PHP_SELF' => ENTRY . $path,
        'REQUEST_URI' => ENTRY . $path . ($query === '' ? '' : '?' . $query),
        'QUERY_STRING' => $query,
        'REMOTE_ADDR' => '127.0.0.1',
        'REMOTE_PORT' => '50000',
        'DOCUMENT_ROOT' => $entryDir,
        'SystemRoot' => getenv('SystemRoot') ?: 'C:\\Windows',
        'TEMP' => $runtime,
        'TMP' => $runtime,
    ];
    if ($path !== '') {
        $env['PATH_INFO'] = $path;
    }
    if ($method === 'POST') {
        $env['CONTENT_TYPE'] = $contentType === null ? 'application/x-www-form-urlencoded' : $contentType;
        $env['CONTENT_LENGTH'] = (string)strlen($post);
    }
    foreach ($headers as $name => $value) {
        $env['HTTP_' . strtoupper(str_replace('-', '_', $name))] = $value;
    }
    $cmd = escapeshellarg($phpCgi) . ' -d session.save_path=' . escapeshellarg($runtime)
        . ' -d display_errors=0 -d error_reporting=0 -d session.gc_probability=0 -d upload_tmp_dir=' . escapeshellarg($runtime);
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
    $respHeaders = [];
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
        $respHeaders[$name] = $value;
    }
    return [$status, $respHeaders, $setCookies, substr($out, $sep + 4)];
}

// ====================================================================== 表单（浏览器 jQuery serialize() 的取值，同 admin_system_golden.php）

function serialize_form($html, $index)
{
    // 浏览器把 textarea 的内容当作 RCDATA（只解码字符引用，里面的标签是文字）；libxml 会把其中的标签当作元素丢掉。先按浏览器的规则
    // 取出文字再转义回去，DOM 的 textContent 就是浏览器里的值（自定义规则的匹配规则一栏里都是 HTML 片段）。
    $html = preg_replace_callback('#(<textarea\b[^>]*>)(.*?)(</textarea\s*>)#is', function ($m) {
        return $m[1] . htmlspecialchars(html_entity_decode($m[2], ENT_QUOTES | ENT_HTML5, 'UTF-8'), ENT_NOQUOTES, 'UTF-8') . $m[3];
    }, $html);
    $doc = new DOMDocument();
    libxml_use_internal_errors(true);
    $doc->loadHTML('<?xml encoding="utf-8" ?>' . $html, LIBXML_NOERROR | LIBXML_NOWARNING);
    libxml_clear_errors();
    $forms = $doc->getElementsByTagName('form');
    if ($forms->length <= $index) {
        return [];
    }
    $form = $forms->item($index);
    $xp = new DOMXPath($doc);
    $fields = [];
    $radioLast = [];
    foreach ($xp->query('.//input|.//select|.//textarea', $form) as $el) {
        $name = $el->getAttribute('name');
        if ($name === '' || $el->hasAttribute('disabled')) {
            continue;
        }
        $tag = strtolower($el->nodeName);
        if ($tag === 'input') {
            $type = strtolower($el->getAttribute('type'));
            if (in_array($type, ['submit', 'button', 'image', 'reset', 'file'], true)) {
                continue;
            }
            $value = $el->hasAttribute('value') ? $el->getAttribute('value') : (in_array($type, ['checkbox', 'radio'], true) ? 'on' : '');
            if ($type === 'radio') {
                if ($el->hasAttribute('checked')) {
                    $radioLast[$name] = count($fields);
                    $fields[] = [$name, $value, 'radio'];
                }
                continue;
            }
            if ($type === 'checkbox' && !$el->hasAttribute('checked')) {
                continue;
            }
            $fields[] = [$name, str_replace(["\r\n", "\r", "\n"], ["\n", "\n", "\r\n"], $value), $type];
        } elseif ($tag === 'select') {
            $options = $xp->query('.//option', $el);
            $chosen = null;
            foreach ($options as $o) {
                if ($o->hasAttribute('selected')) {
                    $chosen = $o;
                }
            }
            if ($chosen === null && $options->length > 0 && !$el->hasAttribute('multiple')) {
                $chosen = $options->item(0);
            }
            if ($chosen !== null) {
                $v = $chosen->hasAttribute('value') ? $chosen->getAttribute('value') : trim(preg_replace('/\s+/', ' ', $chosen->textContent));
                $fields[] = [$name, $v, 'select'];
            }
        } else {
            $text = $el->textContent;
            if (substr($text, 0, 1) === "\n") {
                $text = substr($text, 1);
            }
            $fields[] = [$name, str_replace(["\r\n", "\r", "\n"], ["\n", "\n", "\r\n"], $text), 'textarea'];
        }
    }
    $out = [];
    foreach ($fields as $i => $f) {
        if ($f[2] === 'radio' && $radioLast[$f[0]] !== $i) {
            continue;
        }
        $out[] = [$f[0], $f[1]];
    }
    return $out;
}

function apply_mods(array $fields, array $mods)
{
    foreach ($mods as $m) {
        if ($m[0] === 'set') {
            $done = false;
            foreach ($fields as &$f) {
                if ($f[0] === $m[1] && !$done) {
                    $f[1] = $m[2];
                    $done = true;
                }
            }
            unset($f);
            if (!$done) {
                $fields[] = [$m[1], $m[2]];
            }
        } elseif ($m[0] === 'remove') {
            $fields = array_values(array_filter($fields, function ($f) use ($m) {
                return $f[0] !== $m[1];
            }));
        } else {
            $fields[] = [$m[1], $m[2]];
        }
    }
    return $fields;
}

function encode_fields(array $fields)
{
    $parts = [];
    foreach ($fields as $f) {
        $v = $f[0] === '__token__' && $f[1] !== 'bad' ? '{token}' : encode_component($f[1]);
        $parts[] = encode_component($f[0]) . '=' . $v;
    }
    return implode('&', $parts);
}

function encode_component($s)
{
    $out = '';
    for ($i = 0; $i < strlen($s); $i++) {
        $c = $s[$i];
        $o = ord($c);
        if (($o >= 0x30 && $o <= 0x39) || ($o >= 0x41 && $o <= 0x5A) || ($o >= 0x61 && $o <= 0x7A) || strpos("-_.!~*'()", $c) !== false) {
            $out .= $c;
        } elseif ($c === ' ') {
            $out .= '+';
        } else {
            $out .= '%' . strtoupper(bin2hex($c));
        }
    }
    return $out;
}

// ====================================================================== 文件

function copy_tree($from, $to)
{
    @mkdir($to, 0777, true);
    if (!is_dir($from)) {
        return;
    }
    $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($from, FilesystemIterator::SKIP_DOTS),
        RecursiveIteratorIterator::SELF_FIRST);
    foreach ($it as $f) {
        $dst = $to . DIRECTORY_SEPARATOR . substr($f->getPathname(), strlen($from) + 1);
        if ($f->isDir()) {
            @mkdir($dst, 0777, true);
        } else {
            copy($f->getPathname(), $dst);
        }
    }
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
