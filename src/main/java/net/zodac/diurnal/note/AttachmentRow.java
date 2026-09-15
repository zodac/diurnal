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
 * One row of the notes page's attachments table: a file under both of its names, the day it is attached to, and where to fetch it from.
 *
 * <p>
 * Both names are a LIST of runs rather than a string, exactly as {@link NoteRow}'s snippet is, so the template decides where the {@code <mark>} goes
 * and Qute still escapes every character of a user-supplied name on the way out. Never render one with {@code .raw}.
 *
 * <p>
 * The ISO {@code date} is carried alongside the human label because it is also the row's day LINK - the dashboard opens on that day
 * ({@code /?date=…}), where the file sits embedded in the note that mentions it.
 *
 * @param date     the day as an ISO-8601 string, for the dashboard deep link
 * @param dayLabel the same day spelled out for reading, via {@link net.zodac.diurnal.time.DayLabels}
 * @param name     the attachment's display name, which the note's own text embeds, in runs with any search-term occurrences flagged
 * @param fileName the name the file was uploaded under, which a rename leaves alone, in the same marked-up form - equal to the display name until
 *                 someone renames the file
 * @param sizeKb   the file's size in whole kilobytes as plain digits, rounded the way the note box's hover card rounds it. A STRING, because the
 *                 {@code "{size} KB"} message it fills takes one - the figure reaches the page ungrouped and {@code js-num} (app.js) groups it for
 *                 the viewer's own language, which the server cannot do
 * @param url      where to fetch the bytes from
 */
public record AttachmentRow(String date, String dayLabel, List<NoteSnippetPart> name, List<NoteSnippetPart> fileName, String sizeKb,
    String url) {

}
