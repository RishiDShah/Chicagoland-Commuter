package com.chicagoland.commuter.ui;

import com.chicagoland.commuter.SystemStatusService;
import com.chicagoland.commuter.gtfs.GtfsRefreshService;
import com.chicagoland.commuter.gtfs.StopSearchService;
import com.chicagoland.commuter.gtfs.StopSearchService.StopSuggestion;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.CallbackDataProvider;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/** Home screen: pick a start and destination station. Routing comes in a later milestone. */
@Route("")
@PageTitle("Chicagoland Commuter")
public class PlanTripView extends VerticalLayout {

    private static final DateTimeFormatter UPDATED = DateTimeFormatter.ofPattern("MMM d, yyyy")
            .withZone(ZoneId.of("America/Chicago"));

    private final SystemStatusService system;
    private final GtfsRefreshService schedules;
    private final Span databaseLine = statusSpan();
    private final Span scheduleLine = statusSpan();

    public PlanTripView(SystemStatusService system, GtfsRefreshService schedules, StopSearchService stops) {
        this.system = system;
        this.schedules = schedules;
        setMaxWidth("480px");
        getStyle().set("margin", "0 auto");

        var title = new H1("Chicagoland Commuter");
        title.getStyle().set("font-size", "var(--lumo-font-size-xxl)");

        var from = stopPicker("From", "e.g. Bartlett", stops);
        var to = stopPicker("To", "e.g. UIC-Halsted", stops);

        var result = new Paragraph();
        var plan = new Button("Plan trip", e -> {
            if (from.isEmpty() || to.isEmpty()) {
                result.setText("Choose a station or stop in both boxes.");
            } else {
                result.setText("Routing from " + from.getValue().label() + " to " + to.getValue().label()
                        + " is coming soon.");
            }
        });
        plan.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        plan.setWidthFull();

        add(title, from, to, plan, result, databaseLine, scheduleLine);
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

    /** Updates the status lines. Returns true once there is nothing left to wait for. */
    private boolean refreshStatus() {
        try {
            databaseLine.setText("Database connected · PostGIS " + system.postgisVersion());
        } catch (RuntimeException ex) {
            databaseLine.setText("Database not reachable. Is Docker Desktop running?");
            scheduleLine.setText("");
            return false;
        }
        var status = schedules.status();
        if (status.ready()) {
            var n = NumberFormat.getIntegerInstance();
            scheduleLine.setText("Schedules: " + status.feeds().stream()
                    .map(f -> f.name() + " " + n.format(f.stops()) + " stops")
                    .collect(Collectors.joining(" · "))
                    + " · updated " + UPDATED.format(status.feeds().getFirst().importedAt())
                    + (status.loading() ? " · checking for updates…" : ""));
        } else if (status.loading()) {
            scheduleLine.setText("Loading CTA, Metra and Pace schedules… The first time takes a few minutes.");
        } else if (status.error() != null) {
            scheduleLine.setText("Couldn't load schedules: " + status.error());
        } else {
            scheduleLine.setText("No schedules loaded yet.");
        }
        return !status.loading();
    }

    private static ComboBox<StopSuggestion> stopPicker(String label, String placeholder, StopSearchService stops) {
        var box = new ComboBox<StopSuggestion>(label);
        box.setPlaceholder(placeholder);
        box.setWidthFull();
        box.setItemLabelGenerator(StopSuggestion::label);
        // Loads matches from the database as you type, a page at a time
        box.setItems((CallbackDataProvider.FetchCallback<StopSuggestion, String>) query ->
                stops.search(query.getFilter().orElse(""), query.getOffset(), query.getLimit()).stream());
        return box;
    }

    private static Span statusSpan() {
        var span = new Span();
        span.getStyle().set("font-size", "var(--lumo-font-size-s)").set("color", "var(--lumo-secondary-text-color)");
        return span;
    }
}
