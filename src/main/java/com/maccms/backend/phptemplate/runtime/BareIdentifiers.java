package com.maccms.backend.phptemplate.runtime;

import java.util.Set;

/**
 * 登记的裸标识符名单 BARE-NAMES（技术设计 v15 §6.5.1 BARE-3）。
 *
 * <p>名字原样（区分大小写）在名单内时，表达式位置的裸名求值为名字本身的字节串（BARE-4）；
 * 其他名字由解析器按 BARE-5 拒绝。名单写死在代码里，只此一项：不读配置、系统属性或环境变量，
 * 运行时不可扩充；增加名字须出设计修订并附扫描证据。只依赖 JDK（§6.3）。</p>
 */
public final class BareIdentifiers {

    /** 现只有 {@code color}（两份 {@code user/ajax_info.html:15}，PRE-5 §9.9 ⑧ 与 v14 逐记号复核）。 */
    public static final Set<String> REGISTERED = Set.of("color");

    private BareIdentifiers() {
    }
}
