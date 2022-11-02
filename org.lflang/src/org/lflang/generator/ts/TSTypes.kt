package org.lflang.generator.ts

import org.lflang.ASTUtils
import org.lflang.TimeValue
import org.lflang.generator.TargetTypes
import org.lflang.lf.StateVar

object TSTypes : TargetTypes {

    override fun getTargetType(s: StateVar): String {
        val type = super.getTargetType(s)
        return if (!ASTUtils.isInitialized(s)) {
            "$type | undefined"
        } else {
            type
        }
    }

    override fun supportsGenerics(): Boolean {
        return true
    }

    override fun getTargetTimeType(): String {
        return "TimeValue"
    }

    override fun getTargetTagType(): String {
        return "TimeValue"
    }

    override fun getTargetUndefinedType(): String {
        return "Present"
    }

    override fun getTargetTimeExpr(value: TimeValue): String {
        return if (value.unit != null) {
            "TimeValue.${value.unit.canonicalName}(${value.time})"
        } else {
            // The value must be zero.
            "TimeValue.zero()"
        }
    }

    override fun getTargetFixedSizeListType(baseType: String, size: Int): String {
        return "Array($size)<$baseType>"
    }

    override fun getTargetVariableSizeListType(baseType: String): String {
        return "Array<$baseType>"
    }
}
