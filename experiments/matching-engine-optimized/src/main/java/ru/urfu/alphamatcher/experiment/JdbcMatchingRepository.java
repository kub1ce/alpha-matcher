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
    static final int MAX_HYDRATED_ROWS=10000;
    private final boolean materializedUnion;

    public JdbcMatchingRepository() { this(true); }
    private JdbcMatchingRepository(boolean materializedUnion) { this.materializedUnion = materializedUnion; }
    /** Historical SQL comparator only. Rules and row mapping are identical. */
    static JdbcMatchingRepository baselineForComparison() { return new JdbcMatchingRepository(false); }
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
                 LIMIT %d
                """.formatted(MAX_HYDRATED_ROWS+1);
        try (var statement = connection.prepareStatement(sql)) {
            if(statement.getMaxRows()!=0 || statement.getLargeMaxRows()!=0)throw new java.sql.SQLNonTransientException("Operation hydration may be truncated by JDBC row limit");
            statement.setLong(1, id);
            try (var rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String purpose = rs.getString("payment_purpose");
                String status = rs.getString("status_code");
                boolean active = rs.getBoolean("status_active");
                Long linked = rs.getObject("contract_id", Long.class);
                Map<String, Party> parties = new LinkedHashMap<>();
                int rows=0;
                do {if(++rows>MAX_HYDRATED_ROWS)throw new java.sql.SQLDataException("Operation hydration row budget exceeded");addParty(rs, parties); } while (rs.next());
                return Optional.of(new Operation(id, purpose, status, active, linked, parties));
            }
        }
    }

    @Override
    public List<Candidate> candidates(Connection connection, PurposeParser.Features features) throws SQLException {
        var search=discover(connection,features);
        if(search.evidence().completeness()!=PurposeParser.Completeness.COMPLETE)
            throw new java.sql.SQLNonTransientException("Candidate search incomplete; use the structured discovery result");
        return search.candidates();
    }

    @Override
    public CandidateSearch.Result discover(Connection connection,PurposeParser.Features features)throws SQLException {
        var required=CandidateSearch.requiredKeys(features);
        boolean analysisComplete=CandidateSearch.interpretationsComplete(features);
        if(features.issues().stream().anyMatch(i->i.contains("MATCH_LIMIT")||i.contains("TIME_LIMIT")||i.startsWith("SIZE_LIMIT:"))
                || !ContractNumbers.variants(features.contractSearchNumbers()).complete())
            return new CandidateSearch.Result(List.of(),new CandidateSearch.Evidence(PurposeParser.Completeness.INCOMPLETE,
                    required,List.of(),List.of(),CandidateSearch.route(features),false,CandidateSearch.Origin.JDBC,"Parser or interpretation budget incomplete; SQL not executed"));
        Query query = candidateQuery(features, materializedUnion);
        if (query == null) return new CandidateSearch.Result(List.of(),new CandidateSearch.Evidence(
                required.isEmpty()&&analysisComplete?PurposeParser.Completeness.COMPLETE:PurposeParser.Completeness.INCOMPLETE,
                required,List.of(),List.of(),CandidateSearch.route(features),required.isEmpty(),CandidateSearch.Origin.JDBC,"No executable search plan"));
        try (var statement = connection.prepareStatement(query.sql())) {
            if(statement.getMaxRows()!=0 || statement.getLargeMaxRows()!=0)
                return new CandidateSearch.Result(List.of(),new CandidateSearch.Evidence(PurposeParser.Completeness.INCOMPLETE,
                        required,List.of(),query.parameters(),CandidateSearch.route(features),false,CandidateSearch.Origin.JDBC,"A JDBC row limit could truncate candidate paths"));
            for (int i = 0; i < query.parameters().size(); i++) statement.setString(i + 1, query.parameters().get(i));
            try (var rs = statement.executeQuery()) {
                Map<Long, String> numbers = new LinkedHashMap<>();
                Map<Long, Map<String, Party>> parties = new LinkedHashMap<>();
                int rows=0;
                while (rs.next()) {
                    rows++;
                    long id = rs.getLong("id");
                    numbers.put(id, rs.getString("contract_number"));
                    addParty(rs, parties.computeIfAbsent(id, ignored -> new LinkedHashMap<>()));
                }
                List<Candidate> result = new ArrayList<>();
                numbers.forEach((id, number) -> result.add(new Candidate(id, number, parties.get(id))));
                boolean complete=analysisComplete && rows<=MAX_HYDRATED_ROWS && new java.util.LinkedHashSet<>(required).equals(new java.util.LinkedHashSet<>(query.coveredKeys()));
                return new CandidateSearch.Result(result,new CandidateSearch.Evidence(complete?PurposeParser.Completeness.COMPLETE:PurposeParser.Completeness.INCOMPLETE,
                        required,query.coveredKeys(),query.parameters(),CandidateSearch.route(features),true,CandidateSearch.Origin.JDBC,
                        !analysisComplete?"Retained keys searched only; parser evidence incomplete, no absence/uniqueness proof":rows>MAX_HYDRATED_ROWS?"Hydrated row budget exceeded; candidate list is partial":"All planned keys bound and cursor exhausted within row budget"));
            }
        }
    }

    record Query(String sql, List<String> parameters,List<String> coveredKeys) { }

    static Query candidateQuery(PurposeParser.Features features) {
        return candidateQuery(features, true);
    }

    private static Query candidateQuery(PurposeParser.Features features, boolean materializedUnion) {
        if (!features.hasSearchKeys()) return null;
        if(features.issues().stream().anyMatch(issue->issue.contains("MATCH_LIMIT")||issue.contains("TIME_LIMIT")||issue.startsWith("SIZE_LIMIT:"))) return null;
        List<String> parameters = new ArrayList<>();
        var covered=new java.util.LinkedHashSet<String>();
        String filter;
        String candidateJoin = "";
        if (!features.contractNumbers().isEmpty()) {
            var variants = ContractNumbers.variants(features.contractSearchNumbers()).values();
            parameters.addAll(variants);
            variants.forEach(n->covered.add("CONTRACT:"+n));
            filter = "c.contract_number IN (" + placeholders(parameters.size()) + ")";
        } else {
            List<String> branches = new ArrayList<>();
            if (!features.inns().isEmpty()) {
                branches.add("SELECT cp.contract_id FROM contract_party cp JOIN client cl ON cl.id=cp.client_id WHERE cl.inn IN (" + placeholders(features.inns().size()) + ")");
                parameters.addAll(features.inns());
                features.inns().forEach(n->covered.add("INN:"+n));
                branches.add("SELECT cp.contract_id FROM contract_party cp WHERE cp.inn IN (" + placeholders(features.inns().size()) + ")");
                parameters.addAll(features.inns());
            }
            for (var name : features.names()) {
                if(NameNormalizer.initials(name.surface())) {
                    var initials=java.util.regex.Pattern.compile("(?iu)^([а-яё-]{1,80}) +([а-яё])\\. *([а-яё])\\.$").matcher(name.surface());
                    if(initials.matches()) {
                        branches.add("SELECT cp.contract_id FROM contract_party cp JOIN client cl ON cl.id=cp.client_id WHERE cl.last_name=? AND cl.first_name LIKE ? AND cl.middle_name LIKE ?");
                        parameters.addAll(List.of(initials.group(1),initials.group(2)+"%",initials.group(3)+"%"));
                        covered.add("NAME:"+NameNormalizer.key(name.surface()));
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
                    covered.add("NAME:"+NameNormalizer.key(name.surface()));
                }
            }
            if (!features.organizations().isEmpty()) {
                List<String> variants = new ArrayList<>();
                for (String organization : features.organizations()) {
                    int space = organization.indexOf(' ');
                    if (space < 0) continue;
                    covered.add("ORG:"+organization);
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
            String union = String.join(" UNION ", branches);
            if (materializedUnion) {
                // UNION DISTINCT cannot be merged: collect each matching contract once, then hydrate all roles.
                candidateJoin = "JOIN (" + union + ") AS hits ON hits.contract_id=c.id";
                filter = "1=1";
            } else filter = "c.id IN (" + union + ")";
        }
        String sql = """
                SELECT c.id, c.contract_number, r.code AS role_code, r.is_active AS role_active,
                       p.client_id, p.inn, cl.inn AS client_inn, p.name AS party_name, cl.name AS client_name,
                       cl.last_name, cl.first_name, cl.middle_name
                  FROM contract c
                  %s
                  LEFT JOIN contract_party p ON p.contract_id = c.id
                  LEFT JOIN contract_party_role r ON r.id = p.role_id
                  LEFT JOIN client cl ON cl.id = p.client_id
                 WHERE %s
                 ORDER BY c.id
                 LIMIT %d
                """.formatted(candidateJoin, filter,MAX_HYDRATED_ROWS+1);
        if (!candidateJoin.isEmpty()) sql=sql.replace("SELECT c.id,", "SELECT /*+ NO_BNL(r) */ c.id,").replace("ORDER BY c.id", "ORDER BY hits.contract_id");
        return new Query(sql, List.copyOf(parameters),List.copyOf(covered));
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
