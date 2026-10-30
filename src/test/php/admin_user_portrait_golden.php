<?php
/*
 * B14（MACC-38）后台会员表单"上传图片"（会员头像）的 PHP 黄金样本生成脚本（契约见 docs/architecture/23-b14-admin-member.md 第 7.7 节）。
 *
 * 旧后台的会员编辑表单（application/admin/view/user/info.html:42-46、159-182）用 layui upload 把文件 POST 到
 * upload/upload?flag=user&user_id=<会员号>（附带 thumb=0、thumb_class=''），走 admin/controller/Upload::upload →
 * common/model/Upload::upload 的 flag=user 分支（Upload.php:79-88、188-213：写 upload/user/<id%10>/<id>.jpg、think\Image 缩略图、
 * 按 $GLOBALS['user'] 更新 user_portrait、登记 mac_annex）；成功后脚本把返回的地址填进表单的头像一栏，管理员再点"保存"。
 * 后台没有会员会话，$GLOBALS['user'] 不存在，上传本身不改 mac_user（本脚本的黄金即证据）。
 *
 * 每个场景是一串真实的后台请求：经 admin_portrait_child.php（B13 的子进程入口 admin_content_child.php 加上 VENDOR_PATH）跑完整的
 * ThinkPHP 路由、admin/controller/Base 的构造函数（登录、admin_auth 权限）与控制器；同一场景内按浏览器的方式保存并回送 cookie
 * （PHP 会话）。每个请求记录状态码、Location、Content-Type 与正文字节（base64），以及请求后 mac_user、mac_annex 的全部行与临时站点根目录 upload/ 下的文件清单（路径、大小、sha256、
 * 开头 4 字节、getimagesize 的尺寸与类型）。文本列一律十六进制。请求前后的秒级时间（started/after）用于比较写入的 annex_time。
 *
 * 场景之间互不依赖：每个场景先清空 mac_user、mac_annex、mac_admin 并把自增计数复位（一次性库上的测试夹具；新版的复放测试做同样的事），
 * 写入自己的行，使用新的配置、会话与站点目录。上传的图片由本脚本用 GD 按固定参数画出（WebP 取仓库的测试样本），字节写进黄金，新版
 * 复放时上传同样的字节。
 *
 * 流程：外部管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入 install.sql → 逐个场景执行 → 输出 JSON → 删除一次性库与临时目录。
 * 不修改任何旧 PHP 文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，不访问网络。需要仓库根目录下的 vendor/
 * （不在 git 中，按 AGENTS.md 从备份恢复；用其中的 think-image）。
 *
 * 管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；另需 PHP_CGI。
 * 用法（仓库根目录，PHP 8.0 与 7.3 各一份）：
 *   PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe src/test/php/admin_user_portrait_golden.php \
 *       > src/test/resources/admin/member/portrait-php80-golden.json
 * 只跑部分场景：GOLDEN_ONLY=<标签前缀>（逗号分隔多个）；排查时 GOLDEN_DEBUG=1（错误页带异常信息，不用于正式黄金）。
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
if (!is_file($repo . '/vendor/topthink/think-image/src/Image.php')) {
    fwrite(STDERR, "缺少 vendor/topthink/think-image（按 AGENTS.md 从备份恢复 vendor/）\n");
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
/** 跟踪的表与主键（顺序即输出顺序）。 */
const TABLES = ['mac_user' => 'user_id', 'mac_annex' => 'annex_id'];

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$work = sys_get_temp_dir() . '/maccms-b14-portrait-golden-' . bin2hex(random_bytes(4));
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

/** 每个场景都带上的配置（仓库值之上）：后台登录不要验证码，语言简体；头像 100x100；上传存本地、不加水印、不生成缩略图。 */
function base_config()
{
    return ['app' => ['admin_login_verify' => '0', 'lang' => 'zh-cn', 'pagesize' => '20'],
        'user' => ['portrait_status' => '1', 'portrait_size' => '100x100'],
        'upload' => ['protocol' => 'http', 'mode' => 'local', 'watermark' => '0', 'thumb' => '0']];
}

/** 管理员：admin（超级管理员）、editor（有会员表单与上传权限）、viewer（只有会员表单，没有 upload/upload）。 */
function admins()
{
    return [
        ['admin_id' => 1, 'admin_name' => 'admin', 'admin_pwd' => md5('admin888'), 'admin_status' => 1, 'admin_auth' => ''],
        ['admin_id' => 2, 'admin_name' => 'editor', 'admin_pwd' => md5('ed pass'), 'admin_status' => 1,
            'admin_auth' => ',user/data,user/info,upload/upload,'],
        ['admin_id' => 3, 'admin_name' => 'viewer', 'admin_pwd' => md5('vw pass'), 'admin_status' => 1,
            'admin_auth' => ',user/data,user/info,'],
    ];
}

function u($id, $name, array $extra = [])
{
    return array_merge(['user_id' => $id, 'group_id' => 2, 'user_name' => $name, 'user_pwd' => md5('pw' . $id),
        'user_nick_name' => '', 'user_status' => 1, 'user_portrait' => '', 'user_points' => 0, 'user_reg_time' => 1690000000 + $id,
        'user_login_time' => 1700000000 + $id, 'user_end_time' => 0], $extra);
}

/** 会员：1（头像目录 user/1，已有旧头像地址）、15（user/5）、23（user/3）。 */
function users()
{
    return [
        u(1, 'alice01', ['user_portrait' => 'upload/user/1/1.jpg', 'user_nick_name' => '爱丽丝', 'user_points' => 100]),
        u(15, 'portrait15'),
        u(23, 'portrait23', ['user_portrait' => 'http://img.example.test/a.jpg']),
    ];
}

/** GD 画的测试图（固定参数，同一 PHP 版本每次运行字节相同；新版复放上传的是黄金里记录的字节）。 */
function image_bytes($kind, $w, $h)
{
    $im = imagecreatetruecolor($w, $h);
    if ($kind === 'png') {
        imagealphablending($im, false);
        imagesavealpha($im, true);
        imagefill($im, 0, 0, imagecolorallocatealpha($im, 0, 0, 0, 127));
    } else {
        imagefill($im, 0, 0, imagecolorallocate($im, 240, 240, 240));
    }
    for ($i = 0; $i < 8; $i++) {
        $c = imagecolorallocate($im, ($i * 37) % 256, ($i * 91) % 256, ($i * 53) % 256);
        imagefilledrectangle($im, $i * intdiv($w, 8), $i * intdiv($h, 10), ($i + 1) * intdiv($w, 8), $h - 1, $c);
    }
    ob_start();
    if ($kind === 'jpeg') {
        imagejpeg($im, null, 90);
    } elseif ($kind === 'png') {
        imagepng($im);
    } else {
        imagegif($im);
    }
    imagedestroy($im);
    return ob_get_clean();
}

function req($label, $method, $path, $query = '', $post = null, array $opt = [])
{
    return array_merge(['label' => $label, 'method' => $method, 'path' => $path, 'query' => $query, 'post' => $post,
        'multipart' => null, 'ajax' => true, 'headers' => []], $opt);
}

function login($name = 'admin', $pwd = 'admin888')
{
    return req('login', 'POST', '/admin/index/login', '', ['admin_name' => $name, 'admin_pwd' => $pwd]);
}

/**
 * layui upload 发出的请求（info.html:159-163）：地址 upload/upload?flag=user&user_id=<id>，表单字段 thumb=0、thumb_class=''（lay-data）
 * 加文件字段 file。$query 为空时用 flag=user&user_id=$userId。
 */
function upload($label, $userId, $fileName, $content, $type, array $opt = [])
{
    $query = isset($opt['query']) ? $opt['query'] : 'flag=user&user_id=' . $userId;
    $parts = isset($opt['parts']) ? $opt['parts'] : [['thumb', '0'], ['thumb_class', ''], ['file', $fileName, $content, $type]];
    unset($opt['query'], $opt['parts']);
    return req($label, 'POST', '/admin/upload/upload', $query, null, array_merge(['multipart' => $parts], $opt));
}

/** 会员表单的"保存"（info.html 提交的字段；头像一栏为上传后脚本填进的地址）。 */
function save_user($label, array $over = [])
{
    return req($label, 'POST', '/admin/user/info', '', array_merge(['user_id' => '1', 'user_name' => 'alice01', 'user_pwd' => '',
        'user_status' => '1', 'group_id' => '2', 'user_portrait' => 'upload/user/1/1.jpg', 'user_nick_name' => '爱丽丝',
        'user_end_time' => '1970-01-01 08:00:00', 'user_points' => '100', 'user_phone' => '', 'user_qq' => '', 'user_email' => '',
        'user_question' => '', 'user_answer' => ''], $over));
}

function sc($label, array $steps, array $opt = [])
{
    return array_merge(['label' => $label, 'config' => [], 'annex' => [], 'files' => [], 'steps' => array_merge([login()], $steps)],
        $opt);
}

function scenarios($repo)
{
    $jpeg = image_bytes('jpeg', 300, 200);
    $png = image_bytes('png', 120, 80);
    $gif = image_bytes('gif', 64, 48);
    $webp = file_get_contents(dirname($repo) . '/src/test/resources/member/webp/lossy.webp');
    // 2 MB 以上（php.ini 的 upload_max_filesize = 2M；新版 spring.servlet.multipart.max-file-size = 2MB）
    $big = $jpeg . str_repeat("\0", 2 * 1024 * 1024 + 1);
    return [
        // 完整流程：打开编辑表单 → 上传 → 保存表单（头像一栏是返回的地址）→ 重新打开表单
        sc('flow', [
            req('open', 'GET', '/admin/user/info', 'id=15', null, ['ajax' => false]),
            upload('upload', 15, 'a.jpg', $jpeg, 'image/jpeg'),
            save_user('save', ['user_id' => '15', 'user_name' => 'portrait15', 'user_portrait' => 'upload/user/5/15.jpg',
                'user_nick_name' => '', 'user_points' => '0']),
            req('reopen', 'GET', '/admin/user/info', 'id=15', null, ['ajax' => false]),
        ]),
        // 已有头像地址的会员：上传覆盖同一路径的文件，保存后地址不变
        sc('flow-existing', [
            upload('upload', 1, 'a.jpg', $jpeg, 'image/jpeg'),
            save_user('save'),
        ], ['files' => ['upload/user/1/1.jpg' => 'old']]),
        // 再次上传同一会员（覆盖文件，不重复登记附件）
        sc('again', [
            upload('first', 1, 'a.jpg', $jpeg, 'image/jpeg'),
            upload('second', 1, 'b.PNG', $png, 'image/png'),
        ]),
        sc('png', [upload('upload', 15, 'me.PNG', $png, 'image/png')]),
        sc('gif', [upload('upload', 15, 'g.gif', $gif, 'image/gif')]),
        sc('webp', [upload('upload', 23, 'w.webp', $webp, 'image/webp')]),
        sc('imgdata', [upload('upload', 15, null, null, null, ['parts' => [['imgdata', 'data:image/png;base64,' . base64_encode($png)]]])]),
        // 头像尺寸配置只写一个数（宽高相同）
        sc('size-square', [upload('upload', 15, 'a.jpg', $jpeg, 'image/jpeg')], ['config' => ['user' => ['portrait_size' => '64']]]),
        // 前台的头像开关不影响后台
        sc('portrait-off', [upload('upload', 15, 'a.jpg', $jpeg, 'image/jpeg')], ['config' => ['user' => ['portrait_status' => '0']]]),
        // 这个文件已登记过：不再登记，旧行不变
        sc('preregistered', [upload('upload', 15, 'p.png', $png, 'image/png')], [
            'annex' => [['annex_id' => 7, 'annex_time' => 1700000100, 'annex_file' => 'upload/user/5/15.jpg', 'annex_size' => 9,
                'annex_type' => 'file']],
            'files' => ['upload/user/5/15.jpg' => 'old']]),
        // 扩展名属于"文件"类而内容是图片
        sc('file-ext', [upload('upload', 15, 'n.txt', $jpeg, 'text/plain')]),
        // 权限：有会员表单、没有 upload/upload 的管理员
        sc('denied', [login('viewer', 'vw pass'), upload('upload', 15, 'a.jpg', $jpeg, 'image/jpeg')]),
        sc('editor', [login('editor', 'ed pass'), upload('upload', 15, 'a.jpg', $jpeg, 'image/jpeg')]),
        sc('not-logged-in', [upload('upload', 15, 'a.jpg', $jpeg, 'image/jpeg')], ['steps' => [upload('upload', 15, 'a.jpg', $jpeg, 'image/jpeg')]]),
        // 会员号：缺失、0、不存在、非数字、负数、小数、路径
        sc('id-missing', [upload('upload', null, 'a.jpg', $jpeg, 'image/jpeg', ['query' => 'flag=user'])]),
        sc('id-zero', [upload('upload', 0, 'a.jpg', $jpeg, 'image/jpeg')]),
        sc('id-absent', [upload('upload', 999, 'a.jpg', $jpeg, 'image/jpeg')]),
        sc('id-text', [upload('upload', 'abc', 'a.jpg', $jpeg, 'image/jpeg')]),
        sc('id-negative', [upload('upload', '-3', 'a.jpg', $jpeg, 'image/jpeg')]),
        sc('id-decimal', [upload('upload', '1.5', 'a.jpg', $jpeg, 'image/jpeg')]),
        sc('id-leading', [upload('upload', '15abc', 'a.jpg', $jpeg, 'image/jpeg')]),
        sc('id-leading-zero', [upload('upload', '015', 'a.jpg', $jpeg, 'image/jpeg')]),
        sc('id-path', [upload('upload', '..%2F..%2F1', 'a.jpg', $jpeg, 'image/jpeg')]),
        // 失败：伪装图片、禁止的扩展名、PHP 内容、脚本标记、没有文件、超过大小上限
        sc('not-image', [upload('upload', 15, 'n.jpg', str_repeat('not an image ', 80), 'image/jpeg')]),
        sc('forbidden-ext', [upload('upload', 15, 'x.exe', $jpeg, 'application/octet-stream')]),
        sc('php-content', [upload('upload', 15, 'x.jpg', "<?php echo 1;\n", 'image/jpeg')]),
        sc('script-mark', [upload('upload', 15, 'x.jpg', $jpeg . '<script>alert(1)</script>', 'image/jpeg')]),
        sc('no-file', [upload('upload', 15, null, null, null, ['parts' => [['thumb', '0']]])]),
        sc('too-big', [upload('upload', 15, 'big.jpg', $big, 'image/jpeg')]),
        // 失败的上传不改已有的头像文件
        sc('fail-keeps-old', [upload('upload', 15, 'n.jpg', str_repeat('not an image ', 80), 'image/jpeg')], [
            'files' => ['upload/user/5/15.jpg' => 'old']]),
    ];
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
        @mkdir($site . '/upload', 0777, true);
        foreach ($sc['files'] as $rel => $content) {
            @mkdir(dirname($site . '/' . $rel), 0777, true);
            file_put_contents($site . '/' . $rel, $content);
        }
        $maccms = deep_merge($base, $sc['config']);
        write_conf($repo, $conf, $maccms, $host, $port, $db, $user, $password);
        $seed = ['mac_user' => users(), 'mac_annex' => $sc['annex']];
        seed($pdo, $seed);
        $rows0 = read_all($pdo, $types);
        $files0 = list_files($site . '/upload');
        $jar = [];
        $steps = [];
        foreach ($sc['steps'] as $st) {
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
            $post = '';
            if ($st['multipart'] !== null) {
                list($post, $contentType) = multipart($st['multipart']);
            } elseif (is_array($st['post'])) {
                $post = http_build_query($st['post']);
            }
            $req = ['path' => $st['path'], 'query' => $st['query'], 'method' => $st['method'], 'post' => $post,
                'contentType' => $contentType, 'headers' => $headers, 'remote' => '127.0.0.1'];
            $started = time();
            list($status, $respHeaders, $setCookies, $body) = run_request($repo, $phpCgi, $conf, $runtime, $site, $req);
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
            $steps[] = ['label' => $st['label'], 'request' => ['method' => $st['method'], 'path' => $st['path'],
                'query' => $st['query'], 'post' => $st['multipart'] !== null ? null : $post,
                'multipart' => $st['multipart'] !== null ? multipart_describe($st['multipart']) : null,
                'ajax' => $st['ajax'], 'headers' => (object)$st['headers']],
                'started' => $started, 'after' => $after, 'status' => $status,
                'contentType' => isset($respHeaders['content-type']) ? $respHeaders['content-type'] : null,
                'location' => isset($respHeaders['location']) ? $respHeaders['location'] : null,
                'body' => base64_encode($body), 'rows' => read_all($pdo, $types), 'files' => (object)list_files($site . '/upload')];
        }
        $out[] = ['label' => $sc['label'], 'config' => (object)$sc['config'],
            'maccmsPhp' => '<?php return ' . var_export($maccms, true) . ';', 'seed' => encode_seed($seed, $types),
            'filesSeed' => (object)array_map('base64_encode', $sc['files']), 'files0' => (object)$files0, 'rows0' => $rows0,
            'steps' => $steps];
    }
    return json_encode(['generator' => 'backend/src/test/php/admin_user_portrait_golden.php', 'php' => PHP_VERSION,
            'gd' => gd_info()['GD Version'], 'timezone' => 'PRC', 'entry' => ENTRY, 'host' => 'www.example.test',
            'remote' => '127.0.0.1', 'baseConfig' => base_config(), 'admins' => admins(), 'scenarios' => $out],
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

/** 清空跟踪的表与 mac_admin 并复位自增计数（一次性库上的测试夹具），写入场景的行与管理员。 */
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

/** upload/ 下的文件（相对站点根的路径 → 大小、sha256、开头 4 字节的十六进制与 getimagesize 的尺寸和类型）。 */
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
            $info = @getimagesize($f->getPathname());
            $out[$rel] = ['size' => $f->getSize(), 'sha256' => hash_file('sha256', $f->getPathname()),
                'magic' => bin2hex((string)file_get_contents($f->getPathname(), false, null, 0, 4)),
                'image' => $info === false ? null : $info[0] . 'x' . $info[1] . ' ' . $info['mime']];
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

/**
 * 请求里的 multipart 字段（新版复放时按此重建正文）。超过 1 MB 的文件内容是"开头的图片 + 一串 \0"（超过大小上限的场景），记为
 * content（开头部分，base64）与 zeros（其后 \0 的个数），避免黄金里存几 MB 的 base64。
 */
function multipart_describe(array $parts)
{
    $out = [];
    foreach ($parts as $p) {
        if (count($p) === 2) {
            $out[] = ['name' => $p[0], 'value' => $p[1]];
            continue;
        }
        $content = $p[2];
        $zeros = 0;
        if (strlen($content) > 1024 * 1024) {
            $head = rtrim($content, "\0");
            $zeros = strlen($content) - strlen($head);
            $content = $head;
        }
        $out[] = ['name' => $p[0], 'filename' => $p[1], 'content' => base64_encode($content), 'zeros' => $zeros, 'type' => $p[3]];
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
    if (getenv('GOLDEN_DEBUG') === '1') {
        // 只用于排查：错误页带上异常信息（生成正式黄金时不设 GOLDEN_DEBUG）
        $c = file_get_contents($repo . '/application/config.php');
        file_put_contents($conf . '/config.php', str_replace("'app_debug'              => false", "'app_debug' => true", $c));
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
        file_put_contents($entryFile, '<?php require ' . var_export(__DIR__ . DIRECTORY_SEPARATOR . 'admin_portrait_child.php', true) . ';');
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
