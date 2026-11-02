package com.maccms.backend.integration.demo;

import com.maccms.backend.integration.http.OutboundHttpClient;
import com.maccms.backend.integration.http.OutboundRequest;
import com.maccms.backend.integration.http.OutboundResponse;

/**
 * 测试用的已登记调用方：出站客户端按"本包之外的第一个栈帧"检查调用方，{@code integration.http} 包内的测试
 * （APPK-2 V-33 {@code OutboundAddressPinningTest}）经这里发请求，调用方即本类所在的 {@code integration.demo}。
 */
public final class DemoOutboundCaller {

    private DemoOutboundCaller() {
    }

    public static OutboundResponse send(OutboundHttpClient client, OutboundRequest request) {
        return client.send(request);
    }
}
