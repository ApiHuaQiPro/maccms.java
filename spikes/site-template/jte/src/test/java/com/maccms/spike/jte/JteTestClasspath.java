package com.maccms.spike.jte;

import com.maccms.spike.jte.model.VideoCard;
import gg.jte.html.HtmlTemplateOutput;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** 测试中动态编译模板所需的 javac 类路径：jte 运行时 + 本工程的视图模型类。 */
final class JteTestClasspath {

    private JteTestClasspath() {
    }

    static List<String> entries() {
        return Stream.of(HtmlTemplateOutput.class, VideoCard.class).map(JteTestClasspath::location).distinct().toList();
    }

    private static String location(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
