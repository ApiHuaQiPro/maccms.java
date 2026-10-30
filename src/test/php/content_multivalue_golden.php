<?php
/*
 * 多值字段编解码黄金向量生成脚本（MACC-27 / B03）。只读加载仓库内的 thinkphp/ 与 application/common.php，
 * 用合成输入调用旧函数 mac_play_list、mac_play_list_one、mac_art_list、mac_plot_list、mac_screenshot_list，
 * 把结果写成 JSON。不修改任何旧 PHP 文件，不连数据库，不读仓库外配置，不访问网络。
 *
 * 用法（仓库根目录）：
 *   php src/test/php/content_multivalue_golden.php > src/test/resources/content/multivalue/php-vectors.json
 * MAC_PLAYER_SORT 是常量（application/common/behavior/Init.php:55），每个取值在单独的子进程中运行。
 * 说明见 docs/architecture/11-content-read-completion.md 第 3 节。
 */

// 与 application/common.php:12 相同：notice/warning 不中断（缺少 $、缺少分组时旧代码得到 null 继续执行）
error_reporting(E_ERROR | E_PARSE);

$repo = realpath(__DIR__ . '/../../../php');

/** 合成播放器/下载器/服务器配置（形态同 application/extra/vodplayer.php、voddowner.php、vodserver.php）。 */
function players()
{
    return [
        'p_a' => ['status' => '1', 'sort' => '10', 'show' => '播放器A'],
        'p_b' => ['status' => '1', 'sort' => '30', 'show' => '播放器B'],
        'p_off' => ['status' => '0', 'sort' => '99', 'show' => '停用播放器'],
        'p_c' => ['status' => '1', 'sort' => '30', 'show' => '与B同序'],
        'p_9' => ['status' => '1', 'sort' => '9', 'show' => '序号9'],
        'p_int' => ['status' => 1, 'sort' => 20, 'show' => '整数配置'],
        'p_nosort' => ['status' => '1', 'show' => '无sort'],
        'p_str' => ['status' => '1', 'sort' => 'abc', 'show' => '非数字sort'],
    ];
}

function downers()
{
    return [
        'd_a' => ['status' => '1', 'sort' => '5', 'show' => '下载A'],
        'd_b' => ['status' => '1', 'sort' => '50', 'show' => '下载B'],
        'd_off' => ['status' => '0', 'sort' => '90', 'show' => '停用下载'],
    ];
}

function servers()
{
    return [
        's1' => ['status' => '1', 'sort' => '1', 'show' => '服务器1', 'des' => 'http://srv1.example.test/'],
    ];
}

/** [id, url_one, from_one, server_one（null 表示两参数调用，与 mac_play_list 内部调用相同）] */
function play_list_one_cases()
{
    return [
        ['one.basic', "第1集\$http://a.test/1.m3u8#第2集\$http://a.test/2.m3u8", 'dplayer', null],
        ['one.empty-title', "\$http://a.test/1.m3u8", 'dplayer', null],
        ['one.no-dollar', "http://a.test/1.m3u8#http://a.test/2.m3u8", 'dplayer', null],
        ['one.trailing-hash', "第1集\$u1#", 'dplayer', null],
        ['one.gaps', "#第1集\$u1##第2集\$u2", 'dplayer', null],
        ['one.crlf', "第1集\$u1\r\n#第2集\$u2\r\n", 'dplayer', null],
        ['one.zero-segment', "0#第2集\$u2", 'dplayer', null],
        ['one.zero-url', "第1集\$0", 'dplayer', null],
        ['one.from-part', "第1集\$u1\$p2#第2集\$u2\$", 'dplayer', null],
        ['one.from-zero', "第1集\$u1\$0", 'dplayer', null],
        ['one.extra-parts', "a\$b\$c\$d", 'dplayer', null],
        ['one.server-prefix', "第1集\$u1#u2", 'dplayer', 'http://srv.test/'],
        ['one.server-prefix-no-url', "第1集\$#\$", 'dplayer', 'http://srv.test/'],
        ['one.empty', '', 'dplayer', null],
        ['one.only-hash', '#', 'dplayer', null],
        ['one.space-segment', ' ', 'dplayer', null],
        ['one.double-dollar', '$$', 'dplayer', null],
        ['one.empty-from-one', "第1集\$u1", '', null],
        ['one.entities', "&lt;b&gt;第1集&amp;\$http://a.test/x?a=1&amp;b=2", 'dplayer', null],
        ['one.unicode', "第一集·上\$http://例子.test/视频.mp4", 'dplayer', null],
    ];
}

/** [id, flag, from, url, server, note] */
function play_list_cases()
{
    return [
        ['list.basic', 'play', 'p_a$$$p_b', "1\$u1#2\$u2$$$1\$v1", '$$$s1', 'n1$$$n2'],
        ['list.disabled-filtered', 'play', 'p_a$$$p_off$$$p_b', "1\$a$$$1\$off$$$1\$b", '', ''],
        ['list.unknown-player', 'play', 'p_a$$$nope', "1\$a$$$1\$x", '', ''],
        ['list.sort-tie', 'play', 'p_c$$$p_b', "1\$c$$$1\$b", '', ''],
        ['list.sort-numeric', 'play', 'p_9$$$p_a', "1\$n$$$1\$a", '', ''],
        ['list.int-config', 'play', 'p_int$$$p_a', "1\$i$$$1\$a", '', ''],
        ['list.missing-sort', 'play', 'p_nosort$$$p_a', "1\$m$$$1\$a", '', ''],
        ['list.non-numeric-sort', 'play', 'p_str$$$p_a', "1\$s$$$1\$a", '', ''],
        ['list.fewer-urls', 'play', 'p_a$$$p_b', "1\$u1", '', 'only'],
        ['list.trailing-groups', 'play', 'p_a$$$', "1\$u1$$$", 's1$$$', 'n1$$$'],
        ['list.from-zero', 'play', '0', "1\$u1", '', ''],
        ['list.from-empty', 'play', '', "1\$u1", '', ''],
        ['list.crlf', 'play', 'p_a$$$p_b', "1\$u1\r\n#2\$u2\$\$\$\r\n1\$v1", 's1$$$', "n\r\n\$\$\$m"],
        ['list.same-from-twice', 'play', 'p_a$$$p_a', "1\$x$$$1\$y", '', ''],
        ['list.all-disabled', 'play', 'p_off$$$nope', "1\$x$$$1\$y", '', ''],
        ['list.empty-groups', 'play', 'p_a$$$p_b', "$$$#", '', ''],
        ['list.three-sorted', 'play', 'p_a$$$p_9$$$p_b$$$p_int', "1\$a$$$1\$n$$$1\$b$$$1\$i", 's1$$$$$$s1', 'a$$$b$$$c$$$d'],
        ['list.down', 'down', 'd_a$$$d_off$$$d_b', "1\$da$$$1\$doff$$$1\$db", '', ''],
        ['list.down-uses-downer', 'down', 'p_a$$$d_a', "1\$pa$$$1\$da", '', ''],
    ];
}

/** [id, title, note, content] */
function art_list_cases()
{
    return [
        ['art.aligned', 't1$$$t2$$$t3', 'n1$$$n2$$$n3', '<p>c1</p>$$$<p>c2</p>$$$<p>c3</p>'],
        ['art.missing-title', 't1', '', 'c1$$$c2'],
        ['art.empty-content', 't1', 'n1', ''],
        ['art.zero-content', 't1', 'n1', '0'],
        ['art.zero-title', '0', '0', 'a'],
        ['art.trailing', 't1$$$', 'n1$$$', 'a$$$'],
        ['art.crlf-html', "t\r\n1\$\$\$t2", 'n1', "<p>a</p>\r\n\$\$\$\r\n<p>b</p>"],
        ['art.more-titles', 't1$$$t2$$$t3', 'n1$$$n2$$$n3', 'only'],
        ['art.single', '', '', '<p>正文</p>'],
    ];
}

/** [id, name, detail] */
function plot_list_cases()
{
    return [
        ['plot.aligned', '第1集$$$第2集', '剧情1$$$剧情2'],
        ['plot.missing-detail', '第1集$$$第2集', '剧情1'],
        ['plot.empty-name', '', '剧情1'],
        ['plot.zero-name', '0', '剧情1'],
        ['plot.trailing', '第1集$$$', '剧情1$$$'],
        ['plot.more-details', '第1集', '剧情1$$$剧情2'],
        ['plot.zero-detail', '第1集', '0'],
        ['plot.crlf', "第1集\r\n\$\$\$第2集", "<p>a</p>\r\n\$\$\$<p>b</p>"],
    ];
}

/** [id, raw]（调用方只在 !empty 时调用，见 application/common/model/Art.php:422-424） */
function screenshot_cases()
{
    return [
        ['shot.titled', 't1$u1#t2$u2'],
        ['shot.untitled', 'u1#u2'],
        ['shot.gaps', '#u1##'],
        ['shot.empty-url', 't$'],
        ['shot.zero-segment', '0#u'],
        ['shot.empty-title', '$u'],
        ['shot.crlf', "t1\$u1\r\n#u2"],
        ['shot.extra-parts', 't$u$x'],
    ];
}

if ($argc < 2) {
    // 父进程：两个 MAC_PLAYER_SORT 分支各一个子进程，合并输出
    $out = [];
    foreach (['sort1', 'sort0'] as $branch) {
        $cmd = escapeshellarg(PHP_BINARY) . ' ' . escapeshellarg(__FILE__) . ' ' . escapeshellarg($branch);
        $json = shell_exec($cmd);
        $data = json_decode($json, true);
        if (!is_array($data)) {
            fwrite(STDERR, "branch $branch failed:\n$json\n");
            exit(1);
        }
        $out[$branch] = $data;
    }
    $flags = JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES;
    $lines = ['{'];
    $lines[] = '  "description": ' . json_encode('MacCMS 多值字段编解码黄金向量：由 src/test/php/content_multivalue_golden.php '
            . '执行仓库内旧 PHP 函数生成；输入全部为合成数据。PHP null 原样记为 null。不要手工修改，修改生成脚本后重新生成。', $flags) . ',';
    $lines[] = '  "php": ' . json_encode(PHP_VERSION, $flags) . ',';
    $lines[] = '  "sources": ' . json_encode([
            'mac_art_list' => 'application/common.php:1294-1318',
            'mac_plot_list' => 'application/common.php:1320-1341',
            'mac_play_list' => 'application/common.php:1346-1407',
            'mac_screenshot_list' => 'application/common.php:1415-1432',
            'mac_play_list_one' => 'application/common.php:1434-1455',
            'lang' => 'application/lang/zh-cn.php:84-85（the=第、episode=集）',
            'MAC_PLAYER_SORT' => 'application/common/behavior/Init.php:55（app.player_sort）',
        ], $flags) . ',';
    $lines[] = '  "config": ' . json_encode(['vodplayer' => players(), 'voddowner' => downers(), 'vodserver' => servers()], $flags) . ',';
    $sections = ['playListOne', 'playList', 'artList', 'plotList', 'screenshotList'];
    foreach ($sections as $si => $section) {
        if ($section === 'playList') {
            $lines[] = '  "playList": {';
            foreach (['sort1', 'sort0'] as $bi => $branch) {
                $lines[] = '    "' . $branch . '": [';
                $n = count($out[$branch]['playList']);
                foreach ($out[$branch]['playList'] as $i => $v) {
                    $lines[] = '      ' . json_encode($v, $flags) . ($i < $n - 1 ? ',' : '');
                }
                $lines[] = '    ]' . ($bi === 0 ? ',' : '');
            }
            $lines[] = '  },';
            continue;
        }
        $lines[] = '  "' . $section . '": [';
        $n = count($out['sort1'][$section]);
        foreach ($out['sort1'][$section] as $i => $v) {
            $lines[] = '    ' . json_encode($v, $flags) . ($i < $n - 1 ? ',' : '');
        }
        $lines[] = '  ]' . ($si < count($sections) - 1 ? ',' : '');
    }
    $lines[] = '}';
    echo implode("\n", $lines), "\n";
    exit(0);
}

// ---- 子进程：引导（与 index.php、thinkphp/start.php 相同的常量，不执行 App::run） ----
$branch = $argv[1];
define('APP_PATH', $repo . '/application/');
define('ROOT_PATH', $repo . '/');
define('RUNTIME_PATH', sys_get_temp_dir() . '/maccms-multivalue-golden-nonexistent/');
define('ENTRANCE', 'index');
define('MAC_PLAYER_SORT', $branch === 'sort1' ? '1' : '0');
require $repo . '/thinkphp/base.php';
\think\Config::set(include APP_PATH . 'config.php');
require THINK_PATH . 'helper.php';
require APP_PATH . 'common.php';
error_reporting(E_ERROR | E_PARSE);
\think\Lang::load(APP_PATH . 'lang/zh-cn.php');
\think\Config::set('vodplayer', players());
\think\Config::set('voddowner', downers());
\think\Config::set('vodserver', servers());

/** 关联数组 → 有序列表（保留 PHP 的键，键即 sid/nid/page/序号），便于 JSON 中保持顺序。 */
function keyed($arr)
{
    $out = [];
    foreach ($arr as $k => $v) {
        $out[] = ['key' => $k, 'value' => $v];
    }
    return $out;
}

$result = ['playListOne' => [], 'playList' => [], 'artList' => [], 'plotList' => [], 'screenshotList' => []];
foreach (play_list_one_cases() as $c) {
    list($id, $url, $from, $server) = $c;
    $r = $server === null ? mac_play_list_one($url, $from) : mac_play_list_one($url, $from, $server);
    $result['playListOne'][] = ['id' => $id, 'urlOne' => $url, 'fromOne' => $from, 'serverOne' => $server,
        'out' => keyed($r)];
}
foreach (play_list_cases() as $c) {
    list($id, $flag, $from, $url, $server, $note) = $c;
    $r = mac_play_list($from, $url, $server, $note, $flag);
    $rows = [];
    foreach ($r as $k => $v) {
        $v['urls'] = keyed($v['urls']);
        $rows[] = ['key' => $k, 'value' => $v];
    }
    $result['playList'][] = ['id' => $id, 'flag' => $flag, 'from' => $from, 'url' => $url, 'server' => $server,
        'note' => $note, 'out' => $rows];
}
foreach (art_list_cases() as $c) {
    list($id, $title, $note, $content) = $c;
    $result['artList'][] = ['id' => $id, 'title' => $title, 'note' => $note, 'content' => $content,
        'out' => keyed(mac_art_list($title, $note, $content))];
}
foreach (plot_list_cases() as $c) {
    list($id, $name, $detail) = $c;
    $result['plotList'][] = ['id' => $id, 'name' => $name, 'detail' => $detail,
        'out' => keyed(mac_plot_list($name, $detail))];
}
foreach (screenshot_cases() as $c) {
    list($id, $raw) = $c;
    $result['screenshotList'][] = ['id' => $id, 'raw' => $raw, 'out' => keyed(mac_screenshot_list($raw))];
}
echo json_encode($result, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
