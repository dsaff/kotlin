// WITH_RUNTIME

fun <K, T> foo(x: (K) -> T): Pair<K, T> = TODO()

class Foo<K>

fun main() {
    val x = foo<Int, Foo<<!UNRESOLVED_REFERENCE!>_<!>>> { <!ARGUMENT_TYPE_MISMATCH!>it.toFloat()<!> }

}