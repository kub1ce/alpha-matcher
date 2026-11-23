package ru.urfu.alphamatcher.ui;

import java.util.Map;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;

/** Internal links that retain the source of a client card. */
final class ClientNavigation {
    private ClientNavigation() { }

    static RouterLink link(String caption, String clientId, String source) {
        RouterLink link = new RouterLink(caption, ClientDetailView.class,
                new RouteParameters("id", clientId));
        link.setQueryParameters(QueryParameters.simple(Map.of("from", source)));
        return link;
    }

    static RouterLink back(String source) {
        if (source != null && source.matches("contract/(?:AG-2026-[0-9]{3}|DB-[0-9]{1,19})"))
            return new RouterLink("", ContractDetailView.class,
                    new RouteParameters("id", source.substring("contract/".length())));
        if (source != null && source.matches("operation/[A-Za-z0-9_-]{1,64}"))
            return new RouterLink("", OperationDetailView.class,
                    new RouteParameters("id", source.substring("operation/".length())));
        return new RouterLink("", ClientsView.class);
    }
}
