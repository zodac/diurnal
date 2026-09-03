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

package net.zodac.diurnal.stats;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.log.ActionLog;
import net.zodac.diurnal.log.DailyActionTotal;
import net.zodac.diurnal.log.DatedActionCount;
import net.zodac.diurnal.log.MonthlyActionTotal;
import net.zodac.diurnal.note.Note;
import net.zodac.diurnal.persistence.LogStatements;
import net.zodac.diurnal.stats.cache.SubjectStatsCache;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.time.DaySpan;
import net.zodac.diurnal.time.Durations;
import net.zodac.diurnal.user.Language;
import net.zodac.diurnal.user.User;
import net.zodac.diurnal.user.UserSettings;
import org.jspecify.annotations.Nullable;

/**
 * Computes per-action statistics (counts, streaks, trends) from a user's logged entries.
 */
@ApplicationScoped
public class StatsService {

    private final AppClock clock;
    private final LogStatements statements;

    /**
     * Injects the application clock and the database's native statements.
     *
     * @param clock      the application clock for date-boundary logic
     * @param statements the native action-log statements for the configured database
     */
    @Inject
    public StatsService(final AppClock clock, final LogStatements statements) {
        this.clock = clock;
        this.statements = statements;
    }

    /**
     * Returns stats for every subject of the user that has any data: their day NOTES first, then each active action that has at least one logged
     * entry, ordered by action name.
     *
     * <p>
     * Notes are pinned ahead of the actions here, BEFORE the caller paginates, so "first" means the first card on page one rather than the first card
     * of whichever page it happened to land on. They are a subject like any other from this point on — the statistics themselves are computed by the
     * same {@link #assemble} from the same shape of input, so nothing downstream needs to know which kind it is holding.
     *
     * <p>
     * The per-subject totals, comparative counts and best-month/best-year figures are aggregated in the database (a monthly {@code GROUP BY}); only
     * the per-day rollup is read back, and solely to compute the streak/gap and days-with-multiples figures — so a long history no longer hydrates
     * every log row.
     *
     * @param userId the user whose stats to compute
     * @return the notes subject (when it has any data) followed by every action that has been logged
     */
    public List<SubjectStats> forAllSubjects(final UUID userId) {
        // Read ONCE and threaded down: every subject's figures must be measured against the same "today", and the same read carries the colour the
        // notes subject is drawn in.
        final User user = User.findById(userId);
        final LocalDate today = todayFor(user);

        final List<SubjectStats> cached = fromCache(userId, user, today);
        if (!cached.isEmpty()) {
            return cached;
        }

        final List<SubjectStats> computed = computeAllSubjects(userId, user, today);
        // A read path that writes, deliberately. The endpoints calling this carry no @Transactional - a page render holding a connection open is
        // what that rule exists to prevent - so the cache write opens its own short transaction (joining one if a caller already has it open, the
        // shape PostgresSessionStore uses for the same reason). A failure to store costs the next reader a recompute and nothing else.
        final List<SubjectStatsCache> rows = computed.stream().map(stats -> SubjectStatsCaching.from(userId, stats)).toList();
        try {
            QuarkusTransaction.joiningExisting().run(() -> SubjectStatsCache.store(userId, rows));
        } catch (final RuntimeException e) { // NOPMD: AvoidCatchingGenericException/EmptyCatchBlock - see below
            // Best-effort by design, and deliberately silent. Two first-of-the-day reads for the same user race here: each deletes rows the other
            // cannot see yet and then inserts the same (user, subject) keys, so the loser trips the primary key. The figures it failed to store are
            // exactly the ones the winner did store, so there is nothing to recover and nothing an operator could act on - and a cache write must
            // never turn a GET into a 500. The next reader either hits the winner's rows or recomputes.
        }
        return computed;
    }

    // The cached figures for a user, EMPTY when they must be recomputed. A stored-but-empty result is indistinguishable from a miss and is
    // therefore treated as one, which is deliberate: the only user it affects is one with no actions and no notes, for whom recomputing is two
    // trivial queries over no rows, and the alternative would be a tombstone row keyed on a subject that does not exist.
    //
    // The subjects themselves are rebuilt live rather than read back, because nothing about a name or a colour is stored (see SubjectStatsCache);
    // that is what keeps a rename, a recolour and the note-colour preference off the invalidation surface. Rebuilding here also reproduces
    // computeAllSubjects' ordering exactly - notes first, then the actions name-ascending - since a cached row whose subject no longer resolves is
    // simply skipped.
    private static List<SubjectStats> fromCache(final UUID userId, final @Nullable User user, final LocalDate today) {
        final List<SubjectStatsCache> rows = SubjectStatsCache.findFresh(userId, today);
        if (rows.isEmpty()) {
            return List.of();
        }

        final Map<UUID, SubjectStatsCache> bySubject = new HashMap<>(rows.size());
        for (final SubjectStatsCache row : rows) {
            bySubject.put(row.subjectId, row);
        }

        final List<SubjectStats> subjects = new ArrayList<>(rows.size());
        final SubjectStatsCache notesRow = bySubject.get(StatSubject.NOTES_ID);
        if (notesRow != null) {
            subjects.add(SubjectStatsCaching.toStats(notesRow, StatSubject.notes(noteColourFor(user))));
        }
        for (final Action action : Action.findByUser(userId)) {   // name-ascending
            final SubjectStatsCache row = bySubject.get(action.id);
            if (row != null) {
                subjects.add(SubjectStatsCaching.toStats(row, StatSubject.of(action)));
            }
        }
        return List.copyOf(subjects);
    }

    private static List<SubjectStats> computeAllSubjects(final UUID userId, final @Nullable User user, final LocalDate today) {
        // The note days double as the existence check, so reading them FIRST means a user who has never written a note pays for this one query and
        // nothing else - no monthly rollup, no assembly. That is the common case on the Stats page's and GET /api/v1/stats' hot path.
        final List<DailyActionTotal> noteDays = Note.dailyTotals(userId, StatSubject.NOTES_ID);
        final List<SubjectStats> actions = activeActions(userId, today);
        if (noteDays.isEmpty()) {
            return actions;
        }

        // The notes subject is assembled from exactly the same two inputs an action's is - the days it happened on, and its monthly rollup - both
        // projected into the shared DailyActionTotal/MonthlyActionTotal shapes by the note queries, so there is no parallel computation to keep in
        // step.
        final List<SubjectStats> subjects = new ArrayList<>(actions.size() + 1);
        subjects.add(assemble(StatSubject.notes(noteColourFor(user)), noteDays, today));
        subjects.addAll(actions);
        return List.copyOf(subjects);
    }

    // Every action that has at least one logged entry, name-ascending. Split out of forAllSubjects only so the notes subject can be prepended to it;
    // nothing outside this class wants the actions alone (the Stats page and GET /api/v1/stats both show every subject). Takes `today` rather than
    // resolving it, so answering one request never reads the user twice.
    private static List<SubjectStats> activeActions(final UUID userId, final LocalDate today) {
        final List<Action> actions = Action.findByUser(userId);   // name-ascending
        if (actions.isEmpty()) {
            return List.of();
        }
        final List<UUID> actionIds = actions.stream().map(action -> action.id).toList();
        return assembleAll(userId, actions, actionIds, today).stream()
                .filter(SubjectStatsExtensions::hasData)
                .toList();
    }

    /**
     * Returns stats for the actions the user logged on {@code date}, highest count on that day first (ties broken by name, matching the day panel's
     * ordering), up to {@code limit}. Actions not logged on that date are excluded entirely.
     *
     * <p>
     * This is the dashboard summary strip, which follows the calendar's selected day. Only the day's top few actions are aggregated - but each one's
     * figures still cover its <em>whole</em> history (the day only decides <em>which</em> actions are shown, not the window they are computed over).
     *
     * @param userId the user whose stats to compute
     * @param date the day whose top actions to summarise
     * @param limit the maximum number of actions to return
     * @return the day's top actions' stats, highest daily count first
     */
    public List<SubjectStats> forDate(final UUID userId, final LocalDate date, final int limit) {
        return forCounts(userId, Map.of(date, ActionLog.countsByAction(userId, date)), limit)
                .getOrDefault(date, List.of());
    }

    /**
     * The {@link #forDate(UUID, LocalDate, int)} summary for every day of {@code month}, in one pass: the month's logs are read once and each day's
     * top actions are picked from memory, so the dashboard can back-fill a whole month's summaries with a single request instead of one per day.
     *
     * <p>
     * The union of every day's top actions is aggregated once, so an action appearing on twenty days is still computed only once.
     *
     * @param userId the user whose stats to compute
     * @param month the month whose days to summarise
     * @param limit the maximum number of actions per day
     * @return each logged day of the month mapped to its top actions' stats; days with no logs are absent
     */
    public Map<LocalDate, List<SubjectStats>> forMonth(final UUID userId, final YearMonth month, final int limit) {
        final Map<LocalDate, Map<UUID, Integer>> countsByDate = ActionLog.findByUserAndRange(userId, month.atDay(1), month.atEndOfMonth())
            .stream()
            .collect(Collectors.groupingBy(DatedActionCount::date, Collectors.toMap(DatedActionCount::actionId, DatedActionCount::count)));
        return forCounts(userId, countsByDate, limit);
    }

    // Picks each date's top `limit` actions from its pre-fetched counts, then aggregates the UNION of those actions once. The daily counts only rank
    // the actions; every returned figure still spans the action's full history. Ties keep the name-ascending order Action.findByUser returns them in
    // (the sort is stable), which is how the day panel orders the same actions.
    private Map<LocalDate, List<SubjectStats>> forCounts(final UUID userId, final Map<LocalDate, Map<UUID, Integer>> countsByDate, final int limit) {
        final boolean noneLogged = countsByDate.values().stream().allMatch(Map::isEmpty);
        if (noneLogged) {
            return Map.of();
        }

        final List<Action> all = Action.findByUser(userId);   // name-ascending
        final Map<LocalDate, List<Action>> topByDate = new LinkedHashMap<>();
        final Set<UUID> unionIds = new LinkedHashSet<>();
        for (final Map.Entry<LocalDate, Map<UUID, Integer>> entry : countsByDate.entrySet()) {
            final Map<UUID, Integer> counts = entry.getValue();
            final List<Action> top = all.stream()
                .filter(action -> counts.getOrDefault(action.id, 0) > 0)
                .sorted(Comparator.comparingInt((Action action) -> counts.getOrDefault(action.id, 0)).reversed())
                .limit(limit)
                .toList();
            if (!top.isEmpty()) {
                topByDate.put(entry.getKey(), top);
                top.forEach(action -> unionIds.add(action.id));
            }
        }
        if (topByDate.isEmpty()) {
            return Map.of();
        }

        final LocalDate today = todayFor(User.findById(userId));
        final List<Action> unionActions = all.stream()
            .filter(action -> unionIds.contains(action.id))
            .toList();
        final Map<UUID, SubjectStats> statsByAction = assembleAll(userId, unionActions, List.copyOf(unionIds), today).stream()
            .collect(Collectors.toMap(stats -> stats.subject().id(), stats -> stats));

        final Map<LocalDate, List<SubjectStats>> byDate = new LinkedHashMap<>();
        topByDate.forEach((date, top) -> byDate.put(date, top.stream().map(action -> statsByAction.get(action.id)).toList()));
        return byDate;
    }

    /**
     * Builds the frequency chart for one to {@code MAX_SERIES} actions over ONE calendar window - the Stats page's per-action graph, and the same
     * figures the public API serves. A month window is drawn as one bar per day, a year window as one bar per month; charting more than one action
     * groups their bars within each slot and scales them all against a single peak, so they are directly comparable.
     *
     * <p>
     * Every request input is validated here rather than at either surface, so the two cannot drift on what they accept. Nothing is coerced: an
     * unrecognised {@code period}, a malformed {@code at} key, too many actions, a repeated action and a never-logged comparison each get their own
     * result case. Omitting an input is not an error - {@code period} falls back to {@link FrequencyPeriod#DEFAULT}, {@code at} to the window
     * containing today, and {@code compareIds} to charting {@code actionId} alone.
     *
     * @param userId the user whose logs to chart
     * @param subjectId the subject the graph was opened from, always the first series
     * @param compareIds the further actions to chart alongside it, in the order they were added
     * @param rawPeriod the requested period, or {@code null}/blank for the default
     * @param rawAt the requested window key, or {@code null}/blank for the window containing today
     * @param language the language to word the chart's month/year labels in - the viewing user's own for the web surface, always
     *     {@link Language#DEFAULT} for the public API (see {@code AppMessages}' class Javadoc: English stays the API's one contract, so this is
     *     the sole caller-supplied value rather than the request's resolved language)
     * @return the assembled chart, or the case explaining why it could not be assembled
     */
    FrequencyResult frequency(final UUID userId, final UUID subjectId, final List<UUID> compareIds, final @Nullable String rawPeriod,
        final @Nullable String rawAt, final Language language) {
        final String periodValue = rawPeriod == null || rawPeriod.isBlank() ? FrequencyPeriod.DEFAULT.value() : rawPeriod;
        if (!FrequencyPeriod.isValid(periodValue)) {
            return new FrequencyResult.UnknownPeriod(periodValue);
        }

        // The graph's own subject first, then the comparisons, in the order the user added them.
        final List<UUID> requested = Stream.concat(Stream.of(subjectId), compareIds.stream()).toList();
        final FrequencyResult badSelection = rejectSelection(requested);
        if (badSelection != null) {
            return badSelection;
        }

        // The notes subject is charted alongside actions, and is resolved FIRST — before any action lookup — so its sentinel id can never be
        // shadowed by a row. It needs no ownership check: there is exactly one notes subject per user, and it is theirs by construction.
        final List<UUID> actionIds = requested.stream().filter(id -> !StatSubject.NOTES_ID.equals(id)).toList();

        // Ordered by the request, not by the name-ascending order findByUserAndIds returns: the legend and the bar order within each column follow
        // the order the user built the comparison in, so adding an action never re-shuffles the bars already on screen.
        final Map<UUID, Action> ownedById = actionIds.isEmpty()
            ? Map.of()
            : Action.findByUserAndIds(userId, actionIds).stream().collect(Collectors.toMap(action -> action.id, action -> action));
        if (ownedById.size() != actionIds.size()) {
            return new FrequencyResult.NotOwned();
        }

        final FrequencyResult unlogged = rejectUnlogged(userId, compareIds);
        if (unlogged != null) {
            return unlogged;
        }

        final User user = User.findById(userId);
        final LocalDate today = todayFor(user);
        final FrequencyPeriod period = FrequencyPeriod.of(periodValue);
        final LocalDate anchor = resolveAnchor(period, rawAt, today);
        if (anchor == null) {
            return new FrequencyResult.UnknownWindow(Objects.requireNonNull(rawAt));
        }

        final List<StatSubject> charted = chartedSubjects(requested, ownedById, user);
        final boolean notesCharted = requested.size() != actionIds.size();
        final LocalDate windowEnd = FrequencyKeys.end(period, anchor);

        // Each arm reads ONLY the window it draws. The year view used to roll up the subjects' whole history and keep the anchor year's twelve
        // months out of it, which is a read that grows with every year the account survives to draw a chart that never does.
        final Map<UUID, Map<Integer, Long>> countsByAction = switch (period) {
            case MONTH -> dailySlots(dailyTotals(userId, actionIds, notesCharted, anchor, windowEnd));
            case YEAR -> monthlySlots(monthlyRollups(userId, actionIds, notesCharted, anchor, windowEnd));
        };

        return new FrequencyResult.Charted(
            FrequencyCharts.build(charted, period, anchor, countsByAction, today,
                earliestLoggedMonth(statements, userId, actionIds, notesCharted), language));
    }

    /**
     * The subjects the frequency chart's compare picker may offer: the user's day notes and their actions that have at least one entry, are not
     * already on the graph, and whose name matches the search term. The notes subject is offered first (as it is on the Stats page); the actions
     * follow name-ascending, matching every other action list in the app.
     *
     * @param userId the user whose subjects to offer
     * @param charted the subjects already on the graph (the primary plus any comparisons), which are never offered again
     * @param query the case-insensitive name filter, or {@code null}/blank for no filtering
     * @return the offerable subjects, notes first then actions name-ascending
     */
    public List<StatSubject> compareCandidates(final UUID userId, final List<UUID> charted, final @Nullable String query) {
        final Set<UUID> logged = ActionLog.loggedActionIds(userId);
        final Set<UUID> excluded = Set.copyOf(charted);
        final String term = query == null ? "" : query.strip().toLowerCase(Locale.ENGLISH);

        final List<StatSubject> actions = Action.findByUser(userId).stream()   // name-ascending
            .filter(action -> logged.contains(action.id))
            .filter(action -> !excluded.contains(action.id))
            .filter(action -> term.isEmpty() || action.name.toLowerCase(Locale.ENGLISH).contains(term))
            .map(StatSubject::of)
            .toList();

        final StatSubject notes = StatSubject.notes(noteColourFor(User.findById(userId)));
        final boolean offersNotes = !excluded.contains(StatSubject.NOTES_ID)
            && Note.count("userId = ?1", userId) > 0L
            && (term.isEmpty() || notes.name().toLowerCase(Locale.ENGLISH).contains(term));

        // Notes are pinned ahead of the actions, exactly as forAllSubjects pins them on the Stats page.
        final List<StatSubject> candidates = new ArrayList<>(actions.size() + 1);
        if (offersNotes) {
            candidates.add(notes);
        }
        candidates.addAll(actions);
        return List.copyOf(candidates);
    }

    @Nullable
    private static FrequencyResult rejectSelection(final List<UUID> requested) {
        if (requested.size() > FrequencyCharts.MAX_SERIES) {
            return new FrequencyResult.TooManySubjects(requested.size(), FrequencyCharts.MAX_SERIES);
        }
        final UUID repeated = firstRepeated(requested);
        return repeated == null ? null : new FrequencyResult.DuplicateSubject(repeated);
    }

    // The compare picker only offers subjects with at least one entry, so the API rejects the same set rather than drawing a flat series the UI could
    // never produce - for notes that means "has written at least one note". The graph's OWN subject is exempt: its card is reachable with no entries,
    // and an empty chart is the honest answer there.
    @Nullable
    private static FrequencyResult rejectUnlogged(final UUID userId, final List<UUID> compareIds) {
        final Set<UUID> logged = ActionLog.loggedActionIds(userId);
        final long noteCount = Note.count("userId = ?1", userId);
        for (final UUID compareId : compareIds) {
            final boolean absent = StatSubject.NOTES_ID.equals(compareId) ? (noteCount == 0L) : !logged.contains(compareId);
            if (absent) {
                return new FrequencyResult.NotLogged(compareId);
            }
        }
        return null;
    }

    // Null means the caller asked for a window this period cannot name - an absent or blank request is the period's own default, not a rejection.
    @Nullable
    private static LocalDate resolveAnchor(final FrequencyPeriod period, final @Nullable String rawAt, final LocalDate today) {
        if (rawAt == null || rawAt.isBlank()) {
            return FrequencyKeys.anchorOf(period, today);
        }
        return FrequencyKeys.isValid(period, rawAt) ? FrequencyKeys.anchor(period, rawAt) : null;
    }

    // Ordered by the request, not by the name-ascending order findByUserAndIds returns: the legend and the bar order within each column follow the
    // order the user built the comparison in, so adding an action never re-shuffles the bars already on screen.
    private static List<StatSubject> chartedSubjects(final List<UUID> requested, final Map<UUID, Action> ownedById, final @Nullable User user) {
        final StatSubject notesSubject = StatSubject.notes(noteColourFor(user));
        return requested.stream()
            .map(id -> StatSubject.NOTES_ID.equals(id) ? notesSubject : StatSubject.of(Objects.requireNonNull(ownedById.get(id))))
            .toList();
    }

    // Both sources project into the SAME monthly rollup record, so the two are simply concatenated and the chart builder never learns that more than
    // one kind of subject exists. Each query is skipped entirely when its side is not charted (the action rollups reject an empty id list, and a
    // notes query would otherwise be a pointless round trip).
    private static List<MonthlyActionTotal> monthlyRollups(final UUID userId, final List<UUID> actionIds, final boolean notesCharted,
        final LocalDate from, final LocalDate to) {
        final List<MonthlyActionTotal> totals = new ArrayList<>();
        if (!actionIds.isEmpty()) {
            totals.addAll(ActionLog.monthlyTotalsForActions(userId, actionIds, from, to));
        }
        if (notesCharted) {
            totals.addAll(Note.monthlyTotals(userId, StatSubject.NOTES_ID, from, to));
        }
        return totals;
    }

    @Nullable
    private static UUID firstRepeated(final List<UUID> ids) {
        final Set<UUID> seen = new HashSet<>();
        for (final UUID id : ids) {
            if (!seen.add(id)) {
                return id;
            }
        }
        return null;
    }

    private static List<DailyActionTotal> dailyTotals(final UUID userId, final List<UUID> actionIds, final boolean notesCharted,
        final LocalDate from, final LocalDate to) {
        final List<DailyActionTotal> totals = new ArrayList<>();
        if (!actionIds.isEmpty()) {
            totals.addAll(ActionLog.dailyTotalsForActions(userId, actionIds, from, to));
        }
        if (notesCharted) {
            totals.addAll(Note.dailyTotals(userId, StatSubject.NOTES_ID, from, to));
        }
        return totals;
    }

    private static Map<UUID, Map<Integer, Long>> dailySlots(final List<DailyActionTotal> dailyTotals) {
        return dailyTotals.stream()
            .collect(Collectors.groupingBy(DailyActionTotal::actionId,
                Collectors.toMap(total -> total.date().getDayOfMonth(), DailyActionTotal::total, Long::sum)));
    }

    // No year filter: the rollup is already bounded to the window being drawn, so every row it returns belongs in a slot.
    private static Map<UUID, Map<Integer, Long>> monthlySlots(final List<MonthlyActionTotal> monthlyTotals) {
        return monthlyTotals.stream()
            .collect(Collectors.groupingBy(MonthlyActionTotal::actionId,
                Collectors.toMap(MonthlyActionTotal::month, MonthlyActionTotal::total, Long::sum)));
    }

    // Month precision is enough: every chart window starts on a month boundary, so the earliest LOGGED month is exactly the earliest window worth
    // stepping back to. Asked as its own cheap query per charted subject - one index probe each - rather than read off a whole-history rollup, which
    // is what the month view was paying for despite drawing none of it.
    @Nullable
    private static LocalDate earliestLoggedMonth(final LogStatements statements, final UUID userId, final List<UUID> actionIds,
        final boolean notesCharted) {
        final LocalDate earliestLog = actionIds.isEmpty() ? null : ActionLog.earliestLoggedDate(statements, userId, actionIds);
        final LocalDate earliestNote = notesCharted ? Note.earliestNoteDate(userId) : null;
        return Stream.of(earliestLog, earliestNote)
            .filter(Objects::nonNull)
            .min(LocalDate::compareTo)
            .map(date -> date.withDayOfMonth(1))
            .orElse(null);
    }

    // ── Shared computation ────────────────────────────────────────────────

    private LocalDate todayFor(final @Nullable User user) {
        // Streak boundaries are evaluated in the user's own timezone (else the server default).
        return clock.today(clock.zoneFor(user == null ? null : user.timezone));
    }

    private static String noteColourFor(final @Nullable User user) {
        return user == null ? UserSettings.DEFAULT_NOTE_COLOUR : user.noteColour;
    }

    private static List<SubjectStats> assembleAll(final UUID userId, final List<Action> actions,
        final List<UUID> actionIds, final LocalDate today) {
        // ONE read of the history, not two. Every figure assemble() produces - including the per-month and per-year totals, which used to be a
        // second whole-history aggregate - comes off this daily rollup.
        final Map<UUID, List<DailyActionTotal>> days = groupDays(ActionLog.dailyTotalsForActions(userId, actionIds));
        return actions.stream()
                .map(action -> assemble(StatSubject.of(action), days.getOrDefault(action.id, List.of()), today))
                .toList();
    }

    private static Map<UUID, List<DailyActionTotal>> groupDays(final List<DailyActionTotal> rows) {
        // Rows arrive ordered by (action, date), so each action's list is ascending and holds one entry per day.
        final Map<UUID, List<DailyActionTotal>> byAction = new HashMap<>();
        for (final DailyActionTotal row : rows) {
            byAction.computeIfAbsent(row.actionId(), _ -> new ArrayList<>()).add(row);
        }
        return byAction;
    }

    private static SubjectStats assemble(final StatSubject subject, final List<DailyActionTotal> sortedDays, final LocalDate today) {
        if (sortedDays.isEmpty()) {
            final DaySpan noSpan = new DaySpan(today, today);
            return new SubjectStats(subject, 0, 0, 0L, null, null, null, noSpan, noSpan, noSpan,
                    0L, 0L, 0L, 0L, null, 0L, "—", 0L, today);
        }

        // The daily rollup carries the streak/gap dates AND the per-day count, so the two figures that need to know how OFTEN a day was recorded
        // (the days with multiples) come off the same read as the ones that only need to know THAT it was.
        final List<LocalDate> sortedDates = sortedDays.stream()
            .map(DailyActionTotal::date)
            .toList();
        final List<LocalDate> multipleDates = datesWithMultiples(sortedDays);

        final YearMonth thisMonth = YearMonth.from(today);
        final YearMonth prevMonth = thisMonth.minusMonths(1L);
        final int thisYear = today.getYear();

        // Rolled up HERE from the daily rows rather than read back from the database as a second aggregate over the same history. A
        // (user, subject, day) is unique, so a month's total is the sum of its days' totals by construction - the two were verified to produce
        // identical sets - and the daily rows have already been read for the streak, gap and multiples figures above.
        final Map<YearMonth, Long> byMonth = new HashMap<>();
        final Map<Integer, Long> byYear = new HashMap<>();
        long totalCount = 0L;
        for (final DailyActionTotal day : sortedDays) {
            byMonth.merge(YearMonth.from(day.date()), day.total(), Long::sum);
            byYear.merge(day.date().getYear(), day.total(), Long::sum);
            totalCount += day.total();
        }

        final Map.Entry<YearMonth, Long> bestMonth = byMonth.entrySet().stream()
            .max(Map.Entry.comparingByValue()).orElse(null);
        final Map.Entry<Integer, Long> bestYear = byYear.entrySet().stream()
            .max(Map.Entry.comparingByValue()).orElse(null);

        return new SubjectStats(
                subject,
                sortedDates.size(),
                multipleDates.size(),
                totalCount,
                sortedDates.getFirst(),
                sortedDates.getLast(),
                multipleDates.isEmpty() ? null : multipleDates.getLast(),
                currentStreak(sortedDates, today),
                longestStreak(sortedDates, today),
                longestGap(sortedDates, today),
                byMonth.getOrDefault(thisMonth, 0L),
                byMonth.getOrDefault(prevMonth, 0L),
                byYear.getOrDefault(thisYear, 0L),
                byYear.getOrDefault(thisYear - 1, 0L),
                bestMonth != null ? bestMonth.getKey() : null,
                bestMonth != null ? bestMonth.getValue() : 0L,
                bestYear  != null ? String.valueOf(bestYear.getKey()) : "—",
                bestYear  != null ? bestYear.getValue() : 0L,
                today);
    }

    /**
     * The days on which the subject was recorded MORE than once, ascending - the input to both of the "days with multiples" figures (how many such
     * days there are, and the most recent of them). A day recorded exactly once does not count, which is the whole of the rule; a notes subject
     * consequently never has one, since a day holds at most one note.
     */
    static List<LocalDate> datesWithMultiples(final List<DailyActionTotal> sortedDays) {
        return sortedDays.stream()
            .filter(day -> day.total() > 1L)
            .map(DailyActionTotal::date)
            .toList();
    }

    // ── Streaks and gaps ──────────────────────────────────────────────────
    // Each of these returns the run's actual DATES (a half-open DaySpan), not just its length: the figures are
    // displayed as calendar durations, and "31 days" is one month or one month and three days depending on which
    // months it covered, so the breakdown can only be computed from the real range. An action with no logged
    // dates yields an empty span anchored at today.

    /**
     * The run of consecutive days, up to (and including) today, on which the action was performed. A run still counts as current when today itself
     * has not been logged yet but yesterday was, so a streak is not reported as broken until a whole day has been missed.
     */
    static DaySpan currentStreak(final List<LocalDate> sortedDates, final LocalDate today) {
        final Set<LocalDate> performed = new HashSet<>(sortedDates);
        final LocalDate lastDay = performed.contains(today) ? today : today.minusDays(1L);
        if (!performed.contains(lastDay)) {
            return new DaySpan(today, today);
        }

        LocalDate firstDay = lastDay;
        while (performed.contains(firstDay.minusDays(1L))) {
            firstDay = firstDay.minusDays(1L);
        }
        return new DaySpan(firstDay, lastDay.plusDays(1L));
    }

    /**
     * The longest run of consecutive days on which the action was <em>not</em> performed, looking both at the blank days between any two logged dates
     * and at the open run from the last logged date to today. Ties keep the earliest run.
     */
    static DaySpan longestGap(final List<LocalDate> sortedDates, final LocalDate today) {
        if (sortedDates.isEmpty()) {
            return new DaySpan(today, today);
        }

        final int dateCount = sortedDates.size();
        DaySpan longest = new DaySpan(today, today);
        for (int i = 1; i < dateCount; i++) {
            // The blank days between two logged dates: the day after the earlier one, up to (excluding) the later.
            final DaySpan gap = new DaySpan(sortedDates.get(i - 1).plusDays(1L), sortedDates.get(i));
            longest = longer(longest, gap);
        }
        final DaySpan openGap = new DaySpan(sortedDates.getLast().plusDays(1L), today.plusDays(1L));
        return longer(longest, openGap);
    }

    /**
     * The longest run of consecutive performed days anywhere in the action's history. Ties keep the earliest run.
     */
    static DaySpan longestStreak(final List<LocalDate> sortedDates, final LocalDate today) {
        if (sortedDates.isEmpty()) {
            return new DaySpan(today, today);
        }

        final int dateCount = sortedDates.size();
        final LocalDate first = sortedDates.getFirst();
        DaySpan longest = new DaySpan(first, first.plusDays(1L));
        LocalDate runStart = first;
        for (int i = 1; i < dateCount; i++) {
            final LocalDate date = sortedDates.get(i);
            if (!date.equals(sortedDates.get(i - 1).plusDays(1L))) {
                runStart = date;
            }
            longest = longer(longest, new DaySpan(runStart, date.plusDays(1L)));
        }
        return longest;
    }

    private static DaySpan longer(final DaySpan current, final DaySpan candidate) {
        return Durations.days(candidate) > Durations.days(current) ? candidate : current;
    }
}
