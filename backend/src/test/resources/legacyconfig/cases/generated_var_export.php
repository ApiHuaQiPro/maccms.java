<?php
return array (
  'quote' => 'it\'s \\ back\\slash \\\' end\\',
  'unicode' => '中文 😀 é',
  'newlines' => 'a
b
c	d',
  'php_tags' => '<?php ?> */ // # /* $x {$y} "dq"',
  'empty' => '',
  'zero_str' => '0',
  'int' => 42,
  'neg' => -7,
  'zero' => 0,
  'max' => 9223372036854775807,
  'min_plus_one' => -9223372036854775807,
  'floats' => 
  array (
    0 => 0.1,
    1 => -0.0,
    2 => 1.0,
    3 => 1.5E-7,
    4 => 1.0E+25,
    5 => -2.5,
    6 => 3.0E+300,
    7 => 123456789.125,
    8 => 5.0E-324,
  ),
  'bools' => 
  array (
    0 => true,
    1 => false,
  ),
  'null' => NULL,
  'keys' => 
  array (
    1 => 'int-from-string',
    '01' => 'string',
    '-0' => 'string',
    -5 => 'int-from-negative-string',
    ' 1' => 'space',
    '1.0' => 'float-like-string',
    9223372036854775807 => 'max-int-key',
    '9223372036854775808' => 'overflow-stays-string',
    7 => 'seven',
    'x' => 'x',
  ),
  'nested' => 
  array (
    'a' => 
    array (
      'b' => 
      array (
        'c' => 
        array (
        ),
      ),
    ),
  ),
  'list' => 
  array (
    0 => 'x',
    1 => 'y',
    2 => 'z',
  ),
);