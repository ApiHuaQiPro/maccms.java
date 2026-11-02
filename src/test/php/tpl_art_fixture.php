<?php
/*
 * APPK-2 S3-05 / C-33 ④：生成 ART-FIX 夹具 `art-infodata.sql`（V-16、§6.21 INFO-1～INFO-7、D1-FLD-1～5）。
 *
 * 为什么用生成器而不是手写整份 SQL：夹具要求 `mac_art` ≥ 50 行（AC-3 前置），且每行的 D1-FLD-3 列都要
 * 填哨兵（ART-FIX-5）。手写 56 行既容易漏列，也无法复核“7301～7304 只出现在 D1-FLD-3 列”。这里用一份
 * 数据表描述 + 一个渲染函数产出，规则集中在一处，复核时看本文件即可。
 *
 * 用法（仓库根目录，PHP 7.3.33）：
 *   D:/Tools/php/7.3/php.exe src/test/php/tpl_art_fixture.php \
 *     --out src/test/resources/phptemplate/model-fixtures/art-infodata.sql
 *
 * 生成结果必须满足 V-16 的 ART-FIX-1～5；脚本末尾自带断言，任何一条不成立就非 0 退出、不写文件。
 */

error_reporting(E_ALL);
ini_set('display_errors', 'On');

$out = null;
for ($i = 1; $i < $argc; $i++) {
    if ($argv[$i] === '--out' && isset($argv[$i + 1])) {
        $out = $argv[++$i];
    } elseif (substr($argv[$i], 0, 6) === '--out=') {
        $out = substr($argv[$i], 6);
    }
}
if ($out === null) {
    fwrite(STDERR, "用法：tpl_art_fixture.php --out <path>\n");
    exit(2);
}
if (!preg_match('~^(/|[A-Za-z]:)~', $out)) {
    $out = getcwd() . '/' . $out;
}

const TYPE_PARENT_A = 9101;   // type_pid = 0 的顶级分类
const TYPE_CHILD_A = 9102;    // type_pid = TYPE_PARENT_A 的子分类
const TYPE_PARENT_B = 9103;
const TYPE_CHILD_B = 9104;
const GROUP_ID = 9101;
const MODULE_ID = 1;          // type_mid = 1（文章）

const FIRST_ON = 910001;      // a1
const ON_COUNT = 52;          // n
const LAST_ON = 910052;       // an = FIRST_ON + ON_COUNT - 1
const OFF_BEFORE = 910000;    // ART-FIX-4
const OFF_AFTER = 910053;     // ART-FIX-3
const BASE_TIME = 1577836800; // 2020-01-01 08:00:00 +08:00（DATA-1 固定值，不含对照当天）

/** 分类行（列序与 install.sql:574-594 一致）。 */
function typeRows()
{
    $extend = '{"class":[],"area":[],"lang":[],"year":[],"star":[],"director":[],"state":[],"version":[],"weekday":[]}';
    $rows = [
        [TYPE_PARENT_A, '顶级甲', 'top-a', 1, MODULE_ID, 0, 1, $extend],
        [TYPE_CHILD_A, '子级甲', 'sub-a', 1, MODULE_ID, TYPE_PARENT_A, 1, $extend],
        [TYPE_PARENT_B, '顶级乙', 'top-b', 2, MODULE_ID, 0, 1, $extend],
        [TYPE_CHILD_B, '子级乙', 'sub-b', 2, MODULE_ID, TYPE_PARENT_B, 1, $extend],
    ];
    $out = [];
    foreach ($rows as $r) {
        // type_id,type_name,type_en,type_sort,type_mid,type_pid,type_status,type_tpl,type_tpl_list,
        // type_tpl_detail,type_tpl_play,type_tpl_down,type_key,type_des,type_title,type_union,
        // type_extend,type_logo,type_pic,type_jumpurl
        $out[] = sprintf(
            "(%d, %s, %s, %d, %d, %d, %d, '', '', '', '', '', '', '', '', '', %s, '', '', '')",
            $r[0], q($r[1]), q($r[2]), $r[3], $r[4], $r[5], $r[6], q($r[7])
        );
    }
    return $out;
}

/** SQL 字符串字面量。 */
function q($s)
{
    return "'" . str_replace(["\\", "'"], ["\\\\", "\\'"], (string)$s) . "'";
}

/**
 * 一行 mac_art（列序与 install.sql:108-175 一致；共 46 列）。
 *
 * $spec 允许覆盖的列：art_id、type_id、type_id_1、group_id、art_name、art_en、art_status、art_time、
 * art_content、art_title、art_note、art_pic_screenshot、art_letter、art_color。
 * 其余全部由 ART-FIX-5 决定：D1-FLD-3 的列一律填哨兵，且哨兵值只出现在这些列里。
 */
function artRow(array $spec)
{
    $id = $spec['art_id'];
    // D1-FLD-3 里的哨兵：文本列带编号，整型列取 7301～7304
    $sentinelText = function ($prefix) use ($id) { return $prefix . '-' . $id; };

    $row = [
        'art_id' => $id,
        'type_id' => $spec['type_id'],
        'type_id_1' => $spec['type_id_1'],
        'group_id' => $spec['group_id'],
        'art_name' => $spec['art_name'],
        'art_sub' => 'D1SENTINEL-sub',
        'art_en' => $spec['art_en'],
        'art_status' => $spec['art_status'],
        'art_letter' => $spec['art_letter'],
        'art_color' => $spec['art_color'],
        'art_from' => 'D1SENTINEL-from',
        'art_author' => 'D1SENTINEL-author',
        'art_tag' => 'D1SENTINEL-tag',
        'art_class' => 'D1SENTINEL-class',
        'art_pic' => $spec['art_pic'],
        'art_pic_thumb' => $spec['art_pic_thumb'],
        'art_pic_slide' => $spec['art_pic_slide'],
        'art_pic_screenshot' => $spec['art_pic_screenshot'],
        'art_blurb' => 'D1SENTINEL-blurb',
        'art_remarks' => 'D1SENTINEL-remarks',
        'art_jumpurl' => 'http://d1sentinel.test/' . $id,
        'art_tpl' => 'D1SENTINEL-tpl',
        'art_level' => 1,
        'art_lock' => 1,
        'art_points' => 7301,
        'art_points_detail' => 7302,
        'art_up' => 1,
        'art_down' => 2,
        'art_hits' => 3,
        'art_hits_day' => 4,
        'art_hits_week' => 5,
        'art_hits_month' => 6,
        'art_time' => $spec['art_time'],
        'art_time_add' => 7303,
        'art_time_hits' => 7304,
        'art_time_make' => 7303,
        'art_score' => '0.5',
        'art_score_all' => 7,
        'art_score_num' => 8,
        'art_rel_art' => 'D1SENTINEL-rel',
        'art_rel_vod' => 'D1SENTINEL-rel',
        // art_pwd 是 varchar(10)：哨兵只能用 D1SENT 前缀 + 后 3 位编号（IA-7 ② 只要求“不含以 D1SENTINEL 开头的字串”，
        // 设针本身不得进 info；为守住列宽这里用 D1SENT-pwd<后三位>，生成器下方断言其长度 ≤ 10）。
        'art_pwd' => 'D1SENT' . substr((string)$id, -3),
        'art_pwd_url' => $sentinelText('D1SENTINEL-pwdurl'),
        'art_title' => $spec['art_title'],
        'art_note' => $spec['art_note'],
        'art_content' => $spec['art_content'],
    ];

    // 按 install.sql 的列序输出；列清单同时是“46 列”的断言依据
    $order = ['art_id', 'type_id', 'type_id_1', 'group_id', 'art_name', 'art_sub', 'art_en', 'art_status',
        'art_letter', 'art_color', 'art_from', 'art_author', 'art_tag', 'art_class', 'art_pic',
        'art_pic_thumb', 'art_pic_slide', 'art_pic_screenshot', 'art_blurb', 'art_remarks', 'art_jumpurl',
        'art_tpl', 'art_level', 'art_lock', 'art_points', 'art_points_detail', 'art_up', 'art_down',
        'art_hits', 'art_hits_day', 'art_hits_week', 'art_hits_month', 'art_time', 'art_time_add',
        'art_time_hits', 'art_time_make', 'art_score', 'art_score_all', 'art_score_num', 'art_rel_art',
        'art_rel_vod', 'art_pwd', 'art_pwd_url', 'art_title', 'art_note', 'art_content'];
    if (count($row) !== 46 || array_diff($order, array_keys($row)) || array_diff(array_keys($row), $order)) {
        fwrite(STDERR, "列集合不是 46 列或缺失列\n");
        exit(4);
    }
    $vals = [];
    foreach ($order as $c) {
        $v = $row[$c];
        $vals[] = is_int($v) ? (string)$v : q($v);
    }
    // 列宽断言：值超长会在 MySQL STRICT 模式下报 1406，提前在这里拦住
    $widths = ['art_pwd' => 10, 'art_pwd_url' => 255, 'art_letter' => 1, 'art_color' => 6, 'art_from' => 30,
        'art_author' => 30, 'art_tag' => 100, 'art_class' => 255, 'art_blurb' => 255, 'art_remarks' => 100,
        'art_jumpurl' => 150, 'art_tpl' => 30, 'art_rel_art' => 255, 'art_rel_vod' => 255,
        'art_name' => 255, 'art_en' => 255, 'art_sub' => 255, 'art_pic' => 1024, 'art_pic_thumb' => 1024,
        'art_pic_slide' => 1024];
    foreach ($widths as $c => $w) {
        if (strlen((string)$row[$c]) > $w) {
            fwrite(STDERR, "列 $c 的值超过 varchar($w)：" . strlen((string)$row[$c]) . "\n");
            exit(4);
        }
    }
    return '(' . implode(', ', $vals) . ')';
}

/* ---------------------------------------------------------------- 组装数据 */

$types = typeRows();

$arts = [];
// ART-FIX-4：a1 之前唯一一行，停用
$arts[] = artRow([
    'art_id' => OFF_BEFORE, 'type_id' => TYPE_CHILD_A, 'type_id_1' => TYPE_PARENT_A, 'group_id' => 0,
    'art_name' => '停用-前', 'art_en' => 'off-before', 'art_status' => 0,
    'art_letter' => '0', 'art_color' => '000000', 'art_time' => BASE_TIME - 86400,
    'art_pic' => '', 'art_pic_thumb' => '', 'art_pic_slide' => '', 'art_pic_screenshot' => '',
    'art_title' => 'D1SENTINEL-title', 'art_note' => 'D1SENTINEL-note', 'art_content' => 'D1SENTINEL-content',
]);

// a1 = FIRST_ON（ART-FIX-1）：正文 p1$$$p2、标题 t1、note 空、截图两图、子分类、用户组
$arts[] = artRow([
    'art_id' => FIRST_ON, 'type_id' => TYPE_CHILD_A, 'type_id_1' => TYPE_PARENT_A, 'group_id' => GROUP_ID,
    'art_name' => '篇一 A&B<i>"', 'art_en' => 'a1', 'art_status' => 1,
    'art_letter' => 'P', 'art_color' => 'FF0000', 'art_time' => BASE_TIME,
    'art_pic' => '/uploads/art/910001.jpg', 'art_pic_thumb' => '/uploads/art/910001-thumb.jpg',
    'art_pic_slide' => '/uploads/art/910001-slide.jpg',
    'art_pic_screenshot' => '封面$http://img.test/1.jpg##http://img.test/2.jpg',
    'art_title' => 't1', 'art_note' => '', 'art_content' => 'p1$$$p2',
]);

// a2..a(n-1)：两类子树交替，内容各不相同
for ($k = 2; $k <= ON_COUNT - 1; $k++) {
    $id = FIRST_ON + $k - 1;
    $useA = ($k % 2 === 0);
    $arts[] = artRow([
        'art_id' => $id,
        'type_id' => $useA ? TYPE_CHILD_A : TYPE_CHILD_B,
        'type_id_1' => $useA ? TYPE_PARENT_A : TYPE_PARENT_B,
        'group_id' => 0,
        'art_name' => '第' . $k . '篇',
        'art_en' => 'a' . $k,
        'art_status' => 1,
        'art_letter' => chr(65 + ($k % 26)),
        'art_color' => '',
        'art_time' => BASE_TIME + 86400 * ($k - 1),
        'art_pic' => '', 'art_pic_thumb' => '', 'art_pic_slide' => '',
        'art_pic_screenshot' => '',
        'art_title' => 'D1SENTINEL-title', 'art_note' => 'D1SENTINEL-note',
        'art_content' => 'D1SENTINEL-content',
    ]);
}

// an = LAST_ON（ART-FIX-2）：分类不存在、正文与截图空、group_id = 0
$arts[] = artRow([
    'art_id' => LAST_ON, 'type_id' => 9199, 'type_id_1' => 0, 'group_id' => 0,
    'art_name' => '末篇', 'art_en' => 'an', 'art_status' => 1,
    'art_letter' => 'Z', 'art_color' => '', 'art_time' => BASE_TIME + 86400 * (ON_COUNT - 1),
    'art_pic' => '', 'art_pic_thumb' => '', 'art_pic_slide' => '', 'art_pic_screenshot' => '',
    'art_title' => 'D1SENTINEL-title', 'art_note' => 'D1SENTINEL-note', 'art_content' => '',
]);

// ART-FIX-3：an 之后唯一一行，停用
$arts[] = artRow([
    'art_id' => OFF_AFTER, 'type_id' => TYPE_CHILD_A, 'type_id_1' => TYPE_PARENT_A, 'group_id' => 0,
    'art_name' => '停用-后', 'art_en' => 'off-after', 'art_status' => 0,
    'art_letter' => '0', 'art_color' => '000000', 'art_time' => BASE_TIME + 86400 * ON_COUNT,
    'art_pic' => '', 'art_pic_thumb' => '', 'art_pic_slide' => '', 'art_pic_screenshot' => '',
    'art_title' => 'D1SENTINEL-title', 'art_note' => 'D1SENTINEL-note', 'art_content' => 'D1SENTINEL-content',
]);

/* ------------------------------------------------- ART-FIX 断言（不成立就不写文件） */

$fail = [];
if (count($arts) < 50) {
    $fail[] = 'mac_art 行数 ' . count($arts) . ' < 50（AC-3 前置）';
}
$on = [];
$off = [];
foreach ($arts as $r) {
    // $r 是渲染好的 SQL 行：第 1 列 art_id、第 8 列 art_status（列序固定见 artRow）
    $cols = preg_split('/,(?=(?:[^\']|\'[^\']*\')*$)/', substr($r, 1, -1), 46);
    $id = (int)trim($cols[0]);
    $status = trim($cols[7]);
    if ($status === '1') {
        $on[] = $id;
    } else {
        $off[] = $id;
    }
}
sort($on);
sort($off);
if (count($on) !== ON_COUNT) {
    $fail[] = '启用行数 ' . count($on) . ' != ' . ON_COUNT;
}
if ($on[0] !== FIRST_ON || $on[count($on) - 1] !== LAST_ON) {
    $fail[] = 'a1/an 不是 ' . FIRST_ON . '/' . LAST_ON;
}
for ($i = 1; $i < count($on); $i++) {
    if ($on[$i] <= $on[$i - 1]) {
        $fail[] = '启用行编号不是严格升序';
    }
}
if ($off !== [OFF_BEFORE, OFF_AFTER]) {
    $fail[] = '停用行不是恰好 ' . OFF_BEFORE . ' 与 ' . OFF_AFTER . '：' . implode(',', $off);
}
// ART-FIX-5：7301～7304 只能出现在 D1-FLD-3 列里，且不得出现在夹具的其他列。
// 7303 每行两次（art_time_add 与 art_time_make 都是 D1-FLD-3 列），其余每行一次。
$allSql = implode("\n", $arts) . "\n" . implode("\n", $types);
$expected = ['7301' => 1, '7302' => 1, '7303' => 2, '7304' => 1];
foreach ($expected as $n => $perRow) {
    $want = $perRow * count($arts);
    if (substr_count($allSql, $n) !== $want) {
        $fail[] = "哨兵 $n 出现 " . substr_count($allSql, $n) . " 次，应为每行 $perRow 次共 $want";
    }
}
if ($fail) {
    fwrite(STDERR, "ART-FIX 断言失败：\n  - " . implode("\n  - ", $fail) . "\n");
    exit(5);
}

/* ---------------------------------------------------------------- 输出 */

$sql = <<<'HDR'
-- APPK-2 S3-05 / C-33 ④：D-1 模型调用黄金的 MySQL 夹具（ART-FIX）。
--
-- 本文件由 `src/test/php/tpl_art_fixture.php` 生成，不要手改；改数据规则请改生成器再重新生成。
-- 依据：技术设计 v13 `9c73bf6ced044f40eeb87ef7861e616b6157499a` 的 V-16 样例说明与
--       §6.21 INFO-1～INFO-7、D1-FLD-1～5、PROJ-1、QG-1～QG-7（docs/design/APPK-2/tech-design.md）。
-- 用途：PHP 黄金脚本（`tpl_runtime_golden.php --model-calls art-infodata`）与 Java 侧
--       `TagModelsMySqlIT` 加载同一份文件，得到可逐字节比对的两侧结果。
-- 只允许导入一次性库 maccms_it_*；不得导入 maccms_dev 或任何共享库。
-- 表结构由 `php/application/install/sql/install.sql` 提供（本文件只插入数据）。
--
-- 数据形状：
--   mac_art 共 54 行（≥ 50，满足 AC-3 前置）；启用行 a1..an = 910001..910052（n = 52，严格升序）；
--   停用行恰好 2 行：910000（a1 之前，ART-FIX-4）与 910053（an 之后，ART-FIX-3）。
--   ART-FIX-1：a1 = 910001，art_content='p1$$$p2'、art_title='t1'、art_note=''、
--              art_pic_screenshot='封面$http://img.test/1.jpg##http://img.test/2.jpg'、
--              type_id 指向 type_pid≠0 的子分类 9102、group_id=9101（存在的用户组）。
--   ART-FIX-2：an = 910052，type_id=9199（不存在的分类）、art_content 与 art_pic_screenshot 为空串、group_id=0。
--   ART-FIX-5：每行 D1-FLD-3 的列都填哨兵（D1SENTINEL-* 文本与 7301～7304 整数）；其他列不出现这些值。
--   分类：mac_type 4 行（9101 顶级 / 9102 子级 / 9103 顶级 / 9104 子级），供 IA-5 多候选样例使用。
--   用户组：mac_group 1 行（9101）。

HDR;

$sql .= "-- 清空（只针对本文夹具有关的行；一次性库里其余表不动）\n";
$sql .= "DELETE FROM `mac_art`;\n";
$sql .= "DELETE FROM `mac_type` WHERE `type_id` IN (" . implode(', ', [TYPE_PARENT_A, TYPE_CHILD_A, TYPE_PARENT_B, TYPE_CHILD_B]) . ");\n";
$sql .= "DELETE FROM `mac_group` WHERE `group_id` = " . GROUP_ID . ";\n\n";

$sql .= "-- 分类（mac_type，列序同 install.sql:574-594）\n";
$sql .= "INSERT INTO `mac_type`\n";
$sql .= "  (`type_id`, `type_name`, `type_en`, `type_sort`, `type_mid`, `type_pid`, `type_status`, `type_tpl`,\n";
$sql .= "   `type_tpl_list`, `type_tpl_detail`, `type_tpl_play`, `type_tpl_down`, `type_key`, `type_des`,\n";
$sql .= "   `type_title`, `type_union`, `type_extend`, `type_logo`, `type_pic`, `type_jumpurl`) VALUES\n";
$sql .= '  ' . implode(",\n  ", $types) . ";\n\n";

$sql .= "-- 用户组（mac_group）\n";
$sql .= "INSERT INTO `mac_group`\n";
$sql .= "  (`group_id`, `group_name`, `group_status`, `group_type`, `group_popedom`, `group_points_day`,\n";
$sql .= "   `group_points_week`, `group_points_month`, `group_points_year`, `group_points_free`) VALUES\n";
$sql .= '  (' . GROUP_ID . ", '黄金夹具组', 1, '', '', 0, 0, 0, 0, 0);\n\n";

$sql .= "-- 文章（mac_art 共 " . count($arts) . " 行，列序同 install.sql:108-175，共 46 列）\n";
$sql .= "INSERT INTO `mac_art`\n";
$sql .= "  (`art_id`, `type_id`, `type_id_1`, `group_id`, `art_name`, `art_sub`, `art_en`, `art_status`,\n";
$sql .= "   `art_letter`, `art_color`, `art_from`, `art_author`, `art_tag`, `art_class`,\n";
$sql .= "   `art_pic`, `art_pic_thumb`, `art_pic_slide`, `art_pic_screenshot`, `art_blurb`, `art_remarks`,\n";
$sql .= "   `art_jumpurl`, `art_tpl`, `art_level`, `art_lock`, `art_points`, `art_points_detail`,\n";
$sql .= "   `art_up`, `art_down`, `art_hits`, `art_hits_day`, `art_hits_week`, `art_hits_month`,\n";
$sql .= "   `art_time`, `art_time_add`, `art_time_hits`, `art_time_make`,\n";
$sql .= "   `art_score`, `art_score_all`, `art_score_num`, `art_rel_art`, `art_rel_vod`,\n";
$sql .= "   `art_pwd`, `art_pwd_url`, `art_title`, `art_note`, `art_content`) VALUES\n";
$sql .= implode(",\n", $arts) . ";\n";

$dir = dirname($out);
if (!is_dir($dir) && !mkdir($dir, 0777, true) && !is_dir($dir)) {
    fwrite(STDERR, "无法创建目录：$dir\n");
    exit(3);
}
file_put_contents($out, $sql);
echo json_encode([
    'ok' => true, 'out' => $out, 'art_rows' => count($arts), 'type_rows' => count($types),
    'on_range' => [FIRST_ON, LAST_ON], 'off_rows' => [OFF_BEFORE, OFF_AFTER],
    'bytes' => strlen($sql),
], JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT), "\n";
