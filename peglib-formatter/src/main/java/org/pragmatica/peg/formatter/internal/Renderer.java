package org.pragmatica.peg.formatter.internal;

import org.pragmatica.peg.formatter.Doc;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Wadler / Lindig "best" pretty-printer renderer.
 *
 * <p>Given a {@link Doc} tree and a target line width, produces the formatted
 * string. At each {@link Doc.Group} the algorithm asks "can the group fit on
 * one line from the current column?" — if yes it renders the group in
 * <em>flat</em> mode (lines become spaces / softlines become empty), otherwise
 * in <em>break</em> mode (lines and softlines become newline + current indent).
 *
 * <p>Implementation: a work stack of {@code (indent, mode, doc)} frames drives
 * a single linear pass over the doc tree. {@code fits} probes a hypothetical
 * flat rendering from the current position to decide group mode.
 *
 * <p>Internal: not part of the public API.
 *
 * @since 0.3.3
 */
public final class Renderer {
    private enum Mode { FLAT, BREAK }

    private record Frame(int indent, Mode mode, Doc doc) {}

    private Renderer() {}

    /**
     * Render {@code doc} at the given {@code width}. The rendered string
     * never contains trailing whitespace on non-final lines beyond the
     * indentation explicitly emitted by the doc tree.
     */
    public static String render(Doc doc, int width) {
        if (doc == null) {
            throw new IllegalArgumentException("doc must not be null");
        }
        if (width <= 0) {
            throw new IllegalArgumentException("width must be > 0");
        }
        var sb = new StringBuilder();
        var stack = new ArrayDeque<Frame>();
        stack.push(new Frame(0, Mode.BREAK, doc));
        int column = 0;
        while (!stack.isEmpty()) {
            var frame = stack.pop();
            column = step(frame, stack, sb, width, column);
        }
        return sb.toString();
    }

    private static int step(Frame frame, Deque<Frame> stack, StringBuilder sb, int width, int column) {
        var doc = frame.doc();
        switch (doc) {
            case Doc.Empty ignored -> {
                return column;
            }
            case Doc.Text text -> {
                sb.append(text.value());
                return column + text.value().length();
            }
            case Doc.Line ignored -> {
                if (frame.mode() == Mode.FLAT) {
                    sb.append(' ');
                    return column + 1;
                }
                sb.append('\n');
                appendSpaces(sb, frame.indent());
                return frame.indent();
            }
            case Doc.Softline ignored -> {
                if (frame.mode() == Mode.FLAT) {
                    return column;
                }
                sb.append('\n');
                appendSpaces(sb, frame.indent());
                return frame.indent();
            }
            case Doc.HardLine ignored -> {
                sb.append('\n');
                appendSpaces(sb, frame.indent());
                return frame.indent();
            }
            case Doc.Concat concat -> {
                stack.push(new Frame(frame.indent(), frame.mode(), concat.right()));
                stack.push(new Frame(frame.indent(), frame.mode(), concat.left()));
                return column;
            }
            case Doc.Indent indent -> {
                stack.push(new Frame(frame.indent() + indent.amount(), frame.mode(), indent.inner()));
                return column;
            }
            case Doc.Group group -> {
                Mode mode;
                if (containsHardLine(group.inner())) {
                    mode = Mode.BREAK;
                } else {
                    mode = fits(group.inner(), frame.indent(), width - column, stack)
                           ? Mode.FLAT
                           : Mode.BREAK;
                }
                stack.push(new Frame(frame.indent(), mode, group.inner()));
                return column;
            }
        }
    }

    private static boolean containsHardLine(Doc doc) {
        return switch (doc) {
            case Doc.HardLine ignored -> true;
            case Doc.Concat c -> containsHardLine(c.left()) || containsHardLine(c.right());
            case Doc.Indent i -> containsHardLine(i.inner());
            // A nested Group breaks the chain — if a nested group contains a
            // hard line, that inner group will force-break independently of
            // the enclosing group.
            case Doc.Group ignored -> false;
            case Doc.Empty ignored -> false;
            case Doc.Text ignored -> false;
            case Doc.Line ignored -> false;
            case Doc.Softline ignored -> false;
        };
    }

    private static void appendSpaces(StringBuilder sb, int count) {
        for (int i = 0; i < count; i++) {
            sb.append(' ');
        }
    }

    /**
     * Can {@code inner} fit in flat mode within {@code remaining} columns?
     * Also considers the remainder of the current work stack in FLAT mode,
     * because a group's flat form stretches until the next line/softline
     * that <em>breaks</em> in the surrounding context. Per Wadler/Lindig, a
     * FLAT Line in the surrounding context counts as one column then stops
     * the probe; a BREAK Line / Softline counts as fitting immediately.
     */
    private static boolean fits(Doc inner, int indent, int remaining, Deque<Frame> surrounding) {
        if (remaining < 0) {
            return false;
        }
        var probe = new ArrayDeque<Frame>();
        probe.push(new Frame(indent, Mode.FLAT, inner));

        var surroundingIter = surrounding.iterator();
        int budget = remaining;
        while (true) {
            Frame f;
            if (!probe.isEmpty()) {
                f = probe.pop();
            } else if (surroundingIter.hasNext()) {
                f = surroundingIter.next();
            } else {
                return true;
            }
            switch (f.doc()) {
                case Doc.Empty ignored -> {}
                case Doc.Text text -> {
                    budget -= text.value().length();
                    if (budget < 0) {
                        return false;
                    }
                }
                case Doc.Line ignored -> {
                    if (f.mode() == Mode.FLAT) {
                        budget -= 1;
                        if (budget < 0) {
                            return false;
                        }
                    } else {
                        return true;
                    }
                }
                case Doc.Softline ignored -> {
                    if (f.mode() == Mode.BREAK) {
                        return true;
                    }
                    // FLAT softline contributes 0 columns
                }
                case Doc.HardLine ignored -> {
                    return true;
                }
                case Doc.Concat concat -> {
                    probe.push(new Frame(f.indent(), f.mode(), concat.right()));
                    probe.push(new Frame(f.indent(), f.mode(), concat.left()));
                }
                case Doc.Indent ind -> {
                    probe.push(new Frame(f.indent() + ind.amount(), f.mode(), ind.inner()));
                }
                case Doc.Group group -> {
                    // Optimistically assume the group fits flat too.
                    probe.push(new Frame(f.indent(), Mode.FLAT, group.inner()));
                }
            }
        }
    }
}
