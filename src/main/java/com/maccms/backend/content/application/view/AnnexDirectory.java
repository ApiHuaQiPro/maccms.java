package com.maccms.backend.content.application.view;

import java.util.List;

/**
 * 附件目录浏览的一页（旧 Annex::file）。
 *
 * @param uppath 上一级目录（{@code @} 分隔）
 * @param child  不是 upload/ 本身（显示"返回上级目录"）
 * @param items  目录与文件（目录的 {@code path} 为 {@code @} 分隔，文件的为 {@code /upload/…}）
 * @param size   文件合计大小（旧 {@code mac_format_size}）
 */
public record AnnexDirectory(String uppath, boolean child, List<Item> items, int dirs, int files, String size) {

    public AnnexDirectory {
        items = List.copyOf(items);
    }

    /** 一项；{@code size} 为 {@code mac_format_size} 的文字，{@code time} 为修改时间（目录为空、0）。 */
    public record Item(boolean file, String name, String path, String size, long time) {
    }
}
