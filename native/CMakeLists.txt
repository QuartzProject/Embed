# The native host: kits in C++ over quartz.h, and the runner every quartz_add_executable links.
add_library(quartz_embed_native STATIC src/Runner.cpp src/Paths.cpp src/Spawn.cpp src/Terminal.cpp src/FileSystem.cpp
    src/Process.cpp src/Http.cpp src/Archives.cpp)
add_library(quartz::embed_native ALIAS quartz_embed_native)
target_compile_features(quartz_embed_native PUBLIC cxx_std_23)
target_include_directories(quartz_embed_native PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(quartz_embed_native PUBLIC ${QUARTZ_EMBED_C})
target_compile_options(quartz_embed_native PRIVATE -ffp-contract=off -fno-rtti -Wall -Wextra)
