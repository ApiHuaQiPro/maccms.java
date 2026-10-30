<?php
/*
 * B18（MACC-42）资源站 Provide 文件解析的黄金样本（docs/architecture/26-b18-collect-preview.md 第 7 节）。
 *
 * 直接调用旧 application/common/model/Collect.php 的 vod()/vod_xml()/vod_json()/art()/actor()/role()/website()/comment_json()，
 * 不修改旧代码、不连库、不访问网络：这些方法在命名空间 app\common\model 里以非限定名调用 mac_curl_get()、model()、config()、
 * lang()，PHP 先在当前命名空间里找函数，所以本脚本在同一命名空间里定义同名函数——mac_curl_get() 返回本用例的文件内容，config('bind')
 * 返回本用例的绑定，model('Type') 返回空的分类缓存，lang() 原样返回语言包键。采集地址用一个不存在的主机名，只为通过 checkCjUrl。
 * 模型对象用 newInstanceWithoutConstructor() 创建（不初始化数据库连接）。
 *
 * 每行的"入库预检"（{type}_data 循环开头的跳过条件：分类未绑定、名称为空、名称命中过滤词）是从各 {type}_data 原样抄出的条件，
 * 用旧 mac_array_filter() 计算（{type}_data 本身会写库，不能直接调用）。
 *
 * 值用带类型的 JSON 表示（同 b15_php_semantics_golden.php 的 typed()）：字符串 base64，浮点用 IEEE754 位，数组保留键的类型与顺序。
 *
 * 用法（仓库根目录，PHP 8.0 与 7.3 各一份）：
 *   D:/Tools/php/8.0/php.exe src/test/php/collect_provide_golden.php > src/test/resources/collect/provide/php80-provide-golden.json
 *   D:/Tools/php/7.3/php.exe src/test/php/collect_provide_golden.php > src/test/resources/collect/provide/php73-provide-golden.json
 */

namespace app\common\model {

    function mac_curl_get($url, $heads = [], $cookie = '')
    {
        return $GLOBALS['__case_input'];
    }

    function config($name = '')
    {
        if ($name === 'bind') {
            return $GLOBALS['__case_bind'];
        }
        throw new \RuntimeException('unexpected config(' . $name . ')');
    }

    function lang($key, $vars = [])
    {
        return $key;
    }

    class __TypeStub
    {
        public function getCache($k)
        {
            return [];
        }
    }

    function model($name)
    {
        if ($name === 'Type') {
            return new __TypeStub();
        }
        throw new \RuntimeException('unexpected model(' . $name . ')');
    }
}

namespace {

    error_reporting(E_ALL & ~E_NOTICE & ~E_WARNING & ~E_DEPRECATED);
    ini_set('display_errors', 'stderr');
    ini_set('serialize_precision', '-1');

    $root = realpath(__DIR__ . '/../../../php');
    define('APP_PATH', $root . '/application/');
    define('ROOT_PATH', $root . '/');
    require $root . '/thinkphp/base.php';
    require APP_PATH . 'common.php';
    \think\Loader::addNamespace('app', APP_PATH);

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

    /** 各 {type}_data 循环开头的跳过条件（原样抄自 Collect.php；$f 为该类型的过滤词，即 maccms.php 的 collect.{type}.filter）。 */
    function precheck($fn, $v, $f)
    {
        $filter_arr = explode(',', $f);
        $filter_arr = array_filter($filter_arr);
        $des = '';
        switch ($fn) {
            case 'vod': // Collect.php:452-460
                if ($v['type_id'] == 0) { $des = 'model/collect/type_err'; }
                elseif (empty($v['vod_name'])) { $des = 'model/collect/name_err'; }
                elseif (mac_array_filter($filter_arr, $v['vod_name']) !== false) { $des = 'model/collect/name_in_filter_err'; }
                break;
            case 'art': // Collect.php:1150-1158
                if ($v['type_id'] == 0) { $des = 'model/collect/type_err'; }
                elseif (empty($v['art_name'])) { $des = 'model/collect/name_err'; }
                elseif (mac_array_filter($filter_arr, $v['art_name']) !== false) { $des = 'model/collect/name_in_filter_err'; }
                break;
            case 'actor': // Collect.php:1474-1482（旧语言包键少了开头的 m，照旧）
                if ($v['type_id'] == 0) { $des = 'model/collect/type_err'; }
                elseif (empty($v['actor_name']) || empty($v['actor_sex'])) { $des = 'odel/collect/actor_data_require'; }
                elseif (mac_array_filter($filter_arr, $v['actor_name']) !== false) { $des = 'model/collect/name_in_filter_err'; }
                break;
            case 'role': // Collect.php:1746-1751
                if (empty($v['role_name']) || empty($v['role_actor']) || empty($v['vod_name'])) { $des = 'model/collect/role_data_require'; }
                elseif (mac_array_filter($filter_arr, $v['role_name']) !== false) { $des = 'model/collect/name_in_filter_err'; }
                break;
            case 'website': // Collect.php:2066-2074
                if ($v['type_id'] == 0) { $des = 'model/collect/type_err'; }
                elseif (empty($v['website_name'])) { $des = 'model/collect/name_err'; }
                elseif (mac_array_filter($filter_arr, $v['website_name']) !== false) { $des = 'model/collect/name_in_filter_err'; }
                break;
            case 'comment': // Collect.php:2335-2340
                if (empty($v['comment_name']) || empty($v['comment_content']) || empty($v['rel_name'])) { $des = 'model/collect/comment_data_require'; }
                elseif (mac_array_filter($filter_arr, $v['comment_content']) !== false) { $des = 'model/collect/name_in_filter_err'; }
                break;
        }
        return $des;
    }

    $flag = md5('http://provide.invalid/api.php/provide/vod/');
    $other = md5('http://other.invalid/api.php');

    // ---------------------------------------------------------------- 用例的输入
    $vodJson = json_encode([
        'code' => 1, 'msg' => '数据列表', 'page' => 2, 'pagecount' => 9, 'limit' => '20', 'total' => 170,
        'list' => [
            ['vod_id' => 101, 'type_id' => 1, 'type_name' => '动作片', 'vod_name' => '甲片', 'vod_sub' => '', 'vod_remarks' => 'HD',
                'vod_play_from' => 'ckm3u8', 'vod_play_url' => '第1集$http://v.invalid/1.m3u8', 'vod_time' => '2026-10-01 10:00:00',
                'vod_content' => '<p>简介</p>', 'vod_score' => '8.5', 'vod_year' => 2026],
            ['vod_id' => 102, 'type_id' => 2, 'type_name' => '喜剧片', 'vod_name' => '乙片 广告', 'vod_play_from' => 'yun'],
            ['vod_id' => 103, 'type_id' => 3, 'type_name' => '未绑', 'vod_name' => '丙片'],
            ['vod_id' => 104, 'type_id' => '15', 'vod_name' => ''],
            ['vod_id' => 105, 'type_id' => 1, 'vod_name' => '0'],
        ],
        'class' => [['type_id' => 1, 'type_pid' => 0, 'type_name' => '动作片'], ['type_id' => 2, 'type_name' => '喜剧片'],
            ['type_id' => 3, 'type_name' => '<b>未绑</b>'], ['type_id' => '15', 'type_name' => '字符串编号']],
    ], JSON_UNESCAPED_UNICODE);

    $vodJsonDl = '{"page":1,"pagecount":1,"limit":20,"total":2,"list":[' .
        '{"vod_id":1,"type_id":1,"vod_name":"有dl","vod_play_from":"旧来源","dl":{"ckm3u8":"第1集$http://a.invalid/1.m3u8#第2集$http://a.invalid/2.m3u8","yun":"HD$http://b.invalid/x"}},' .
        '{"vod_id":2,"type_id":1,"vod_name":"dl为空","dl":{}},' .
        '{"vod_id":3,"type_id":1,"vod_name":"dl为字符串","dl":"abc"},' .
        '{"vod_id":4,"type_id":1,"vod_name":"dl值各类","dl":{"a":null,"b":true,"c":false,"d":3,"e":2.5,"7":"数字键"}}' .
        ']}';

    $vodJsonTypes = '{"page":"1","pagecount":"1","limit":"20","total":"9","list":[' .
        '{"vod_id":1,"type_id":"1","vod_name":"字符串1"},' .
        '{"vod_id":2,"type_id":1.0,"vod_name":"浮点1.0"},' .
        '{"vod_id":3,"type_id":2.5,"vod_name":"浮点2.5"},' .
        '{"vod_id":4,"type_id":null,"vod_name":"null"},' .
        '{"vod_id":5,"type_id":true,"vod_name":"true"},' .
        '{"vod_id":6,"vod_name":"缺type_id"},' .
        '{"vod_id":7,"type_id":"01","vod_name":"前导零"},' .
        '{"vod_id":8,"type_id":1e20,"vod_name":"大浮点"},' .
        '{"vod_id":9,"type_id":0.1,"vod_name":"小浮点"}' .
        '],"class":[{"type_id":"1","type_name":"一"},{"type_id":1.0,"type_name":"浮点"},{"type_name":"缺编号"},{"type_id":[1],"type_name":"数组"}]}';

    $vodJsonValues = '{"page":1,"pagecount":1,"limit":20,"total":3,"extra":[1,2],"list":{' .
        '"a":{"vod_id":1,"type_id":1,"vod_name":"对象形式的list","123":"数字串键","-5":"负数键","05":"前导零键","":"空键","dup":1,"dup":2,' .
        '"big":12345678901234567890,"min":-9223372036854775808,"f1":1.0,"f2":0.1,"f3":-0.0,"f4":1.5e-7,"f5":1E+25,"t":true,"n":null,' .
        '"nested":{"x":[1,{"y":"z"}],"0":"零"},"esc":"\u4e2d\ud83d\ude00\/\"\\\\\b\f\n\r\t\u0001"},' .
        '"5":{"vod_id":2,"type_id":2,"vod_name":"数字键5"},' .
        '"n":null,"f":false,"l":[1,2]}}';

    $vodJsonTags = '{"page":1,"pagecount":1,"limit":20,"total":1,"list":[{"vod_id":1,"type_id":1,"vod_name":"x<SCRIPT>y{:z",' .
        '"vod_content":"<iframe src=1><FrameSet><object onError=1>OnErRoR","vod_pic":"<ScRiPt"}]}';

    $deep = function ($n) {
        return '{"page":1,"list":[{"vod_id":1,"type_id":1,"vod_name":"深","d":' . str_repeat('[', $n) . str_repeat(']', $n) . '}]}';
    };

    $vodXml = '<?xml version="1.0" encoding="utf-8"?>' . "\n" .
        '<rss version="5.1"><list page="3" pagecount="10" pagesize="20" recordcount="199">' . "\n" .
        '<video><last>2026-10-01 12:00:00</last><id>11</id><tid>1</tid><name><![CDATA[片名&甲]]></name><type>动作片</type>' .
        '<pic>http://p.invalid/a.jpg</pic><lang>国语</lang><area>大陆</area><year>2026</year><state>更新至5集</state>' .
        '<note><![CDATA[HD]]></note><actor><![CDATA[张三,李四]]></actor><director>王五</director>' .
        '<dl><dd flag="ckm3u8"><![CDATA[第1集$http://a.invalid/1.m3u8 #第2集$ http://a.invalid/2.m3u8 ||x]]></dd><dd flag="yun"><![CDATA[正片]]></dd></dl>' .
        '<des><![CDATA[<p>简介&amp;</p>]]></des></video>' . "\n" .
        '<video><id>12</id><tid>2</tid><name>  前后空白  </name><name>第二个name</name><state>0</state><dt>flv</dt></video>' . "\n" .
        '<video><id>13</id><tid>9</tid><name>混合<b>粗</b>内容&lt;&#x4e2d;</name><state></state><dl></dl></video>' . "\n" .
        '</list><class><ty id="1">动作片</ty><ty id="2">喜剧片</ty><ty id="9"><![CDATA[未绑]]></ty><ty>缺id</ty></class></rss>';

    $vodXmlBadPic = '<?xml version="1.0" encoding="utf-8"?><rss><list page="1" pagecount="1" pagesize="20" recordcount="2">' .
        '<video><id>1</id><tid>1</tid><name>图片地址有and号</name><pic>http://p.invalid/a.jpg?x=1&y=2</pic></video>' .
        '<video><id>2</id><tid>1</tid><name>已有CDATA</name><pic><![CDATA[http://p.invalid/b.jpg?x=1&y=2]]></pic></video>' .
        '</list></rss>';

    $gbk = function_exists('mb_convert_encoding')
        ? mb_convert_encoding('<?xml version="1.0" encoding="GBK"?><rss><list page="1" pagecount="1" pagesize="20" recordcount="1"><video><id>1</id><tid>1</tid><name>中文名</name></video></list></rss>', 'GBK', 'UTF-8')
        : null;

    $cases = [
        // ------------------------------------------------ 视频 JSON
        ['vod-json-list-bind', 'vod', ['type' => '2', 'ac' => 'list'], $vodJson, [$flag . '_1' => 5, $flag . '_2' => 0, $flag . '_15' => 7, $other . '_3' => 9], '广告,,0'],
        ['vod-json-videolist', 'vod', ['type' => '2', 'ac' => 'videolist'], $vodJson, [$flag . '_1' => 5], ''],
        ['vod-json-dl', 'vod', ['type' => '2', 'ac' => 'list'], $vodJsonDl, [$flag . '_1' => 5], ''],
        ['vod-json-type-ids', 'vod', ['type' => '2', 'ac' => 'list'], $vodJsonTypes,
            [$flag . '_1' => 5, $flag . '_' => 6, $flag . '_2.5' => 7, $flag . '_01' => 8, $flag . '_1.0E+20' => 9, $flag . '_0.1' => 10], ''],
        ['vod-json-values', 'vod', ['type' => '2', 'ac' => 'list'], $vodJsonValues, [$flag . '_1' => 5, $flag . '_2' => 6], ''],
        ['vod-json-filter-tags', 'vod', ['type' => '2', 'ac' => 'list'], $vodJsonTags, [$flag . '_1' => 5], ''],
        ['vod-json-no-list', 'vod', ['type' => '2', 'ac' => 'list'], '{"code":1}', [], ''],
        ['vod-json-list-scalar', 'vod', ['type' => '2', 'ac' => 'list'], '{"page":1,"list":"abc","class":5}', [], ''],
        ['vod-json-empty-object', 'vod', ['type' => '2', 'ac' => 'list'], '{}', [], ''],
        ['vod-json-empty-array', 'vod', ['type' => '2', 'ac' => 'list'], '[]', [], ''],
        ['vod-json-zero', 'vod', ['type' => '2', 'ac' => 'list'], '0', [], ''],
        ['vod-json-false', 'vod', ['type' => '2', 'ac' => 'list'], 'false', [], ''],
        ['vod-json-truncated', 'vod', ['type' => '2', 'ac' => 'list'], '{"page":1,"list":[{"vod_name":"截断的中文内容很长很长"', [], ''],
        ['vod-json-trailing', 'vod', ['type' => '2', 'ac' => 'list'], '{"page":1} x', [], ''],
        ['vod-json-bom', 'vod', ['type' => '2', 'ac' => 'list'], "\xEF\xBB\xBF" . '{"page":1,"list":[]}', [], ''],
        ['vod-json-bad-utf8', 'vod', ['type' => '2', 'ac' => 'list'], '{"page":1,"list":[{"vod_name":"' . "\xC3\x28" . '"}]}', [], ''],
        ['vod-json-lone-surrogate', 'vod', ['type' => '2', 'ac' => 'list'], '{"page":1,"list":[{"vod_name":"\ud800"}]}', [], ''],
        ['vod-json-control-char', 'vod', ['type' => '2', 'ac' => 'list'], '{"page":1,"list":[{"vod_name":"a' . "\x01" . 'b"}]}', [], ''],
        ['vod-json-single-quote', 'vod', ['type' => '2', 'ac' => 'list'], "{'page':1}", [], ''],
        ['vod-json-depth-ok', 'vod', ['type' => '2', 'ac' => 'list'], $deep(508), [$flag . '_1' => 5], ''],
        ['vod-json-depth-over', 'vod', ['type' => '2', 'ac' => 'list'], $deep(509), [$flag . '_1' => 5], ''],
        ['vod-json-null-items', 'vod', ['type' => '2', 'ac' => 'list'], '{"page":1,"list":[null,false,[],[1,2],{"type_id":1,"vod_name":"正常"}]}', [$flag . '_1' => 5, $flag . '_' => 6], ''],
        ['vod-json-whitespace', 'vod', ['type' => '2', 'ac' => 'list'], " \t\r\n" . '{"page" : 1 , "list" : [ ] }' . " \n", [], ''],
        ['vod-empty-input', 'vod', ['type' => '2', 'ac' => 'list'], '', [], ''],
        ['vod-zero-input', 'vod', ['type' => '1', 'ac' => 'list'], '0', [], ''],
        // ------------------------------------------------ 视频 XML
        ['vod-xml-list-bind', 'vod', ['type' => '1', 'ac' => 'list'], $vodXml, [$flag . '_1' => 5, $flag . '_2' => 6], ''],
        ['vod-xml-videolist', 'vod', ['type' => '1', 'ac' => 'videolist'], $vodXml, [$flag . '_1' => 5], ''],
        ['vod-xml-down', 'vod', ['type' => '1', 'ac' => 'list', 'param' => base64_encode('&ct=1&x=2')], $vodXml, [$flag . '_1' => 5], ''],
        ['vod-xml-bad-pic', 'vod', ['type' => '1', 'ac' => 'list'], $vodXmlBadPic, [$flag . '_1' => 5], ''],
        ['vod-xml-malformed', 'vod', ['type' => '1', 'ac' => 'list'], '<rss><list><video></list></rss>', [], ''],
        ['vod-xml-not-xml', 'vod', ['type' => '1', 'ac' => 'list'], '{"page":1}', [], ''],
        ['vod-xml-empty-root', 'vod', ['type' => '1', 'ac' => 'list'], '<rss/>', [], ''],
        ['vod-xml-text-root', 'vod', ['type' => '1', 'ac' => 'list'], '<rss>只有文字</rss>', [], ''],
        ['vod-xml-attr-root', 'vod', ['type' => '1', 'ac' => 'list'], '<rss version="5.1"/>', [], ''],
        ['vod-xml-no-list', 'vod', ['type' => '1', 'ac' => 'list'], '<rss><class><ty id="1">一</ty></class></rss>', [], ''],
        ['vod-xml-namespace', 'vod', ['type' => '1', 'ac' => 'list'], '<rss xmlns="urn:x"><list page="1"><video><id>1</id><tid>1</tid><name>默认命名空间</name></video></list></rss>', [$flag . '_1' => 5], ''],
        ['vod-xml-prefixed', 'vod', ['type' => '1', 'ac' => 'list'], '<rss xmlns:p="urn:p"><list page="1"><p:video><id>1</id></p:video><video><p:name>前缀名</p:name><id>2</id><tid>1</tid></video></list></rss>', [$flag . '_1' => 5], ''],
        ['vod-xml-crlf', 'vod', ['type' => '1', 'ac' => 'list'], "<rss><list page=\"1\" pagesize=\"a\tb\nc\"><video><id>1</id><tid>1</tid><name>行\r\n尾\r结束</name></video></list></rss>", [$flag . '_1' => 5], ''],
        ['vod-xml-deep-ok', 'vod', ['type' => '1', 'ac' => 'list'], '<rss><list page="1"><video><id>1</id><tid>1</tid><name>深</name><x>' . str_repeat('<a>', 253) . str_repeat('</a>', 253) . '</x></video></list></rss>', [$flag . '_1' => 5], ''],
        ['vod-xml-deep-over', 'vod', ['type' => '1', 'ac' => 'list'], '<rss><list page="1"><video><id>1</id><tid>1</tid><name>深</name><x>' . str_repeat('<a>', 254) . str_repeat('</a>', 254) . '</x></video></list></rss>', [], ''],
        ['vod-xml-bad-name', 'vod', ['type' => '1', 'ac' => 'list'], '<rss><list><video><name>a&b</name><pic>x&y</pic></video></list></rss>', [], ''],
        ['vod-xml-whitespace-root', 'vod', ['type' => '1', 'ac' => 'list'], "<rss>
</rss>", [], ''],
        ['vod-xml-cdata-root', 'vod', ['type' => '1', 'ac' => 'list'], '<rss><![CDATA[x]]></rss>', [], ''],
        ['vod-xml-comment', 'vod', ['type' => '1', 'ac' => 'list'], '<rss><list page="1"><video><id>1</id><tid>1</tid><name>a<!--c-->b<![CDATA[c]]><?p q?>d<x>e</x>f</name></video></list></rss>', [$flag . '_1' => 5], ''],
        ['vod-xml-filter-tags', 'vod', ['type' => '1', 'ac' => 'list'], '<rss><list page="1"><video><id>1</id><tid>1</tid><name><![CDATA[a<script>b]]></name><des><![CDATA[x onerror=1 {:y]]></des></video></list></rss>', [$flag . '_1' => 5], ''],
        // ------------------------------------------------ 自动判断（collect_type 既不是 1 也不是 2：先 JSON，失败再 XML）
        ['vod-auto-json', 'vod', ['type' => '0', 'ac' => 'list'], $vodJsonDl, [$flag . '_1' => 5], ''],
        ['vod-auto-xml', 'vod', ['type' => '', 'ac' => 'list'], $vodXml, [$flag . '_1' => 5], ''],
        ['vod-auto-neither', 'vod', ['type' => '3', 'ac' => 'list'], 'neither', [], ''],
        // ------------------------------------------------ 其他类型（只有 JSON）
        ['art-json', 'art', ['ac' => 'list'], '{"page":1,"pagecount":2,"limit":"10","total":11,"list":[{"art_id":1,"type_id":3,"art_name":"文章甲 敏感"},{"art_id":2,"type_id":4,"art_name":""},{"art_id":3,"type_id":3,"art_name":"文章丙"}],"class":[{"type_id":3,"type_name":"新闻"}]}',
            [$flag . '_3' => 21], '敏感'],
        ['art-json-detail', 'art', ['ac' => 'detail'], '{"page":1,"list":[{"art_id":1,"type_id":3,"art_name":"甲"}],"class":[{"type_id":3,"type_name":"新闻"}]}', [$flag . '_3' => 21], ''],
        ['actor-json', 'actor', ['ac' => 'list'], '{"page":1,"pagecount":1,"limit":20,"total":3,"list":[{"actor_id":1,"type_id":8,"actor_name":"演员甲","actor_sex":"男"},{"actor_id":2,"type_id":8,"actor_name":"演员乙","actor_sex":""},{"actor_id":3,"type_id":99,"actor_name":"演员丙","actor_sex":"女"}],"class":[{"type_id":8,"type_name":"男演员"}]}',
            [$flag . '_8' => 30], ''],
        ['role-json', 'role', ['ac' => 'list'], '{"page":1,"pagecount":1,"limit":20,"total":2,"list":[{"role_id":1,"role_name":"角色甲","role_actor":"演员甲","vod_name":"片甲","type_id":1},{"role_id":2,"role_name":"角色乙","role_actor":"","vod_name":"片乙"}],"class":[{"type_id":1,"type_name":"不用"}]}',
            [$flag . '_1' => 5], '甲'],
        ['website-json', 'website', ['ac' => 'list'], '{"page":1,"pagecount":1,"limit":20,"total":2,"list":[{"website_id":1,"type_id":5,"website_name":"站甲"},{"website_id":2,"type_id":6,"website_name":"站乙"}],"class":[{"type_id":5,"type_name":"导航"}]}',
            [$flag . '_5' => 40], ''],
        ['comment-json', 'comment', ['ac' => 'list'], '{"page":1,"pagecount":1,"limit":20,"total":2,"list":[{"comment_id":1,"comment_name":"网友","comment_content":"很好 垃圾","rel_name":"片甲","type_id":1},{"comment_id":2,"comment_name":"网友","comment_content":"一般","rel_name":""}]}',
            [$flag . '_1' => 5], '垃圾'],
        ['art-json-malformed', 'art', ['ac' => 'list'], '<rss/>', [], ''],
    ];
    if ($gbk !== null) {
        $cases[] = ['vod-xml-gbk', 'vod', ['type' => '1', 'ac' => 'list'], $gbk, [$flag . '_1' => 5], ''];
    }

    $ref = new ReflectionClass('app\common\model\Collect');
    $out = ['php' => PHP_VERSION, 'cjflag' => $flag, 'cases' => []];
    foreach ($cases as $c) {
        list($name, $fn, $p, $input, $bind, $filter) = $c;
        $GLOBALS['__case_input'] = $input;
        $GLOBALS['__case_bind'] = $bind;
        $param = array_merge(['ac' => 'list', 't' => '', 'page' => '', 'h' => '', 'ids' => '', 'wd' => '', 'type' => '',
            'param' => '', 'cjflag' => $flag, 'cjurl' => 'http://provide.invalid/api.php/provide/vod/'], $p);
        $m = $ref->newInstanceWithoutConstructor();
        $res = $fn === 'comment' ? $m->comment_json($param) : $m->$fn($param);
        $checks = [];
        if ($res['code'] == 1) {
            foreach ($res['data'] as $k => $v) {
                $checks[] = precheck($fn, $v, $filter);
            }
        }
        $out['cases'][] = [
            'name' => $name, 'fn' => $fn, 'type' => $param['type'], 'ac' => $param['ac'], 'param' => $param['param'],
            'input' => base64_encode($input), 'bind' => typed($bind), 'filter' => $filter,
            'result' => typed($res), 'checks' => $checks,
        ];
    }
    // 每个用例一行（深嵌套用例按缩进排版会膨胀到几 MB）
    $lines = [];
    foreach ($out['cases'] as $c) {
        $j = json_encode($c, JSON_UNESCAPED_SLASHES, 8192);
        if ($j === false) {
            fwrite(STDERR, json_last_error_msg() . "\n");
            exit(1);
        }
        $lines[] = $j;
    }
    echo '{"php":', json_encode($out['php']), ',"cjflag":', json_encode($out['cjflag']), ',"cases":[', "\n",
        implode(",\n", $lines), "\n]}\n";
}
