cmake_minimum_required(VERSION 3.28)
project(quartz_hello CXX)

set(CMAKE_CXX_STANDARD 23)
list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_LIST_DIR}/../../cmake")
include(QuartzEmbed)

# A command line tool written in Quartz: one binary, no JVM. It may read the files its user names, wherever they are.
quartz_add_executable(qwc SOURCES wc.qz GRANT_ARGUMENT_PATHS)

# A C++ host that embeds a module and calls into it.
add_executable(host host.cpp)
quartz_add_sources(host NAME scores SOURCES scores.qz INTERFACES host.qzi)
