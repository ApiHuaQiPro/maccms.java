<?php
/*
 * B16 黄金向量生成脚本（路线图 B16，MACC-40）：只读加载仓库内的 thinkphp/ 与 application/common.php，在内存中套用合成的
 * view/path 配置，执行旧代码得到：
 *   1. 静态页地址与文件名：mac_url_*（静态分支，application/common.php:1732-2428）与 reset_html_filename（:2802-2824），
 *      以及 admin/controller/Make.php 里不经 mac_url 的固定文件名（index/wap_index/map/rss/label）；
 *   2. 定时任务的执行判断（api/controller/Timming.php:21-37 的条件原样复制，time() 换成合成的时刻）；
 *   3. runtime 回写后的 extra/timming.php 字节（真实的 mac_arr2file 写进临时目录后读回）。
 * 不修改任何旧 PHP 文件，不连数据库，不读仓库外配置，不访问网络。
 *
 * 用法（仓库根目录）：
 *   php src/test/php/b16_static_timming_golden.php > src/test/resources/staticgen/b16-php80-golden.json
 * 说明见 docs/architecture/25-b16-static-timming.md 第 9 节。
 */

error_reporting(E_ALL & ~E_NOTICE & ~E_WARNING & ~E_DEPRECATED);

$repo = realpath(__DIR__ . '/../../../php');
define('APP_PATH', $repo . '/application/');
define('ROOT_PATH', $repo . '/');
define('RUNTIME_PATH', sys_get_temp_dir() . '/maccms-b16-golden-nonexistent/');
define('BIND_MODULE', 'admin');
define('ENTRANCE', 'admin');
define('IN_FILE', '/admin.php');
require $repo . '/thinkphp/base.php';
\think\Config::set(include APP_PATH . 'config.php');
require THINK_PATH . 'helper.php';
require APP_PATH . 'common.php';
error_reporting(E_ALL & ~E_NOTICE & ~E_WARNING & ~E_DEPRECATED);
// 旧站 application/config.php 的 default_timezone
date_default_timezone_set('PRC');

$base = include APP_PATH . 'extra/maccms.php';
define('MAC_PATH', '/');

function branches()
{
    $allStatic = ['index' => '2', 'map' => '2', 'rss' => '2', 'label' => '2', 'vod_type' => '2', 'art_type' => '2',
        'topic_index' => '2', 'topic_detail' => '2', 'vod_detail' => '2', 'vod_play' => '2', 'vod_down' => '2',
        'art_detail' => '2'];
    return [
        // 仓库默认的 path 规则，播放/下载页模式 2（单页 + ?id-sid-nid）
        'repo_paths_mode2' => ['view' => $allStatic, 'path' => []],
        // 含各种占位符的规则，模式 3（每集一页），分页符 -，后缀 htm
        'placeholders_mode3' => [
            'view' => array_merge($allStatic, ['vod_play' => '3', 'vod_down' => '3']),
            'path' => [
                'topic_index' => 'zt/list',
                'topic_detail' => 'zt/{en}-{md5}',
                'vod_type' => '{type_en}/',
                'vod_detail' => 'v/{type_pen}/{type_en}/{en}/{id}',
                'vod_play' => 'p/{year}/{month}{day}/{id}-{md5}',
                'vod_down' => 'd/{type_id}/{type_pid}/{id}',
                'art_type' => 'a/{id}/{md5}',
                'art_detail' => 'a/{year}{month}/{en}',
                'page_sp' => '-',
                'suffix' => 'htm',
            ],
        ],
        // 以 / 结尾与以 / 开头的规则、{page} 占位符，模式 4（每个播放源一页），后缀 shtml
        'slashes_mode4' => [
            'view' => array_merge($allStatic, ['vod_play' => '4', 'vod_down' => '4']),
            'path' => [
                'topic_index' => 'topic/',
                'topic_detail' => '/zhuanti/{id}/',
                'vod_type' => 'list/{id}/{page}',
                'vod_detail' => '/vod/{id}/',
                'vod_play' => 'play/{id}/',
                'vod_down' => 'down/{id}/index',
                'art_type' => 'artlist/{type_en}/index',
                'art_detail' => 'art/{type_id}/{id}/',
                'page_sp' => '_',
                'suffix' => 'shtml',
            ],
        ],
    ];
}

// ---- 合成数据（形状同旧 listData/getCache 的结果：type 为本分类行，type_1 为上级分类行，顶级分类没有 type_1） ----
$typeTop = ['type_id' => 1, 'type_en' => 'dianying', 'type_mid' => 1, 'type_pid' => 0];
$typeSub = ['type_id' => 6, 'type_en' => 'dongzuopian', 'type_mid' => 1, 'type_pid' => 1];
$artType = ['type_id' => 21, 'type_en' => 'zixun', 'type_mid' => 2, 'type_pid' => 0];
$vodSub = ['vod_id' => 123, 'vod_en' => 'hecheng-shipin', 'vod_time' => 1700000000, 'type_id' => 6,
    'type' => $typeSub, 'type_1' => $typeTop];
$vodTop = ['vod_id' => 9, 'vod_en' => 'dingji', 'vod_time' => 1704038400, 'type_id' => 1, 'type' => $typeTop, 'type_1' => null];
$art = ['art_id' => 55, 'art_en' => 'wenzhang55', 'art_time' => 1700000000, 'type_id' => 21, 'type' => $artType,
    'type_1' => null];
$topic = ['topic_id' => 7, 'topic_en' => 'zhuanti7'];

$cases = [
    // Make.php 的固定文件名（不经 mac_url）
    ['index', function () { return 'index.html'; }],
    ['index.wap', function () { return 'wap_index.html'; }],
    ['map', function () { return 'map.html'; }],
    ['rss.baidu', function () { return 'rss/baidu.xml'; }],
    ['rss.index.p2', function () { return 'rss/index' . $GLOBALS['config']['path']['page_sp'] . '2.xml'; }],
    ['label.dotted', function () { return 'label/about.html'; }],
    ['label.bare', function () { return 'label/about'; }],
    // mac_url 静态分支
    ['vod.type.top', function () use ($typeTop) { return mac_url_type($typeTop, ['id' => 1, 'page' => 1]); }],
    ['vod.type.top.p2', function () use ($typeTop) { return mac_url_type($typeTop, ['id' => 1, 'page' => 2]); }],
    ['vod.type.sub.p3', function () use ($typeSub) { return mac_url_type($typeSub, ['id' => 6, 'page' => 3]); }],
    ['art.type', function () use ($artType) { return mac_url_type($artType, ['id' => 21, 'page' => 1]); }],
    ['art.type.p2', function () use ($artType) { return mac_url_type($artType, ['id' => 21, 'page' => 2]); }],
    ['vod.detail.sub', function () use ($vodSub) { return mac_url_vod_detail($vodSub); }],
    ['vod.detail.top', function () use ($vodTop) { return mac_url_vod_detail($vodTop); }],
    ['vod.play.1-1', function () use ($vodSub) { return mac_url_vod_play($vodSub, ['sid' => 1, 'nid' => 1]); }],
    ['vod.play.2-3', function () use ($vodSub) { return mac_url_vod_play($vodSub, ['sid' => 2, 'nid' => 3]); }],
    ['vod.play.top.1-12', function () use ($vodTop) { return mac_url_vod_play($vodTop, ['sid' => 1, 'nid' => 12]); }],
    ['vod.down.1-1', function () use ($vodSub) { return mac_url_vod_down($vodSub, ['sid' => 1, 'nid' => 1]); }],
    ['vod.down.3-2', function () use ($vodTop) { return mac_url_vod_down($vodTop, ['sid' => 3, 'nid' => 2]); }],
    ['art.detail.p1', function () use ($art) { return mac_url_art_detail($art, ['page' => 1]); }],
    ['art.detail.p2', function () use ($art) { return mac_url_art_detail($art, ['page' => 2]); }],
    ['topic.index.p1', function () { return mac_url_topic_index(['page' => 1]); }],
    ['topic.index.p2', function () { return mac_url_topic_index(['page' => 2]); }],
    ['topic.detail', function () use ($topic) { return mac_url_topic_detail($topic); }],
];

$out = ['description' => 'B16 黄金向量：由 src/test/php/b16_static_timming_golden.php 执行仓库内旧 PHP 代码生成（PHP '
    . PHP_VERSION . '）；输入全部为合成数据。不要手工修改，修改生成脚本后重新生成。', 'paths' => []];
foreach (branches() as $name => $b) {
    $config = $base;
    $config['view'] = array_merge($config['view'], $b['view']);
    $config['path'] = array_merge($config['path'], $b['path']);
    $GLOBALS['config'] = $config;
    $rows = [];
    foreach ($cases as $c) {
        $url = $c[1]();
        // Make::info 对模式 3/4 的播放/下载页先 explode('?')（Make.php:829-830、844-845）；其余直接交给 buildHtml。
        // reset_html_filename 本身也会去掉 ? 之后的部分，两种写法结果相同，这里统一直接调用
        $rows[] = ['id' => $c[0], 'url' => $url, 'file' => reset_html_filename($url)];
    }
    $out['paths'][] = ['branch' => $name, 'view' => $config['view'], 'path' => $config['path'], 'cases' => $rows];
}

// ---- 定时任务的执行判断（Timming.php:21-37，原样复制，time() 换成 $now） ----
function timming_due($v, $now, $enforce)
{
    $param = ['enforce' => $enforce];
    $oldweek = null;
    $oldhours = null;
    if(!empty($v['runtime'])) { $oldweek= date('w',$v['runtime']); $oldhours= date('H',$v['runtime']); }
    $curweek= date('w',$now) ;	$curhours= date("H",$now);
    if(strlen($oldhours)==1 && intval($oldhours) <10){ $oldhours= '0'.$oldhours; }
    if(strlen($curhours)==1 && intval($curhours) <10){ $curhours= substr($curhours,1,1); }
    $last = (!empty($v['runtime']) ? date('Y-m-d H:i:s',$v['runtime']) : '从未');
    $due = $v['status']=='1' &&
        ( empty($v['runtime']) || ($oldweek."-".$oldhours) != ($curweek."-".$curhours) && strpos($v['weeks'],$curweek)!==false && strpos($v['hours'],$curhours)!==false  || $param['enforce'] =='1');
    return ['due' => $due, 'last' => $last];
}

$allHours = '00,01,02,03,04,05,06,07,08,09,10,11,12,13,14,15,16,17,18,19,20,21,22,23';
// 2026-10-26 是星期一（w=1）
$mon0930 = 1792978200;   // 2026-10-26 09:30:00 PRC
$schedules = [
    ['id' => 'never.run', 'task' => ['status' => '1', 'weeks' => '1', 'hours' => '10', 'runtime' => ''], 'now' => $mon0930],
    ['id' => 'never.run.zero', 'task' => ['status' => '1', 'weeks' => '1', 'hours' => '10', 'runtime' => 0], 'now' => $mon0930],
    ['id' => 'closed', 'task' => ['status' => '0', 'weeks' => '1', 'hours' => $allHours, 'runtime' => ''], 'now' => $mon0930],
    ['id' => 'closed.enforce', 'task' => ['status' => '0', 'weeks' => '1', 'hours' => $allHours, 'runtime' => 1], 'now' => $mon0930, 'enforce' => '1'],
    ['id' => 'same.hour', 'task' => ['status' => '1', 'weeks' => '1', 'hours' => $allHours, 'runtime' => $mon0930 - 600], 'now' => $mon0930],
    ['id' => 'same.hour.enforce', 'task' => ['status' => '1', 'weeks' => '1', 'hours' => $allHours, 'runtime' => $mon0930 - 600], 'now' => $mon0930, 'enforce' => '1'],
    ['id' => 'prev.hour', 'task' => ['status' => '1', 'weeks' => '1', 'hours' => $allHours, 'runtime' => $mon0930 - 3600], 'now' => $mon0930],
    ['id' => 'same.hour.last.week', 'task' => ['status' => '1', 'weeks' => '1', 'hours' => $allHours, 'runtime' => $mon0930 - 7 * 86400], 'now' => $mon0930],
    ['id' => 'week.not.listed', 'task' => ['status' => '1', 'weeks' => '2,3', 'hours' => $allHours, 'runtime' => $mon0930 - 86400], 'now' => $mon0930],
    ['id' => 'hour.not.listed', 'task' => ['status' => '1', 'weeks' => '1', 'hours' => '08,10', 'runtime' => $mon0930 - 86400], 'now' => $mon0930],
    ['id' => 'hour.substring', 'task' => ['status' => '1', 'weeks' => '1', 'hours' => '109', 'runtime' => $mon0930 - 86400], 'now' => $mon0930],
    ['id' => 'week.substring', 'task' => ['status' => '1', 'weeks' => '10', 'hours' => '09', 'runtime' => $mon0930 - 86400], 'now' => $mon0930],
    ['id' => 'sunday', 'task' => ['status' => '1', 'weeks' => '0', 'hours' => '23', 'runtime' => 1792893600], 'now' => 1792943999],
    ['id' => 'midnight', 'task' => ['status' => '1', 'weeks' => '1,2,3,4,5,6,0', 'hours' => '00', 'runtime' => 1792893600], 'now' => 1792944000],
    ['id' => 'empty.weeks', 'task' => ['status' => '1', 'weeks' => '', 'hours' => $allHours, 'runtime' => $mon0930 - 86400], 'now' => $mon0930],
    ['id' => 'status.int', 'task' => ['status' => 1, 'weeks' => '1', 'hours' => '09', 'runtime' => $mon0930 - 86400], 'now' => $mon0930],
    ['id' => 'status.spaced', 'task' => ['status' => ' 1', 'weeks' => '1', 'hours' => '09', 'runtime' => $mon0930 - 86400], 'now' => $mon0930],
    ['id' => 'runtime.string', 'task' => ['status' => '1', 'weeks' => '1', 'hours' => '09', 'runtime' => (string) ($mon0930 - 600)], 'now' => $mon0930],
    ['id' => 'runtime.zero.string', 'task' => ['status' => '1', 'weeks' => '1', 'hours' => '09', 'runtime' => '0'], 'now' => $mon0930],
    ['id' => 'enforce.other', 'task' => ['status' => '1', 'weeks' => '1', 'hours' => '09', 'runtime' => $mon0930 - 600], 'now' => $mon0930, 'enforce' => '2'],
];
$out['schedules'] = [];
foreach ($schedules as $s) {
    $enforce = isset($s['enforce']) ? $s['enforce'] : '';
    $r = timming_due($s['task'], $s['now'], $enforce);
    $out['schedules'][] = ['id' => $s['id'], 'task' => $s['task'], 'now' => $s['now'], 'enforce' => $enforce,
        'due' => $r['due'], 'last' => $r['last']];
}

// ---- runtime 回写：Timming.php:41-43 用 mac_arr2file 把整张表写回 ----
$list = include APP_PATH . 'extra/timming.php';
$tmp = sys_get_temp_dir() . '/maccms-b16-golden-' . getmypid() . '.php';
$runtimes = [];
foreach (['bb' => 1792978200, 'aa' => 1792981800] as $k => $t) {
    $list[$k]['runtime'] = $t;
    mac_arr2file($tmp, $list);
    $runtimes[] = ['set' => $k, 'runtime' => $t, 'content' => file_get_contents($tmp)];
}
@unlink($tmp);
$out['runtime'] = ['writes' => $runtimes];

echo json_encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT), "\n";
