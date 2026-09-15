// ── The dashboard's note box ─────────────────────────────────────────────────────────────────────
// A day's free-text note: the textarea, its two caches, the Save/Undo/Clear controls, the character
// counter, the drag-resize, and the per-tab retention of unsaved writing across a navigation.
//
// Split out of dashboard.js, which had grown to hold the calendar engine, three separate caches, the day
// panel, the stats summary AND all of this. The seam is a real one rather than a line-count exercise: the
// calendar needs to know only WHICH days have a note (to paint its green day numbers) and needs hooks to
// load, clear and evict them; everything else here is private. That interface is the whole of what this
// module exposes.
//
// Loaded BEFORE dashboard.js, so `Diurnal.noteBox` exists when the calendar wires itself up; the calendar
// then hands its adapter back through bindCalendar(). With no #note-panel in the DOM every method is a
// no-op, so the dashboard is unaffected if the box is ever not rendered.
window.Diurnal = window.Diurnal || {};

(function () {
    'use strict'

    const notePanel  = document.getElementById('note-panel')
    const noteInput  = document.getElementById('note-input')

    // Everything below assumes the panel exists; without it the module publishes no-ops and stops.
    if (!notePanel || !noteInput) {
        window.Diurnal.noteBox = {
            bindCalendar: function () {},
            load: function () {},
            disable: function () {},
            hasNote: function () { return false },
            mergeMonths: function () {},
            dropMonth: function () {}
        }
        return
    }

    // Shared with every other page's fetches — see Diurnal.requireSession in app.js for the two shapes
    // an expired session arrives in, and why touching a response body without this check strands the page.
    // Resolved at CALL time, not captured at module init: layout.html loads app.js (which defines it) AFTER
    // the page body, so this script runs first and an up-front reference would capture `undefined`.
    function requireSession(resp) {
        return window.Diurnal.requireSession(resp)
    }
    function pad2(n) { return String(n).padStart(2, '0') }

    // Set by dashboard.js once its calendar adapter exists (see bindCalendar). Until then the two calendar
    // hooks are harmless no-ops, which is exactly the state during this module's own initial seeding.
    let cal = { ensureNotes: function () { return Promise.resolve() }, noteChanged: function () {} }

    // The day's free-text note. Unlike the day panel and the summary card, the note box is NOT re-rendered
    // per day: the whole panel ships once with the page and a date change only rewrites the textarea's
    // value from the caches below. That is what makes a drag-resized box keep its dimensions across a date
    // change with no re-application — nothing inside #note-panel is ever replaced.
    //
    // Two caches, deliberately separate:
    //   noteSaved  — what the server holds. Filled a month at a time from /internal/notes, seeded from the
    //                inline content the page shipped for its initial day.
    //   noteDrafts — edits the user has NOT saved. Keyed by date so flicking to another day and back
    //                restores the half-written note instead of silently discarding it.
    // notePanel/noteInput are resolved at the top of the module (their absence short-circuits it entirely).
    const noteStatus = document.getElementById('note-status')
    const noteError  = document.getElementById('note-error')
    const noteSaveBtn  = document.getElementById('note-save')
    const noteUndoBtn  = document.getElementById('note-undo')
    const noteClearBtn = document.getElementById('note-clear')
    const noteCount    = document.getElementById('note-count')
    const NOTE_MAX     = Number(notePanel.dataset.noteMax)
    // The "Character count" preference (Settings > Notes). Read once: a preference change is a PATCH from
    // another page, so this page's copy cannot go stale under it.
    const SHOW_COUNT   = notePanel.dataset.noteCounter !== 'false'
    const noteSaved   = {} // dateStr -> saved content ('' when the day has no note)
    const noteDrafts  = {} // dateStr -> unsaved edit
    let noteDate = null    // the day the box is currently showing, or null when nothing is selected

    // ── Attachments ──────────────────────────────────────────────────────────
    // A day's files, and the mirror layer that makes them look like files rather than like text. See the
    // "attachment mirror" block in app.css for why the mirror sits BEHIND the textarea rather than over it,
    // and note.NoteTokens for the [[name]] token the note's own text carries.
    const noteEditor      = notePanel.querySelector('.note-editor')
    const noteHighlights  = document.getElementById('note-highlights')
    const noteDropzone    = document.getElementById('note-dropzone')
    const noteProgress    = document.getElementById('note-progress')
    const noteProgressBar = document.getElementById('note-progress-bar')
    const noteAttachBtn   = document.getElementById('note-attach')
    const noteAttachInput = document.getElementById('note-attach-input')
    const attachCard      = document.getElementById('note-attachment-card')
    const attachPreview   = document.getElementById('note-attachment-preview')
    const attachPreviewLink = document.getElementById('note-attachment-preview-link')
    const attachPlayer    = document.getElementById('note-attachment-player')
    const attachClose     = document.getElementById('note-attachment-close')
    const progressRow     = document.getElementById('note-progress-row')
    const attachCancel    = document.getElementById('note-attach-cancel')
    const attachName      = document.getElementById('note-attachment-name')
    const attachSize      = document.getElementById('note-attachment-size')
    const attachDownload  = document.getElementById('note-attachment-download')
    const attachRenameBtn = document.getElementById('note-attachment-rename')
    const attachActions   = document.getElementById('note-attachment-actions')
    const attachDeleteBtn = document.getElementById('note-attachment-delete')
    const attachConfirm   = document.getElementById('note-attachment-confirm')
    const attachDeleteOk  = document.getElementById('note-attachment-delete-confirm')
    const attachDeleteNo  = document.getElementById('note-attachment-delete-cancel')
    const attachRenameRow = document.getElementById('note-attachment-rename-row')
    const attachRenameInp = document.getElementById('note-attachment-rename-input')

    // The three strings this module composes, plus the size phrasing. Carried on the panel rather than in
    // layout.html's window.Diurnal.i18n block, which is an inline script whose CSP hash is pinned by
    // SecurityHeadersFilterIT - and these are wanted on exactly one page. See the panel's own comment.
    // The deployment's attachment ceiling, so an over-sized file is refused before a byte is sent (see tooLarge).
    const MAX_ATTACHMENT_BYTES = Number(notePanel.dataset.maxAttachmentBytes || 0)

    const ATTACH_TEXT = {
        attaching: notePanel.dataset.i18nAttaching || '',
        couldNotAttach: notePanel.dataset.i18nCouldNotAttach || '',
        tooLarge: notePanel.dataset.i18nAttachmentTooLarge || '',
        size: notePanel.dataset.i18nAttachmentSize || ''
    }
    // Keep-in-sync pair with AppMessages#attachmentSizeKb's own '%SIZE%' argument (UI_PATTERNS.md section 6).
    const SIZE_TOKEN = '%SIZE%'
    // How full the progress rail starts. Small enough to read as "just begun", large enough to be a visible bar.
    const START_PROGRESS = 0.08
    // How long the pointer must rest on a pill before the transient card appears. A pointer crossing the box passes over
    // every pill on its way, and a card that opened instantly under each one would flicker rather than inform. A CLICK
    // bypasses this entirely, because a click is a request.
    const HOVER_DELAY_MS = 1000

    const noteAttachments = {}   // dateStr -> [{id, name, byteSize, image, url, token}]
    const attachRequests  = {}   // dateStr -> in-flight load, so a day is never fetched twice at once
    let cardAttachment = null    // the attachment the hover card is currently describing
    let cardDate = null          // and the day it belongs to, so a late response cannot act on another day
    let cardHideTimer = null
    // Whether the open card was PINNED by a click. A pinned card ignores the pointer entirely - it does not follow it to
    // another pill and does not close when it leaves - so the only ways out are its own close button, Escape, or a
    // click outside the panel. A hover-opened card is the other state, and is what closes itself.
    let cardPinned = false
    // The upload currently in flight, so the cancel button beside the progress bar can abort it. Null between uploads.
    let uploadInFlight = null
    let cardShowTimer = null
    let dragDepth = 0            // dragenter/dragleave fire per CHILD, so the zone is counted rather than toggled

    // A bare + rather than a {1,100} quantifier: this file is served as-is but is read by the same Qute-aware
    // tooling as the templates, where a `{n}` reads as an expression and corrupts the pattern (the same reason
    // ISO_DATE below spells out `\d\d\d\d`). A negated class cannot backtrack catastrophically, and the
    // server bounds the name anyway.
    const ATTACHMENT_TOKEN = /\[\[([^[\]\r\n]+)]]/g

    function escapeHtml(value) {
        return String(value).replace(/[&<>"']/g, function (c) {
            return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', '\'': '&#39;'}[c]
        })
    }

    // ── The draft that outlives the page ─────────────────────────────────────
    // A draft only ever lived in the map above, so clicking a navbar link discarded half-written prose:
    // every page here is a full load, which re-executes this script against empty caches. The draft being
    // WRITTEN is therefore mirrored into sessionStorage, which has exactly the lifetime wanted — scoped to
    // THIS tab, surviving in-app navigation and a reload, wiped when the tab (or the browser) closes. It is
    // also dropped on the login page (app.js, beside the retained day selection), so a logout or a second
    // user on the same tab never inherits the first one's journal.
    //
    // An earlier version instead raised the browser's own beforeunload confirmation. Retaining the work is
    // strictly better: the prompt could not be worded, appeared on every in-app click, and asked the user to
    // make a decision the app can simply avoid needing. The "Unsaved changes" status line is the whole of the
    // signal now, and it comes back with the draft.
    //
    // ONE draft is carried, not the whole map: the day last edited, which is the writing the user is actually
    // in the middle of. Drafts on other days still survive moving around the calendar (that is the map's job,
    // and it is untouched) — they simply do not survive a page load. That keeps a single note's worth of
    // private content in browser storage rather than a month of it, which matters because sessionStorage is
    // written to the browser profile on disk, unlike the map.
    const NOTE_DRAFT_KEY = 'diurnal.noteDraft'
    // `\d\d\d\d` rather than `\d{4}`: see the same guard in dashboard.js — a `{4}` quantifier reads as a Qute
    // expression, which corrupts the pattern.
    const ISO_DATE = /^\d\d\d\d-\d\d-\d\d$/

    // Called wherever the shown day's draft changes. Written on every keystroke, deliberately synchronous
    // rather than debounced: a debounce's flush window is exactly the moment a navigation lands. A full quota
    // (or storage being unavailable at all) is swallowed — the draft then simply does not outlive the page,
    // which is the behaviour this whole mechanism replaced and so needs no telling.
    function persistDraft() {
        const draft = noteDate === null ? undefined : noteDrafts[noteDate]
        // Nothing to carry: no day is showing, it has no draft, or its draft has caught up with the stored
        // note (which is the case after a save or an undo) — so the previous day's entry goes too.
        if (draft === undefined || draft === noteSaved[noteDate]) {
            try { sessionStorage.removeItem(NOTE_DRAFT_KEY) } catch (e) {}
            return
        }
        try { sessionStorage.setItem(NOTE_DRAFT_KEY, JSON.stringify({ date: noteDate, content: draft })) } catch (e) {}
    }

    // Restore whatever the previous page in this tab left behind, into the ordinary per-date draft map — so a
    // restored draft is thereafter indistinguishable from one typed on this page. Anything malformed is
    // ignored rather than thrown: the stored value is not a contract with the server, and a bad one (a hand-
    // edited key, a format from a past release) must never break the box.
    function restoreDraft() {
        let stored = null
        try { stored = sessionStorage.getItem(NOTE_DRAFT_KEY) } catch (e) {}
        if (!stored) {return}
        let parsed = null
        try { parsed = JSON.parse(stored) } catch (e) { return }
        if (parsed === null || typeof parsed !== 'object') {return}
        if (ISO_DATE.test(parsed.date) && typeof parsed.content === 'string') {
            noteDrafts[parsed.date] = parsed.content
        }
    }

    // Merge one range response into the note cache. The feed holds ONLY the days that HAVE a note, so every
    // other day of a merged month is recorded as '' — recording the absence is what makes a later day switch
    // a pure cache read rather than a request that would have found nothing.
    //
    // Called by the calendar's month cache (fetchNoteSpan), which owns the fetching, the LRU and eviction;
    // this side owns what a note IS. `force` is the authoritative-refresh path, which must overwrite.
    function mergeNoteMonths(byDate, monthKeys, force) {
        monthKeys.forEach(function (ym) {
            const year  = parseInt(ym.substring(0, 4), 10)
            const month = parseInt(ym.substring(5, 7), 10) - 1
            const last  = new Date(Date.UTC(year, month + 1, 0)).getUTCDate()
            for (let d = 1; d <= last; d++) {
                const key = `${ym  }-${  pad2(d)}`
                // An already-known value wins unless this is a forced refresh: the fetch may land after the
                // user has started typing, and overwriting then would eat their keystrokes. (A DRAFT is held
                // separately in noteDrafts and is never touched here at all.)
                if (force || noteSaved[key] === undefined) { noteSaved[key] = byDate[key] || '' }
            }
        })
    }

    // Evict one month's notes, called from the calendar cache's dropMonth so both sides evict together.
    // Drafts are deliberately NOT dropped: an unsaved note the user is part-way through writing must survive
    // browsing to another year and back, which is exactly when an eviction happens.
    function dropNoteMonth(ym) {
        const prefix = `${ym  }-`
        Object.keys(noteSaved).forEach(function (d) { if (d.indexOf(prefix) === 0) { delete noteSaved[d] } })
        // The month's attachment lists go with its notes, for the same reason and on the same schedule: they are
        // a per-day cache of the same journal, and leaving them behind would make eviction bound only half of it.
        Object.keys(noteAttachments).forEach(function (d) { if (d.indexOf(prefix) === 0) { delete noteAttachments[d] } })
    }

    // Whether a day has a note, for the calendar's green day number. An unloaded month reads as `false`, so a
    // day number simply gains its colour when that month's notes land.
    function hasNote(dateStr) {
        return Boolean(noteSaved[dateStr])
    }

    // Paint the box for a day: its draft if it has one, otherwise what the server holds.
    //
    // `keepStatus` leaves the status line exactly as it is, for the one caller that repaints LATE (see
    // loadNote below). Everywhere else the line is re-derived here, which is what clears "Unsaved changes"
    // on an Undo and carries it across a date change.
    function showNote(dateStr, keepStatus) {
        if (!noteInput || noteDate !== dateStr) {return}
        const draft = noteDrafts[dateStr]
        noteInput.value = draft === undefined ? (noteSaved[dateStr] || '') : draft
        refreshNoteState()
        renderHighlights()
        if (!keepStatus) {
            setNoteStatus(noteIsDirty() ? window.Diurnal.i18n.unsavedChanges : '', 'brand')
        }
    }

    function loadNote(dateStr) {
        if (!notePanel || !noteInput) {return}
        noteDate = dateStr
        setNoteEnabled(true)
        showNote(dateStr)
        // The day's files, warmed the same way its note is. Until they land the mirror draws no pills, so a
        // [[name]] token simply reads as text for the moment before its file is known - never as a broken one.
        ensureAttachments(dateStr).then(function () {}).catch(function () {})
        if (noteSaved[dateStr] === undefined) {
            // The month's notes ride the calendar's cache, so this is a no-op read for any month already
            // resident (the visible one and its neighbours) and one range request otherwise.
            //
            // It can nevertheless land LONG after the day was selected: this read waits on whatever request
            // is already in flight for the month, which at page load is the calendar's own +/-2-month warm-up.
            // By then the user may have typed in the box and even saved it, so this repaint must not touch
            // the status line - it would wipe the "Saved" acknowledgement (or the "Unsaved changes" line)
            // that their own action put there moments earlier. The CONTENT is safe to write unconditionally:
            // a draft wins over the merged value, and the merge never overwrites a note the save already
            // stored, so this only ever fills in a box the user has not touched.
            cal.ensureNotes(dateStr).then(function () { showNote(dateStr, true) }).catch(function () {})
        }
    }

    function setNoteEnabled(enabled) {
        if (!noteInput) {return}
        noteInput.disabled = !enabled
        if (noteAttachBtn) { noteAttachBtn.disabled = !enabled }
        if (!enabled) {
            noteInput.value = ''
            setNoteStatus('')
            if (noteError) { noteError.innerHTML = '' }
            closeCard()
            hideProgress()
        }
        refreshNoteState()
        renderHighlights()
    }

    // The status line carries two different kinds of message, so it is driven EXPLICITLY at each call site
    // rather than derived: a persistent state ("Unsaved changes", which must stay put until it is no longer
    // true) and a transient acknowledgement ("Saved", which fades). Deriving both from the dirty flag is
    // what made an earlier version clear "Saved" the instant it was set.
    // `tone` colours the message the same way the rest of the app does: 'success' is the settings cards'
    // green acknowledgement, 'brand' is the on-brand accent the active navbar link uses.
    function setNoteStatus(text, tone) {
        if (!noteStatus) {return}
        clearTimeout(noteStatus._hideTimer)
        noteStatus.textContent = text
        noteStatus.classList.toggle('text-success', tone === 'success')
        noteStatus.classList.toggle('text-brand', tone === 'brand')
    }

    function flashNoteStatus(text, tone) {
        if (!noteStatus) {return}
        setNoteStatus(text, tone)
        noteStatus._hideTimer = setTimeout(function () { setNoteStatus('') }, 2000)
    }

    function noteIsDirty() {
        return noteInput !== null && noteDate !== null && !noteInput.disabled
            && noteInput.value !== (noteSaved[noteDate] || '')
    }

    // CODE POINTS, not `value.length`: the server measures the bound that way, so counting UTF-16 units here
    // would tell an emoji-heavy note it is twice as long as the server thinks it is. Same rule the other
    // client-side evaluators follow (see TEXT_INPUT.md).
    function noteLength() {
        return noteInput === null ? 0 : Array.from(noteInput.value).length
    }

    function noteIsOverLimit() {
        return NOTE_MAX > 0 && noteLength() > NOTE_MAX
    }

    // The counter is shown only while a day is selected and the box is typeable, and turns red once the note
    // is over its bound — where it doubles as the reason Save has gone inert.
    //
    // That second job is why turning the preference off does NOT simply suppress it: over the bound it is the
    // only thing on screen explaining a dead Save button, so it comes back regardless and goes away again as
    // soon as the note is back under. Hiding a running count is what the user asked for; hiding the reason
    // they cannot save is not.
    function refreshNoteCount() {
        if (!noteCount) {return}
        const live = noteDate !== null && noteInput !== null && !noteInput.disabled && (SHOW_COUNT || noteIsOverLimit())
        noteCount.hidden = !live
        if (!live) {return}
        const length = noteLength()
        noteCount.textContent = `${length.toLocaleString(window.Diurnal.lang)  } / ${  NOTE_MAX.toLocaleString(window.Diurnal.lang)}`
        noteCount.classList.toggle('note-count-over', noteIsOverLimit())
    }

    // Save/Undo are live only while the box is dirty, so an untouched box offers nothing to click and a
    // pointless request cannot be fired — the first line of defence for "never save when nothing changed"
    // (the save handler itself re-checks, so a stale enabled button cannot slip one through either).
    // Clear is shown only when the STORED note is non-empty: with nothing stored there is nothing to clear,
    // and offering it would imply a write that would be a no-op.
    // Buttons ONLY — the status line is set by the caller.
    function refreshNoteState() {
        const dirty = noteIsDirty()
        // Over the bound the note cannot be stored, so Save goes inert — but Undo and Clear stay live, because
        // they are exactly what the user needs to get back under it.
        if (noteSaveBtn) { noteSaveBtn.disabled = !dirty || noteIsOverLimit() }
        if (noteUndoBtn) { noteUndoBtn.disabled = !dirty }
        if (noteClearBtn) {
            const clearable = noteDate !== null && !noteInput.disabled && Boolean(noteSaved[noteDate])
            noteClearBtn.hidden = !clearable
            // Nothing left to clear once the box is already empty, even though a note is still stored.
            noteClearBtn.disabled = clearable && noteInput.value === ''
        }
        refreshNoteCount()
    }

    if (noteInput) {
        noteInput.addEventListener('input', function () {
            if (noteDate === null) {return}
            noteDrafts[noteDate] = noteInput.value
            persistDraft()
            if (noteError) { noteError.innerHTML = '' }
            refreshNoteState()
            // The pills follow the text as it is typed, including a token being edited away character by
            // character - and the hover card goes with them, since the rectangles it was placed against are gone.
            closeCard()
            renderHighlights()
            setNoteStatus(noteIsDirty() ? window.Diurnal.i18n.unsavedChanges : '', 'brand')
        })
    }

    if (noteSaveBtn) {
        noteSaveBtn.addEventListener('click', function () {
            // Re-checked here and not only on the button: a save that would store exactly what is already
            // stored is a request with no effect, and the backend would answer with the same value it holds.
            if (noteDate === null || !noteIsDirty() || noteIsOverLimit()) {return}
            const dateStr = noteDate
            const content = noteInput.value
            noteSaveBtn.disabled = true
            setNoteStatus(window.Diurnal.i18n.saving)
            // JSON, not a form body: a note runs to thousands of characters and Quarkus caps a form
            // attribute at 2KB (413, before the request reaches the resource). fetch rather than htmx
            // keeps an expected 422 off the console. See NotesInternalResource.
            fetch(window.Diurnal.url(`/internal/notes/${  dateStr}`), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ content: content })
            }).then(function (resp) {
                requireSession(resp)
                if (!resp.ok) {
                    return resp.json().then(function (body) { throw new Error(body && body.message) })
                }
                return resp.json()
            }).then(function (stored) {
                noteSaved[dateStr] = stored.content
                delete noteDrafts[dateStr]
                persistDraft()
                if (noteDate === dateStr) {
                    noteInput.value = stored.content // the STORED (normalised) form, not what was typed
                    // A save is also what collects the files the note no longer names (NoteService), so the day's
                    // attachment cache is no longer authoritative - it is dropped and re-read.
                    delete noteAttachments[dateStr]
                    ensureAttachments(dateStr).then(function () {}).catch(function () {})
                }
                refreshNoteState()
                renderHighlights()
                if (noteDate === dateStr) { flashNoteStatus(window.Diurnal.i18n.saved, 'success') }
                // The cache was just updated in place, so the grid only needs repainting — writing the first
                // note on a day turns its number green, clearing the last one turns it back.
                cal.noteChanged()
            }).catch(function (err) {
                if (noteDate !== dateStr) {return}
                setNoteStatus('')
                if (noteError) {
                    noteError.innerHTML = window.Diurnal.bannerHtml(err && err.message ? err.message : window.Diurnal.i18n.couldNotSaveNote)
                }
                refreshNoteState()
            })
        })
    }

    // Undo throws the unsaved edit away and repaints from what the server holds.
    if (noteUndoBtn) {
        noteUndoBtn.addEventListener('click', function () {
            if (noteDate === null) {return}
            delete noteDrafts[noteDate]
            persistDraft()
            if (noteError) { noteError.innerHTML = '' }
            showNote(noteDate)
        })
    }

    // Clear empties the box WITHOUT writing: the emptied note becomes an ordinary unsaved edit, which the
    // user then Saves (which deletes the note) or Undoes. So a single click is never destructive, and it
    // costs no request at all until they commit to it.
    if (noteClearBtn) {
        noteClearBtn.addEventListener('click', function () {
            if (noteDate === null || noteInput.value === '') {return}
            noteInput.value = ''
            noteDrafts[noteDate] = ''
            persistDraft()
            if (noteError) { noteError.innerHTML = '' }
            closeCard()
            refreshNoteState()
            renderHighlights()
            setNoteStatus(noteIsDirty() ? window.Diurnal.i18n.unsavedChanges : '', 'brand')
            noteInput.focus()
        })
    }


    // ── The mirror ───────────────────────────────────────────────────────────
    // Rebuild the layer behind the textarea: the note's own text, drawn transparent, with a pill around every
    // [[name]] token that names a file this day actually holds. A token naming nothing is left as plain text,
    // which is what makes a hand-typed "[[see the appendix]]" ordinary prose rather than a broken attachment.
    //
    // Runs on every keystroke, which sounds expensive and is not: it is one string pass and one innerHTML
    // write over at most NOTE_MAX characters, on the same event that already re-counts the note's code points.
    function renderHighlights() {
        if (!noteHighlights) {return}
        const value = noteInput.value
        const known = attachmentNames()
        let html = ''
        let cut = 0
        let match
        ATTACHMENT_TOKEN.lastIndex = 0
        while ((match = ATTACHMENT_TOKEN.exec(value)) !== null) {
            if (!known.has(match[1])) { continue }
            html += escapeHtml(value.slice(cut, match.index))
            html += `<span class="note-chip" data-attachment-name="${  escapeHtml(match[1])  }">${  escapeHtml(match[0])  }</span>`
            cut = match.index + match[0].length
        }
        // The trailing newline is a browser quirk, not padding: a `white-space: pre-wrap` box collapses a final
        // line break, so without it the mirror is one line shorter than the textarea once the note ends on one.
        noteHighlights.innerHTML = `${html + escapeHtml(value.slice(cut))  }\n`
        noteHighlights.scrollTop = noteInput.scrollTop
    }

    function attachmentsForDay(dateStr) {
        return noteAttachments[dateStr] || []
    }

    function attachmentNames() {
        const names = new Set()
        attachmentsForDay(noteDate).forEach(function (attachment) { names.add(attachment.name) })
        return names
    }

    function attachmentByName(name) {
        return attachmentsForDay(noteDate).filter(function (a) { return a.name === name })[0] || null
    }

    // Fill the day's attachment cache, once per day. A day with no files is still RECORDED as loaded (an empty
    // array), so flicking back and forth across a month costs one request per day at most - the same rule the
    // note cache itself follows with its recorded absences.
    function ensureAttachments(dateStr) {
        if (noteAttachments[dateStr] !== undefined) { return Promise.resolve() }
        if (attachRequests[dateStr] !== undefined) { return attachRequests[dateStr] }
        const request = fetch(window.Diurnal.url(`/internal/note-attachments/${  dateStr}`), { headers: { 'Accept': 'application/json' } })
            .then(requireSession)
            .then(function (resp) { return resp.ok ? resp.json() : { attachments: [] } })
            .then(function (body) {
                // An already-known value wins, the same rule the note cache's own merge follows: an upload that finished while this was in flight
                // has already put the new file in, and overwriting with the list the server held BEFORE it would take the pill straight back off.
                if (noteAttachments[dateStr] === undefined) { noteAttachments[dateStr] = body.attachments || [] }
                if (noteDate === dateStr) { renderHighlights() }
            })
            .catch(function () { noteAttachments[dateStr] = [] })
            .then(function () { delete attachRequests[dateStr] })
        attachRequests[dateStr] = request
        return request
    }

    // ── Uploading ────────────────────────────────────────────────────────────
    // XMLHttpRequest rather than fetch, for the one thing fetch still cannot do: report how much of a request
    // BODY has been sent. `fetch` can only stream a request body over HTTP/2 with a ReadableStream, which is
    // neither universally supported nor available over plain HTTP - and a progress indicator that only ever
    // showed a spinner would be a worse answer than the determinate bar this gives.
    //
    // An expired session arrives in the same two shapes it does for fetch (see Diurnal.requireSession), so the
    // same check is made here against the status and the URL the request actually ended on.
    // Checked HERE rather than left to the server's 413, because the server's answer arrives only after the file has
    // been sent: for anything large that means a progress bar filling for a minute, and for a file large enough the
    // connection is dropped mid-body and the browser reports a bare ERR_CONNECTION_RESET with no message at all. The
    // server still refuses it too (RequestBodyLimitFilter, on Content-Length) - this is the courtesy, not the control.
    function tooLarge(file) {
        return MAX_ATTACHMENT_BYTES > 0 && file.size > MAX_ATTACHMENT_BYTES
    }

    function uploadFiles(files) {
        if (noteDate === null || files.length === 0) {return}
        const dateStr = noteDate
        // Copied out of the live FileList SYNCHRONOUSLY, before anything is awaited: the picker's own handler clears
        // `input.value` the moment this returns (so choosing the same file twice still fires a change), and a
        // DataTransfer's list does not outlive its drop event either - so a list read later is an empty one.
        const queued = Array.prototype.slice.call(files)
        // An over-sized file is refused before anything is sent, and refused ALONE: the others in the same drop are
        // still uploaded, since one file being too big says nothing about the rest.
        const oversized = queued.filter(tooLarge)
        const sendable = queued.filter(function (file) { return !tooLarge(file) })
        if (oversized.length > 0) {
            failUpload(dateStr, ATTACH_TEXT.tooLarge)
            if (sendable.length === 0) {return}
        }
        // The day's existing files are read FIRST, because the answer is appended to them: starting while that read
        // is still in flight would leave the cache holding the new file and nothing else. For the day on screen
        // this resolved as it was selected, so it costs nothing.
        ensureAttachments(dateStr).then(function () {
            if (noteDate !== dateStr) {return}
            // One at a time, in the order they were dropped: each upload's answer carries the name it was actually
            // given, which depends on what is already on the day - so two in flight together could be handed the
            // same name. Sequencing them also keeps the progress bar describing one thing.
            uploadFile(sendable[0], sendable.slice(1))
        }).catch(function () {})
    }

    function uploadFile(file, remaining) {
        const dateStr = noteDate
        const xhr = new XMLHttpRequest()
        // Held so the cancel button can abort it. One upload is ever in flight (they are sequenced), so one handle is
        // all there is to keep.
        uploadInFlight = xhr
        xhr.open('POST', window.Diurnal.url(`/internal/note-attachments/${  dateStr  }?filename=${  encodeURIComponent(file.name)}`))
        xhr.setRequestHeader('Content-Type', file.type || 'application/octet-stream')
        xhr.setRequestHeader('Accept', 'application/json')
        xhr.responseType = 'text'
        xhr.upload.onprogress = function (ev) { if (ev.lengthComputable) { showProgress(ev.loaded / ev.total) } }
        // Filled the moment the last byte is SENT, which is honest - what is left after that is the server's own
        // work, not the upload's. Without it a small file never animates at all: the whole body goes in one chunk,
        // onprogress may not fire even once, and the rail sits empty for the round trip as though nothing happened.
        xhr.upload.onload = function () { showProgress(1) }
        xhr.onload = function () { finishUpload(xhr, dateStr, remaining) }
        xhr.onerror = function () { failUpload(dateStr, ATTACH_TEXT.couldNotAttach) }
        // A cancelled upload is not a failure to report: the user asked for it, so the bar and the status simply go,
        // the queue behind it is dropped, and nothing is said. Whatever reached the server is discarded with the
        // request - an attachment row is written only once the whole body has arrived.
        xhr.onabort = function () {
            uploadInFlight = null
            hideProgress()
            if (noteDate === dateStr) { setNoteStatus(noteIsDirty() ? window.Diurnal.i18n.unsavedChanges : '', 'brand') }
        }
        if (noteError) { noteError.innerHTML = '' }
        setNoteStatus(ATTACH_TEXT.attaching)
        // A visible sliver rather than nothing: a rail at exactly zero reads as a control that has failed to start.
        showProgress(START_PROGRESS)
        xhr.send(file)
    }

    function finishUpload(xhr, dateStr, remaining) {
        uploadInFlight = null
        hideProgress()
        if (sessionExpired(xhr)) {
            window.location.assign(window.Diurnal.url('/login'))
            return
        }
        if (xhr.status === 413) {
            failUpload(dateStr, ATTACH_TEXT.tooLarge)
            return
        }
        if (xhr.status < 200 || xhr.status > 299) {
            failUpload(dateStr, refusalMessage(xhr))
            return
        }

        let body = null
        try { body = JSON.parse(xhr.responseText) } catch (e) { body = null }
        if (body === null || !body.attachment) {
            failUpload(dateStr, ATTACH_TEXT.couldNotAttach)
            return
        }

        noteAttachments[dateStr] = attachmentsForDay(dateStr).concat([body.attachment])
        // The token goes in at the caret only while the day it belongs to is still the one on screen; the file
        // is stored either way, and the next save of that day is what settles whether the note still names it.
        if (noteDate === dateStr) {
            insertToken(body.attachment.token)
        }
        setNoteStatus(noteIsDirty() ? window.Diurnal.i18n.unsavedChanges : '', 'brand')
        if (remaining.length > 0 && noteDate === dateStr) { uploadFile(remaining[0], remaining.slice(1)) }
    }

    function failUpload(dateStr, message) {
        uploadInFlight = null
        hideProgress()
        if (noteDate !== dateStr) {return}
        setNoteStatus('')
        if (noteError) { noteError.innerHTML = window.Diurnal.bannerHtml(message || ATTACH_TEXT.couldNotAttach) }
    }

    // The server's own worded refusal, which is a whole translated sentence on this surface. Anything else -
    // a proxy's HTML error page, an empty body - falls back to the generic line rather than showing the user
    // whatever markup came back.
    function refusalMessage(xhr) {
        try {
            const body = JSON.parse(xhr.responseText)
            return (body && body.message) || ATTACH_TEXT.couldNotAttach
        } catch (e) {
            return ATTACH_TEXT.couldNotAttach
        }
    }

    function sessionExpired(xhr) {
        if (xhr.status === 401) { return true }
        const finalUrl = xhr.responseURL || ''
        return finalUrl !== '' && new URL(finalUrl, window.location.href).pathname === window.Diurnal.url('/login')
    }

    function showProgress(fraction) {
        if (!noteProgress || !noteProgressBar || !progressRow) {return}
        const percent = Math.max(0, Math.min(100, Math.round(fraction * 100)))
        progressRow.hidden = false
        noteProgress.setAttribute('aria-valuenow', String(percent))
        noteProgressBar.style.width = `${percent  }%`
    }

    function hideProgress() {
        if (!noteProgress || !noteProgressBar || !progressRow) {return}
        progressRow.hidden = true
        noteProgressBar.style.width = '0'
    }

    // Write the token where the caret is, as an ordinary unsaved edit: an upload stores the FILE, and the note
    // is written by the user pressing Save like any other change they make. Spaces are added either side only
    // where there is not already whitespace, so the token does not end up welded onto the previous word.
    function insertToken(token) {
        const start = noteInput.selectionStart
        const end = noteInput.selectionEnd
        const value = noteInput.value
        const before = value.slice(0, start)
        const after = value.slice(end)
        const lead = (before === '' || /\s$/.test(before)) ? '' : ' '
        const trail = (after === '' || /^\s/.test(after)) ? '' : ' '
        const inserted = lead + token + trail
        noteInput.value = before + inserted + after
        const caret = before.length + inserted.length
        noteInput.setSelectionRange(caret, caret)
        noteDrafts[noteDate] = noteInput.value
        persistDraft()
        refreshNoteState()
        renderHighlights()
        noteInput.focus()
    }

    // ── Drag and drop ────────────────────────────────────────────────────────
    // The whole panel is the target, not just the textarea: a user aiming a file at "the note" is aiming at the
    // card, and a drop that lands on the button row two pixels low should not silently do nothing. The overlay
    // is drawn over the writing area, which is where the eye is.
    function filesInDrag(ev) {
        const types = ev.dataTransfer ? ev.dataTransfer.types : null
        return Boolean(types) && Array.prototype.indexOf.call(types, 'Files') !== -1
    }

    function showDropzone(shown) {
        if (noteDropzone) { noteDropzone.hidden = !shown }
    }

    function clearDropzone() {
        dragDepth = 0
        showDropzone(false)
    }

    if (noteDropzone) {
        notePanel.addEventListener('dragenter', function (ev) {
            if (!filesInDrag(ev) || noteInput.disabled) {return}
            ev.preventDefault()
            dragDepth++
            showDropzone(true)
        })
        notePanel.addEventListener('dragover', function (ev) {
            if (!filesInDrag(ev) || noteInput.disabled) {return}
            // Both of these are required for a drop to fire at all, and the effect is what makes the cursor say
            // "copy" rather than "move" while the file is over the box.
            ev.preventDefault()
            ev.dataTransfer.dropEffect = 'copy'
        })
        // Counted rather than toggled: dragenter/dragleave fire for every CHILD element the pointer crosses, so a
        // plain toggle flickers the overlay off the moment the file passes over the textarea.
        //
        // Deliberately NOT gated on filesInDrag, unlike the two handlers above: a leave that is not recognised as
        // carrying files would leave the count standing and the overlay showing over a box with no drag anywhere
        // near it, which reads as the note box having broken.
        notePanel.addEventListener('dragleave', function () {
            dragDepth = Math.max(0, dragDepth - 1)
            if (dragDepth === 0) { showDropzone(false) }
        })
        // The backstop for a drag that ends somewhere this panel never hears about - released outside the window,
        // cancelled with Escape, or dropped on another element entirely. Each of those can leave the count above
        // standing, and the overlay is the one piece of this feature that is wrong to leave on screen.
        document.addEventListener('dragend', clearDropzone)
        document.addEventListener('drop', clearDropzone)
        notePanel.addEventListener('drop', function (ev) {
            if (!filesInDrag(ev)) {return}
            ev.preventDefault()
            clearDropzone()
            if (noteInput.disabled || noteDate === null) {return}
            uploadFiles(ev.dataTransfer.files)
        })
    }

    if (noteAttachBtn && noteAttachInput) {
        noteAttachBtn.addEventListener('click', function () { noteAttachInput.click() })
        noteAttachInput.addEventListener('change', function () {
            uploadFiles(noteAttachInput.files)
            // Cleared so that choosing the SAME file twice in a row still fires a change event the second time.
            noteAttachInput.value = ''
        })
    }

    // ── The hover card ───────────────────────────────────────────────────────
    // The mirror is behind the textarea and takes no pointer events, so which pill the pointer is over is
    // answered by measuring: every pill's own client rectangles are compared against the pointer. getClientRects
    // (plural) rather than getBoundingClientRect, because a token that wraps across two lines is two rectangles,
    // and its bounding box would cover the whole width of the line between them.
    function chipAt(x, y) {
        if (!noteHighlights) { return null }
        const chips = noteHighlights.querySelectorAll('.note-chip')
        for (let i = 0; i < chips.length; i++) {
            const rects = chips[i].getClientRects()
            for (let r = 0; r < rects.length; r++) {
                const rect = rects[r]
                if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) { return chips[i] }
            }
        }
        return null
    }

    function openCard(chip) {
        const attachment = attachmentByName(chip.dataset.attachmentName)
        if (attachment === null) { return }
        clearTimeout(cardHideTimer)
        if (cardAttachment !== null && cardAttachment.id === attachment.id && !attachCard.hidden) {
            // Already showing this one: leave it exactly as it is, or moving the pointer along a pill would
            // restart a rename the user had begun typing into.
            return
        }

        cardAttachment = attachment
        cardDate = noteDate
        attachName.textContent = attachment.name
        attachSize.textContent = ATTACH_TEXT.size.replace(SIZE_TOKEN, kilobytes(attachment.byteSize))
        attachDownload.setAttribute('href', attachment.url)
        attachDownload.setAttribute('download', attachment.name)
        // What the card can show is the server's answer, not a guess made here: `preview` is decided from the name the
        // file was UPLOADED under, which a rename cannot change (AttachmentNames.previewFor).
        const isImage = attachment.preview === 'image'
        attachPreviewLink.hidden = !isImage
        // The src is only set for an image, so a non-image never costs a request - and it is REMOVED on the way
        // out rather than emptied, or the previous file's picture would flash behind the next one's name and a
        // src="" would have the browser re-request the page itself.
        setPreviewSource(isImage ? attachment.url : null)
        setPlayerSource(attachment.preview === 'audio' ? attachment.url : null)
        attachRenameRow.hidden = true
        showConfirm(false)
        attachCard.hidden = false
        positionCard(chip)
        chip.classList.add('note-chip-active')
    }

    function positionCard(chip) {
        const panelBox = notePanel.getBoundingClientRect()
        const chipBox = chip.getClientRects()[0]
        if (!chipBox) { return }
        const cardWidth = attachCard.offsetWidth
        const cardHeight = attachCard.offsetHeight
        const left = Math.max(4, Math.min(chipBox.left - panelBox.left, panelBox.width - cardWidth - 4))
        // Below the pill by default, above it when there is no room - so the card never hangs off the bottom of
        // a box the user has not resized.
        const below = chipBox.bottom - panelBox.top + 6
        const top = (below + cardHeight <= panelBox.height) ? below : Math.max(4, chipBox.top - panelBox.top - cardHeight - 6)
        attachCard.style.left = `${left  }px`
        attachCard.style.top = `${top  }px`
    }

    function closeCard() {
        clearTimeout(cardHideTimer)
        clearTimeout(cardShowTimer)
        if (!attachCard) {return}
        attachCard.hidden = true
        cardPinned = false
        attachCard.classList.remove('note-attachment-card-pinned')
        if (attachClose !== null) { attachClose.hidden = true }
        attachRenameRow.hidden = true
        showConfirm(false)
        setPreviewSource(null)
        setPlayerSource(null)
        cardAttachment = null
        cardDate = null
        if (noteHighlights) {
            noteHighlights.querySelectorAll('.note-chip-active').forEach(function (chip) { chip.classList.remove('note-chip-active') })
        }
    }

    // Closing is delayed so the pointer can travel the six pixels from the pill to the card without the card
    // disappearing under it on the way. Cancelled by anything that re-enters either.
    // The image AND the link around it: the thumbnail is what the card shows, and the link is how the file is seen at
    // its own size. Both are cleared on the way out rather than emptied, or the previous file's picture would flash
    // behind the next one's name and a src="" would have the browser re-request the page itself.
    // The player is PAUSED before its src goes, not merely hidden: a hidden <audio> keeps playing, and removing the
    // attribute without pausing leaves some browsers decoding what they had already buffered. Cleared the same way the
    // thumbnail is - removed rather than emptied, since src="" re-requests the page itself.
    function setPlayerSource(url) {
        if (attachPlayer === null) {return}
        if (url === null) {
            attachPlayer.pause()
            attachPlayer.removeAttribute('src')
            attachPlayer.load()
            attachPlayer.hidden = true
        } else if (attachPlayer.getAttribute('src') !== url) {
            attachPlayer.setAttribute('src', url)
            attachPlayer.hidden = false
        }
    }

    function setPreviewSource(url) {
        if (url === null) {
            attachPreview.removeAttribute('src')
            attachPreviewLink.removeAttribute('href')
        } else {
            attachPreview.setAttribute('src', url)
            attachPreviewLink.setAttribute('href', url)
        }
    }

    function pinCard() {
        cardPinned = true
        clearTimeout(cardHideTimer)
        attachCard.classList.add('note-attachment-card-pinned')
        if (attachClose !== null) { attachClose.hidden = false }
    }

    function scheduleCloseCard() {
        clearTimeout(cardHideTimer)
        if (cardPinned) {return}
        // A clip that is PLAYING holds the card open: the pointer has to leave the pill to reach anything else on the
        // page, and closing under it would either cut the sound off mid-word or leave it coming from a hidden element.
        // An explicit close (Escape, or a click outside the panel) still closes, and pauses on the way - see closeCard.
        if (attachPlayer !== null && !attachPlayer.paused) {return}
        cardHideTimer = setTimeout(closeCard, 200)
    }

    function kilobytes(bytes) {
        // Rounded UP, and never to zero: a file that exists is at least one kilobyte's worth of "there is
        // something here", and "0 KB" reads as an empty file the server would have refused.
        return Math.max(1, Math.ceil(bytes / 1024)).toLocaleString(window.Diurnal.lang)
    }

    if (noteEditor && attachCard) {
        noteEditor.addEventListener('mousemove', function (ev) {
            if (noteInput.disabled) {return}
            // A pinned card is not the pointer's to move or to close - see cardPinned.
            if (cardPinned) {return}
            const chip = chipAt(ev.clientX, ev.clientY)
            if (chip === null) {
                clearTimeout(cardShowTimer)
                if (!attachCard.hidden) { scheduleCloseCard() }
                return
            }
            // A DWELL, not an immediate open: a pointer crossing the note box on its way somewhere else passes over
            // every pill in its path, and a card that appeared under each of them would be a flicker rather than an
            // answer. Already showing this pill? Leave it - re-arming the timer on every mousemove would mean a card
            // that never settled. A CLICK is unaffected and opens at once (it pins, below): the delay is the cost of
            // not having asked for anything.
            if (!attachCard.hidden && cardAttachment !== null && cardAttachment.name === chip.dataset.attachmentName) {return}
            clearTimeout(cardShowTimer)
            cardShowTimer = setTimeout(function () { openCard(chip) }, HOVER_DELAY_MS)
        })
        // A click as well as a hover, because a touch device has no hover at all: without this an attachment could be
        // attached on a phone and then never renamed or removed on one. It is the same hit test - the pill is in a
        // layer that takes no pointer events, so what is actually clicked is the textarea underneath, and where the
        // pointer landed is answered by measuring rather than by the event's target.
        //
        // It only ever OPENS. Closing on a miss reads as the obvious other half and is not: a click on a pill also
        // moves the caret, which can scroll the focused box into view, so the pointer-down and the pointer-up can
        // land on different text - and the card the same gesture had just opened would shut again.
        noteEditor.addEventListener('click', function (ev) {
            const chip = chipAt(ev.clientX, ev.clientY)
            if (chip === null) {return}
            // PINS it, which is the whole difference between clicking a pill and passing over one: the card stays until
            // it is dismissed, so the file can be renamed, played or read at leisure without the pointer pinning it in
            // place. Pinning AFTER openCard, so clicking the pill a hover-card is already showing pins that card rather
            // than being swallowed by its "already showing this one" short-circuit.
            clearTimeout(cardShowTimer)
            openCard(chip)
            pinCard()
        })
        // What closes a card opened by a tap, on a device that has no pointer to move away: anything outside the
        // panel, or Escape. Both are what the rest of the app's dismissable surfaces already answer to.
        document.addEventListener('click', function (ev) {
            if (!attachCard.hidden && !notePanel.contains(ev.target)) { closeCard() }
        })
        document.addEventListener('keydown', function (ev) {
            if (ev.key === 'Escape' && !attachCard.hidden) { closeCard() }
        })
        noteEditor.addEventListener('mouseleave', scheduleCloseCard)
        attachCard.addEventListener('mouseenter', function () { clearTimeout(cardHideTimer) })
        attachCard.addEventListener('mouseleave', scheduleCloseCard)
        // A clip that reaches its end releases the card again: scheduleCloseCard refuses to close while something is
        // playing, so without this the card would sit open until the pointer happened to visit and leave it. The usual
        // delay still applies, and the mouseenter above still cancels it, so a pointer resting on the card keeps it.
        if (attachPlayer !== null) {
            attachPlayer.addEventListener('ended', scheduleCloseCard)
        }
        if (attachClose !== null) {
            attachClose.addEventListener('click', closeCard)
        }
        if (attachCancel !== null) {
            attachCancel.addEventListener('click', function () {
                if (uploadInFlight !== null) { uploadInFlight.abort() }
            })
        }
        // The mirror is redrawn on every keystroke, so the rectangles the card was placed against are gone the
        // moment the user types - and a card left floating over unrelated text is worse than no card.
        noteInput.addEventListener('scroll', function () {
            noteHighlights.scrollTop = noteInput.scrollTop
            closeCard()
        })
    }

    if (attachRenameBtn) {
        attachRenameBtn.addEventListener('click', function () {
            if (cardAttachment === null) {return}
            showConfirm(false)
            attachRenameRow.hidden = false
            attachRenameInp.value = cardAttachment.name
            attachRenameInp.focus()
            attachRenameInp.select()
        })
    }

    if (attachRenameInp) {
        attachRenameInp.addEventListener('keydown', function (ev) {
            if (ev.key === 'Escape') { attachRenameRow.hidden = true; return }
            if (ev.key !== 'Enter') {return}
            ev.preventDefault()
            renameAttachment(cardAttachment, cardDate, attachRenameInp.value)
        })
    }

    // Remove asks first, in place, the way every other destructive action in the app does - a file is not recoverable
    // once it is gone, and the card is reached by hovering rather than by a deliberate click, so the button it offers
    // is the easiest one in the box to press by accident.
    function showConfirm(asking) {
        if (!attachConfirm || !attachActions) {return}
        attachConfirm.hidden = !asking
        attachActions.hidden = asking
    }

    if (attachDeleteBtn) {
        attachDeleteBtn.addEventListener('click', function () {
            attachRenameRow.hidden = true
            showConfirm(true)
        })
    }
    if (attachDeleteNo) {
        attachDeleteNo.addEventListener('click', function () { showConfirm(false) })
    }
    if (attachDeleteOk) {
        attachDeleteOk.addEventListener('click', function () { deleteAttachment(cardAttachment, cardDate) })
    }

    // A rename and a delete BOTH rewrite the day's stored note server-side, in the same transaction as the row
    // they follow - so the answer carries the note as it now stands, and the saved cache is replaced from it
    // rather than edited here. The DRAFT is a different matter: it is the user's own unsaved writing, which the
    // server has never seen, so the same token change is applied to it locally instead of throwing it away.
    function renameAttachment(attachment, dateStr, submitted) {
        if (attachment === null || dateStr === null) {return}
        postAttachment(window.Diurnal.url(`/internal/note-attachments/${  dateStr  }/${  attachment.id  }/rename`), dateStr,
            JSON.stringify({ name: submitted }), function (body) {
                const renamed = body.attachment
                replaceToken(dateStr, attachment.name, renamed.token)
                noteAttachments[dateStr] = attachmentsForDay(dateStr).map(function (a) { return a.id === renamed.id ? renamed : a })
                applyStoredNote(dateStr, body.noteContent)
            })
    }

    function deleteAttachment(attachment, dateStr) {
        if (attachment === null || dateStr === null) {return}
        postAttachment(window.Diurnal.url(`/internal/note-attachments/${  dateStr  }/${  attachment.id  }/delete`), dateStr, null,
            function (body) {
            replaceToken(dateStr, attachment.name, '')
            noteAttachments[dateStr] = attachmentsForDay(dateStr).filter(function (a) { return a.id !== attachment.id })
            applyStoredNote(dateStr, body.noteContent)
        })
    }

    // `url` arrives already prefixed by Diurnal.url at the call site rather than being built here: the guard that
    // keeps a sub-path deployment working (AppPathsAreCentralisedTest) reads the SOURCE, so a path literal has to
    // sit inside that call to be recognised as wrapped at all.
    function postAttachment(url, dateStr, body, onSuccess) {
        const options = { method: 'POST', headers: { 'Accept': 'application/json' } }
        if (body !== null) {
            options.headers['Content-Type'] = 'application/json'
            options.body = body
        }
        fetch(url, options)
            .then(requireSession)
            .then(function (resp) {
                if (!resp.ok) {
                    return resp.json().then(function (failure) { throw new Error(failure && failure.message) })
                }
                return resp.json()
            })
            .then(function (answer) {
                closeCard()
                onSuccess(answer)
                if (noteDate === dateStr) { showNote(dateStr) }
                renderHighlights()
                cal.noteChanged()
            })
            .catch(function (err) {
                if (noteDate !== dateStr) {return}
                if (noteError) {
                    noteError.innerHTML = window.Diurnal.bannerHtml(err && err.message ? err.message : ATTACH_TEXT.couldNotAttach)
                }
            })
    }

    // The same token change the server made to the STORED note, applied to the unsaved draft. An empty `to`
    // removes the token. The prose around it is left exactly as it was, matching what the server does.
    function replaceToken(dateStr, from, to) {
        const draft = noteDrafts[dateStr]
        if (draft === undefined) {return}
        noteDrafts[dateStr] = draft.split(`[[${  from  }]]`).join(to)
        persistDraft()
    }

    function applyStoredNote(dateStr, content) {
        if (typeof content !== 'string') {return}
        noteSaved[dateStr] = content
        // A draft that has caught up with what is now stored is no longer an unsaved edit.
        if (noteDrafts[dateStr] === content) {
            delete noteDrafts[dateStr]
            persistDraft()
        }
    }

    // ── Note box resize ──────────────────────────────────────────────────────
    // Three drag handles: the right edge, the bottom edge and the corner. Native `resize: both` gives only
    // a corner grip and cannot do the edges, so this is hand-rolled on Pointer Events (mouse + touch, no
    // library) exactly like the settings stats picker's drag.
    //
    // The size is written straight onto #note-panel as an inline style and held nowhere else: the panel is
    // never re-rendered, so it survives every date change for free, and a page navigation re-runs this
    // script against a fresh element, so it resets — which is precisely the required lifetime.
    const MIN_CALENDAR_WIDTH = 280 // px of calendar to leave when the box is widened beside it

    // The panel's own natural (un-dragged) width, measured by dropping the inline width for one layout read
    // and putting it straight back. Deliberately NOT read off a sibling: the day logger shares the note's
    // grid column, so widening the note widens the logger too — deriving the floor from it would let the
    // floor climb with every drag, and the box could then never be returned to its default. Measured once
    // per drag (on pointerdown), so the forced reflow is not in the move path, and it re-derives itself
    // after a viewport change with no stored baseline to go stale.
    function noteNaturalWidth() {
        const inline = notePanel.style.width
        notePanel.style.width = ''
        const natural = notePanel.getBoundingClientRect().width
        notePanel.style.width = inline
        return natural
    }

    function noteMaxWidth() {
        const main = document.getElementById('dashboard-main')
        const available = main ? main.clientWidth : 0
        // Looked up here rather than held from module init: this module owns no part of the calendar, and the
        // element is only needed to answer "is the box currently BESIDE the calendar, or stacked under it?"
        const calWrap = document.getElementById('calendar-wrap')
        const calendar = calWrap ? calWrap.getBoundingClientRect() : null
        const panelBox = notePanel.getBoundingClientRect()
        // Side by side (lg+) only when the calendar sits to the note's physical left; stacked in one
        // column the box may use the full content width. The dashboard's 2x2 grid position is pinned
        // regardless of language (.claude/UI_PATTERNS.md section 7), so this comparison is never
        // direction-aware — the calendar is always physically left of the note panel.
        const sideBySide = calendar !== null && panelBox.left > calendar.left + 1
        return sideBySide ? Math.max(noteNaturalWidth(), available - MIN_CALENDAR_WIDTH) : available
    }

    notePanel.querySelectorAll('[data-note-resize]').forEach(function (handle) {
            handle.addEventListener('pointerdown', function (ev) {
                const axis = handle.dataset.noteResize
                const startX = ev.clientX, startY = ev.clientY
                const box = notePanel.getBoundingClientRect()
                const startW = box.width, startH = box.height
                // The floor is the box's DEFAULT size, so a drag can only ever make it larger: the natural
                // column width, and the CSS min-height the panel already carries.
                const minW = noteNaturalWidth()
                const maxW = noteMaxWidth()
                const minH = parseFloat(window.getComputedStyle(notePanel).minHeight) || startH

                handle.setPointerCapture(ev.pointerId)
                document.body.classList.add('note-resizing')
                ev.preventDefault()

                function onMove(moveEv) {
                    if (axis !== 'bottom') {
                        notePanel.style.width = `${Math.min(Math.max(startW + (moveEv.clientX - startX), minW), maxW)  }px`
                    }
                    if (axis !== 'right') {
                        notePanel.style.height = `${Math.max(startH + (moveEv.clientY - startY), minH)  }px`
                    }
                }
                function onUp() {
                    handle.removeEventListener('pointermove', onMove)
                    handle.removeEventListener('pointerup', onUp)
                    handle.removeEventListener('pointercancel', onUp)
                    document.body.classList.remove('note-resizing')
                }
                handle.addEventListener('pointermove', onMove)
                handle.addEventListener('pointerup', onUp)
                handle.addEventListener('pointercancel', onUp)
        })
    })


    // Seed from the inline content the page shipped for its initially selected day, so opening the dashboard
    // costs no note request at all. (Safe to read verbatim: normalisation strips leading newlines, so the
    // newline HTML parsing eats after a <textarea> tag can never be part of a stored note.)
    if (notePanel.dataset.noteDate) {
        noteSaved[notePanel.dataset.noteDate] = noteInput.value
    }

    // Then bring back anything left unsaved earlier in this tab. Only the CACHE is filled here: the box is
    // painted by the first loadNote (dashboard.js selects a day on DOMContentLoaded), which reads a draft in
    // preference to the stored note and puts the "Unsaved changes" line back with it.
    restoreDraft()

    // The interface the calendar drives, and nothing else.
    window.Diurnal.noteBox = {
        bindCalendar: function (adapter) { cal = adapter },
        load: loadNote,
        disable: function () { noteDate = null; setNoteEnabled(false) },
        hasNote: hasNote,
        mergeMonths: mergeNoteMonths,
        dropMonth: dropNoteMonth
    }
})()
