// WITH_RUNTIME
import kotlin.test.assertEquals

@Suppress("OPTIONAL_DECLARATION_USAGE_IN_NON_COMMON_SOURCE")
@kotlin.jvm.JvmInline
value class S(val x: String)

interface IFoo {
    val S.extVal: String
}

interface GFoo<T> {
    val T.extVal: String
}

object FooImpl : IFoo {
    override val S.extVal: String
        get() = x
}

object GFooImpl : GFoo<S> {
    override val S.extVal: String
        get() = x
}

class TestFoo : IFoo by FooImpl

class TestGFoo : GFoo<S> by GFooImpl

fun box(): String {
    with(TestFoo()) {
        assertEquals("OK", S("OK").extVal)
    }

    with(TestGFoo()) {
        assertEquals("OK", S("OK").extVal)
    }

    return "OK"
}
