<?php
/**
 * APPK-2 S3-06 / S306-1：RT-4 弱比较全矩阵（PHP 7.3 实测），补 S3-05 黄金 rt4_compare 没有覆盖的 NaN 与混合类型组合。
 *
 * 用法（仓库根目录）：
 *   php7.3 src/test/php/c17_compare_matrix.php > src/test/resources/phptemplate-runtime/rt4-compare-matrix.txt
 *
 * 每行 "左|右|位串"，位串依次是 ==、!=、<、>、<=、>=、in_array(右, [左]) 的结果（1 真 0 假）。
 * 值的标签与 PhpSemanticsCompareMatrixTest.VALUES 一一对应，两边须同步改。
 * 矩阵里同一标签的左右两边是同一个数组（Zend 先判同一张哈希表即相等）；末尾 "=名称|结果" 各行补上
 * 分别构造的数组，名称与 PhpSemanticsCompareMatrixTest.EXTRA 对应。
 */
if (PHP_MAJOR_VERSION !== 7 || PHP_MINOR_VERSION !== 3) {
    fwrite(STDERR, "need PHP 7.3, got " . PHP_VERSION . "\n");
    exit(2);
}
$vals = [
    'NAN' => NAN, 'INF' => INF, '-INF' => -INF,
    'i0' => 0, 'i1' => 1, 'i-1' => -1, 'f0' => 0.0, 'f-0' => -0.0, 'f1.5' => 1.5,
    's0' => "0", 'sabc' => "abc", 's' => "", 's1' => "1", 's1.5' => "1.5", 's_1' => " 1", 's1abc' => "1abc",
    'sNAN' => "NAN", 'sINF' => "INF", 's1e999' => "1e999", 's-1e999' => "-1e999",
    'null' => null, 'true' => true, 'false' => false,
    'a[]' => [], 'a[0]' => [0], 'a[NAN]' => [NAN], 'a[s0]' => ["0"],
];
echo "# PHP " . PHP_VERSION . " " . (PHP_INT_SIZE * 8) . "-bit; columns: == != < > <= >= in_array(rhs,[lhs])\n";
foreach ($vals as $ka => $a) {
    foreach ($vals as $kb => $b) {
        $bits = [$a == $b, $a != $b, $a < $b, $a > $b, $a <= $b, $a >= $b, in_array($b, [$a])];
        echo $ka, '|', $kb, '|', implode('', array_map('intval', $bits)), "\n";
    }
}
$x = [NAN];
$y = [NAN];
$extra = [
    'distinct [NAN] == [NAN]' => $x == $y,
    'distinct [NAN] <= [NAN]' => $x <= $y,
    'distinct [NAN] >= [NAN]' => $x >= $y,
    'in_array(NAN, [NAN])' => in_array(NAN, [NAN]),
    'in_array([NAN], [[NAN]])' => in_array($x, [$y]),
    '[1, NAN] < [2, NAN]' => [1, NAN] < [2, NAN],
    '[NAN, 1] < [NAN, 2]' => [NAN, 1] < [NAN, 2],
    '[NAN, 1] > [NAN, 2]' => [NAN, 1] > [NAN, 2],
    '[a=>NAN] == [a=>NAN]' => ['a' => NAN] == ['a' => NAN],
    '["0"] == ["0.0"]' => ["0"] == ["0.0"],
];
foreach ($extra as $name => $r) {
    echo '=', $name, '|', (int) $r, "
";
}
