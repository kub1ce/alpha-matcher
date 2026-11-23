package ru.urfu.alphamatcher.ui;

import java.util.Map;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;

/** Explicit, internal source context for operation cards. Unknown sources fall back to the registry. */
final class OperationNavigation {
    private OperationNavigation() { }

    static RouterLink link(String caption, String operationId, String source) {
        RouterLink link = new RouterLink(caption, OperationDetailView.class,
                new RouteParameters("id", operationId));
        link.setQueryParameters(QueryParameters.simple(Map.of("from", source)));
        return link;
    }

    static RouterLink back(String source) {
        if (source == null) return new RouterLink("", OperationsView.class);
        return switch (source) {
            case "home" -> new RouterLink("", HomeView.class);
            case "operations" -> new RouterLink("", OperationsView.class);
            case "manual" -> new RouterLink("", ManualReviewView.class);
            case "audit" -> new RouterLink("", AuditLogView.class);
            case "runs" -> new RouterLink("", RunHistoryView.class);
            case "matching" -> new RouterLink("", MatchingView.class);
            default -> parameterizedBack(source);
        };
    }

    private static RouterLink parameterizedBack(String source) {
        if (source.matches("manual/[A-Za-z0-9_-]{1,64}"))
            return new RouterLink("", ManualReviewDetailView.class,
                    new RouteParameters("id", source.substring("manual/".length())));
        if (source.matches("contract/(?:AG-2026-[0-9]{3}|DB-[0-9]{1,19})"))
            return new RouterLink("", ContractDetailView.class,
                    new RouteParameters("id", source.substring("contract/".length())));
        if (source.matches("client/(?:CL-[0-9]{3}|DB-CL-[0-9]{1,19})"))
            return new RouterLink("", ClientDetailView.class,
                    new RouteParameters("id", source.substring("client/".length())));
        return new RouterLink("", OperationsView.class);
    }
}
