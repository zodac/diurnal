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
    }

    // Whether a day has a note, for the calendar's green day number. An unloaded month reads as `false`, so a
    // day number simply gains its colour when that month's notes land.
    function hasNote(dateStr) {
        return Boolean(noteSaved[dateStr])
    }

    // Paint the box for a day: its draft if it has one, otherwise what the server holds.
    function showNote(dateStr) {
        if (!noteInput || noteDate !== dateStr) {return}
        const draft = noteDrafts[dateStr]
        noteInput.value = draft === undefined ? (noteSaved[dateStr] || '') : draft
        refreshNoteState()
        setNoteStatus(noteIsDirty() ? window.Diurnal.i18n.unsavedChanges : '', 'brand')
    }

    function loadNote(dateStr) {
        if (!notePanel || !noteInput) {return}
        noteDate = dateStr
        setNoteEnabled(true)
        showNote(dateStr)
        if (noteSaved[dateStr] === undefined) {
            // The month's notes ride the calendar's cache, so this is a no-op read for any month already
            // resident (the visible one and its neighbours) and one range request otherwise.
            cal.ensureNotes(dateStr).then(function () { showNote(dateStr) }).catch(function () {})
        }
    }

    function setNoteEnabled(enabled) {
        if (!noteInput) {return}
        noteInput.disabled = !enabled
        if (!enabled) {
            noteInput.value = ''
            setNoteStatus('')
            if (noteError) { noteError.innerHTML = '' }
        }
        refreshNoteState()
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
            setNoteStatus('Saving...')
            // JSON, not a form body: a note runs to thousands of characters and Quarkus caps a form
            // attribute at 2KB (413, before the request reaches the resource). fetch rather than htmx
            // keeps an expected 422 off the console. See NotesInternalResource.
            fetch(`/internal/notes/${  dateStr}`, {
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
                }
                refreshNoteState()
                if (noteDate === dateStr) { flashNoteStatus(window.Diurnal.i18n.saved, 'success') }
                // The cache was just updated in place, so the grid only needs repainting — writing the first
                // note on a day turns its number green, clearing the last one turns it back.
                cal.noteChanged()
            }).catch(function (err) {
                if (noteDate !== dateStr) {return}
                setNoteStatus('')
                if (noteError) {
                    noteError.innerHTML = window.Diurnal.bannerHtml(err && err.message ? err.message : 'Could not save the note.')
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
            refreshNoteState()
            setNoteStatus(noteIsDirty() ? window.Diurnal.i18n.unsavedChanges : '', 'brand')
            noteInput.focus()
        })
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
        // Side by side (lg+) only when the calendar actually sits to the left; stacked in one column the
        // box may use the full content width.
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
