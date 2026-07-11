# Quartz sources in a C++ project: compiled with the `quartz` tool at build time and linked in as byte arrays.
#
#   quartz_add_sources(<target> NAME <module> SOURCES <.qz...> [INTERFACES <.qzi...>] [KITS <kit...>]
#                      [DENY_WARNINGS])
#       Compiles a module and embeds it: `extern "C" const uint8_t quartz_module_<module>[]` and `..._size`.
#   quartz_add_executable(<name> SOURCES <.qz...> [INTERFACES <.qzi...>] [KITS <kit...>] [ENTRY <fn>]
#                         [DENY_WARNINGS])
#       A self-contained executable that runs the module's `main` (or ENTRY) with the native kits.
#
# Quartz comes from, in order: existing `quartz::c` and `quartz::cli` targets, QUARTZ_SOURCE_DIR (a checkout added
# with add_subdirectory), or an installed `quartz` tool, `quartz.h` and libquartz found on the system.
include_guard(GLOBAL)

set(QUARTZ_EMBED_DIR "${CMAKE_CURRENT_LIST_DIR}/.." CACHE INTERNAL "")
set(QUARTZ_SOURCE_DIR "" CACHE PATH "A Quartz checkout to build the compiler and libquartz from")

if(NOT TARGET quartz::c)
    if(QUARTZ_SOURCE_DIR)
        add_subdirectory("${QUARTZ_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/quartz" EXCLUDE_FROM_ALL SYSTEM)
        add_library(quartz::c ALIAS quartz_c)
        add_executable(quartz::cli ALIAS quartz-cli)
    else()
        find_program(QUARTZ_EXECUTABLE quartz REQUIRED)
        find_path(QUARTZ_INCLUDE_DIR quartz/quartz.h REQUIRED)
        find_library(QUARTZ_LIBRARY quartz REQUIRED)
        add_library(quartz::c UNKNOWN IMPORTED)
        set_target_properties(quartz::c PROPERTIES IMPORTED_LOCATION "${QUARTZ_LIBRARY}"
            INTERFACE_INCLUDE_DIRECTORIES "${QUARTZ_INCLUDE_DIR}")
        add_executable(quartz::cli IMPORTED)
        set_target_properties(quartz::cli PROPERTIES IMPORTED_LOCATION "${QUARTZ_EXECUTABLE}")
    endif()
endif()

if(NOT TARGET quartz_embed_native)
    add_subdirectory("${QUARTZ_EMBED_DIR}/native" "${CMAKE_BINARY_DIR}/quartz-embed-native" EXCLUDE_FROM_ALL)
endif()

function(quartz_add_sources target)
    cmake_parse_arguments(QZ "DENY_WARNINGS" "NAME" "SOURCES;INTERFACES;KITS" ${ARGN})
    if(NOT QZ_NAME)
        set(QZ_NAME "${target}")
    endif()
    string(MAKE_C_IDENTIFIER "${QZ_NAME}" symbol)
    # Paths relative to the source directory, so trap messages and the module carry no machine paths.
    set(inputs)
    set(files)
    foreach(kit IN LISTS QZ_KITS)
        set(qzi "${QUARTZ_EMBED_DIR}/kits/${kit}.qzi")
        if(NOT EXISTS "${qzi}")
            message(FATAL_ERROR "quartz_add_sources(${target}): no kit called ${kit}")
        endif()
        list(APPEND files "${qzi}")
    endforeach()
    foreach(file IN LISTS QZ_INTERFACES QZ_SOURCES)
        cmake_path(ABSOLUTE_PATH file BASE_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}" NORMALIZE OUTPUT_VARIABLE absolute)
        list(APPEND files "${absolute}")
    endforeach()
    foreach(file IN LISTS files)
        cmake_path(RELATIVE_PATH file BASE_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}" OUTPUT_VARIABLE relative)
        list(APPEND inputs "${relative}")
    endforeach()
    set(flags --host-only)
    if(QZ_DENY_WARNINGS)
        list(APPEND flags --deny-warnings)
    endif()
    set(dir "${CMAKE_CURRENT_BINARY_DIR}/quartz/${QZ_NAME}")
    set(module "${dir}/${QZ_NAME}.qzb")
    set(source "${dir}/${QZ_NAME}.qzb.cpp")
    add_custom_command(OUTPUT "${module}"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${dir}"
        COMMAND quartz::cli build ${inputs} ${flags} -o "${module}"
        DEPENDS ${files} quartz::cli
        WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
        COMMENT "Compiling Quartz module ${QZ_NAME}"
        VERBATIM)
    add_custom_command(OUTPUT "${source}"
        COMMAND "${CMAKE_COMMAND}" -DINPUT=${module} -DOUTPUT=${source} -DSYMBOL=quartz_module_${symbol}
            -P "${QUARTZ_EMBED_DIR}/cmake/QuartzEmbedBin2C.cmake"
        DEPENDS "${module}" "${QUARTZ_EMBED_DIR}/cmake/QuartzEmbedBin2C.cmake"
        VERBATIM)
    target_sources(${target} PRIVATE "${source}")
    target_link_libraries(${target} PRIVATE quartz::c)
endfunction()

function(quartz_add_executable name)
    cmake_parse_arguments(QZ "DENY_WARNINGS" "ENTRY" "SOURCES;INTERFACES;KITS" ${ARGN})
    if(NOT QZ_ENTRY)
        set(QZ_ENTRY main)
    endif()
    string(MAKE_C_IDENTIFIER "${name}" id)
    set(QZE_SYMBOL "quartz_module_${id}")
    set(QZE_ENTRY "${QZ_ENTRY}")
    set(main "${CMAKE_CURRENT_BINARY_DIR}/quartz/${name}/main.cpp")
    configure_file("${QUARTZ_EMBED_DIR}/native/src/Main.cpp.in" "${main}" @ONLY)
    add_executable(${name} "${main}")
    target_link_libraries(${name} PRIVATE quartz_embed_native)
    set(deny)
    if(QZ_DENY_WARNINGS)
        set(deny DENY_WARNINGS)
    endif()
    quartz_add_sources(${name} NAME ${name} SOURCES ${QZ_SOURCES} INTERFACES ${QZ_INTERFACES} KITS ${QZ_KITS} ${deny})
endfunction()
