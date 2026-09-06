// Generates src/main/resources/messages/msg_en-XA.properties - the PSEUDOLOCALE bundle - from the English source
// export that scripts/generate-source-messages.sh produces.
//
// A pseudolocale is English mechanically disguised, and it answers three questions no real translation can. Every
// letter is replaced with an accented look-alike, so any string still reading as plain English on screen never went
// through the message bundle at all - it is hardcoded. Every value is padded, because translations run longer than
// English (German and Finnish routinely by a third), so a layout that only ever saw English is asked whether it
// copes. And every value is bracketed, so a value whose closing bracket is missing on screen has been truncated by
// its container.
//
// WHAT MUST SURVIVE THE DISGUISE, and why this is not a regex: a message value is itself a small Qute template, so
// it may hold an expression ({count}, {#if count == 1}, {count.arabicPluralCategory}) or markup (<strong>) that the
// renderer has to read back exactly. Accenting a letter inside one would turn a working bundle into a render-time
// failure, and padding inside one would corrupt a parameter name. So the walk copies every {...} region and every
// <...> tag through untouched and disguises only the literal text between them - which is also precisely the text a
// translator would have translated.
//
// Not compiled or run as part of `mvn package` - see scripts/generate-pseudo-messages.sh, which builds and invokes
// it.

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GeneratePseudoMessages {

    private static final Path SOURCE = Path.of("translations", "msg_en-GB.properties");
    private static final Path TARGET = Path.of("src", "main", "resources", "messages", "msg_en-XA.properties");
    private static final char OPEN = '⟦';
    private static final char CLOSE = '⟧';
    private static final char PAD = '~';

    // Roughly a third longer, the expansion a European translation of English routinely reaches.
    private static final double EXPANSION = 0.35;

    private static final Map<Character, Character> ACCENTS = Map.ofEntries(
        Map.entry('a', 'á'), Map.entry('b', 'b'), Map.entry('c', 'ç'), Map.entry('d', 'ð'), Map.entry('e', 'é'),
        Map.entry('f', 'f'), Map.entry('g', 'ĝ'), Map.entry('h', 'ĥ'), Map.entry('i', 'î'), Map.entry('j', 'ĵ'),
        Map.entry('k', 'ķ'), Map.entry('l', 'ļ'), Map.entry('m', 'm'), Map.entry('n', 'ñ'), Map.entry('o', 'ó'),
        Map.entry('p', 'p'), Map.entry('q', 'q'), Map.entry('r', 'ŕ'), Map.entry('s', 'ŝ'), Map.entry('t', 'ţ'),
        Map.entry('u', 'û'), Map.entry('v', 'v'), Map.entry('w', 'ŵ'), Map.entry('x', 'x'), Map.entry('y', 'ý'),
        Map.entry('z', 'ž'), Map.entry('A', 'Á'), Map.entry('B', 'B'), Map.entry('C', 'Ç'), Map.entry('D', 'Ð'),
        Map.entry('E', 'É'), Map.entry('F', 'F'), Map.entry('G', 'Ĝ'), Map.entry('H', 'Ĥ'), Map.entry('I', 'Î'),
        Map.entry('J', 'Ĵ'), Map.entry('K', 'Ķ'), Map.entry('L', 'Ļ'), Map.entry('M', 'M'), Map.entry('N', 'Ñ'),
        Map.entry('O', 'Ó'), Map.entry('P', 'P'), Map.entry('Q', 'Q'), Map.entry('R', 'Ŕ'), Map.entry('S', 'Ŝ'),
        Map.entry('T', 'Ţ'), Map.entry('U', 'Û'), Map.entry('V', 'V'), Map.entry('W', 'Ŵ'), Map.entry('X', 'X'),
        Map.entry('Y', 'Ý'), Map.entry('Z', 'Ž'));

    private GeneratePseudoMessages() {

    }

    public static void main(final String[] args) throws IOException {
        final List<String> lines = Files.readAllLines(SOURCE, StandardCharsets.UTF_8);
        final List<String> out = new ArrayList<>();
        out.add("# Pseudolocale (en-XA) for web.AppMessages - GENERATED, do not hand-edit.");
        out.add("# Regenerate with scripts/generate-pseudo-messages.sh after any @Message wording change.");
        out.add("#");
        out.add("# NOT a translation and NOT offered in the Settings picker (Language#developerOnly). English, accented so a");
        out.add("# hardcoded string stands out, padded so a layout is asked whether it copes, and bracketed so a truncation is");
        out.add("# visible. See .claude/I18N.md's \"Pseudolocale\" section.");
        out.add("");

        int keys = 0;
        for (final String line : lines) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            final int split = line.indexOf('=');
            if (split < 0) {
                continue;
            }
            out.add(line.substring(0, split + 1) + disguise(line.substring(split + 1)));
            keys++;
        }

        Files.write(TARGET, out, StandardCharsets.UTF_8);
        final PrintStream stdout = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        stdout.println("OK wrote " + TARGET + " (" + keys + " keys)");
    }

    private static String disguise(final String value) {
        final StringBuilder result = new StringBuilder(value.length() * 2);
        result.append(OPEN);
        int letters = 0;
        int at = 0;
        while (at < value.length()) {
            final char current = value.charAt(at);
            if (current == '{') {
                at = copyRegion(value, at, result, '{', '}');
            } else if (current == '<') {
                at = copyRegion(value, at, result, '<', '>');
            } else {
                final Character accented = ACCENTS.get(current);
                result.append(accented == null ? current : accented.charValue());
                letters += accented == null ? 0 : 1;
                at++;
            }
        }

        // Padded in proportion to the DISGUISED text only: padding by the raw length would inflate an entry that is mostly parameters, which is
        // the opposite of what a layout needs asking.
        result.append(String.valueOf(PAD).repeat(Math.max(1, (int) Math.round(letters * EXPANSION))));
        return result.append(CLOSE).toString();
    }

    // Copies a {...} expression or a <...> tag through verbatim, counting nesting so a Qute section holding another expression survives whole.
    private static int copyRegion(final String value, final int from, final StringBuilder result, final char open, final char close) {
        int depth = 0;
        int at = from;
        while (at < value.length()) {
            final char current = value.charAt(at);
            result.append(current);
            at++;
            if (current == open) {
                depth++;
            } else if (current == close) {
                depth--;
                if (depth == 0) {
                    return at;
                }
            }
        }
        return at;
    }
}
