// WITH_RUNTIME

@Suppress("OPTIONAL_DECLARATION_USAGE_IN_NON_COMMON_SOURCE")
@kotlin.jvm.JvmInline
value class S1(val s1: String)
@Suppress("OPTIONAL_DECLARATION_USAGE_IN_NON_COMMON_SOURCE")
@kotlin.jvm.JvmInline
value class S2(val s2: String)

object X1
object X2

fun <T> test(s1: S1, x: T) {
    if (s1.s1 != "OK" && x != X1) throw AssertionError()
}

fun <T> test(s2: S2, x: T) {
    if (s2.s2 != "OK" && x != X2) throw AssertionError()
}

fun box(): String {
    test(S1("OK"), X1)
    test(S2("OK"), X2)

    return "OK"
}