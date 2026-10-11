package com.maccms.backend.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.maccms.backend.content.application.view.CategoryDto;
import com.maccms.backend.content.application.view.CategoryExtendDto;
import com.maccms.backend.content.application.view.CategoryListResponse;
import com.maccms.backend.content.application.view.CategoryRefDto;
import com.maccms.backend.content.application.view.VideoDetailDto;
import com.maccms.backend.content.application.view.VideoSummaryDto;
import com.maccms.backend.contract.OpenApiContract;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * content.application.view 是已发布只读类型：每个 record 与 OpenAPI 中的一个 schema 一一对应，
 * 组件名就是 JSON 字段名。移包（MACC-8）后字段集合与序列化结果仍须与契约完全一致。
 */
class PublishedViewContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static OpenApiContract contract;

    @BeforeAll
    static void loadContract() {
        contract = OpenApiContract.load(OpenApiContract.CONTENT_READ);
    }

    static List<Arguments> views() {
        return List.of(
                Arguments.of(CategoryListResponse.class, "CategoryListResponse"),
                Arguments.of(CategoryDto.class, "Category"),
                Arguments.of(CategoryExtendDto.class, "CategoryExtend"),
                Arguments.of(CategoryRefDto.class, "CategoryRef"),
                Arguments.of(VideoSummaryDto.class, "VideoSummary"),
                Arguments.of(VideoDetailDto.class, "VideoDetail"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("views")
    void recordComponentsAreExactlyTheSchemaProperties(Class<? extends Record> type, String schema) {
        assertThat(type.getPackageName()).isEqualTo("com.maccms.backend.content.application.view");
        Map<String, Object> props = contract.map(contract.schema(schema).get("properties"));
        assertThat(Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName))
                .containsExactlyInAnyOrderElementsOf(props.keySet());
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("views")
    void serializedSampleMatchesSchema(Class<? extends Record> type, String schema) throws Exception {
        contract.assertMatches(JSON.valueToTree(sample(type)), contract.schema(schema));
    }

    /** 每个组件填入契约允许的空值；列表放一个样例元素，确保嵌套类型也被校验。 */
    private static Object sample(Class<?> type) throws Exception {
        RecordComponent[] rc = type.getRecordComponents();
        Object[] args = new Object[rc.length];
        for (int i = 0; i < rc.length; i++) {
            Class<?> t = rc[i].getType();
            if (t == String.class) {
                args[i] = "";
            } else if (t == int.class) {
                args[i] = 0;
            } else if (t == long.class) {
                args[i] = 0L;
            } else if (t == boolean.class) {
                args[i] = false;
            } else if (t == BigDecimal.class) {
                args[i] = new BigDecimal("0.0");
            } else if (t == List.class) {
                Class<?> element = (Class<?>) ((java.lang.reflect.ParameterizedType) rc[i].getGenericType())
                        .getActualTypeArguments()[0];
                args[i] = element == type ? List.of() : List.of(sample(element));
            } else if (t.isRecord()) {
                args[i] = sample(t);
            } else {
                throw new AssertionError("未覆盖的组件类型 " + t + "（" + type.getSimpleName() + "." + rc[i].getName() + "）");
            }
        }
        Constructor<?> c = type.getDeclaredConstructor(Arrays.stream(rc).map(RecordComponent::getType).toArray(Class[]::new));
        return c.newInstance(args);
    }
}
