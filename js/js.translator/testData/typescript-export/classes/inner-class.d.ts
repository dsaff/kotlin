declare namespace JS_TESTS {
    type Nullable<T> = T | null | undefined
    namespace foo {
        class TestInner {
            constructor(a: string);
            readonly a: string;
            readonly Inner: {
                new(a: string): TestInner.Inner;
            } & typeof TestInner.Inner;
        }
        namespace TestInner {
            class Inner {
                protected constructor($outer: foo.TestInner, a: string);
                readonly a: string;
                readonly concat: string;
                readonly SecondInner: {
                    new(a: string): TestInner.Inner.SecondInner;
                } & typeof TestInner.Inner.SecondInner;
            }
            namespace Inner {
                class SecondInner {
                    protected constructor($outer: foo.TestInner.Inner, a: string);
                    readonly a: string;
                    readonly concat: string;
                }
            }
        }
    }
}