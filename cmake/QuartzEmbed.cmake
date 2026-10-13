# Quartz sources in a C++ project: compiled with the `quartz` tool at build time and linked in as byte arrays.
#
#   quartz_add_sources(<target> NAME <module> SOURCES <.qz...> [INTERFACES <.qzi...>] [KITS <kit...>]
#                      [RULES <rule=level...>] [DENY_WARNINGS])
#       Compiles a module and embeds it: `extern "C" const uint8_t quartz_module_<module>[]` and `..._size`.
#   quartz_add_executable(<name> SOURCES <.qz...> [INTERFACES <.qzi...>] [KITS <kit...>] [ENTRY <fn>]
#                         [RULES <rule=level...>] [DENY_WARNINGS] [GRANT_ARGUMENT_PATHS])
#       A self-contained executable that runs the module's `main` (or ENTRY) with the native kits.
#
# KITS defaults to every kit; a module only binds what it calls. Quartz comes from, in order: existing
# `Quartz::quartz_c` and `Quartz::quartz-cli` targets, QUARTZ_SOURCE_DIR (a checkout added with add_subdirectory),
# or `find_package(Quartz)` (a tree installed with `cmake --install`, found through CMAKE_PREFIX_PATH).
include_guard(GLOBAL)

set(QUARTZ_EMBED_DIR "${CMAKE_CURRENT_LIST_DIR}/.." CACHE INTERNAL "")
set(QUARTZ_EMBED_KITS fs terminal process archives CACHE INTERNAL "Kits with an interface")
set(QUARTZ_SOURCE_DIR "" CACHE PATH "A Quartz checkout to build the compiler and libquartz from")

if(TARGET Quartz::quartz_c AND TARGET Quartz::quartz-cli)
    set(QUARTZ_EMBED_C Quartz::quartz_c)
    set(QUARTZ_EMBED_CLI Quartz::quartz-cli)
elseif(QUARTZ_SOURCE_DIR)
    add_subdirectory("${QUARTZ_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/quartz" EXCLUDE_FROM_ALL SYSTEM)
    set(QUARTZ_EMBED_C quartz_c)
    set(QUARTZ_EMBED_CLI quartz-cli)
else()
    find_package(Quartz CONFIG REQUIRED)
    set(QUARTZ_EMBED_C Quartz::quartz_c)
    set(QUARTZ_EMBED_CLI Quartz::quartz-cli)
endif()

if(NOT TARGET quartz_embed_native)
    add_subdirectory("${QUARTZ_EMBED_DIR}/native" "${CMAKE_BINARY_DIR}/quartz-embed-native" EXCLUDE_FROM_ALL)
endif()

function(quartz_add_sources target)
    cmake_parse_arguments(QZ "DENY_WARNINGS" "NAME" "SOURCES;INTERFACES;KITS;RULES" ${ARGN})
    if(NOT QZ_NAME)
        set(QZ_NAME "${target}")
    endif()
    if(NOT DEFINED QZ_KITS)
        set(QZ_KITS ${QUARTZ_EMBED_KITS})
    endif()
    string(MAKE_C_IDENTIFIER "${QZ_NAME}" symbol)
    # Each source is recorded under a name relative to its project, so traces and the module carry no machine paths.
    set(inputs)
    set(files)
    foreach(kit IN LISTS QZ_KITS)
        if(kit STREQUAL "http")
            continue()
        endif()
        set(qzi "${QUARTZ_EMBED_DIR}/kits/${kit}.qzi")
        if(NOT EXISTS "${qzi}")
            message(FATAL_ERROR "quartz_add_sources(${target}): no kit called ${kit}")
        endif()
        list(APPEND files "${qzi}")
        list(APPEND inputs --source-name "embed/kits/${kit}.qzi" "${qzi}")
    endforeach()
    foreach(file IN LISTS QZ_INTERFACES QZ_SOURCES)
        cmake_path(ABSOLUTE_PATH file BASE_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}" NORMALIZE OUTPUT_VARIABLE absolute)
        cmake_path(RELATIVE_PATH absolute BASE_DIRECTORY "${PROJECT_SOURCE_DIR}" OUTPUT_VARIABLE relative)
        list(APPEND files "${absolute}")
        list(APPEND inputs --source-name "${relative}" "${absolute}")
    endforeach()
    set(flags --host-only)
    if(QZ_DENY_WARNINGS)
        list(APPEND flags --deny-warnings)
    endif()
    foreach(rule IN LISTS QZ_RULES)
        list(APPEND flags --rule "${rule}")
    endforeach()
    set(dir "${CMAKE_CURRENT_BINARY_DIR}/quartz/${QZ_NAME}")
    set(module "${dir}/${QZ_NAME}.qzb")
    set(source "${dir}/${QZ_NAME}.qzb.cpp")
    add_custom_command(OUTPUT "${module}"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${dir}"
        COMMAND ${QUARTZ_EMBED_CLI} build ${inputs} ${flags} -o "${module}"
        DEPENDS ${files} ${QUARTZ_EMBED_CLI}
        COMMENT "Compiling Quartz module ${QZ_NAME}"
        VERBATIM)
    add_custom_command(OUTPUT "${source}"
        COMMAND "${CMAKE_COMMAND}" -DINPUT=${module} -DOUTPUT=${source} -DSYMBOL=quartz_module_${symbol}
            -P "${QUARTZ_EMBED_DIR}/cmake/QuartzEmbedBin2C.cmake"
        DEPENDS "${module}" "${QUARTZ_EMBED_DIR}/cmake/QuartzEmbedBin2C.cmake"
        VERBATIM)
    target_sources(${target} PRIVATE "${source}")
    target_link_libraries(${target} PRIVATE ${QUARTZ_EMBED_C})
endfunction()

function(quartz_add_executable name)
    cmake_parse_arguments(QZ "DENY_WARNINGS;GRANT_ARGUMENT_PATHS" "ENTRY" "SOURCES;INTERFACES;KITS;RULES" ${ARGN})
    if(NOT QZ_ENTRY)
        set(QZ_ENTRY main)
    endif()
    string(MAKE_C_IDENTIFIER "${name}" id)
    set(QZE_SYMBOL "quartz_module_${id}")
    set(QZE_ENTRY "${QZ_ENTRY}")
    set(QZE_ARGUMENT_PATHS false)
    if(QZ_GRANT_ARGUMENT_PATHS)
        set(QZE_ARGUMENT_PATHS true)
    endif()
    set(main "${CMAKE_CURRENT_BINARY_DIR}/quartz/${name}/main.cpp")
    configure_file("${QUARTZ_EMBED_DIR}/native/src/Main.cpp.in" "${main}" @ONLY)
    add_executable(${name} "${main}")
    target_link_libraries(${name} PRIVATE quartz_embed_native)
    set(extra)
    if(QZ_DENY_WARNINGS)
        list(APPEND extra DENY_WARNINGS)
    endif()
    if(DEFINED QZ_KITS)
        list(APPEND extra KITS ${QZ_KITS})
    endif()
    if(QZ_RULES)
        list(APPEND extra RULES ${QZ_RULES})
    endif()
    quartz_add_sources(${name} NAME ${name} SOURCES ${QZ_SOURCES} INTERFACES ${QZ_INTERFACES} ${extra})
endfunction()
