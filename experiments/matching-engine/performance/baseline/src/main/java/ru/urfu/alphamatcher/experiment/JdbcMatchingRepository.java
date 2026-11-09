package ru.urfu.alphamatcher.experiment;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.urfu.alphamatcher.experiment.MatchingModel.Candidate;
import ru.urfu.alphamatcher.experiment.MatchingModel.Operation;
import ru.urfu.alphamatcher.experiment.MatchingModel.Party;

/** SELECT-only access to the actual project schema; never updates confirmed links or candidates. */
public final class JdbcMatchingRepository implements MatchingRepository {
    @Override
    public Optional<Operation> operation(Connection connection, long id) throws SQLException {
        String sql = """
                SELECT o.id, o.payment_purpose, o.contract_id, s.code AS status_code,
                       s.is_active AS status_active, r.code AS role_code, r.is_active AS role_active,
                       p.client_id, p.inn, cl.inn AS client_inn, p.name AS party_name, cl.name AS client_name,
                       cl.last_name, cl.first_name, cl.middle_name
                  FROM operation o
                  JOIN operation_status s ON s.id = o.status_id
                  LEFT JOIN operation_party p ON p.operation_id = o.id
                  LEFT JOIN operation_party_role r ON r.id = p.role_id
                  LEFT JOIN client cl ON cl.id = p.client_id
                 WHERE o.id = ?
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (var rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String purpose = rs.getString("payment_purpose");
                String status = rs.getString("status_code");
                boolean active = rs.getBoolean("status_active");
                Long linked = rs.getObject("contract_id", Long.class);
                Map<String, Party> parties = new LinkedHashMap<>();
                do { addParty(rs, parties); } while (rs.next());
                return Optional.of(new Operation(id, purpose, status, active, linked, parties));
            }
        }
    }

    @Override
    public List<Candidate> candidates(Connection connection, PurposeParser.Features features) throws SQLException {
        Query query = candidateQuery(features);
        if (query == null) return List.of();
        try (var statement = connection.prepareStatement(query.sql())) {
            for (int i = 0; i < query.parameters().size(); i++) statement.setString(i + 1, query.parameters().get(i));
            try (var rs = statement.executeQuery()) {
                Map<Long, String> numbers = new LinkedHashMap<>();
                Map<Long, Map<String, Party>> parties = new LinkedHashMap<>();
                while (rs.next()) {
                    long id = rs.getLong("id");
                    numbers.put(id, rs.getString("contract_number"));
                    addParty(rs, parties.computeIfAbsent(id, ignored -> new LinkedHashMap<>()));
                }
                List<Candidate> result = new ArrayList<>();
                numbers.forEach((id, number) -> result.add(new Candidate(id, number, parties.get(id))));
                return List.copyOf(result);
            }
        }
    }

    record Query(String sql, List<String> parameters) { }

    static Query candidateQuery(PurposeParser.Features features) {
        if (!features.hasSearchKeys()) return null;
        if(features.issues().stream().anyMatch(issue->issue.contains("MATCH_LIMIT")||issue.contains("TIME_LIMIT")||issue.contains("SIZE_LIMIT"))) return null;
        List<String> parameters = new ArrayList<>();
        String filter;
        if (!features.contractNumbers().isEmpty()) {
            var variants = ContractNumbers.variants(features.contractNumbers()).values();
            parameters.addAll(variants);
            filter = "c.contract_number IN (" + placeholders(parameters.size()) + ")";
        } else {
            List<String> branches = new ArrayList<>();
            if (!features.inns().isEmpty()) {
                branches.add("SELECT cp.contract_id FROM contract_party cp JOIN client cl ON cl.id=cp.client_id WHERE cl.inn IN (" + placeholders(features.inns().size()) + ")");
                parameters.addAll(features.inns());
                branches.add("SELECT cp.contract_id FROM contract_party cp WHERE cp.inn IN (" + placeholders(features.inns().size()) + ")");
                parameters.addAll(features.inns());
            }
            for (var name : features.names()) {
                if(NameNormalizer.initials(name.surface())) {
                    var initials=java.util.regex.Pattern.compile("(?iu)^([а-яё-]{1,80}) +([а-яё])\\. *([а-яё])\\.$").matcher(name.surface());
                    if(initials.matches()) {
                        branches.add("SELECT cp.contract_id FROM contract_party cp JOIN client cl ON cl.id=cp.client_id WHERE cl.last_name=? AND cl.first_name LIKE ? AND cl.middle_name LIKE ?");
                        parameters.addAll(List.of(initials.group(1),initials.group(2)+"%",initials.group(3)+"%"));
                    }
                    continue;
                }
                for(String hypothesis:NameNormalizer.hypotheses(name.surface())) {
                    String[] parts = hypothesis.split(" ");
                    if (parts.length != 3 || NameNormalizer.initials(name.surface())) continue;
                    branches.add("SELECT cp.contract_id FROM contract_party cp JOIN client cl ON cl.id=cp.client_id WHERE cl.last_name=? AND cl.first_name=? AND cl.middle_name=?");
                    parameters.addAll(List.of(parts));
                    branches.add("SELECT cp.contract_id FROM contract_party cp WHERE cp.name=?");
                    parameters.add(hypothesis);
                }
            }
            if (!features.organizations().isEmpty()) {
                List<String> variants = new ArrayList<>();
                for (String organization : features.organizations()) {
                    int space = organization.indexOf(' ');
                    if (space < 0) continue;
                    String prefix = organization.substring(0, space), name = organization.substring(space + 1);
                    variants.addAll(List.of(organization, prefix + " «" + name + "»", prefix + " \"" + name + "\""));
                    String full=switch(prefix) { case "ооо" -> "общество с ограниченной ответственностью"; case "ао" -> "акционерное общество"; case "пао" -> "публичное акционерное общество"; default -> null; };
                    if(full!=null) variants.addAll(List.of(full+" "+name,full+" «"+name+"»",full+" \""+name+"\""));
                }
                if (!variants.isEmpty()) {
                    branches.add("SELECT cp.contract_id FROM contract_party cp JOIN client cl ON cl.id=cp.client_id WHERE cl.name IN (" + placeholders(variants.size()) + ")");
                    parameters.addAll(variants);
                    branches.add("SELECT cp.contract_id FROM contract_party cp WHERE cp.name IN (" + placeholders(variants.size()) + ")");
                    parameters.addAll(variants);
                }
            }
            if (branches.isEmpty()) return null;
            filter = "c.id IN (" + String.join(" UNION ", branches) + ")";
        }
        String sql = """
                SELECT c.id, c.contract_number, r.code AS role_code, r.is_active AS role_active,
                       p.client_id, p.inn, cl.inn AS client_inn, p.name AS party_name, cl.name AS client_name,
                       cl.last_name, cl.first_name, cl.middle_name
                  FROM contract c
                  LEFT JOIN contract_party p ON p.contract_id = c.id
                  LEFT JOIN contract_party_role r ON r.id = p.role_id
                  LEFT JOIN client cl ON cl.id = p.client_id
                 WHERE %s
                 ORDER BY c.id
                """.formatted(filter);
        return new Query(sql, List.copyOf(parameters));
    }

    private static String placeholders(int count) { return String.join(",", java.util.Collections.nCopies(count, "?")); }

    private static void addParty(ResultSet rs, Map<String, Party> parties) throws SQLException {
        String role = rs.getString("role_code");
        if (role != null) parties.put(role, new Party(rs.getObject("client_id", Long.class),
                rs.getString("inn"), rs.getString("client_inn"), rs.getBoolean("role_active"),
                rs.getString("party_name"), rs.getString("client_name"),
                rs.getString("last_name"), rs.getString("first_name"), rs.getString("middle_name")));
    }
}
