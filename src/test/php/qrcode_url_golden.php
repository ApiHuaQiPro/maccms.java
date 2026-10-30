<?php
/*
 * B06（MACC-30）二维码接口的 URL 校验黄金向量：旧 index/controller/Qrcode.php:18 用 filter_var($url, FILTER_VALIDATE_URL)
 * 决定是否输出 PNG。本脚本只调用 PHP 内置的 filter_var，不连接数据库、不访问网络、不读写文件。
 *
 * 用法（仓库根目录，PHP 8.0 与 7.3 各一份）：
 *   D:/Tools/php/8.0/php.exe src/test/php/qrcode_url_golden.php > src/test/resources/site/qrcode/php80-url-vectors.json
 * QrcodeUrlTest 逐条比对 Java 的判定。说明见 docs/architecture/16-b06-misc-pages-provide.md 第 4.7 节。
 */
$inputs = [
    'http://www.example.test/index.php/vod/detail/id/1.html',
    'https://www.example.test/',
    'http://www.example.test',
    'http://www.example.test:8080/a?b=1&c=2#frag',
    'http://user:pass@www.example.test/',
    'http://127.0.0.1/',
    'http://[::1]/',
    'http://[2001:db8::1]:8080/x',
    'http://localhost/',
    'http://a/',
    'http://a.b-c.d_e/',
    'http://-a.test/',
    'http://a-.test/',
    'http://a..test/',
    'http://.a.test/',
    'http://a.test./',
    'http://exa mple.test/',
    'http://例子.测试/',
    'http://www.example.test/路径',
    'http://www.example.test/%E8%B7%AF',
    'http://www.example.test/a b',
    'http://www.example.test/<script>',
    'http://www.example.test/"q"',
    "http://www.example.test/\x01",
    'http:/www.example.test/',
    'http:www.example.test',
    'http://',
    'http:///path',
    '//www.example.test/',
    'www.example.test',
    'ftp://ftp.example.test/file',
    'mailto:user@example.test',
    'news:comp.lang.php',
    'file:///etc/passwd',
    'javascript:alert(1)',
    'data:text/plain,hello',
    'foo://bar',
    'a1+.-://host/',
    '1http://host/',
    'HTTP://WWW.EXAMPLE.TEST/',
    'http://www.example.test:0/',
    'http://www.example.test:65535/',
    'http://www.example.test:65536/',
    'http://www.example.test:abc/',
    'http://www.example.test?x',
    'http://www.example.test#x',
    'http://www.example.test/?a[]=1',
    'http://www.example.test/~user/',
    'http://www.example.test/a|b',
    'http://www.example.test/a{b}',
    'http://www.example.test/a\\b',
    'http://www.example.test/a^b',
    'http://www.example.test/a`b',
    'http://' . str_repeat('a', 63) . '.test/',
    'http://' . str_repeat('a', 64) . '.test/',
    'http://' . str_repeat('a.', 120) . 'test/',
    ' http://www.example.test/',
    'http://www.example.test/ ',
    '0',
    '',
];
$out = [];
foreach ($inputs as $s) {
    $out[] = ['input' => $s, 'valid' => filter_var($s, FILTER_VALIDATE_URL) !== false];
}
echo json_encode(['php' => PHP_VERSION, 'vectors' => $out], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT), "\n";
