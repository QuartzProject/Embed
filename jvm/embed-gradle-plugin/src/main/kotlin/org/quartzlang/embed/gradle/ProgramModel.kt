package org.quartzlang.embed.gradle

/** A compiled program as the build sees it: its module bytes, what the host may call and what it imports. */
internal class ProgramInfo(
    val binary: ByteArray,
    val exports: List<FunctionInfo>,
    val imports: List<ImportInfo>,
    val types: Map<Int, QuartzType>,
) {
    fun type(id: Int): QuartzType = types.getValue(id)
}

internal data class FunctionInfo(
    /** With its package: `org.example.greet`. */
    val name: String,
    val pkg: String,
    /** What a caller passes; `state` parameters are not among them. */
    val params: List<ParamInfo>,
    val result: Int,
    /** 0 nothing written, 1 `private`, 2 `internal`, 3 `pub`. */
    val visibility: Int,
    val suspend: Boolean,
    val attributes: List<AttributeInfo>,
) {
    val shortName: String get() = name.substringAfterLast('.')
    val isPublic: Boolean get() = visibility == 3
}

internal data class ParamInfo(val name: String, val type: Int)

internal data class AttributeInfo(val name: String, val filter: String?, val args: List<AttributeArg>)

internal data class AttributeArg(val key: String, val kind: Kind, val intValue: Long, val floatValue: Double,
                                 val text: String) {
    enum class Kind { INT, FLOAT, BOOL, STR, NAME }

    /** The value as the module metadata stores it. */
    fun encoded(): String = when (kind) {
        Kind.INT -> intValue.toString()
        Kind.FLOAT -> floatValue.toString()
        Kind.BOOL -> (intValue != 0L).toString()
        Kind.STR, Kind.NAME -> text
    }
}

internal data class ImportInfo(val name: String, val pkg: String, val required: Boolean, val builtIn: Boolean)

/** A type of the program, with its parts as ids into [ProgramInfo.types] so recursive types stay finite. */
internal data class QuartzType(
    val kind: Kind,
    /** How the source writes it: `Player`, `[int]`, `Box<int>`. */
    val name: String,
    val pkg: String,
    val element: Int,
    val key: Int,
    val fields: List<Field>,
    val cases: List<Case>,
    val pointerNullable: Boolean,
) {
    /** The order of qz_kind in quartz.h. */
    enum class Kind {
        VOID, BOOL, I8, I16, I32, I64, U8, U16, U32, U64, F32, F64, CHAR, STR, BYTES, LIST, MAP, STRUCT, ENUM,
        NULLABLE, TUPLE, HANDLE, FUNCTION, OTHER
    }

    data class Field(val name: String, val type: Int)

    data class Case(val name: String, val payload: List<Int>)

    val qualifiedName: String get() = if (pkg.isEmpty()) name else "$pkg.$name"

    /** `Result<T, E>`: variants `Ok` and `Err` with one value each. */
    val isResult: Boolean
        get() = kind == Kind.ENUM && cases.size == 2 && cases[0].name == "Ok" && cases[1].name == "Err" &&
            cases.all { it.payload.size == 1 }
}

/** One diagnostic of qz_compile_with. */
internal data class CompilerDiagnostic(
    val severity: String,
    /** The warning rule (`unused-variable`), also when raised to an error; empty for other errors. */
    val rule: String,
    val file: String,
    val line: Int,
    val column: Int,
    val message: String,
)
