package com.maccms.backend.collect.application;

import java.util.Optional;

/** 本站分类名（旧 {@code model('Type')->getCache('type_list')[$id]['type_name']}，不论分类是否启用）。 */
@FunctionalInterface
public interface TypeNames {

    Optional<String> name(long typeId);
}
