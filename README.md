# Routing tickets with a System One decision model

Apache Camel routes support tickets using a **typed decision** instead of an LLM's prose.

The model gets a ticket and one closed question: *which team should handle this?* It answers with one
of three labels, a probability per label, and a calibrated confidence. Camel's Choice EIP does the
rest. A fast, hallucination-free smart if-statement in front of a route.

```
POST /ticket ──> typesafe-ai:triage ──> confidence < 0.32 ? ──> human review
                                              │
                                              └──> technical / commercial / legal
```

The same decision is wired twice, so you can compare the two ways of calling a System One model
from Camel:

| Route | Decision step | Question lives in |
|---|---|---|
| `POST /ticket` | `camel-typesafe-ai`, the component added in Camel 4.23. One `to:` does the call. | `triage-questions.json` |
| `POST /ticket-l4j` | A Camel bean using LangChain4j's experimental decision API | `TriageDecisions.java`, as a typed interface |

Two things make this different from asking an LLM to classify:

- The answer is **one of the labels you declared**. Not "Technical." or "I think technical" or a
  paragraph. There is nothing to parse and nothing to hallucinate.
- You get **calibrated confidence**, so the route can send ambiguous tickets to a human instead of
  guessing. That check runs first in the route, before any department match.

The second point is the one that earns its keep. Send `hello` and the model still answers
`technical`, but at a confidence of 0.019. The route sends it to a human. Ask a chat model to
classify the same ticket and you get the word "technical" with nothing to tell you it was a guess.

## What is a System One model?

[TypeSafe AI](https://typesafe.ai) launched **Jev** in September 2026. Instead of generating text, it
returns structured decisions in a single forward pass. Three primitives: `choice` (pick one of up to
255 labeled options), `score` (a level on an ordered rubric), `noul` (calibrated yes/no probability).
Every answer carries probabilities and a confidence value.

Jev is early access only right now. So this sample runs against **tev1:4b** on Ollama: an open
4.2B decision model that speaks the same wire protocol, which Ollama serves natively at
`/v1/systemone` since 0.35. Runs on your laptop, no API key. Switching to Jev is three properties.

## Prerequisites

- Camel CLI: `jbang app install camel@apache/camel`
- Ollama 0.35+

`camel-typesafe-ai` is new in 4.23.0, which is not released yet. So `application.properties` pins
`camel.jbang.camel-version = 4.23.0-SNAPSHOT` and adds the ASF snapshot repository through
`camel.jbang.repos`. Once 4.23.0 ships, drop the repo line and pin the release.

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

### Reading the numbers

A `choice` answer carries three things worth knowing apart:

| Field | Meaning |
|---|---|
| `probabilities` | Mass per label. Sums to 1. |
| `answer_confidence` | Probability of the label it picked. Just `probabilities[choice]`. |
| `confidence` | How peaked the distribution is: `1 - normalized entropy`. 0 is a flat guess, 1 is certain. |

`confidence` is the one this route gates on, because Jev returns it too and the swap stays
config-only. It runs lower than people expect: a three-way coin flip lands near 0.01, not near
0.33. How high a sure answer sits depends entirely on the backend. tev1 puts clear tickets at 0.82
and up. Do not carry a threshold over from another classifier, or even from another backend.
Measure your own criteria and put the threshold in the gap.

## Run the routes

```bash
camel run *
```

That starts both variants in one JVM: `/ticket` (component) and `/ticket-l4j` (LangChain4j bean).
To run one at a time, name the files. Both need `departments.camel.yaml`, where the department
queues live (`application.properties` is picked up automatically):

```bash
camel run ticket-routing.camel.yaml departments.camel.yaml
camel run ticket-routing-l4j.camel.yaml TriageDecisions.java departments.camel.yaml
```

Then send tickets through the component route:

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
to human review: there is nothing to classify, and the model says so.

Now the same eight through the LangChain4j bean route:

```bash
curl -d 'Your API has been timing out for the last hour.' localhost:8080/ticket-l4j
curl -d 'The webhook integration returns 500 since your last deploy.' localhost:8080/ticket-l4j
curl -d 'My payouts have been failing for 3 days and I was double charged.' localhost:8080/ticket-l4j
curl -d 'Please delete all personal data you hold about me under GDPR.' localhost:8080/ticket-l4j
curl -d 'We are a 200-person team, what does enterprise pricing look like?' localhost:8080/ticket-l4j
curl -d 'We need your SOC 2 report before we can renew.' localhost:8080/ticket-l4j
curl -d 'hello' localhost:8080/ticket-l4j
curl -d 'thanks, have a nice weekend' localhost:8080/ticket-l4j
```

Same queues for all eight. The confidences differ, and `hello` is the one to compare: 0.019
through the component, 0.275 through the bean. The next section explains why.

### Why the threshold is 0.32

All eight tickets, measured against the criteria in `triage-questions.json` on `tev1:4b`, through
both routes:

| Ticket | component | bean | Result |
|---|---|---|---|
| Please delete all personal data you hold about me under GDPR. | 0.9890 | 0.9932 | legal |
| We are a 200-person team, what does enterprise pricing look like? | 0.9832 | 0.9925 | commercial |
| Your API has been timing out for the last hour. | 0.9477 | 0.9750 | technical |
| The webhook integration returns 500 since your last deploy. | 0.8827 | 0.9359 | technical |
| My payouts have been failing for 3 days and I was double charged. | 0.8216 | 0.8875 | commercial |
| We need your SOC 2 report before we can renew. | 0.3711 | 0.3747 | legal |
| thanks, have a nice weekend | 0.0770 | 0.0302 | human review |
| hello | 0.0193 | 0.2750 | human review |

Six route and two defer, on both routes. But the columns do not match, and `hello` is off by an
order of magnitude. Same model, same question, same criteria. The difference is the client: the
LangChain4j decision service sends the input keyed by parameter name (`{ticket: ...}`), the
component sends the bare string. That framing shifts every confidence.

So the shared threshold has to sit in both gaps: 0.0770 to 0.3711 through the component, 0.2750 to
0.3747 through the bean. 0.32 does.

That is the whole method, and the lesson it teaches: **the client is part of the calibration**.
Run your tickets through the pipeline you ship, find the gap, put the threshold in it.

The SOC 2 ticket is the one to watch. `legal` is right per the criteria ("a security
questionnaire"), but it sits closest to the threshold on both routes. Tightening the `legal`
criteria would move it.

Two numbers move these values and neither is the route: the criteria text, and the backend. Change
either one and this table is stale.

## Switch to Jev

Swap three properties in `application.properties`:

```properties
typesafe.base-url = https://api.typesafe.ai
typesafe.model = jev-latest
camel.component.typesafe-ai.api-key = {{env:TYPESAFE_API_KEY}}
```

No route change, no question change, and both routes switch together: the component and the bean
read the same `typesafe.*` keys. `baseUrl` excludes `/v1/systemone`, both clients append it.
`apiKey` is a secret option, so resolve it from an environment variable or a vault. For local runs
with real secrets, use `application-dev.properties`. It is gitignored.

Pin a model version rather than `jev-latest` once you have tuned the threshold. An alias can change
behaviour without you touching the route.

## How it works

| File | What it does |
|---|---|
| `ticket-routing.camel.yaml` | The component route |
| `triage-questions.json` | The `choice` question and its criteria |
| `ticket-routing-l4j.camel.yaml` | The bean route |
| `TriageDecisions.java` | The same question as a LangChain4j decision service |
| `departments.camel.yaml` | One `direct:` route per department, shared by both entry routes |
| `application.properties` | Backend config, dependencies and the confidence threshold |

The whole model call is one step:

```yaml
- to:
    uri: typesafe-ai:triage
```

The name `triage` identifies the endpoint, it is not sent to the model. Three properties do the rest:

```properties
camel.component.typesafe-ai.questions-resource = file:triage-questions.json
camel.component.typesafe-ai.state = ${bodyAs(String)}
camel.component.typesafe-ai.result-property = evaluation
```

`state` is a Simple expression choosing what the model sees, so only the ticket text goes over the
wire. It has to be `bodyAs(String)` here, not `body`: `platform-http` hands over a stream-cached
body and the component accepts only a string, map or list as state. `${body}` fails with
`Content must be a string, map or list`.

`resultProperty` puts the response in an exchange property instead of overwriting the body, which
means the ticket survives to the department routes. Read the answer with plain Simple:

```
${exchangeProperty.evaluation[answers][department][choice]}
${exchangeProperty.evaluation[answers][department][confidence]}
```

The component validates the response before your route sees it. A missing answer, a probability map
that does not cover every label, or a chosen label that is not the most probable one fails the
exchange. You are not parsing JSON and hoping.

Want different departments? Edit `triage-questions.json` and add a matching `when` in the route. The
component reads and validates that file once at endpoint start, so a typo stops the route from
starting rather than failing the first ticket.

## The same decision through LangChain4j

`ticket-routing-l4j.camel.yaml` answers the same question through LangChain4j's decision API
(experimental, 1.21.0). No JSON file: the question is a typed Java interface in
`TriageDecisions.java`, and the criteria are `@Description` on enum constants:

```java
public enum Department {
    @Description("The product is not working: a bug, an outage, API errors, ...")
    TECHNICAL,
    ...
}

public interface Triage {
    @Decide("Which team should handle this support ticket?")
    Choice<Department> department(@V("ticket") String ticket);
}
```

`DecisionServices.create()` turns that into an implementation backed by `TypeSafeDecisionModel`,
which speaks the same `/v1/systemone` protocol as the component. The route calls it as a plain
Camel bean and branches the same way.

What you gain: the answer is `Choice<Department>`, typed at compile time, with `value()`,
`probabilities()`, `margin()` and `confidence()`. A typo in a label is a compile error, not a
runtime surprise. What you pay: a Java class, two snapshot dependencies, and `@V("ticket")`
because JBang does not compile with `-parameters`.

Pick by where the decision lives. Answer used by Java code? The interface. Answer only routes
a message? The component and its JSON file. Same model, same wire protocol. Not the same
calibration though: the service sends the state keyed by parameter name, the component sends it
bare, and the confidences shift (see the threshold section).

### The first step in the route is curl plumbing

`curl -d` sends `application/x-www-form-urlencoded` unless you tell it otherwise. Vert.x form-parses
that body before Camel ever sees it, so the ticket text does not arrive as the body at all: it
arrives as the **name of a single empty form field**, in the body map and in the headers. A header
named `My payouts have been failing for 3 days...` then breaks the response, because header names
cannot contain spaces.

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

Good enough for a demo, and it keeps the curls short. It is not good enough for a real client: form
parsing turns `+` into a space and splits on `&` and `=`, so a ticket containing any of those is
silently altered. Real senders should post `Content-Type: text/plain` and skip this branch entirely.

### Pick options a small model can tell apart

Two rules, and they matter more than the route does.

**Describe when an option applies, not what it is about.** A keyword list does not work. "Payments,
invoicing, refunds" and "pricing, plans, quotes" read as two different teams to you. To a model
scoring semantic closeness they are neighbours, and every ticket comes back with the mass spread
near 0.33. Write the condition instead: *anything about money*, *the product is not working*.

**Keep the options far apart.** Splitting money into billing and sales asks the model to work out
whether the sender has already paid. That is world knowledge, not semantic distance. It answers, often
with the right label, but confidence sits down where small talk sits and no threshold can pass one and
block the other. If two options need a judgement call about the sender's situation to separate, merge
them and let a human split that queue later. `commercial` covers both, and `legal` shares no
vocabulary with it.

## Going further

- Add more questions to `triage-questions.json`. One call can answer several: a `score` for
  frustration or bug severity, a `noul` for "is a refund being requested". The component ships a
  JSON Schema at `schema/typesafe-ai-questions.schema.json` in its jar for editor validation.
- The component also provides a `typesafe-ai` **language**, so a semantic condition can go straight
  into a `when` with its own `threshold` and `uncertainty` band, no producer step.
