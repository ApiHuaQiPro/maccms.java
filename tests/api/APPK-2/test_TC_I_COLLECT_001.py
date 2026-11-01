# CASE: TC-I-COLLECT-001 | 接口: GET /collect/api（API-040） | 追溯: AC-13 / OUT-1-1～OUT-1-4 | 维度: 安全 | 优先级: P1
# 标题: cjurl 格式合法但目标未通过 OUT-1-1～OUT-1-4（地址类别/主机名/端口/用户信息/片段）一律 COLLECT_URL_REJECTED，
#       且错误体不含 URL、主机、解析地址、端口（OUT-1-9）
# 去敏判定（第 2 轮修订）：对每个输入**动态**生成禁止片段——完整 URL、主机名及其子域写法、
#       该主机的 IPv4 等价书写（点分/整数/十六进制/八进制/简写）与 IPv6 等价书写（原样/去括号/大小写）、
#       显式端口 ":"+port 与 ":"+port+"/"；原文与 URL 解码后的文本都检查。
#       这些片段必须由用例从输入算出来，不能用一组固定片段代替（固定片段对 192.0.2.1 / 198.18.0.1 /
#       224.0.0.1 / 93.184.216.34 等输入不生效，会让回显通过）。
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（x-outbound-policy OUT-1-1～OUT-1-4、OUT-1-9）
# 前置: 默认角色管理员有 collect 权限；出站模式 open（生产同款），无 mock
# 说明: 本条只断言「拒绝侧 + 错误体不泄露」。服务端不连接被拒目标是 OUT-1 的实现保证；连接侧的强证据（V-26 对照 mock
#       服务连接日志）不在本脚本内，T3 报告里按「未覆盖」记录。
#       注意分层：cjurl 自身格式不合（scheme/用户信息/片段/超长/控制字符）→ COLLECT_PARAM_ERR，由 TC-I-COLLECT-002 覆盖；
#       本条只放「格式合法、目标被 OUT-1 拒」的样本。

from conftest import assert_error_body_redacted, assert_json_error

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
        # OUT-1-9：msg 与整份响应都不得回显该输入的 URL / 主机 / 端口 / 解析地址（按输入动态生成禁止片段）
        assert_error_body_redacted(body["msg"], cjurl, "%s msg" % label)
        assert_error_body_redacted(resp.text, cjurl, "%s body" % label)
