package com.maccms.spike.thymeleaf;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

/**
 * spike 专用路由，只渲染合成数据，不是正式首页/分类/详情路由。
 * /spike/view/*：Spring MVC 标准视图解析（starter 默认行为）。
 * /spike/buffered/*：Controller 内缓冲渲染。
 */
@Controller
@RequestMapping("/spike")
public class SpikePageController {

    private static final Logger log = LoggerFactory.getLogger(SpikePageController.class);

    private final BufferedHtmlRenderer renderer;

    public SpikePageController(BufferedHtmlRenderer renderer) {
        this.renderer = renderer;
    }

    @GetMapping("/view/{fixture}")
    public ModelAndView view(@PathVariable String fixture) {
        return new ModelAndView("pages/shell", "page", SyntheticPages.byName(fixture)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @GetMapping("/buffered/{fixture}")
    public ResponseEntity<String> buffered(@PathVariable String fixture) {
        return SyntheticPages.byName(fixture)
                .map(page -> renderer.ok("pages/shell", Map.of("page", page)))
                .orElseGet(() -> renderer.error(HttpStatus.NOT_FOUND));
    }

    @ExceptionHandler(BufferedHtmlRenderer.TemplateRenderException.class)
    public ResponseEntity<String> renderFailed(BufferedHtmlRenderer.TemplateRenderException e) {
        log.error("page render failed: {}", SafeLog.typeChain(e));
        return renderer.error(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
