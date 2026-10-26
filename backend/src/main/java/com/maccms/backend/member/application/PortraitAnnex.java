package com.maccms.backend.member.application;

/**
 * 头像上传成功后的附件登记（D-B09-9，B13：旧 {@code common/model/Upload.php:268-289} 把头像文件登记进 mac_annex）。由 content 模块的
 * {@code AnnexAdminService} 实现（mac_annex 的写入方是 content），可写数据源关闭或未注册时不登记。
 */
@FunctionalInterface
public interface PortraitAnnex {

    /**
     * @param file 站点根之下的文件（{@code upload/user/…}）
     * @param type 旧版的类型（文件上传为 {@code image}，{@code imgdata} 为 data URL 里的图片类型）
     * @param size 旧版写入的大小（KB，保留两位小数）
     */
    void register(String file, String type, String size);
}
