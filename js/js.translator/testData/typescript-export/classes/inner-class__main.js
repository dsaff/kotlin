"use strict";
var TestInner = JS_TESTS.foo.TestInner;
function assert(condition) {
    if (!condition) {
        throw "Assertion failed";
    }
}
function box() {
    var outer = new TestInner("Hello ");
    var inner = new outer.Inner("World");
    var secondInner = new inner.SecondInner("!");
    assert(inner.a == "World");
    assert(inner.concat == "Hello World");
    assert(secondInner.a == "!");
    assert(secondInner.concat == "Hello World!");
    return "OK";
}
