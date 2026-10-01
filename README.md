# Routing tickets with a System One decision model

Apache Camel routes support tickets using **typed decisions** instead of an LLM's prose.

The model gets a ticket and three closed questions: *is this actionable? which team? how severe?*
It answers with labels, probabilities and calibrated confidence, in one call. The answer **is**
the route name, one `toD:` does the rest. A fast, hallucination-free router in front of a route.

```
POST /ticket ──> one model call ──> actionable < 0.5 ? ──> human review
                                          │
                                          └──> technical / commercial / legal  (+ severity 0..2)
```

## Why two flavors

I work on both Apache Camel and LangChain4j. Both grew System One support the same month, in
different shapes: Camel got the `semantic` language, LangChain4j got an experimental decision API.
So I built the same triage with both flavors, measured what differs, and wrote it down. This repo
is what I learned.

| Route | Decision step | Questions live in |
|---|---|---|
| `POST /ticket` | The `semantic` language from `camel-semantic` (preview in 4.23) | `ticket-routing.camel.yaml`, next to the route |
| `POST /ticket-l4j` | A Camel bean using LangChain4j's experimental decision API | `TriageDecisions.java`, as a typed record |

Two things make both of them different from asking an LLM to classify:

- The answer is **one of the labels you declared**. Not "Technical." or "I think technical" or a
  paragraph. There is nothing to parse and nothing to hallucinate.
- You get **calibrated probabilities**, so the route can send tickets it should not route to a
  human instead of guessing.

## What is a System One model?

[TypeSafe AI](https://typesafe.ai) launched **Jev** in September 2026. Instead of generating text,
it returns structured decisions in a single forward pass. Three primitives, and this repo uses all
of them:

| Primitive | Returns | Used here for |
|---|---|---|
| `noul` | a calibrated yes/no probability | *is this actionable?* (the human-review gate) |
| `choice` | one of up to 255 labeled options, with probabilities | *which team?* |
| `score` | a position on an ordered rubric | *how severe?* |

Jev is early access only right now. So this sample runs against **tev1:4b** on Ollama: an open
4.2B decision model that speaks the same wire protocol, which Ollama serves natively at
`/v1/systemone` since 0.35. Runs on your laptop, no API key. Switching to Jev is three properties.

## Prerequisites

- Camel CLI: `jbang app install camel@apache/camel`
- Ollama 0.35+

`camel-semantic` and `camel-typesafe-ai` are new in 4.23.0, which is not released yet. So
`application.properties` pins `camel.jbang.camel-version = 4.23.0-SNAPSHOT` and adds the snapshot
repositories through `camel.jbang.repos`. Once 4.23.0 ships, drop the repo line and pin the
release. System One models launched three weeks ago: preview and experimental labels are the
normal state of this whole stack.

## Run tev1

```bash
ollama pull tev1:4b
```

Ollama implements `POST /v1/systemone` itself, the same path and payload as Jev. Check it answers:

```bash
curl -f http://127.0.0.1:11434/v1/systemone \
  -H 'Content-Type: application/json' \
  -d '{"state":"the api is down","model":"tev1:4b",
       "questions":{"q":{"type":"noul","instructions":"Is this a technical problem?"}}}'
```

The first call loads 4.5 GB into memory, so give it a minute.

## Run the routes

```bash
camel run *
```

That starts both variants in one JVM. To run one at a time, name the files. Both need
`departments.camel.yaml`, where the department queues live (`application.properties` is picked up
automatically):

```bash
camel run ticket-routing.camel.yaml departments.camel.yaml
camel run ticket-routing-l4j.camel.yaml TriageDecisions.java departments.camel.yaml
```

Then send tickets. The same eight work on both paths, swap `/ticket` for `/ticket-l4j`:

```bash
curl -d 'Your API has been timing out for the last hour.' localhost:8080/ticket
curl -d 'The webhook integration returns 500 since your last deploy.' localhost:8080/ticket
curl -d 'My payouts have been failing for 3 days and I was double charged.' localhost:8080/ticket
curl -d 'Please delete all personal data you hold about me under GDPR.' localhost:8080/ticket
curl -d 'We are a 200-person team, what does enterprise pricing look like?' localhost:8080/ticket
curl -d 'We need your SOC 2 report before we can renew.' localhost:8080/ticket
curl -d 'hello' localhost:8080/ticket
curl -d 'thanks, have a nice weekend' localhost:8080/ticket
```

The first six route: technical, technical, commercial, legal, commercial, legal. The last two go
to human review. The answers carry the numbers:

```
Routed to commercial (confidence 0.86, severity 1.95)
Sent to human review (actionable 0.004, department technical)
```

## Start with one question

Both flavors are simplest with a single `choice` question. This was the first version of this
repo, and it is the shape to start from.

**The semantic language.** The question is declared next to the route, provider-independent. The
`camel-typesafe-ai` adapter is discovered on the classpath and handles the wire protocol:

```yaml
- semantic:
    question:
      department:
        type: choice
        instructions: Which team should handle this support ticket?
        criteria: { technical: "...", commercial: "...", legal: "..." }

- setVariable:
    name: department
    expression:
      language:
        language: semantic
        expression: ref:department
```

**LangChain4j.** The question is an interface, the criteria are `@Description` on an enum, the
answer is typed:

```java
public enum Department {
    @Description("The product is not working: a bug, an outage, ...")
    TECHNICAL, ...
}

public interface Triage {
    @Decide("Which team should handle this support ticket?")
    Choice<Department> department(@V("ticket") String ticket);
}

Triage triage = DecisionServices.create(Triage.class, model);
```

## Then ask everything in one call

One request can carry several questions, and both flavors batch them. This is what the repo
actually ships: `noul` + `choice` + `score`, one model call per ticket.

**The semantic language**: `refs:` instead of `ref:` returns the decisions as a map, and the full
results land in the `CamelSemanticResults` property:

```yaml
- setVariable:
    name: decisions
    expression:
      language:
        language: semantic
        expression: "refs:actionable,department,severity"
```

**LangChain4j**: a record is one call, one field per question:

```java
public record Assessment(
        @Decide("Is this an actionable support request, rather than small talk or a greeting?")
        YesNoAnswer actionable,
        @Decide("Which team should handle this support ticket?")
        Choice<Department> department,
        @Decide("How severe is the reported issue?")
        Scale<Severity> severity) {
}
```

The `score` question is the same ordered rubric in both, from lowest to highest:

```
0  A question or a request, nothing is broken
1  Something is broken but there is a workaround or it can wait
2  Business is blocked: an outage, failing payments or a legal deadline
```

## Ask the uncertainty question directly

The routes do not gate on confidence. They ask the model whether the ticket is worth routing, and
gate on that:

| Ticket | actionable | department (confidence) | severity |
|---|---|---|---|
| My payouts have been failing for 3 days and I was double charged. | 0.9789 | commercial (0.8650) | 1.95 |
| Please delete all personal data you hold about me under GDPR. | 0.9663 | legal (0.9628) | 0.36 |
| Your API has been timing out for the last hour. | 0.9410 | technical (0.9046) | 1.82 |
| We need your SOC 2 report before we can renew. | 0.9215 | legal (0.3783) | 1.85 |
| We are a 200-person team, what does enterprise pricing look like? | 0.8870 | commercial (0.9403) | 0.10 |
| The webhook integration returns 500 since your last deploy. | 0.8374 | technical (0.8306) | 1.52 |
| hello | **0.0040** | technical (0.9087) | 0.02 |
| thanks, have a nice weekend | **0.0021** | technical (0.9306) | 0.01 |

Measured through the semantic route on `tev1:4b`.

Three lessons in this table:

- **The `actionable` gap is enormous.** Small talk at 0.004, real tickets at 0.84 and up. The
  natural threshold of 0.5 needs no tuning. An earlier version of this repo gated on the choice
  confidence instead, and spent a whole section measuring a 0.29-wide gap to place a 0.32
  threshold in. A dedicated question beats a derived signal.
- **Do not gate on choice confidence in a batch.** `hello` answers `technical` at confidence 0.91.
  The choice question has to pick something, and with several questions in one call the
  distribution gets peaked. The entropy gate that worked for a single question would route small
  talk.
- **The score reads situations, not keywords.** The GDPR ticket is `legal` but severity 0.36: a
  routine request, nothing broken. Failing payouts with a double charge is 1.95: business blocked,
  money lost. The SOC 2 blocker on a renewal is 1.85. Same four words, "how severe is this",
  applied across departments.

## The model names the route

There is no Choice EIP in these routes. The department labels are the `direct:` route names, so
after the gate, routing is one dynamic step:

```yaml
- filter:
    expression:
      simple:
        expression: "${variable.decisions[actionable]} == false"
    steps:
      - to:
          uri: direct:human-review
      - stop: {}
- toD:
    uri: "direct:${variable.department}"
```

Add a department? One criteria entry in the question, one route in `departments.camel.yaml`,
matching names. No `when` to write.

Be clear about why this is safe, because with a chat model it would not be. `toD` on raw LLM
output is an injection hole: one crafted ticket and the model's "answer" is an endpoint URI of
the attacker's choosing. Here the answer cannot leave the declared label set: the semantic
decision is validated against the criteria, and the LangChain4j answer is an enum constant. The
closed label set is what turns "the model picks the endpoint" from a vulnerability into a
one-liner. One thing stays on you: every label needs a matching route, because a missing one now
fails the exchange instead of falling through to a human.

## Two flavors, one decision

| | Semantic language | LangChain4j bean |
|---|---|---|
| Decision step | expression in the route | `bean: triageDecisions` |
| Questions live in | route YAML | Java record |
| Answer arrives as | decision map + results property | `YesNoAnswer`, `Choice<T>`, `Scale<T>` |
| Typed at compile time | no | yes |
| Provider coupling | adapter, swappable | typesafe-ai protocol |
| Works in filter/validate/aggregate | yes, it is a predicate | no, bean call first |
| Needs | route YAML only | a Java class, two dependencies |

Pick by where the decision lives. Answer used by Java code, in a service or an agent? The
LangChain4j record: typed at compile time, a typo in a label is a compile error. Decision drives
a route, a filter, a validation? The semantic language: it is a predicate, it composes with the
EIPs, and the questions are provider-independent.

Underneath the semantic language sits `camel-typesafe-ai`, the provider adapter. It also offers a
direct producer endpoint (`to: typesafe-ai:name` with a questions file) when you want the raw
response or provider-specific options, but for routes the semantic language is the front door.

One caution that applies to both: **the client is part of the calibration**. The semantic route
sends the bare ticket text. The LangChain4j service sends the input keyed by parameter name
(`{ticket: ...}`), and every number shifts: `hello` reads actionable 0.004 through the semantic
route and 0.043 through the bean. Same model, same questions. Measure the pipeline you ship, not
the model.

## Switch to Jev

Swap three properties in `application.properties`:

```properties
typesafe.base-url = https://api.typesafe.ai
typesafe.model = jev-latest
typesafe.api-key = {{env:TYPESAFE_API_KEY}}
```

No route change, no question change, and both routes switch together: both flavors read the same
`typesafe.*` keys. `baseUrl` excludes `/v1/systemone`, the clients append it. `apiKey` is a secret
option, so resolve it from an environment variable or a vault. For local runs with real secrets,
use `application-dev.properties`. It is gitignored.

Pin a model version rather than `jev-latest` once you have measured your questions. An alias can
change behaviour without you touching the route.

## How it works

| File | What it does |
|---|---|
| `ticket-routing.camel.yaml` | The semantic route: three questions declared inline, gate, `toD` |
| `ticket-routing-l4j.camel.yaml` | The bean route |
| `TriageDecisions.java` | The three questions as a LangChain4j decision service |
| `departments.camel.yaml` | One `direct:` route per department, shared by both entry routes |
| `application.properties` | Backend config and dependencies |

The semantic language discovers `camel-typesafe-ai` on the classpath as its adapter and reuses the
`camel.component.typesafe-ai.*` configuration: base URL, model, API key. The adapter story is the
point: the questions are provider-independent, the provider is a classpath decision. The adapter
validates every answer before the route sees it. Background on the design is in the
[Camel blog post on semantic evaluation](https://camel.apache.org/blog/2026/09/semantic-evaluation-system-one/).

On the LangChain4j side, `DecisionServices.create()` turns the interface into an implementation
backed by `TypeSafeDecisionModel`, which speaks the same `/v1/systemone` protocol. What you gain:
answers typed at compile time. What you pay: a Java class, two snapshot dependencies, and
`@V("ticket")` on every parameter because JBang does not compile with `-parameters`.

Each question selects what the model sees with `state: ${bodyAs(String)}`, so only the ticket text
goes over the wire. It has to be `bodyAs(String)`, not `body`: `platform-http` hands over a
stream-cached body and the adapter accepts only a string, map or list as state.

### Reading the numbers

A `choice` answer carries three things worth knowing apart:

| Field | Meaning |
|---|---|
| `probabilities` | Mass per label. Sums to 1. |
| `answer_confidence` | Probability of the label it picked. Just `probabilities[choice]`. |
| `confidence` | How peaked the distribution is: `1 - normalized entropy`. 0 is a flat guess, 1 is certain. |

A `noul` answer is a single calibrated probability, and a `score` answer is a decimal position on
your rubric. The routes gate on the `noul` and log the rest.

### The first step in the route is curl plumbing

`curl -d` sends `application/x-www-form-urlencoded` unless you tell it otherwise. Vert.x
form-parses that body before Camel ever sees it, so the ticket text does not arrive as the body at
all: it arrives as the **name of a single empty form field**, in the body map and in the headers.
A header named `My payouts have been failing for 3 days...` then breaks the response, because
header names cannot contain spaces.

The raw bytes are gone by then, so the first step puts the text back:

```yaml
- choice:
    when:
      - expression:
          simple:
            expression: "${body} is 'java.util.Map'"
        steps:
          - setBody:
              expression:
                simple:
                  expression: "${body.keySet().iterator().next()}"
          - removeHeaders:
              pattern: "*"
```

Good enough for a demo, and it keeps the curls short. It is not good enough for a real client:
form parsing turns `+` into a space and splits on `&` and `=`, so a ticket containing any of those
is silently altered. Real senders should post `Content-Type: text/plain` and skip this branch
entirely.

### Pick options a small model can tell apart

Two rules, and they matter more than the route does.

**Describe when an option applies, not what it is about.** A keyword list does not work.
"Payments, invoicing, refunds" and "pricing, plans, quotes" read as two different teams to you. To
a model scoring semantic closeness they are neighbours, and every ticket comes back with the mass
spread near 0.33. Write the condition instead: *anything about money*, *the product is not
working*.

**Keep the options far apart.** Splitting money into billing and sales asks the model to work out
whether the sender has already paid. That is world knowledge, not semantic distance. It answers,
often with the right label, but confidence sits down where small talk sits and no threshold can
pass one and block the other. If two options need a judgement call about the sender's situation to
separate, merge them and let a human split that queue later. `commercial` covers both, and `legal`
shares no vocabulary with it.

## Going further

- Add more questions. Both flavors batch them in one call: a `noul` for "is a refund being
  requested", a `score` for frustration.
- Semantic predicates drop straight into other EIPs: a `filter` on relevance, a `validate` before
  an action, an `aggregate` correlating messages by topic, an `interceptSendToEndpoint` checking
  outbound replies. The blog post walks through each.
- Swap the department logs in `departments.camel.yaml` for `kafka:`, `jms:` or `mail:` endpoints
  and the demo becomes a system. That is the point of doing this in Camel.
