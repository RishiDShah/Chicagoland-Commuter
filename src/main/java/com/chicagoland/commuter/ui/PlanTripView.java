package com.chicagoland.commuter.ui;

import com.chicagoland.commuter.SystemStatusService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

/** Home screen: enter a start and destination. Routing comes in a later milestone. */
@Route("")
@PageTitle("Chicagoland Commuter")
public class PlanTripView extends VerticalLayout {

    public PlanTripView(SystemStatusService status) {
        setMaxWidth("480px");
        getStyle().set("margin", "0 auto");

        var from = new TextField("From");
        from.setPlaceholder("e.g. Elgin, IL");
        from.setWidthFull();

        var to = new TextField("To");
        to.setPlaceholder("e.g. UIC");
        to.setWidthFull();

        var result = new Paragraph();

        var plan = new Button("Plan trip", e -> result.setText(
                "Routing from " + blankToDash(from.getValue()) + " to " + blankToDash(to.getValue())
                        + " is coming soon."));
        plan.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        plan.setWidthFull();

        var title = new H1("Chicagoland Commuter");
        title.getStyle().set("font-size", "var(--lumo-font-size-xxl)");

        add(title, from, to, plan, result, statusLine(status));
    }

    private static Span statusLine(SystemStatusService status) {
        String text;
        try {
            text = "Database connected · PostGIS " + status.postgisVersion();
        } catch (RuntimeException ex) {
            text = "Database not reachable. Is Docker Desktop running?";
        }
        var span = new Span(text);
        span.getStyle().set("font-size", "var(--lumo-font-size-s)").set("color", "var(--lumo-secondary-text-color)");
        return span;
    }

    private static String blankToDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }
}
