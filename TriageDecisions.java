import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.response.YesNoAnswer;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.model.typesafe.TypeSafeDecisionModel;
import dev.langchain4j.service.V;
import dev.langchain4j.service.decision.Choice;
import dev.langchain4j.service.decision.Decide;
import dev.langchain4j.service.decision.DecisionServices;
import dev.langchain4j.service.decision.Scale;

/**
 * The same triage as triage-questions.json, but through LangChain4j's decision API (experimental,
 * 1.21.0). The questions live in Java types: a record field per question, enum constants as the
 * options, @Description as the criteria. One assess() call answers all three in a single request.
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

    /** Ordered from lowest to highest, the order defines the scale. */
    public enum Severity {
        @Description("A question or a request, nothing is broken")
        ROUTINE,
        @Description("Something is broken but there is a workaround or it can wait")
        DEGRADED,
        @Description("Business is blocked: an outage, failing payments or a legal deadline")
        BLOCKING
    }

    public record Assessment(
            @Decide("Is this an actionable support request, rather than small talk or a greeting?")
            YesNoAnswer actionable,
            @Decide("Which team should handle this support ticket?")
            Choice<Department> department,
            @Decide("How severe is the reported issue?")
            Scale<Severity> severity) {
    }

    public interface Triage {
        Assessment assess(@V("ticket") String ticket);
    }

    private final Triage triage;

    public TriageDecisions(String baseUrl, String modelName) {
        DecisionModel model = TypeSafeDecisionModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .build();
        this.triage = DecisionServices.create(Triage.class, model);
    }

    public Assessment assess(String ticket) {
        return triage.assess(ticket);
    }
}
