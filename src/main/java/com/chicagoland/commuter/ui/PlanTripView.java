package com.chicagoland.commuter.ui;

import com.chicagoland.commuter.SystemStatusService;
import com.chicagoland.commuter.gtfs.GtfsRefreshService;
import com.chicagoland.commuter.gtfs.StopSearchService;
import com.chicagoland.commuter.gtfs.StopSearchService.StopSuggestion;
import com.chicagoland.commuter.routing.TripPlanner;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.CallbackDataProvider;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/** Home screen: pick a start, a destination and a time, and see the best trips. */
@Route("")
@PageTitle("Chicagoland Commuter")
public class PlanTripView extends VerticalLayout {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final DateTimeFormatter UPDATED = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(CHICAGO);

    private final SystemStatusService system;
    private final GtfsRefreshService schedules;
    private final StatusRow databaseRow = new StatusRow();
    private final StatusRow scheduleRow = new StatusRow();

    public PlanTripView(SystemStatusService system, GtfsRefreshService schedules, StopSearchService stops,
                        TripPlanner planner) {
        this.system = system;
        this.schedules = schedules;
        addClassName("screen");
        setPadding(false);
        setSpacing(false);
        setDefaultHorizontalComponentAlignment(FlexComponent.Alignment.STRETCH);

        var title = new H1("Chicagoland Commuter");
        title.addClassName("title");
        var subtitle = new Paragraph("Plan your trip across Metra, CTA and Pace.");
        subtitle.addClassName("muted");
        subtitle.getStyle().set("margin", "0");
        var header = new Div(title, subtitle);
        header.addClassName("header");

        var from = stopPicker("From", "Search a station, e.g. Bartlett", stops);
        var to = stopPicker("To", "Search a station, e.g. UIC-Halsted", stops);

        var leaveAt = new DateTimePicker("Leave at");
        leaveAt.addClassName("field");
        leaveAt.setValue(LocalDateTime.now(CHICAGO).withSecond(0).withNano(0));
        leaveAt.setStep(Duration.ofMinutes(5));

        var results = new Div();
        results.addClassName("results");

        var plan = new Button("Plan trip", e -> {
            results.removeAll();
            if (from.isEmpty() || to.isEmpty() || leaveAt.isEmpty()) {
                results.add(message("Choose a station in both boxes."));
                return;
            }
            var result = planner.plan(from.getValue().stopId(), to.getValue().stopId(), leaveAt.getValue(), 3);
            if (result.options().isEmpty()) {
                results.add(message(result.message()));
            }
            for (int i = 0; i < result.options().size(); i++) {
                results.add(new JourneyCard(result.options().get(i), i == 0));
            }
        });
        plan.addClassName("btn-primary");
        plan.setDisableOnClick(true);
        plan.addClickListener(e -> plan.setEnabled(true));

        var status = new Div(databaseRow, scheduleRow);
        status.addClassName("status");

        add(header, from, to, leaveAt, plan, results, status);
    }

    @Override
    protected void onAttach(AttachEvent event) {
        refreshStatus();
        // While schedules are loading, check back every 5 seconds so the status updates by itself
        event.getUI().setPollInterval(5000);
        event.getUI().addPollListener(e -> {
            if (refreshStatus()) {
                e.getSource().setPollInterval(-1);
            }
        });
    }

    /** Updates the status box. Returns true once there is nothing left to wait for. */
    private boolean refreshStatus() {
        try {
            databaseRow.set(StatusRow.State.OK, "Database connected · PostGIS " + system.postgisVersion());
        } catch (RuntimeException ex) {
            databaseRow.set(StatusRow.State.BAD, "Database not reachable. Is Docker Desktop running?");
            scheduleRow.setVisible(false);
            return false;
        }
        scheduleRow.setVisible(true);
        var status = schedules.status();
        if (status.ready()) {
            var n = NumberFormat.getIntegerInstance();
            scheduleRow.set(StatusRow.State.OK, "Schedules: " + status.feeds().stream()
                    .map(f -> f.name() + " " + n.format(f.stops()) + " stops")
                    .collect(Collectors.joining(" · "))
                    + " · updated " + UPDATED.format(status.feeds().getFirst().importedAt())
                    + (status.loading() ? " · checking for updates…" : ""));
        } else if (status.loading()) {
            scheduleRow.set(StatusRow.State.WAITING,
                    "Loading CTA, Metra and Pace schedules… The first time takes a few minutes.");
        } else if (status.error() != null) {
            scheduleRow.set(StatusRow.State.BAD, "Couldn't load schedules: " + status.error());
        } else {
            scheduleRow.set(StatusRow.State.WAITING, "No schedules loaded yet.");
        }
        return !status.loading();
    }

    private static ComboBox<StopSuggestion> stopPicker(String label, String placeholder, StopSearchService stops) {
        var box = new ComboBox<StopSuggestion>(label);
        box.addClassName("field");
        box.setPlaceholder(placeholder);
        box.setItemLabelGenerator(StopSuggestion::label);
        // Loads matches from the database as you type, a page at a time
        box.setItems((CallbackDataProvider.FetchCallback<StopSuggestion, String>) query ->
                stops.search(query.getFilter().orElse(""), query.getOffset(), query.getLimit()).stream());
        return box;
    }

    private static Paragraph message(String text) {
        var p = new Paragraph(text);
        p.addClassName("message");
        return p;
    }

    /** A line in the status box with a colored dot: orange when fine, red when something's wrong. */
    private static final class StatusRow extends Div {
        enum State { OK, WAITING, BAD }

        private final Span dot = new Span();
        private final Span text = new Span();

        StatusRow() {
            addClassName("status-row");
            dot.addClassName("dot");
            add(dot, text);
        }

        void set(State state, String message) {
            dot.setClassName("dot");
            if (state == State.OK) {
                dot.addClassName("ok");
            } else if (state == State.BAD) {
                dot.addClassName("bad");
            }
            text.setText(message);
        }
    }
}
