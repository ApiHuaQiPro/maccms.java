<?php
/*
 * D-B09-9（B13，MACC-37）前台头像上传登记 mac_annex 的 PHP 黄金样本生成脚本（契约见 docs/architecture/22-b13-admin-content.md
 * 第 7.10 节、18-b09-member-account.md D-B09-9）。
 *
 * 每个场景是一串真实的前台请求：经 portrait_annex_child.php 跑完整的 ThinkPHP 路由、index/controller/User 的构造函数（label_user 按
 * cookie 认出会员）与 User::portrait → common/model/Upload::upload（think\Image 缩略图、UPDATE mac_user、Annex::infoData/saveData）。
 * 每个请求记录状态码、Content-Type 与正文字节（base64），以及请求后 mac_user、mac_annex 的全部行与临时站点根目录 upload/ 下的文件清单。
 * 文本列一律十六进制。请求前后的秒级时间（started/after）用于比较写入的 annex_time。
 *
 * 场景之间互不依赖：每个场景先清空 mac_user、mac_annex 并把自增计数复位（一次性库上的测试夹具；新版的复放测试做同样的事，使新增行的
 * 主键相同），写入自己的会员与附件行，使用新的配置、会话与站点目录。上传的图片由本脚本用 GD 按固定参数画出（WebP 取仓库的测试样本
 * src/test/resources/member/webp/lossy.webp），字节写进黄金，新版复放时上传同样的字节。
 *
 * 流程：外部管理账号新建一次性库 maccms_it_<时间>_<随机> → 导入 install.sql → 逐个场景执行 → 输出 JSON → 删除一次性库与临时目录。
 * 不修改任何旧 PHP 文件，不连接、不读写任何已有库（包括 maccms_dev），不读仓库外配置，不访问网络。需要仓库根目录下的 vendor/
 * （不在 git 中，按 AGENTS.md 从备份恢复；用其中的 think-image）。
 *
 * 管理账号只经环境变量传入：MACCMS_IT_ADMIN_URL、MACCMS_IT_ADMIN_USERNAME、MACCMS_IT_ADMIN_PASSWORD；另需 PHP_CGI。
 * 用法（仓库根目录，PHP 8.0 与 7.3 各一份）：
 *   PHP_CGI=D:/Tools/php/8.0/php-cgi.exe D:/Tools/php/8.0/php.exe src/test/php/portrait_annex_golden.php \
 *       > src/test/resources/member/portrait-annex-php80-golden.json
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

const ENTRY = '/index.php';
/** 跟踪的表与主键（顺序即输出顺序）。 */
const TABLES = ['mac_user' => 'user_id', 'mac_annex' => 'annex_id'];

$pdo = new PDO("mysql:host=$host;port=$port;charset=utf8", $adminUser, $adminPassword,
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("CREATE DATABASE `$dbName` DEFAULT CHARACTER SET utf8");
$work = sys_get_temp_dir() . '/maccms-portrait-golden-' . bin2hex(random_bytes(4));
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

/** 每个场景都带上的配置（仓库值之上）：会员与头像功能打开，头像 100x100，上传存本地。 */
function base_config()
{
    return ['user' => ['status' => '1', 'portrait_status' => '1', 'portrait_size' => '100x100'],
        'upload' => ['protocol' => 'http', 'mode' => 'local', 'watermark' => '0', 'thumb' => '0'],
        'app' => ['cache_core' => '0', 'cache_page' => '0', 'lang' => 'zh-cn']];
}

/** 会员：801（头像目录 user/1）、15（user/5）。登录 cookie 的 user_check = md5(random-名-ID-)（common/model/User.php:446）。 */
function users()
{
    return [
        ['user_id' => 801, 'group_id' => 2, 'user_name' => 'portrait01', 'user_pwd' => md5('x'), 'user_status' => 1,
            'user_random' => 'r801', 'user_portrait' => '', 'user_reg_time' => 1700000000, 'user_login_time' => 1700000000],
        ['user_id' => 15, 'group_id' => 2, 'user_name' => 'portrait15', 'user_pwd' => md5('y'), 'user_status' => 1,
            'user_random' => 'r15', 'user_portrait' => 'upload/user/5/15.jpg', 'user_reg_time' => 1700000000,
            'user_login_time' => 1700000000],
    ];
}

function login_cookie(array $u)
{
    return 'user_id=' . $u['user_id'] . '; user_name=' . rawurlencode($u['user_name']) . '; user_check='
        . md5($u['user_random'] . '-' . $u['user_name'] . '-' . $u['user_id'] . '-');
}

function as_user($id)
{
    foreach (users() as $u) {
        if ($u['user_id'] === $id) {
            return login_cookie($u);
        }
    }
    throw new RuntimeException('user');
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

function file_part($name, $content, $type)
{
    return ['multipart' => [['file', $name, $content, $type]]];
}

function upload($label, $userId, array $body, array $opt = [])
{
    return array_merge(['label' => $label, 'cookie' => $userId === null ? null : as_user($userId), 'multipart' => null,
        'post' => null], $body, $opt);
}

function sc($label, array $steps, array $opt = [])
{
    return array_merge(['label' => $label, 'config' => [], 'annex' => [], 'files' => [], 'steps' => $steps], $opt);
}

function scenarios($repo)
{
    $jpeg = image_bytes('jpeg', 300, 200);
    $png = image_bytes('png', 120, 80);
    $gif = image_bytes('gif', 64, 48);
    $webp = file_get_contents(dirname($repo) . '/src/test/resources/member/webp/lossy.webp');
    return [
        // 文件上传：原图落盘为 <id>.jpg 后被 100x100 缩略图覆盖，UPDATE user_portrait，登记 annex_type=image；再次上传同一文件不重复登记
        sc('jpeg', [
            upload('first', 801, file_part('a.jpg', $jpeg, 'image/jpeg')),
            upload('again', 801, file_part('b.JPEG', $jpeg, 'image/jpeg')),
        ]),
        sc('png', [upload('upload', 801, file_part('me.PNG', $png, 'image/png'))]),
        sc('gif', [upload('upload', 801, file_part('g.gif', $gif, 'image/gif'))]),
        // 静态 WebP：旧版按原格式（WebP 字节）写进 .jpg，新版重新编码为 JPEG（D-B09-13）；登记行除 annex_size 外相同
        sc('webp', [upload('upload', 801, file_part('w.webp', $webp, 'image/webp'))]),
        // imgdata（data URL）：annex_type 取 data URL 里的类型
        sc('imgdata-png', [upload('upload', 801, ['post' => ['imgdata' => 'data:image/png;base64,' . base64_encode($png)]])]),
        sc('imgdata-jpeg', [upload('upload', 801, ['post' => ['imgdata' => 'data:image/jpeg;base64,' . base64_encode($jpeg)]])]),
        // 头像目录 user/5；这个文件已由后台登记过（其他会员上传前的旧行）：不再登记，旧行不变
        sc('preregistered', [upload('upload', 15, file_part('p.png', $png, 'image/png'))], [
            'annex' => [['annex_id' => 7, 'annex_time' => 1700000100, 'annex_file' => 'upload/user/5/15.jpg', 'annex_size' => 9,
                'annex_type' => 'file']],
            'files' => ['upload/user/5/15.jpg' => 'old']]),
        sc('dir5', [upload('upload', 15, file_part('q.gif', $gif, 'image/gif'))], [
            'annex' => [['annex_id' => 3, 'annex_time' => 1700000100, 'annex_file' => 'upload/vod/20260101-1/a.jpg',
                'annex_size' => 3, 'annex_type' => 'image']]]),
        // 扩展名属于"文件"类、"媒体"类而内容是图片：照样生成头像，登记的 annex_type 是扩展名的分类 file / media（Upload.php:144-152）
        sc('file-ext', [upload('upload', 801, file_part('n.txt', $jpeg, 'text/plain'))]),
        sc('media-ext', [upload('upload', 801, file_part('v.MP4', $png, 'video/mp4'))]),
        // 失败的上传都不登记
        sc('not-image', [upload('upload', 801, file_part('n.jpg', str_repeat('not an image ', 80), 'image/jpeg'))]),
        sc('forbidden-ext', [upload('upload', 801, file_part('x.exe', $jpeg, 'application/octet-stream'))]),
        sc('php-content', [upload('upload', 801, file_part('x.jpg', "<?php echo 1;\n", 'image/jpeg'))]),
        sc('imgdata-bad-type', [upload('upload', 801, ['post' => ['imgdata' => 'data:image/svg;base64,' . base64_encode('<svg/>')]])]),
        sc('no-file', [upload('upload', 801, ['post' => ['x' => '1']])]),
        sc('disabled', [upload('upload', 801, file_part('a.jpg', $jpeg, 'image/jpeg'))], ['config' => ['user' => ['portrait_status' => '0']]]),
        sc('not-logged-in', [upload('upload', null, file_part('a.jpg', $jpeg, 'image/jpeg'))]),
    ];
}

// ====================================================================== 引擎

function generate($repo, $pdo, $phpCgi, $work, $host, $port, $db, $user, $password)
{
    $out = [];
    $base = deep_merge(include $repo . '/application/extra/maccms.php', base_config());
    $types = column_types($pdo);
    foreach (scenarios($repo) as $sc) {
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
        $steps = [];
        foreach ($sc['steps'] as $st) {
            $headers = ['X-Requested-With' => 'XMLHttpRequest'];
            if ($st['cookie'] !== null) {
                $headers['Cookie'] = $st['cookie'];
            }
            if ($st['multipart'] !== null) {
                list($post, $contentType) = multipart($st['multipart']);
            } else {
                $post = http_build_query($st['post']);
                $contentType = 'application/x-www-form-urlencoded';
            }
            $req = ['path' => '/user/portrait', 'query' => '', 'method' => 'POST', 'post' => $post,
                'contentType' => $contentType, 'headers' => $headers, 'remote' => '127.0.0.1'];
            $started = time();
            list($status, $respHeaders, $setCookies, $body) = run_request($repo, $phpCgi, $conf, $runtime, $site, $req);
            $after = time();
            $steps[] = ['label' => $st['label'], 'request' => ['method' => 'POST', 'path' => '/user/portrait',
                'cookie' => $st['cookie'], 'post' => $st['multipart'] !== null ? null : $post,
                'multipart' => $st['multipart'] !== null ? multipart_describe($st['multipart']) : null],
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
    return json_encode(['generator' => 'backend/src/test/php/portrait_annex_golden.php', 'php' => PHP_VERSION,
            'gd' => gd_info()['GD Version'], 'timezone' => 'PRC', 'entry' => ENTRY, 'host' => 'www.example.test',
            'baseConfig' => base_config(), 'scenarios' => $out],
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

/** 清空跟踪的表并复位自增计数（一次性库上的测试夹具），写入场景的行。 */
function seed($pdo, array $seed)
{
    foreach (array_keys(TABLES) as $t) {
        $pdo->exec("DELETE FROM $t");
        $pdo->exec("ALTER TABLE $t AUTO_INCREMENT = 1");
    }
    foreach ($seed as $table => $rows) {
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

/** upload/ 下的文件（相对站点根的路径 → 大小、sha256 与开头 4 字节的十六进制）。 */
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

/** 以 php-cgi 执行一个前台请求。返回 [status, headers(小写名 => 值), setCookies, body]。 */
function run_request($repo, $phpCgi, $conf, $runtime, $site, $req)
{
    $query = $req['query'];
    $method = $req['method'];
    $post = $req['post'];
    $entryDir = dirname($runtime) . DIRECTORY_SEPARATOR . 'entry';
    $entryFile = $entryDir . DIRECTORY_SEPARATOR . 'index.php';
    if (!is_file($entryFile)) {
        @mkdir($entryDir, 0777, true);
        file_put_contents($entryFile, '<?php require ' . var_export(__DIR__ . DIRECTORY_SEPARATOR . 'portrait_annex_child.php', true) . ';');
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
        'PATH_INFO' => $req['path'],
        'REMOTE_ADDR' => $req['remote'],
        'REMOTE_PORT' => '50000',
        'DOCUMENT_ROOT' => $entryDir,
        'SystemRoot' => getenv('SystemRoot') ?: 'C:\\Windows',
        'TEMP' => $runtime,
        'TMP' => $runtime,
        'CONTENT_TYPE' => $req['contentType'],
        'CONTENT_LENGTH' => (string)strlen($post),
    ];
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
