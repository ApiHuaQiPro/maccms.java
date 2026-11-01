# CASE: TC-I-COLLECT-003 | 接口: GET|POST /collect/test（API-041） | 追溯: AC-10 / COLLECT_URL_REJECTED | 维度: 安全 | 优先级: P1
# 标题: API-041 的目标拒绝与 API-040 一致（OUT-1），wire code 为 1001，错误体不含 URL/主机/解析地址
# 契约: docs/api/APPK-2/openapi.yaml @ bc612e933aa67abab319b90534c5150c10a51452（CollectTestResult.code、x-outbound-policy、OUT-1-9）
# 前置: 默认角色管理员有 collect 权限
# 判定: HTTP 200 + JSON code=1001 + msg 为 lang(model/collect/cjurl_err)（不附 URL）

# 与 TC-I-COLLECT-001 同类的代表样本（覆盖 OUT-1-1～OUT-1-4 的每个类别各一例，避免重复跑全部 32 条）
REJECTED = [
    ("回环 IPv4", "http://127.0.0.1/vod.xml"),
    ("回环主机名", "http://localhost/vod.xml"),
    ("私网 10/8", "http://10.0.0.1/vod.xml"),
    ("云元数据地址", "http://169.254.169.254/latest/meta-data/"),
    ("元数据主机名", "http://metadata.google.internal/v1/"),
    ("回环 IPv6", "http://[::1]/vod.xml"),
    ("IPv4 映射 IPv6", "http://[::ffff:127.0.0.1]/vod.xml"),
    ("IPv6 唯一本地", "http://[fc00::1]/vod.xml"),
    ("内部服务端口 6379", "http://93.184.216.34:6379/vod.xml"),
    ("非常规 IPv4 十进制写法", "http://2130706433/vod.xml"),
]


def test_TC_I_COLLECT_003(api_as, admin_url):
    api = api_as("default")
    path = admin_url("collect/test")
    for label, cjurl in REJECTED:
        # API-041 由页面 collect-info.js 用 $.post 提交，两个方法都要判定
        for method in ("GET", "POST"):
            resp = api.call(method, path, params={"cjurl": cjurl, "ac": "list"})
            assert resp.status_code == 200, (
                "[%s %s] 应为 HTTP 200，实际 %s" % (method, label, resp.status_code)
            )
            assert "application/json" in resp.headers.get("Content-Type", ""), (
                "[%s %s] API-041 为 JSON，Content-Type=%r"
                % (method, label, resp.headers.get("Content-Type"))
            )
            body = resp.json()
            assert body.get("code") == 1001, (
                "[%s %s] 期望 code=1001，实际 %r" % (method, label, body.get("code"))
            )
            assert "采集链接有误" in body.get("msg", ""), (
                "[%s %s] 期望 COLLECT_URL_REJECTED，实际 msg=%r"
                % (method, label, body.get("msg"))
            )
            # OUT-1-9：不含 URL、主机、解析地址、端口
            for leaked in ("127.0.0.1", "localhost", "10.0.0.1", "169.254",
                           "metadata", "::1", "fc00:", "6379", "2130706433"):
                assert leaked not in resp.text, (
                    "[%s %s] 错误体泄露目标信息：%r" % (method, label, leaked)
                )
