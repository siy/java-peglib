package org.pragmatica.peg.v6.cst;

import java.util.stream.IntStream;

/**
 * Sealed-pattern view over a node in a {@link CstArray}. Three variants ({@code Branch},
 * {@code Leaf}, {@code Error}) carry no state of their own beyond the {@code (index, array)}
 * pair; all accessors delegate to the array, so per-node memory is two references.
 *
 * <p>Variant selection is performed by {@link CstArray#viewAt(int)}: error nodes get
 * {@code Error}, nodes with at least one child get {@code Branch}, and childless nodes get
 * {@code Leaf}.
 */
public sealed interface CstNode permits CstNode.Branch, CstNode.Leaf, CstNode.Error {
    int index();

    CstArray array();

    int kind();

    String kindName();

    int spanStart();

    int spanEnd();

    CharSequence text();

    record Branch(int index, CstArray array) implements CstNode {
        @Override
        public int kind() {
            return array.kindAt(index);
        }

        @Override
        public String kindName() {
            return array.kindNameAt(index);
        }

        @Override
        public int spanStart() {
            return array.spanStart(index);
        }

        @Override
        public int spanEnd() {
            return array.spanEnd(index);
        }

        @Override
        public CharSequence text() {
            return array.textAt(index);
        }

        public IntStream children() {
            return array.children(index);
        }
    }

    record Leaf(int index, CstArray array) implements CstNode {
        @Override
        public int kind() {
            return array.kindAt(index);
        }

        @Override
        public String kindName() {
            return array.kindNameAt(index);
        }

        @Override
        public int spanStart() {
            return array.spanStart(index);
        }

        @Override
        public int spanEnd() {
            return array.spanEnd(index);
        }

        @Override
        public CharSequence text() {
            return array.textAt(index);
        }

        public IntStream children() {
            return IntStream.empty();
        }
    }

    record Error(int index, CstArray array) implements CstNode {
        @Override
        public int kind() {
            return array.kindAt(index);
        }

        @Override
        public String kindName() {
            return array.kindNameAt(index);
        }

        @Override
        public int spanStart() {
            return array.spanStart(index);
        }

        @Override
        public int spanEnd() {
            return array.spanEnd(index);
        }

        @Override
        public CharSequence text() {
            return array.textAt(index);
        }
    }
}
