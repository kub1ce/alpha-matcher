package ru.urfu.alphamatcher.experiment;

import java.util.List;
import java.util.Map;

public final class MatchingModel {
    private MatchingModel() { }

    // ALREADY_MANUAL is reserved: the real SQL schema has no manual-queue state.
    public enum Status { SAFE, REVIEW, NO_CONTRACT, ALREADY_MANUAL, SKIPPED }

    public record Party(Long clientId, String inn, String clientInn, boolean activeRole,
                        String name, String clientName, String lastName, String firstName, String middleName) {
        public Party(Long clientId, String inn, String clientInn, boolean activeRole) {
            this(clientId, inn, clientInn, activeRole, null, null, null, null, null);
        }
        public String personKey() {
            return lastName != null && firstName != null && middleName != null
                    ? NameNormalizer.key(lastName + " " + firstName + " " + middleName)
                    : name == null || !name.matches("(?iu).+ .+ .*(?:ич|овна|евна)") ? "" : NameNormalizer.key(name);
        }
        public String organizationKey() { return NameNormalizer.organizationKey(clientName == null ? name : clientName); }
        public String effectiveInn() {
            return inn == null || inn.isBlank() ? clientInn : inn;
        }
        public boolean consistent() {
            return activeRole && Inn.valid(effectiveInn())
                    && (inn == null || inn.isBlank() || Inn.valid(inn))
                    && (clientInn == null || clientInn.isBlank() || Inn.valid(clientInn))
                    && (inn == null || inn.isBlank() || clientInn == null || clientInn.isBlank()
                        || inn.equals(clientInn));
        }
    }

    public record Operation(long id, String purpose, String status, boolean activeStatus,
                            Long linkedContractId, Map<String, Party> parties) {
        public Operation { parties = Map.copyOf(parties); }
    }
    public record Candidate(long id, String number, Map<String, Party> parties) {
        public Candidate { parties = Map.copyOf(parties); }
    }
    public record Check(String code, boolean passed, String explanation) { }
    public record Decision(long operationId, Status status, Long proposedContractId,
                           List<Long> candidateIds, PurposeParser.Features features, List<Check> checks,CandidateSearch.Evidence searchEvidence) {
        public Decision(long operationId,Status status,Long proposedContractId,List<Long> ids,PurposeParser.Features features,List<Check> checks){
            this(operationId,status,proposedContractId,ids,features,checks,CandidateSearch.unknown(features,List.of(),"Legacy data record").evidence());
        }
        public Decision {
            candidateIds = List.copyOf(candidateIds);
            checks = List.copyOf(checks);
            if ((status == Status.SAFE) != (proposedContractId != null))
                throw new IllegalArgumentException("Только SAFE содержит предложенный договор");
        }
    }
}
