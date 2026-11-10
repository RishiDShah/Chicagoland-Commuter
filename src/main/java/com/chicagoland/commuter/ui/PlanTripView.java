package com.chicagoland.commuter.ui;

import com.chicagoland.commuter.SystemStatusService;
import com.chicagoland.commuter.gtfs.GtfsRefreshService;
import com.chicagoland.commuter.gtfs.StopSearchService;
import com.chicagoland.commuter.gtfs.StopSearchService.StopSuggestion;
import com.chicagoland.commuter.routing.TripPlanner;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
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
import com.vaadin.flow.shared.Registration;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Home screen: pick a start, a destination and a time, and see the best trips. */
@Route("")
@PageTitle("Chicagoland Commuter")
public class PlanTripView extends VerticalLayout {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    private final SystemStatusService system;
    private final GtfsRefreshService schedules;
    private final StatusRow databaseRow = new StatusRow();
    private final StatusRow scheduleRow = new StatusRow();
    private final Div status = new Div(databaseRow, scheduleRow);
    private final DateTimePicker leaveAt = new DateTimePicker("Leave at");
    private final Button useNow = new Button("Use current time");
    /** True until the person picks a time themselves; while true, "Leave at" follows the clock. */
    private boolean followNow = true;
    private Registration pollRegistration;

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

        leaveAt.addClassName("field");
        leaveAt.setStep(Duration.ofMinutes(1));
        setLeaveAtToNow();
        leaveAt.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                followNow = false;   // planning ahead: stop following the clock
                useNow.setVisible(true);
            }
        });
        useNow.addClassName("link-btn");
        useNow.setVisible(false);
        useNow.addClickListener(e -> {
            followNow = true;
            useNow.setVisible(false);
            setLeaveAtToNow();
        });

        var results = new Div();
        results.addClassName("results");

        var plan = new Button("Plan trip", e -> {
            results.removeAll();
            if (followNow) {
                setLeaveAtToNow();
            }
            if (from.isEmpty() || to.isEmpty()) {
                results.add(message("Choose a station in both boxes."));
                return;
            }
            if (leaveAt.isEmpty()) {
                results.add(message("Choose a date and time to leave."));
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

        // Only shown when something is wrong (database down, schedules not loaded yet)
        status.addClassName("status");
        status.setVisible(false);

        add(header, from, to, leaveAt, useNow, plan, results, status);
    }

    @Override
    protected void onAttach(AttachEvent event) {
        refreshStatus();
        // Check back every 15 seconds: keeps "Leave at" on the current time and updates the status
        event.getUI().setPollInterval(15_000);
        pollRegistration = event.getUI().addPollListener(e -> {
            if (followNow) {
                setLeaveAtToNow();
            }
            refreshStatus();
        });
    }

    @Override
    protected void onDetach(DetachEvent event) {
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        event.getUI().setPollInterval(-1);
    }

    private void setLeaveAtToNow() {
        var now = LocalDateTime.now(CHICAGO).withSecond(0).withNano(0);
        if (!now.equals(leaveAt.getValue())) {
            leaveAt.setValue(now);
        }
    }

    /** Shows the status box only when something needs attention; hides it when everything works. */
    private void refreshStatus() {
        try {
            system.postgisVersion();
            databaseRow.setVisible(false);
        } catch (RuntimeException ex) {
            databaseRow.set(StatusRow.State.BAD, "Database not reachable. Is Docker Desktop running?");
            databaseRow.setVisible(true);
            scheduleRow.setVisible(false);
            status.setVisible(true);
            return;
        }
        var feeds = schedules.status();
        if (feeds.ready()) {
            scheduleRow.setVisible(false);
        } else if (feeds.loading()) {
            scheduleRow.set(StatusRow.State.WAITING,
                    "Loading CTA, Metra and Pace schedules… The first time takes a few minutes.");
            scheduleRow.setVisible(true);
        } else if (feeds.error() != null) {
            scheduleRow.set(StatusRow.State.BAD, "Couldn't load schedules: " + feeds.error());
            scheduleRow.setVisible(true);
        } else {
            scheduleRow.set(StatusRow.State.WAITING, "No schedules loaded yet.");
            scheduleRow.setVisible(true);
        }
        status.setVisible(scheduleRow.isVisible());
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

    /** A line in the status box with a dot: gray while waiting, red when something's wrong. */
    private static final class StatusRow extends Div {
        enum State { WAITING, BAD }

        private final Span dot = new Span();
        private final Span text = new Span();

        StatusRow() {
            addClassName("status-row");
            dot.addClassName("dot");
            add(dot, text);
        }

        void set(State state, String message) {
            dot.setClassName("dot");
            if (state == State.BAD) {
                dot.addClassName("bad");
            }
            text.setText(message);
        }
    }
}
