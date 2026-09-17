/*
 * BSD Zero Clause License
 *
 * Copyright (c) 2026-2026 zodac.net
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR
 * IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package net.zodac.diurnal.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What {@link SettingsParser} RETURNS, as opposed to what it reports - the half {@code ImportParserTest} structurally cannot see.
 *
 * <p>
 * An archive holding one bad settings row is refused as a whole, so {@code ImportParser.parse} answers with the problems and throws the draft away:
 * from there, a refused row that quietly contributed a value is indistinguishable from one that contributed nothing. This class reaches the parser
 * directly to pin that second half, because the value a rejection leaves behind is exactly what would be written if the archive were ever accepted
 * for some other reason.
 */
class SettingsParserTest {

    @Test
    void parse_refusedRow_contributesNothingToTheDraft() {
        final List<ImportProblem> problems = new ArrayList<>();
        // One row per KIND of rule, since each reports and returns separately: a picker value, a toggle, free text, a colour, and a resettable.
        final SettingsDraft draft = parse(problems,
            new CsvRow(2, List.of("theme", "neon")),
            new CsvRow(3, List.of("showStatsSummary", "yes")),
            new CsvRow(4, List.of("displayName", "A")),
            new CsvRow(5, List.of("noteColour", "green")),
            new CsvRow(6, List.of("timezone", "Mars/Olympus")));

        assertThat(problems)
            .as("every one of those rows breaks a rule, so the test would be vacuous if any of them were accepted")
            .hasSize(5);

        assertThat(List.of(
            String.valueOf(draft.theme()),
            String.valueOf(draft.showStatsSummary()),
            String.valueOf(draft.displayName()),
            String.valueOf(draft.noteColour()),
            String.valueOf(draft.timezone())))
            .as("a refused row must leave its setting UNDESCRIBED - anything else is a value nobody validated, sitting where the writer would "
                + "take it for one the file asked for")
            .containsOnly("null");
    }

    @Test
    void parse_acceptedRow_carriesTheValueThroughUnchanged() {
        // The counterpart, so the case above cannot pass by the parser simply returning nothing for everything.
        final List<ImportProblem> problems = new ArrayList<>();
        final SettingsDraft draft = parse(problems,
            new CsvRow(2, List.of("theme", "dark")),
            new CsvRow(3, List.of("showStatsSummary", "false")),
            new CsvRow(4, List.of("displayName", "Ada Lovelace")),
            new CsvRow(5, List.of("noteColour", "#123456")),
            new CsvRow(6, List.of("timezone", "Europe/London")));

        assertThat(problems)
            .as("every one of those rows is one the Settings page would have accepted")
            .isEmpty();

        assertThat(List.of(
            String.valueOf(draft.theme()),
            String.valueOf(draft.showStatsSummary()),
            String.valueOf(draft.displayName()),
            String.valueOf(draft.noteColour()),
            String.valueOf(draft.timezone())))
            .as("an accepted row reaches the draft as the value it named")
            .containsExactly("dark", "false", "Ada Lovelace", "#123456", "Europe/London");
    }

    private static SettingsDraft parse(final List<ImportProblem> problems, final CsvRow... rows) {
        return new SettingsParser(List.of(rows), (line, reason) -> problems.add(new ImportProblem(TransferFiles.SETTINGS_FILE, line, reason)))
            .parse();
    }
}
