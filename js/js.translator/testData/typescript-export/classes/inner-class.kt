// CHECK_TYPESCRIPT_DECLARATIONS
// RUN_PLAIN_BOX_FUNCTION
// SKIP_MINIFICATION
// SKIP_NODE_JS
// IGNORE_BACKEND: JS

package foo

@JsExport
class TestInner(val a: String) {
    inner class Inner(val a: String) {
        val concat: String = this@TestInner.a + this.a
        inner class SecondInner(val a: String) {
            val concat: String = this@TestInner.a + this@Inner.a + this.a
        }
    }
}