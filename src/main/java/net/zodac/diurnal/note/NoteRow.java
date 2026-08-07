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

package net.zodac.diurnal.note;

import java.util.List;

/**
 * One row of the notes page: a day, spelled out, and the snippet of what was written on it.
 *
 * <p>
 * The ISO {@code date} is carried alongside the human label because it is also the row's LINK - a result opens the dashboard on that day
 * ({@code /?date=…}), so the note can be read in full beside the actions logged against it.
 *
 * @param date     the day as an ISO-8601 string, for the dashboard deep link
 * @param dayLabel the same day spelled out for reading, via {@link net.zodac.diurnal.time.DayLabels}
 * @param snippet  the preview line's runs of text, with any search-term occurrences flagged
 */
public record NoteRow(String date, String dayLabel, List<NoteSnippetPart> snippet) {

}
