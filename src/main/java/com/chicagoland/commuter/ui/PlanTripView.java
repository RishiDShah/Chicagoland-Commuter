package com.chicagoland.commuter.ui;

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

    private final DateTimePicker leaveAt = new DateTimePicker("Leave at");
    private final Button useNow = new Button("Use current time");
    /** True until the person picks a time themselves; while true, "Leave at" follows the clock. */
    private boolean followNow = true;
    private Registration pollRegistration;

    public PlanTripView(StopSearchService stops, TripPlanner planner) {
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

        add(header, from, to, leaveAt, useNow, plan, results);
    }

    @Override
    protected void onAttach(AttachEvent event) {
        // Check back every 15 seconds to keep "Leave at" on the current time
        event.getUI().setPollInterval(15_000);
        pollRegistration = event.getUI().addPollListener(e -> {
            if (followNow) {
                setLeaveAtToNow();
            }
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
}
