<?php
/* 手写的合成配置（B02 解析器测试）：短数组语法、注释、大小写混写的关键字、省略键、重复键、字符串整数键。
   块注释里出现 array( 'x' => 1 ); 不算代码。 */
// 行注释
return [
    'short' => [1, 2, 3,],
    'Upper' => ARRAY ( 'T' => TRUE, 'F' => False, 'N' => NULL ),
    -5 => 'neg-key',
    10 => 'ten',
    'auto-eleven',
    '3' => 'string-three-is-int-key',
    'auto-twelve',
    'dup' => 'first',
    'other' => 'kept-between',
    'dup' => 'second-overwrites-in-place',
    'leading_zero_float' => 00.5,
    'exp' => 1e3,
    'neg_exp' => -2.5E-3,
    'escapes' => 'a\nb\\c\'d\e\\\\',
    'nested' => array([], [[]], array(array('deep' => 'x'))),
    // 键与值之间的注释和换行
    'spaced'
      =>  /* 行内块注释 */
      'value',
    'tags_in_string' => '<?php ?> // # /* $x {$y} ',
];
?>
