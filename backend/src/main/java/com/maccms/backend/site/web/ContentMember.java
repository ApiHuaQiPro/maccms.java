package com.maccms.backend.site.web;

import com.maccms.backend.member.application.MemberRecordService;
import com.maccms.backend.member.application.MemberWalletService;
import com.maccms.backend.member.application.view.FinanceRecords;
import com.maccms.backend.member.application.view.MemberState;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 内容页（详情、播放、下载）上的会员（B10，docs/architecture/19-b10-member-finance.md 第 5 节）：是否已登录且会员功能可用、当前积分、
 * 播放/下载记录。游客与会员功能不可用（可写数据源关闭）时为 {@link #GUEST}：页面与 B05/B09 完全相同。
 */
final class ContentMember {

    static final ContentMember GUEST = new ContentMember(null, null, null);

    private final MemberState state;
    private final MemberRecordService records;
    private final MemberWalletService wallet;

    private ContentMember(MemberState state, MemberRecordService records, MemberWalletService wallet) {
        this.state = state;
        this.records = records;
        this.wallet = wallet;
    }

    /** 本请求的会员（由 {@link SiteMemberInterceptor} 判定）；服务不全或不可用时为游客。 */
    static ContentMember of(HttpServletRequest request, ObjectProvider<MemberRecordService> records,
                            ObjectProvider<MemberWalletService> wallet) {
        MemberState s = SiteMemberInterceptor.member(request).state();
        MemberRecordService r = records == null ? null : records.getIfAvailable();
        MemberWalletService w = wallet == null ? null : wallet.getIfAvailable();
        if (!s.member() || r == null || w == null || !r.available() || !w.available()) {
            return GUEST;
        }
        return new ContentMember(s, r, w);
    }

    /** 已登录且会员功能可用（可以购买、收藏）。 */
    boolean loggedIn() {
        return state != null;
    }

    /** 当前积分（写库前重读的值）；游客为 0。 */
    long points() {
        if (state == null) {
            return 0;
        }
        return wallet.wallet(state.userId()).map(FinanceRecords.Wallet::points).orElse(0L);
    }

    /** 播放（4）/下载（5）记录，同旧模板加载后请求的 {@code ajax_ulog?ac=set}；失败不影响页面。 */
    void recordView(int type, long vodId, int sid, int nid) {
        if (state != null) {
            records.recordView(state, type, vodId, sid, nid);
        }
    }
}
