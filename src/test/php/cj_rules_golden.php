<?php
/*
 * B20（MACC-44）自定义规则采集的黄金样本（docs/architecture/28-b20-cj-local-html.md 第 7 节）。
 *
 * 直接调用旧 application/common/util/Collection.php 的 url_list()、get_url_lists()、get_content()，不修改旧代码、不连库、不联网：
 * 这些方法在命名空间 app\common\util 里以非限定名调用 mac_curl_get() 与 time()，PHP 先在当前命名空间里找函数，所以本脚本在同一命名空间
 * 里定义同名函数——mac_curl_get() 从用例的页面表取内容（没有时返回 false，同 curl 失败）并记下请求的地址，time() 返回固定值。
 *
 * get_content() 用函数内的 static $oldurl 记住分页时访问过的地址（同一个 PHP 进程内一直累积），所以每个用例在单独的子进程里执行
 * （本脚本不带参数时是驱动，逐个用例以 "php 本脚本 用例名" 启动子进程）。同一个用例里的多个操作共享这份状态，与旧版一次请求里
 * 采集多条内容相同。
 *
 * 每个操作记录：结果（带类型的 JSON，同 collect_provide_golden.php 的 typed()：字符串 base64，数组保留键的类型与顺序）、
 * get_content 的 json_encode 结果（旧 col_content 写进 mac_cj_content.data 的文字）、按顺序请求过的地址；PHP 抛出的错误记为
 * error（类名），不记结果。
 *
 * 用法（仓库根目录，PHP 7.3 与 8.0 各一份）：
 *   D:/Tools/php/7.3/php.exe src/test/php/cj_rules_golden.php > src/test/resources/collect/cj/php73-cj-golden.json
 *   D:/Tools/php/8.0/php.exe src/test/php/cj_rules_golden.php > src/test/resources/collect/cj/php80-cj-golden.json
 */

namespace app\common\util {

    function mac_curl_get($url, $heads = [], $cookie = '')
    {
        $GLOBALS['__fetched'][] = $url;
        return array_key_exists($url, $GLOBALS['__pages']) ? $GLOBALS['__pages'][$url] : false;
    }

    function time()
    {
        return 1700000000;
    }
}

namespace {

    error_reporting(E_ALL & ~E_NOTICE & ~E_WARNING & ~E_DEPRECATED);
    ini_set('display_errors', '0');

    function typed($v)
    {
        if (is_string($v)) {
            return ['s', base64_encode($v)];
        }
        if (is_int($v)) {
            return ['i', (string)$v];
        }
        if (is_float($v)) {
            return ['f', bin2hex(pack('E', $v))];
        }
        if (is_bool($v)) {
            return ['b', $v];
        }
        if ($v === null) {
            return ['n'];
        }
        $items = [];
        foreach ($v as $k => $x) {
            $items[] = [is_int($k) ? 'i' : 's', is_int($k) ? (string)$k : base64_encode($k), typed($x)];
        }
        return ['a', $items];
    }

    /** 节点的默认列（与 install.sql 的 mac_cj_node 相同；PDO 取回的值都是字符串）。 */
    function node($over)
    {
        $n = [
            'nodeid' => '1', 'name' => 'n', 'lastdate' => '0', 'sourcecharset' => 'UTF-8', 'sourcetype' => '1', 'urlpage' => '',
            'pagesize_start' => '1', 'pagesize_end' => '1', 'page_base' => '', 'par_num' => '1', 'url_contain' => '', 'url_except' => '',
            'url_start' => '', 'url_end' => '', 'title_rule' => '', 'title_html_rule' => '', 'type_rule' => '', 'type_html_rule' => '',
            'content_rule' => '', 'content_html_rule' => '', 'content_page_start' => '', 'content_page_end' => '',
            'content_page_rule' => '1', 'content_page' => '1', 'content_nextpage' => '', 'down_attachment' => '0', 'watermark' => '0',
            'coll_order' => '2', 'customize_config' => '', 'program_config' => '', 'mid' => '1',
        ];
        foreach ($over as $k => $v) {
            $n[$k] = $v;
        }
        return $n;
    }

    $L = 'http://cj.example.test';
    $list = "<html><body><div class=\"nav\"><a href=\"/nav.html\">导航</a></div><ul class=\"list\">\r\n"
        . "<li><a href=\"/show/1.html\" title=\"t\">第一篇</a></li>\n"
        . "<li><a href='show/2.html'><b>第二篇</b></a></li>\n"
        . "<li><a href=show/3.html target=_blank>第三篇</A></li>\n"
        . "<li><a href=\"http://other.test/x/4.html\">外站</a></li>\n"
        . "<li><a href=\"/show/5.html\">another title</a></li>\n"
        . "<li><a href=\"/show/6.html\">/slash</a></li>\n"
        . "<li><a name=\"x\">没有地址</a></li>\n"
        . "<li><a href=\"/ad/7.html\">广告</a></li>\n"
        . "<li><a href=\"#\">井号</a> <a href=\"../up/8.html\">上级</a></li>\n"
        . "<li><a class=\"c\" href=\"./show/9.html?id=9&amp;p=1\">带参数</a></li>\n"
        . "</ul><div class=\"foot\"><a href=\"/f.html\">尾</a></div></body></html>";
    $article = "<html><head><title>标题一 - 站点</title></head><body>\r\n<h1 class=\"t\">  标题一  </h1>\n<span class=\"cat\">电影</span>"
        . "<div class=\"meta\">作者：<em>张三</em> 来源：<i>本站</i></div>"
        . "<div id=\"content\"><p>第一段<a href=\"/x.html\">链接</a></p>\r\n<p><img src=\"/img/a.jpg\" alt=\"a\"> 与 <img alt='b' src='img/b.png'>"
        . " 与 <IMG SRC=http://cdn.test/c.gif> 与 <img data-src=\"d.jpg\" src=\"e.jpg\"></p><script>var x=1;</script>ABCabc</div>"
        . "<div class=\"pages\"><a href=\"#\">首页</a><a href=\"/a/1_2.html\">下一页</a><a href=\"/a/1_3.html\">3</a></div></body></html>";
    $page2 = "<html><body><div id=\"content\"><p>第二页</p></div><div class=\"pages\"><a href=\"/a/1.html\">上一页</a>"
        . "<a href=\"/a/1_3.html\">下一页</a></div></body></html>";
    $page3 = "<html><body><div id=\"content\"><p>第三页</p></div><div class=\"pages\"><a href=\"/a/1_2.html\">上一页</a></div></body></html>";
    $gbk = iconv('UTF-8', 'GBK', "<ul><li><a href=\"/g/1.html\">中文标题</a></li></ul>") . "<ul><li><a href=\"/g/2.html\">\xff\xfe坏</a></li></ul>";
    $big5 = iconv('UTF-8', 'BIG5', "<h1>繁體標題</h1><div id=\"content\">內容</div>");

    $contentRules = [
        'title_rule' => "<h1 class=\"t\">[内容]</h1>", 'type_rule' => "<span class=\"cat\">[内容]</span>",
        'content_rule' => "<div id=\"content\">[内容]</div>",
    ];

    $cases = [
        // ---------------------------------------------------------------- url_list
        ['seq-basic', node(['sourcetype' => '1', 'urlpage' => "$L/list_(*).html", 'pagesize_start' => '1', 'pagesize_end' => '3']), [], [['url_list']]],
        ['seq-step', node(['sourcetype' => '1', 'urlpage' => "$L/l/(*)/(*).html", 'pagesize_start' => '2', 'pagesize_end' => '9', 'par_num' => '3']), [], [['url_list']]],
        ['seq-end-below-start', node(['sourcetype' => '1', 'urlpage' => "$L/l_(*).html", 'pagesize_start' => '5', 'pagesize_end' => '2']), [], [['url_list']]],
        ['seq-zero', node(['sourcetype' => '1', 'urlpage' => "$L/l_(*).html", 'pagesize_start' => '0', 'pagesize_end' => '0']), [], [['url_list']]],
        ['multi', node(['sourcetype' => '2', 'urlpage' => "$L/a.html\r\n$L/b.html\n$L/c.html\r\n\r\n $L/d.html "]), [], [['url_list']]],
        ['single', node(['sourcetype' => '3', 'urlpage' => "$L/one.html"]), [], [['url_list']]],
        ['type-other', node(['sourcetype' => '0', 'urlpage' => "$L/one.html"]), [], [['url_list']]],
        ['type-numeric-string', node(['sourcetype' => '03', 'urlpage' => "$L/one.html"]), [], [['url_list']]],

        // ---------------------------------------------------------------- get_url_lists
        ['list-basic', node(['sourcetype' => '3', 'url_start' => "<ul class=\"list\">", 'url_end' => "</ul>"]), ["$L/dir/list.html" => $list],
            [['url_lists', "$L/dir/list.html"]]],
        ['list-filters', node(['sourcetype' => '3', 'url_start' => "<ul class=\"list\">", 'url_end' => "</ul>", 'url_contain' => 'show', 'url_except' => '9.html']),
            ["$L/dir/list.html" => $list], [['url_lists', "$L/dir/list.html"]]],
        ['list-page-base', node(['sourcetype' => '3', 'url_start' => "<ul class=\"list\">", 'url_end' => "</ul>", 'page_base' => 'http://base.test/p/']),
            ["$L/dir/list.html" => $list], [['url_lists', "$L/dir/list.html"]]],
        ['list-base-root-file', node(['sourcetype' => '3', 'url_start' => "<ul class=\"list\">", 'url_end' => "</ul>"]), ["$L/list.html" => $list],
            [['url_lists', "$L/list.html"]]],
        ['list-base-dir-slash', node(['sourcetype' => '3', 'url_start' => "<ul class=\"list\">", 'url_end' => "</ul>"]), ["$L/dir/sub/" => $list],
            [['url_lists', "$L/dir/sub/"]]],
        ['list-base-no-path', node(['sourcetype' => '3', 'url_start' => "<ul class=\"list\">", 'url_end' => "</ul>"]), ["$L" => $list],
            [['url_lists', "$L"]]],
        ['list-base-port-query', node(['sourcetype' => '3', 'url_start' => "<ul class=\"list\">", 'url_end' => "</ul>"]),
            ["http://User:pw@CJ.Example.test:8080/d/list.php?page=2#top" => $list], [['url_lists', "http://User:pw@CJ.Example.test:8080/d/list.php?page=2#top"]]],
        ['list-start-missing', node(['sourcetype' => '3', 'url_start' => "<ol>", 'url_end' => "</ol>"]), ["$L/list.html" => $list],
            [['url_lists', "$L/list.html"]]],
        ['list-start-repeated', node(['sourcetype' => '3', 'url_start' => "<li>", 'url_end' => "</ul>"]), ["$L/list.html" => $list],
            [['url_lists', "$L/list.html"]]],
        ['list-start-empty', node(['sourcetype' => '3', 'url_start' => "", 'url_end' => "</ul>"]), ["$L/list.html" => $list],
            [['url_lists', "$L/list.html"]]],
        ['list-end-blank', node(['sourcetype' => '3', 'url_start' => "<ul class=\"list\">", 'url_end' => " \r\n"]), ["$L/list.html" => $list],
            [['url_lists', "$L/list.html"]]],
        ['list-missing-page', node(['sourcetype' => '3', 'url_start' => "<ul>", 'url_end' => "</ul>"]), [], [['url_lists', "$L/none.html"]]],
        ['list-empty-page', node(['sourcetype' => '3', 'url_start' => "<ul>", 'url_end' => "</ul>"]), ["$L/zero.html" => '0', "$L/e.html" => ''],
            [['url_lists', "$L/zero.html"], ['url_lists', "$L/e.html"], ['url_lists', '']]],
        ['list-gbk', node(['sourcetype' => '3', 'sourcecharset' => 'GBK', 'url_start' => '<ul>', 'url_end' => '</ul>']), ["$L/g.html" => $gbk],
            [['url_lists', "$L/g.html"]]],
        ['list-gbk-as-utf8', node(['sourcetype' => '3', 'sourcecharset' => 'UTF-8', 'url_start' => '<ul>', 'url_end' => '</ul>']), ["$L/g.html" => $gbk],
            [['url_lists', "$L/g.html"]]],
        ['list-lowercase-utf8', node(['sourcetype' => '3', 'sourcecharset' => 'utf-8', 'url_start' => '<ul>', 'url_end' => '</ul>']),
            ["$L/g.html" => "<ul><li><a href=\"/u/1.html\">好\xff的</a></li></ul>"], [['url_lists', "$L/g.html"]]],
        ['list-title-tags', node(['sourcetype' => '3', 'url_start' => '<ul>', 'url_end' => '</ul>']),
            ["$L/t.html" => "<ul><li><a href=\"a.html\"><img src=x.jpg>图 <b>粗</b>&amp;</a></li><li><a href=\"b.html\">></a></li>"
                . "<li><a href=\"c.html\">x</a><a href=\"d.html\">y</a></li></ul>"], [['url_lists', "$L/t.html"]]],

        // ---------------------------------------------------------------- get_content
        ['content-basic', node($contentRules), ["$L/a/1.html" => $article], [['content', "$L/a/1.html"]]],
        ['content-literal-rules', node(['title_rule' => '固定标题', 'type_rule' => '0', 'content_rule' => "<div id=\"content\">[内容]</div>"]),
            ["$L/a/1.html" => $article], [['content', "$L/a/1.html"]]],
        ['content-html-rules', node($contentRules + [
            'title_html_rule' => "标题[|]Title\r\n  [|]\r\n",
            'content_html_rule' => "<a[^>]*>([^<]*)</a>[|]【\\1|$1|\${1}|\\\\1|$9】\r\n<script.*?</script>[|]\r\n<p>[|]\n\n</p>\nabc[|]X",
        ]), ["$L/a/1.html" => $article], [['content', "$L/a/1.html"]]],
        ['content-html-rule-invalid', node($contentRules + ['content_html_rule' => "<p>[|]\r\n(unclosed[|]x"]), ["$L/a/1.html" => $article],
            [['content', "$L/a/1.html"]]],
        ['content-html-rule-blank-lines', node($contentRules + ['content_html_rule' => "\n\n"]), ["$L/a/1.html" => $article], [['content', "$L/a/1.html"]]],
        ['content-html-rule-zero', node($contentRules + ['content_html_rule' => "0"]), ["$L/a/1.html" => $article], [['content', "$L/a/1.html"]]],
        ['content-html-rule-slashes', node($contentRules + ['content_html_rule' => "</p>[|]<br/>\n<\\/a>[|]Z"]), ["$L/a/1.html" => $article],
            [['content', "$L/a/1.html"]]],
        ['content-html-rule-bytes', node($contentRules + ['title_html_rule' => "^..[|]<\$0>", 'type_html_rule' => "[电]+[|]D",
            'content_html_rule' => "第.段[|]P\n[[:space:]]+与[[:space:]]+[|]&\n{x[|]y\n(?P<n>ABC)[|]<\$1>\nx{2,}[|]-"]),
            ["$L/a/1.html" => $article], [['content', "$L/a/1.html"]]],
        ['content-html-rule-modifier', node($contentRules + ['title_html_rule' => "标题\\\\/i[|]T"]), ["$L/a/1.html" => $article], [['content', "$L/a/1.html"]]],
        ['content-html-rule-empty-pattern', node(['title_rule' => "<em>[内容]</em>", 'title_html_rule' => "[|]-"]), ["$L/a/1.html" => $article],
            [['content', "$L/a/1.html"]]],
        ['content-custom', node($contentRules + ['customize_config' => json_encode([
            ['name' => '作者', 'en_name' => 'author', 'rule' => "<em>[内容]</em>", 'html_rule' => '张[|]李'],
            ['name' => '空', 'en_name' => 'skip', 'rule' => '', 'html_rule' => ''],
            ['name' => '来源', 'en_name' => 'from', 'rule' => '本站固定', 'html_rule' => ''],
            ['name' => '覆盖标题', 'en_name' => 'title', 'rule' => "<title>[内容]</title>", 'html_rule' => ''],
            ['name' => '无名', 'en_name' => '', 'rule' => "<i>[内容]</i>", 'html_rule' => ''],
            ['name' => '数字', 'en_name' => '7', 'rule' => "<i>[内容]</i>", 'html_rule' => ''],
            ['name' => '时间', 'en_name' => 'time', 'rule' => "x", 'html_rule' => ''],
        ], JSON_FORCE_OBJECT)]), ["$L/a/1.html" => $article], [['content', "$L/a/1.html"]]],
        ['content-regex-syntax', node(['customize_config' => json_encode(array_map(function ($i, $r) {
            return ['name' => "r$i", 'en_name' => "r$i", 'rule' => "<div id=\"content\">[内容]</div>", 'html_rule' => $r];
        }, range(1, 27), [
            '\d+[|]#', '\x41[|]x', '\x4[|]y', 'a{,2}[|]-', 'abc\\[|]-', '(?i)abc[|]-', '(?#c)ABC[|]-', '\Q.\E[|]-',
            '[\d-]+[|]-', '<\/p>[|]-', '[^<>]+[|]', '[]a]+[|]-', '[a&&b][|]-', '[[:^alpha:]<]+[|]-', '(?P<x>p)(?P=x)?[|]-',
            '<(?:p|div)[^>]*>[|]{\0}', '(p)|(q)[|]$2|\2|${2}|\\\\1|\$1', '\bABC\b[|]-', 'x*[|]-', '^[|]S', '\s+$[|]E',
            '(?=<p>)[|]L', '\$[|]D', '[\x{e4}-\x{e9}]+[|]H', '[\d\]]{2}[|]2', '\101[|]8', '[\101]+[|]9',
        ]), JSON_FORCE_OBJECT)]), ["$L/a/1.html" => $article], [['content', "$L/a/1.html"]]],
        ['content-custom-content-key', node(['title_rule' => "<h1 class=\"t\">[内容]</h1>", 'customize_config' => json_encode([
            ['name' => '正文', 'en_name' => 'content', 'rule' => "<span class=\"cat\">[内容]</span>", 'html_rule' => ''],
        ], JSON_FORCE_OBJECT)]), ["$L/a/1.html" => $article], [['content', "$L/a/1.html"]]],
        ['content-custom-bad-json', node($contentRules + ['customize_config' => '{bad']), ["$L/a/1.html" => $article], [['content', "$L/a/1.html"]]],
        ['content-custom-scalar', node($contentRules + ['customize_config' => '123']), ["$L/a/1.html" => $article], [['content', "$L/a/1.html"]]],
        ['content-no-rules', node([]), ["$L/a/1.html" => $article], [['content', "$L/a/1.html"]]],
        ['content-missing', node($contentRules), [], [['content', "$L/a/none.html"]]],
        ['content-rule-not-found', node(['title_rule' => "<h2>[内容]</h2>", 'content_rule' => "<div id=\"nope\">[内容]</div>"]), ["$L/a/1.html" => $article],
            [['content', "$L/a/1.html"]]],
        ['content-rule-empty-start', node(['title_rule' => "[内容]</h1>"]), ["$L/a/1.html" => $article], [['content', "$L/a/1.html"]]],
        ['content-rule-multi-marker', node(['title_rule' => "<h1 class=\"t\">[内容]</h1>[内容]x"]), ["$L/a/1.html" => $article], [['content', "$L/a/1.html"]]],
        ['content-big5', node(['sourcecharset' => 'BIG5', 'title_rule' => '<h1>[内容]</h1>', 'content_rule' => "<div id=\"content\">[内容]</div>"]),
            ["$L/b.html" => $big5], [['content', "$L/b.html"]]],
        ['content-invalid-utf8', node(['title_rule' => '<h1>[内容]</h1>']), ["$L/b.html" => "<h1>坏\xff</h1>"], [['content', "$L/b.html"]]],
        ['page-next', node($contentRules + ['content_page_start' => "<div class=\"pages\">", 'content_page_end' => "</div>", 'content_page_rule' => '2',
            'content_nextpage' => '下一页', 'content_page' => '1']), ["$L/a/1.html" => $article, "$L/a/1_2.html" => $page2, "$L/a/1_3.html" => $page3],
            [['content', "$L/a/1.html"]]],
        ['page-next-no-split', node($contentRules + ['content_page_start' => "<div class=\"pages\">", 'content_page_end' => "</div>", 'content_page_rule' => '2',
            'content_nextpage' => '下一页', 'content_page' => '0']), ["$L/a/1.html" => $article, "$L/a/1_2.html" => $page2, "$L/a/1_3.html" => $page3],
            [['content', "$L/a/1.html"]]],
        ['page-next-missing', node($contentRules + ['content_page_start' => "<div class=\"pages\">", 'content_page_end' => "</div>", 'content_page_rule' => '2',
            'content_nextpage' => '下一页']), ["$L/a/1.html" => $article], [['content', "$L/a/1.html"]]],
        ['page-next-empty-nextpage', node($contentRules + ['content_page_start' => "<div class=\"pages\">", 'content_page_end' => "</div>",
            'content_page_rule' => '2', 'content_nextpage' => '']), ["$L/a/1.html" => $article, "$L/a/1_2.html" => $page2, "$L/a/1_3.html" => $page3],
            [['content', "$L/a/1.html"]]],
        ['page-all', node($contentRules + ['content_page_start' => "<div class=\"pages\">", 'content_page_end' => "</div>", 'content_page_rule' => '1']),
            ["$L/a/1.html" => $article, "$L/a/1_2.html" => $page2, "$L/a/1_3.html" => $page3], [['content', "$L/a/1.html"]]],
        ['page-all-dup-and-self', node($contentRules + ['content_page_start' => "<div class=\"pages\">", 'content_page_end' => "</div>", 'content_page_rule' => '1']),
            ["$L/p/1.html" => str_replace('<div class="pages">', '<div class="pages"><a href="1.html">1</a><a href="2.html">2</a><a href=\'2.html\'>2</a><a href=#>#</a>', $page3),
                "$L/p/2.html" => $page2], [['content', "$L/p/1.html"]]],
        ['page-oldurl-shared', node($contentRules + ['content_page_start' => "<div class=\"pages\">", 'content_page_end' => "</div>", 'content_page_rule' => '2',
            'content_nextpage' => '下一页']), ["$L/a/1.html" => $article, "$L/a/1_2.html" => $page2, "$L/a/1_3.html" => $page3],
            [['content', "$L/a/1.html"], ['content', "$L/a/1.html"], ['content', "$L/a/1_2.html"]]],
        ['page-blank-start', node($contentRules + ['content_page_start' => " ", 'content_page_end' => "</div>", 'content_page_rule' => '2',
            'content_nextpage' => '下一页']), ["$L/a/1.html" => $article], [['content', "$L/a/1.html"]]],
    ];

    if ($argc < 2) {
        // 驱动：逐个用例启动子进程
        $out = ['php' => PHP_VERSION, 'cases' => []];
        foreach ($cases as $c) {
            $cmd = escapeshellarg(PHP_BINARY) . ' ' . escapeshellarg(__FILE__) . ' ' . escapeshellarg($c[0]);
            $json = shell_exec($cmd);
            $one = json_decode($json, true);
            if (!is_array($one)) {
                fwrite(STDERR, "case {$c[0]} failed: $json\n");
                exit(1);
            }
            $out['cases'][] = $one;
        }
        echo json_encode($out, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES), "\n";
        exit(0);
    }

    require __DIR__ . '/../../../php/application/common/util/Collection.php';
    foreach ($cases as $c) {
        if ($c[0] !== $argv[1]) {
            continue;
        }
        list($name, $config, $pages, $ops) = $c;
        $GLOBALS['__pages'] = $pages;
        $pageList = [];
        foreach ($pages as $u => $body) {
            $pageList[] = [base64_encode($u), base64_encode($body)];
        }
        $results = [];
        foreach ($ops as $op) {
            $GLOBALS['__fetched'] = [];
            $r = ['op' => $op[0], 'url' => isset($op[1]) ? base64_encode($op[1]) : null];
            try {
                $cfg = $config;
                if ($op[0] === 'url_list') {
                    $v = \app\common\util\Collection::url_list($cfg);
                } elseif ($op[0] === 'url_lists') {
                    $v = \app\common\util\Collection::get_url_lists($op[1], $cfg);
                } else {
                    $v = \app\common\util\Collection::get_content($op[1], $cfg);
                    $j = json_encode($v);
                    $r['json'] = $j === false ? null : base64_encode($j);
                }
                $r['result'] = typed($v);
            } catch (\Throwable $e) {
                $r['error'] = get_class($e);
            }
            $r['fetched'] = array_map('base64_encode', $GLOBALS['__fetched']);
            $results[] = $r;
        }
        $typedNode = [];
        foreach ($config as $k => $v) {
            $typedNode[] = [$k, base64_encode($v)];
        }
        echo json_encode(['name' => $name, 'node' => $typedNode, 'pages' => $pageList, 'ops' => $results], JSON_UNESCAPED_SLASHES);
        exit(0);
    }
    fwrite(STDERR, "unknown case\n");
    exit(1);
}
