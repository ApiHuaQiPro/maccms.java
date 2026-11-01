# CASE: TC-I-COLLECT-001 | 接口: GET /collect/api（API-040） | 追溯: AC-10 / OUT-1-1～OUT-1-4 | 维度: 安全 | 优先级: P1
# 标题: cjurl 格式合法但目标未通过 OUT-1-1～OUT-1-4（地址类别/主机名/端口/用户信息/片段）一律 COLLECT_URL_REJECTED，
#       且错误体不含 URL、主机、解析地址、端口（OUT-1-9）
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（x-outbound-policy OUT-1-1～OUT-1-4、OUT-1-9）
# 前置: 默认角色管理员有 collect 权限；出站模式 open（生产同款），无 mock
# 说明: 本条只断言「拒绝侧 + 错误体不泄露」。服务端不连接被拒目标是 OUT-1 的实现保证；连接侧的强证据（V-26 对照 mock
#       服务连接日志）不在本脚本内，T3 报告里按「未覆盖」记录。
#       注意分层：cjurl 自身格式不合（scheme/用户信息/片段/超长/控制字符）→ COLLECT_PARAM_ERR，由 TC-I-COLLECT-002 覆盖；
#       本条只放「格式合法、目标被 OUT-1 拒」的样本。

from conftest import assert_json_error

# 这些 URL 均是合法绝对 http(s) URL（能被 URI 解析），拒绝理由来自 OUT-1-3/4 的地址类别或 OUT-1-2 的端口
REJECTED = [
    ("回环 IPv4", "http://127.0.0.1/vod.xml"),
    ("回环 IPv4 简写", "http://127.1/vod.xml"),
    ("回环 IPv4 十进制写法", "http://2130706433/vod.xml"),
    ("回环 IPv4 八进制写法", "http://0177.0.0.1/vod.xml"),
    ("回环 IPv4 十六进制写法", "http://0x7f000001/vod.xml"),
    ("回环主机名", "http://localhost/vod.xml"),
    ("回环子域主机名", "http://api.localhost/vod.xml"),
    ("IPv4 未指定地址", "http://0.0.0.0/vod.xml"),
    ("私网 10/8", "http://10.0.0.1:80/vod.xml"),
    ("私网 172.16/12", "http://172.16.0.1/vod.xml"),
    ("私网 192.168/16", "http://192.168.1.1/vod.xml"),
    ("运营商 NAT 100.64/10", "http://100.64.0.1/vod.xml"),
    ("链路本地 169.254/16", "http://169.254.1.1/vod.xml"),
    ("云元数据地址", "http://169.254.169.254/latest/meta-data/"),
    ("元数据主机名", "http://metadata.google.internal/computeMetadata/v1/"),
    ("元数据短名", "http://metadata/"),
    ("文档保留 192.0.2/24", "http://192.0.2.1/vod.xml"),
    ("基准测试 198.18/15", "http://198.18.0.1/vod.xml"),
    ("组播 224/4", "http://224.0.0.1/vod.xml"),
    ("保留 240/4", "http://240.0.0.1/vod.xml"),
    ("广播地址", "http://255.255.255.255/vod.xml"),
    ("回环 IPv6", "http://[::1]/vod.xml"),
    ("IPv6 未指定地址", "http://[::]/vod.xml"),
    ("IPv4 映射 IPv6（内嵌回环）", "http://[::ffff:127.0.0.1]/vod.xml"),
    ("NAT64 前缀（内嵌回环）", "http://[64:ff9b::127.0.0.1]/vod.xml"),
    ("IPv6 唯一本地 fc00::/7", "http://[fc00::1]/vod.xml"),
    ("IPv6 链路本地 fe80::/10", "http://[fe80::1]/vod.xml"),
    ("IPv6 组播 ff00::/8", "http://[ff02::1]/vod.xml"),
    ("SSH 端口 22", "http://93.184.216.34:22/vod.xml"),
    ("MySQL 端口 3306", "http://93.184.216.34:3306/vod.xml"),
    ("Redis 端口 6379", "http://93.184.216.34:6379/vod.xml"),
    ("Memcached 端口 11211", "http://93.184.216.34:11211/vod.xml"),
    ("端口 0", "http://93.184.216.34:0/vod.xml"),
]

# OUT-1-9：错误体不得出现的片段
FORBIDDEN_FRAGMENTS = (
    "127.0.0.1",
    "localhost",
    "169.254",
    "0.0.0.0",
    "192.168",
    "10.0.0.1",
    "6379",
    "3306",
    "11211",
    "::1",
    "fc00:",
    "metadata",
    "255.255.255.255",
)


def test_TC_I_COLLECT_001(api_as, admin_url):
    api = api_as("default")
    path = admin_url("collect/api")
    for label, cjurl in REJECTED:
        resp = api.call("GET", path, params={"cjurl": cjurl, "ac": "list"})
        # API-040 为后台动作：HTTP 200 + code=0 + 固定提示（不附 URL）
        body = assert_json_error(resp)
        assert "采集链接有误" in body["msg"], (
            "[%s] 期望 COLLECT_URL_REJECTED（采集链接有误或不能为本地链接），实际 msg=%r"
            % (label, body["msg"])
        )
        text = resp.text
        for leaked in FORBIDDEN_FRAGMENTS:
            assert leaked not in text, "[%s] 错误体泄露了目标信息：%r" % (label, leaked)
