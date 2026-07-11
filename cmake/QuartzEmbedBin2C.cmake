# cmake -DINPUT=<file> -DOUTPUT=<file.cpp> -DSYMBOL=<name> -P QuartzEmbedBin2C.cmake
file(READ "${INPUT}" hex HEX)
string(LENGTH "${hex}" length)
math(EXPR size "${length} / 2")
string(REGEX REPLACE "([0-9a-f][0-9a-f])" "0x\\1," bytes "${hex}")
string(REGEX REPLACE "((0x[0-9a-f][0-9a-f],){32})" "\\1\n    " bytes "${bytes}")
file(WRITE "${OUTPUT}.tmp" "#include <cstddef>\n#include <cstdint>\n\n"
    "extern \"C\" const std::uint8_t ${SYMBOL}[] = {\n    ${bytes}\n};\n"
    "extern \"C\" const std::size_t ${SYMBOL}_size = ${size};\n")
file(COPY_FILE "${OUTPUT}.tmp" "${OUTPUT}" ONLY_IF_DIFFERENT)
file(REMOVE "${OUTPUT}.tmp")
