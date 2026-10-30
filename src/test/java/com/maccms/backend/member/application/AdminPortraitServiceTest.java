package com.maccms.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.maccms.backend.content.application.AdminConfig;
import com.maccms.backend.content.application.view.UploadReply;
import com.maccms.backend.legacyconfig.application.LegacyConfigProperties;
import com.maccms.backend.legacyconfig.application.LegacyConfigService;
import com.maccms.backend.member.infrastructure.MemberAdminMapper;
import com.maccms.backend.member.infrastructure.PortraitStore;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 后台会员头像上传（23 文档第 7.7 节，D-B14-10）：会员号与会员的检查、只在检查与转换通过后写盘、失败时不留文件且原有头像不变、
 * 附件登记、不改 mac_user。与 PHP 的逐项对照在 {@code AdminUserPortraitGoldenMySqlIT}。
 */
class AdminPortraitServiceTest {

    private static final UnaryOperator<String> LANG = Map.of("admin/upload/upload_success", "文件上传成功！",
            "admin/upload/upload_faild", "文件上传失败！", "admin/upload/make_thumb_faild", "文件上传失败！",
            "admin/upload/no_input_file", "未找到上传的文件", "admin/upload/forbidden_ext", "非系统允许的上传格式！",
            "admin/upload/upload_safe", "文件包含危险内容！")::get;
    private static final String FAILED = "{\"msg\":\"文件上传失败！\",\"code\":0,\"data\":[]}";

    @TempDir
    Path root;

    private final MemberAdminMapper reader = mock(MemberAdminMapper.class);
    private final List<String[]> annexed = new ArrayList<>();

    private AdminPortraitService service(String portraitSize, String uploadDir, PortraitAnnex annex) throws IOException {
        Path extra = Files.createDirectories(root.resolve("legacy").resolve("extra"));
        if (!Files.exists(extra.resolve("maccms.php"))) {
            try (Stream<Path> s = Files.list(Path.of("php", "application", "extra"))) {
                for (Path f : s.toList()) {
                    Files.copy(f, extra.resolve(f.getFileName().toString()));
                }
            }
        }
        Path maccms = extra.resolve("maccms.php");
        String php = Files.readString(maccms, StandardCharsets.UTF_8).replaceFirst("'portrait_size' => '[^']*',",
                "'portrait_size' => '" + portraitSize + "',");
        Files.writeString(maccms, php, StandardCharsets.UTF_8);
        AdminConfig config = new AdminConfig(new LegacyConfigService(new LegacyConfigProperties(root.resolve("legacy").toString())));
        assertThat(config.text("user", "portrait_size")).isEqualTo(portraitSize);
        when(reader.countUser(anyLong())).thenReturn(0L);
        when(reader.countUser(15L)).thenReturn(1L);
        return new AdminPortraitService(reader, new PortraitStore(uploadDir), config, annex,
                Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC));
    }

    private AdminPortraitService service() throws IOException {
        return service("100x100", upload().toString(), (f, t, z) -> annexed.add(new String[] {f, t, z}));
    }

    private Path upload() throws IOException {
        return Files.createDirectories(root.resolve("upload"));
    }

    private static Map<String, String> params(String userId) {
        Map<String, String> p = new HashMap<>();
        p.put("flag", "user");
        p.put("thumb", "0");
        p.put("thumb_class", "");
        if (userId != null) {
            p.put("user_id", userId);
        }
        return p;
    }

    static byte[] png(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(200, 40, 40));
        g.fillRect(0, 0, w / 2, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private List<String> files() throws IOException {
        Path u = upload();
        try (Stream<Path> s = Files.walk(u)) {
            return s.filter(Files::isRegularFile).map(p -> u.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
    }

    @Test
    void uploadWritesAReencodedJpegRegistersTheAnnexAndLeavesMacUserAlone() throws IOException {
        byte[] png = png(120, 80);
        UploadReply r = service().upload(params("15"), png, "me.PNG", LANG);
        assertThat(r.json()).isTrue();
        String size = com.maccms.backend.content.application.PhpNumbers.round2Text(png.length / 1024.0);
        assertThat(r.body()).isEqualTo("{\"msg\":\"文件上传成功！\",\"code\":1,\"data\":{\"file\":\"upload\\/user\\/5\\/15.jpg\","
                + "\"type\":\"image\",\"size\":" + size + ",\"flag\":\"user\",\"ctime\":1800000000,\"thumb_class\":\"\",\"thumb\":[]}}");
        byte[] written = Files.readAllBytes(upload().resolve("user/5/15.jpg"));
        assertThat(written[0] & 0xff).isEqualTo(0xff);
        assertThat(written[1] & 0xff).isEqualTo(0xd8);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(written));
        assertThat(img.getWidth()).isEqualTo(100);
        assertThat(img.getHeight()).isEqualTo(100);
        assertThat(files()).containsExactly("user/5/15.jpg");
        assertThat(annexed).hasSize(1);
        assertThat(annexed.getFirst()[0]).isEqualTo("upload/user/5/15.jpg");
        assertThat(annexed.getFirst()[1]).isEqualTo("image");
        assertThat(annexed.getFirst()[2]).isEqualTo(java.math.BigDecimal.valueOf(written.length / 1024.0)
                .setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
        // 只查会员是否存在，不读写会员行（旧后台的上传同样不改 mac_user，头像地址由表单保存写入）
        verify(reader).countUser(15L);
        verifyNoMoreInteractions(reader);
    }

    @Test
    void configuredSizeWithOneNumberIsSquareAndTheDataUrlTypeIsKept() throws IOException {
        AdminPortraitService s = service("64", upload().toString(), (f, t, z) -> annexed.add(new String[] {f, t, z}));
        Map<String, String> p = params("15");
        p.put("imgdata", "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png(30, 20)));
        UploadReply r = s.upload(p, null, null, LANG);
        assertThat(r.body()).contains("\"code\":1").contains("\"type\":\"png\"");
        BufferedImage img = ImageIO.read(upload().resolve("user/5/15.jpg").toFile());
        assertThat(img.getWidth()).isEqualTo(64);
        assertThat(img.getHeight()).isEqualTo(64);
        assertThat(annexed.getFirst()[1]).isEqualTo("png");
    }

    @Test
    void userIdMustBeTheDecimalIdOfAnExistingMember() throws IOException {
        AdminPortraitService s = service();
        byte[] png = png(20, 20);
        for (String id : new String[] {null, "", "0", "999", "abc", "-3", "1.5", "15abc", "015", "+15", " 15", "../../1",
            "..%2F1", "99999999999", "4294967296"}) {
            assertThat(s.upload(params(id), png, "a.png", LANG).body()).as(String.valueOf(id)).isEqualTo(FAILED);
        }
        assertThat(files()).isEmpty();
        assertThat(annexed).isEmpty();
        assertThat(Files.list(root).map(p -> p.getFileName().toString()).toList()).containsExactlyInAnyOrder("legacy", "upload");
        assertThat(AdminPortraitService.userId("4294967295")).isEqualTo(4_294_967_295L);
        assertThat(AdminPortraitService.userId("15")).isEqualTo(15L);
    }

    @Test
    void rejectedFilesAreNeverWrittenAndTheExistingPortraitStays() throws IOException {
        AdminPortraitService s = service();
        Path old = Files.createDirectories(upload().resolve("user/5")).resolve("15.jpg");
        Files.writeString(old, "old");
        byte[] png = png(20, 20);
        byte[] marked = new byte[png.length + 20];
        System.arraycopy(png, 0, marked, 0, png.length);
        System.arraycopy("<script>x</script>".getBytes(StandardCharsets.US_ASCII), 0, marked, png.length, 18);
        assertThat(s.upload(params("15"), "not an image not an image".getBytes(), "n.jpg", LANG).body()).isEqualTo(FAILED);
        assertThat(s.upload(params("15"), "<?php echo 1;".getBytes(), "x.jpg", LANG).body()).contains("非系统允许的上传格式！");
        assertThat(s.upload(params("15"), png, "x.exe", LANG).body()).contains("非系统允许的上传格式！");
        assertThat(s.upload(params("15"), marked, "x.png", LANG).body()).contains("文件包含危险内容！");
        assertThat(s.upload(params("15"), null, null, LANG).body()).contains("未找到上传的文件");
        assertThat(Files.readString(old)).isEqualTo("old");
        assertThat(files()).containsExactly("user/5/15.jpg");
        assertThat(annexed).isEmpty();
    }

    @Test
    void invalidPortraitSizeOrMissingUploadDirectoryFailsWithoutWriting() throws IOException {
        byte[] png = png(20, 20);
        assertThat(service("0x0", upload().toString(), (f, t, z) -> annexed.add(new String[] {f}))
                .upload(params("15"), png, "a.png", LANG).body()).isEqualTo(FAILED);
        assertThat(service("100x100", "", (f, t, z) -> annexed.add(new String[] {f}))
                .upload(params("15"), png, "a.png", LANG).body()).isEqualTo(FAILED);
        assertThat(files()).isEmpty();
        assertThat(annexed).isEmpty();
    }

    @Test
    void aFailedWriteLeavesNoTemporaryFileAndRegistersNothing() throws IOException {
        AdminPortraitService s = service();
        // user/5 被一个普通文件占住：建目录失败
        Files.createDirectories(upload().resolve("user"));
        Files.writeString(upload().resolve("user/5"), "x");
        assertThat(s.upload(params("15"), png(20, 20), "a.png", LANG).body()).isEqualTo(FAILED);
        assertThat(files()).containsExactly("user/5");
        assertThat(annexed).isEmpty();
    }

    @Test
    void annexRegistrationFailureDoesNotFailTheUpload() throws IOException {
        PortraitAnnex broken = mock(PortraitAnnex.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(broken).register(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        UploadReply r = service("100x100", upload().toString(), broken).upload(params("15"), png(20, 20), "a.png", LANG);
        assertThat(r.body()).contains("\"code\":1");
        assertThat(files()).containsExactly("user/5/15.jpg");
    }

    @Test
    void nothingIsReadForInvalidIds() throws IOException {
        AdminPortraitService s = service();
        s.upload(params("abc"), png(20, 20), "a.png", LANG);
        verify(reader, never()).countUser(anyLong());
        verifyNoInteractions(reader);
    }
}
