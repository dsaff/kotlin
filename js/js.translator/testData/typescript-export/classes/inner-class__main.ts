import TestInner = JS_TESTS.foo.TestInner;

function assert(condition: boolean) {
    if (!condition) {
        throw "Assertion failed";
    }
}

function box(): string {
    const outer = new TestInner("Hello ")
    const inner = new outer.Inner("World")
    const secondInner = new inner.SecondInner("!")

    assert(inner.a == "World")
    assert(inner.concat == "Hello World")
    assert(secondInner.a == "!")
    assert(secondInner.concat == "Hello World!")

    return "OK";
}