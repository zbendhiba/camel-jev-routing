import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.model.typesafe.TypeSafeDecisionModel;
import dev.langchain4j.service.decision.Choice;
import dev.langchain4j.service.decision.Decide;
import dev.langchain4j.service.V;
import dev.langchain4j.service.decision.DecisionServices;

/**
 * The same triage decision as triage-questions.json, but through LangChain4j's decision API
 * (experimental, 1.21.0). The question lives in Java types: the enum constants are the options,
 * their @Description is the criteria, and the answer comes back as a typed Choice instead of an
 * exchange property.
 */
public class TriageDecisions {

    public enum Department {
        @Description("The product is not working: a bug, an outage, API errors, a webhook that stopped firing, responses that got slow")
        TECHNICAL,
        @Description("Anything about money: an invoice, a refund, a charge, a price, a quote, a plan, a contract renewal")
        COMMERCIAL,
        @Description("Data protection and compliance: deleting personal data, a GDPR or privacy request, terms of service, a security questionnaire, an audit")
        LEGAL
    }

    public interface Triage {
        @Decide("Which team should handle this support ticket?")
        Choice<Department> department(@V("ticket") String ticket);
    }

    private final Triage triage;

    public TriageDecisions(String baseUrl, String modelName) {
        DecisionModel model = TypeSafeDecisionModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .build();
        this.triage = DecisionServices.create(Triage.class, model);
    }

    public Choice<Department> decide(String ticket) {
        return triage.department(ticket);
    }
}
