# JSLT Guide: JSON Transformation from Basic to Advanced

> **Version pinned to:** `com.schibsted.spt.data:jslt:0.1.14`  
> Features and API shapes may differ in later releases. Every snippet in this guide has been verified against 0.1.14 source.

---

## Prerequisites

- Java 17+, Maven or Gradle project
- Comfortable reading JSON
- Basic Jackson `ObjectMapper` familiarity

**Reading paths**

| Goal | Jump to |
|---|---|
| Quick tour, "show me what it looks like" | Section 3 → Section 4 |
| Write a working transform right now | Section 2 → Section 3 → Section 4 |
| Integrate JSLT into a Spring service | Section 11 |
| Advanced patterns for a gateway/proxy | Section 14 → Section 15 |

---

## Section 1 — What Is JSLT, and When Should I Use It?

JSLT is a JSON query and transformation language for the JVM, developed at Schibsted (Norway's largest media group). The design borrows from `jq` (Unix JSON processor) and XSLT (XML transformations): you write a script that describes the shape of the output, not the steps to build it. The runtime takes a JSON tree in and produces a JSON tree out, with no side effects.

### Five-Way Comparison

| Dimension | **JSLT** | **Jolt** | **JSONPath** | **Jackson (tree)** | **MapStruct** |
|---|---|---|---|---|---|
| **Primary use** | Structural transform + conditional logic | Structural transform via spec | Query / extract only | Full imperative control | Compile-time POJO mapping |
| **Config-stored?** | Yes — script is a plain string | Yes — spec is JSON | Partially | No — it is Java code | No — it is Java code |
| **Performance** | Fast once compiled + cached; loads full tree | Slower — spec is interpreted per call; no compilation step | Fast for simple reads | JVM speed; depends on your code | Fastest — zero overhead at runtime |
| **Maintainability** | Declarative, readable at moderate complexity; degrades past ~100 lines | Spec-as-JSON is clever but hard to scan; complex rules become walls of JSON | Great for one-liners; no construction | Full IDE support, refactoring, type safety | Full IDE support; auto-generated code |
| **Learning curve** | Low–Medium (~1 day to basics, ~1 week to advanced) | Medium — the shift/default/remove spec model is unintuitive | Low — path syntax is simple | Low — standard Java | Low–Medium — annotations + config |

### Use JSLT When

- Transform rules need to live outside compiled code (database, config file, feature flag) and change without a redeploy.
- The transform logic involves conditional reshaping, array mapping, or computed fields — not just field extraction.
- You are building a gateway or proxy that applies per-client or per-endpoint output mapping.
- You want the transform to be testable in isolation without spinning up a Spring context.

### Skip JSLT When

- You need to extract a single field from a known path. JSONPath is four characters: `$.a.b`.
- You are mapping fixed, compile-time-known Java POJOs. MapStruct is zero-overhead and type-safe.
- The transform logic contains real business rules (price calculation, fraud scoring, state machines). Keep that in Java where it can be tested, debugged, and reasoned about.
- The input payload is gigabytes. JSLT loads the full tree into memory (see Section 14).
- You already have a large Jolt spec corpus and no reason to migrate.

**JSLT vs Jolt — the practical distinction:** Both live in configuration and handle structural transforms. Choose JSLT when you need scripting features (`let`, `def`, `if`, built-in functions, recursion). Choose Jolt when your team already owns a Jolt spec library and the transforms are purely structural rearrangements without conditional logic.

---

## Section 2 — Maven / Gradle Setup

**Maven**

```xml
<dependency>
    <groupId>com.schibsted.spt.data</groupId>
    <artifactId>jslt</artifactId>
    <version>0.1.14</version>
</dependency>
```

JSLT pulls in `jackson-databind` transitively. If your project uses Spring Boot's BOM, override the Jackson version in your BOM import or use `<exclusions>` to let Spring Boot manage it:

```xml
<dependency>
    <groupId>com.schibsted.spt.data</groupId>
    <artifactId>jslt</artifactId>
    <version>0.1.14</version>
    <exclusions>
        <exclusion>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

**Gradle (Kotlin DSL)**

```kotlin
implementation("com.schibsted.spt.data:jslt:0.1.14") {
    exclude(group = "com.fasterxml.jackson.core")
}
```

**Sanity check — run this before going further:**

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schibsted.spt.data.jslt.Expression;
import com.schibsted.spt.data.jslt.Parser;

public class Smoke {
    public static void main(String[] args) throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode input = om.readTree("{\"hello\": \"world\"}");
        Expression expr = Parser.compileString("{\"greeting\": .hello}");
        JsonNode output = expr.apply(input);
        System.out.println(om.writeValueAsString(output));
        // prints: {"greeting":"world"}
    }
}
```

---

## Section 3 — Expressions, Paths, Literals, Null Handling

Everything in JSLT is an **expression** — every construct evaluates to a JSON value. There are no statements.

### The Input: `.`

The bare dot `.` refers to the entire input document.

```
// input:  {"id": 1, "name": "Alice"}
// script: .
// output: {"id": 1, "name": "Alice"}
```

### Dot Paths

Navigate into the tree with dot notation:

```
.user.name              // nested field
.items[0]               // first element of an array
.items[0].sku           // nested after array index
.["content-type"]       // key containing special characters
```

**Missing-path semantics:** If any segment does not exist, the result is `null` — not an error. This is a critical difference from Java's `NullPointerException` model.

```
// input:  {"id": 1}
// .user.name  →  null   (no exception)
```

### Literals

```
42          // number
3.14        // float
"hello"     // string
true        // boolean
null        // null
```

### Operators

| Operator | Meaning |
|---|---|
| `+` | Numeric add or string concatenation |
| `-`, `*`, `/` | Arithmetic |
| `==`, `!=` | Equality (structural, not reference) |
| `<`, `>`, `<=`, `>=` | Comparison |
| `and`, `or` | Logical — short-circuit |
| `not(expr)` | Logical negation (it is a function, not `!`) |

```
// "ALICE_1"
uppercase(.name) + "_" + string(.id)
```

### Null / Missing Handling

Use `if` with a fallback for null-coalescing (JSLT 0.1.14 does not have a `??` operator):

```
if (.nickname) .nickname else .fullName
```

Or use the built-in `fallback` function (covered in Section 7):

```
fallback(.nickname, .fullName, "Anonymous")
```

---

## Section 4 — Object Construction `{ }`

Curly braces construct an output object. The most important concept in JSLT.

### Reshaping

```
// input:  {"userId": 42, "profile": {"fullName": "Alice Tan"}, "secret": "x"}
// script:
{
  "id":   .userId,
  "name": .profile.fullName
}
// output: {"id": 42, "name": "Alice Tan"}
```

Fields not mentioned are dropped. This is the default behaviour — explicit projection.

### Computed Field Values

```
{
  "id":       .userId,
  "nameUpper": uppercase(.profile.fullName),
  "hasEmail":  if (.email) true else false
}
```

### Keep All Fields — Wildcard `*`

```
// Copy everything from input unchanged:
{ * : . }

// Add a field while keeping the rest:
{
  "processedAt": now(),
  * : .
}
```

### Drop Specific Fields

```
// Keep everything except "password" and "secret":
{ * - password - secret : . }
```

### Conditional Field Inclusion

A field whose value evaluates to `null` is included as `null`. Use `if` to omit entirely:

```
{
  "id":      .id,
  "premium": if (.tier == "PRO") true
  // when tier != "PRO", "premium" key is absent from output
}
```

---

## Section 5 — Array Construction `[ ]` and `for`

### Literal Arrays

```
[1, 2, 3]
[.a, .b, .c]
```

### The `for` Expression — Mapping Arrays

`for` is not a statement; it is an expression that maps one array to another.

```
// input:
{
  "items": [
    {"sku": "A1", "qty": 2, "price": 5000},
    {"sku": "B3", "qty": 1, "price": 12000}
  ]
}

// script:
{
  "lines": [for (.items) {
    "sku":      .sku,
    "subtotal": .qty * .price
  }]
}

// output:
{"lines": [{"sku":"A1","subtotal":10000},{"sku":"B3","subtotal":12000}]}
```

Inside the `for` body, `.` refers to the current element.

### Filtering Inside `for`

Add an `if` clause after the element expression to filter:

```
[for (.items) . if (.qty > 1)]
```

### Nested `for`

```
[for (.orders) {
  "orderId": .id,
  "skus": [for (.lines) .sku]
}]
```

### Iterating Over Object Keys — `for` on an Object

```
// input: {"Accept": "application/json", "X-Request-Id": "abc"}
// script: turn headers object into array of {key, value} pairs

[for (.) {"key": .key, "value": .value}]
```

When `for` iterates an object, each element is an object with `"key"` and `"value"` fields.

---

## Section 6 — Conditionals: `if` / `else`

`if` is an **expression**. It always returns a value. The `else` branch is optional — without it the expression returns `null` when the condition is falsy.

```
if (CONDITION) THEN_EXPR else ELSE_EXPR
```

### Truthiness Rules

These values are **falsy** in JSLT: `null`, `false`, `0`, `""`, `[]`, `{}`.  
Everything else is truthy.

```
if (.count) "has items" else "empty"
// .count == 0  →  "empty"
// .count == 5  →  "has items"
```

### Chained Conditions

Nest `if`/`else` for multi-branch logic:

```
if (.status == "SUCCESS") "OK"
else if (.status == "PENDING") "WAIT"
else "FAILED"
```

### Common Pitfall: Forgetting `else`

```
// if (.active) "active"
// when .active is false  →  null  (not an error, just null)
```

This is intentional but surprises most newcomers. Always add `else` unless you explicitly want `null`.

---

## Section 7 — Built-in Functions Tour

JSLT 0.1.14 ships ~60 built-in functions. Below are the most-used ones by category.  
Full reference: https://github.com/schibsted/jslt/blob/master/functions.md

### General Purpose

| Function | What it does |
|---|---|
| `size(x)` | Length of string, array, or object |
| `contains(x, y)` | `true` if array `x` contains `y`, or string `x` contains `y` |
| `fallback(a, b, ...)` | Returns first non-null, non-false argument |
| `error("msg")` | Deliberately fails the transform with a message |
| `is-null(x)` | `true` if `x` is `null` |
| `is-string(x)` | Type guard |
| `is-number(x)`, `is-boolean(x)`, `is-array(x)`, `is-object(x)` | Type guards |

```
// "USD" when .currency exists, otherwise "IDR"
fallback(.currency, "IDR")

// Guard-and-fail pattern:
if (not(is-string(.accountId))) error("accountId must be a string") else .accountId
```

### String Functions

| Function | Example | Result |
|---|---|---|
| `string(x)` | `string(42)` | `"42"` |
| `lowercase(s)` | `lowercase("HELLO")` | `"hello"` |
| `uppercase(s)` | `uppercase("hello")` | `"HELLO"` |
| `starts-with(s, prefix)` | `starts-with(.id, "TXN-")` | `true`/`false` |
| `ends-with(s, suffix)` | `ends-with(.file, ".json")` | `true`/`false` |
| `contains(s, sub)` | `contains(.email, "@")` | `true`/`false` |
| `replace(s, regex, replacement)` | `replace(.phone, "[^0-9]", "")` | digits only |
| `split(s, delim)` | `split("a,b,c", ",")` | `["a","b","c"]` |
| `join(arr, delim)` | `join(["a","b"], "-")` | `"a-b"` |
| `trim(s)` | `trim("  hi  ")` | `"hi"` |
| `test(s, regex)` | `test(.email, ".+@.+")` | `true`/`false` |
| `capture(s, regex)` | `capture("2024-01", "(\\d{4})-(\\d{2})")` | `{"0":"2024","1":"01"}` |

### Numeric Functions

| Function | Example | Result |
|---|---|---|
| `number(x)` | `number("42")` | `42` |
| `round(x)` | `round(3.7)` | `4` |
| `floor(x)` | `floor(3.7)` | `3` |
| `ceiling(x)` | `ceiling(3.2)` | `4` |
| `sum(arr)` | `sum([1,2,3])` | `6` |
| `max(a, b)` | `max(.a, .b)` | larger of the two |
| `min(arr)` | `min([5,1,3])` | `1` |
| `mod(a, b)` | `mod(10, 3)` | `1` |

### Object Functions

| Function | What it does |
|---|---|
| `get-key(obj, key, default)` | Safe key lookup with fallback |
| `keys(obj)` | Array of key names |
| `values(obj)` | Array of values |

```
// Safe lookup — no NPE equivalent:
get-key(.metadata, "traceId", "unknown")
```

### Array Functions

| Function | What it does |
|---|---|
| `flatten(arr)` | Flatten one level of nested arrays |
| `index-of(arr, val)` | Zero-based index, or `-1` if not found |
| `array(x)` | Wrap a non-array value in `[x]`; leave arrays as-is |
| `zip(a, b)` | Pair elements: `[[a0,b0],[a1,b1],...]` |

### Date / Time Functions

| Function | What it does |
|---|---|
| `now()` | Current epoch milliseconds as a number |
| `parse-time(s, format, timezone?)` | Parse string to epoch ms |
| `format-time(epoch, format, timezone?)` | Format epoch ms to string |

```
// Unix epoch → ISO-8601 string
format-time(.createdAt, "yyyy-MM-dd'T'HH:mm:ssXXX", "Asia/Jakarta")

// Parse ISO string → epoch ms
parse-time(.timestamp, "yyyy-MM-dd'T'HH:mm:ssXXX")
```

`format` uses Java `DateTimeFormatter` patterns. Timezone defaults to UTC when omitted.

---

## Section 8 — Variables: `let`

`let` binds a name to an expression result within the current scope. It is not mutation — you cannot rebind a `let` variable.

```
let total   = sum([for (.items) .price * .qty])
let discount = if (.member) total * 0.1 else 0

{
  "subtotal":  total,
  "discount":  discount,
  "payable":   total - discount
}
```

**Scoping rule:** A `let` binding is visible to all expressions that follow it in the same block.

**Gotcha:** `let` binds the value at the time of the binding. It does not re-evaluate lazily.

---

## Section 9 — User-Defined Functions: `def`

`def` defines a reusable named function within the script.

```
def fullName(person)
  person.firstName + " " + person.lastName

{
  "id":   .id,
  "name": fullName(.)
}
```

### Recursion

JSLT has no loop construct other than `for`. For tree-walking or accumulation, use recursion:

```
def sumNested(arr)
  if (size(arr) == 0) 0
  else arr[0] + sumNested(arr[1:])

{"total": sumNested(.amounts)}
```

> **Note:** `arr[1:]` is array slicing (from index 1 to end) — available in 0.1.14.

### Mutual Recursion

`def` functions can call other `def` functions defined anywhere in the same script.

### When to Use `def`

- You repeat the same expression 3+ times.
- A sub-expression is complex enough to deserve a name.
- You need recursion over a tree structure.

**Do not** use `def` as a way to sneak Java logic into JSLT. If you need a database call or a regex library, write a Java extension function (Section 12).

---

## Section 10 — Modules and `import`

JSLT lets you split transforms across files and import shared logic.

### File Layout Convention

```
src/main/resources/jslt/
  utils/masking.jslt       ← exports def mask-email(), def mask-card()
  utils/dates.jslt         ← exports def to-iso(), def from-iso()
  transforms/payment.jslt  ← imports utils
```

### Syntax

```
import "jslt/utils/masking.jslt" as masking

{
  "email": masking:mask-email(.email),
  "card":  masking:mask-card(.cardNumber)
}
```

### Resolution in 0.1.14

`Parser.compileString` resolves `import` paths from the **classpath** by default. Place `.jslt` files under `src/main/resources/` and they will be found.

If you compile with `Parser.compileResource(String resourcePath)`, the root is the classpath root. Either way, paths in `import` statements are relative to the classpath root.

> **Verified against 0.1.14 source:** Module resolution uses `ClassLoader.getResourceAsStream`. Files must be on the classpath — arbitrary filesystem paths are not supported without a custom `ResourceResolver`.

### Custom `ResourceResolver`

If you need filesystem or database resolution, implement `com.schibsted.spt.data.jslt.ResourceResolver` and pass it to the parser:

```java
Expression expr = new Parser(new MyDbResourceResolver())
    .compileString(script);
```

---

## Section 11 — Calling JSLT from Java

### Minimal Example

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schibsted.spt.data.jslt.Expression;
import com.schibsted.spt.data.jslt.Parser;

ObjectMapper om = new ObjectMapper();
JsonNode input = om.readTree(rawJson);

Expression expr = Parser.compileString(script);  // compile once
JsonNode output = expr.apply(input);              // apply many times

String result = om.writeValueAsString(output);
```

### Always Cache Compiled Expressions

`Parser.compileString` is expensive — it parses and compiles the script into an AST. **Never call it per request.** Cache the compiled `Expression` keyed on the script text.

The project already does this in `JsltResponseMapper` (`src/main/java/com/nantaaditya/example/helper/JsltResponseMapper.java`):

```java
private final Cache<String, Expression> expressionCache = Caffeine.newBuilder()
    .maximumSize(200)
    .build();

// Called per request — compiles only on cache miss:
Expression expression = expressionCache.get(script, Parser::compileString);
JsonNode result = expression.apply(input);
```

**Thread safety:** Compiled `Expression` objects are immutable and safe to share across threads.

### Passing Variables from Java

Externally injected values are accessed inside JSLT as `$varName`:

```java
// Java side — build a variable map
Map<String, JsonNode> vars = Map.of(
    "requestId", TextNode.valueOf(requestId),
    "env",       TextNode.valueOf("production")
);

// Pass to apply:
JsonNode output = expr.apply(vars, input);
```

> **Verified against 0.1.14 API:** The overload is `Expression.apply(Map<String, JsonNode> variables, JsonNode input)`.

Inside the JSLT script:

```
{
  "traceId": $requestId,
  "env":     $env,
  "data":    .
}
```

### Error Model

| Stage | Exception thrown |
|---|---|
| `Parser.compileString(badScript)` | `JsltException` with line/column info |
| `expr.apply(input)` on bad input shape | `JsltException` with a descriptive message |
| `error("msg")` called inside a script | `JsltException` wrapping the message |

All JSLT exceptions extend `RuntimeException`. Catch at the service boundary, log with the script text + input excerpt, and decide your fallback policy (the project falls back to `null`, signalling callers to use the original body).

---

## Section 12 — Custom Extension Functions

Implement `com.schibsted.spt.data.jslt.Function` to expose Java logic to JSLT scripts.

### Interface (0.1.14)

```java
public interface Function {
    String getName();           // name used in JSLT: my-function(...)
    int getMinArguments();
    int getMaxArguments();
    JsonNode call(JsonNode input, JsonNode[] args);
}
```

`input` is `.` at the call site. `args` are the positional arguments.

### Example: Card Number Masking

```java
public class MaskCardFunction implements Function {

    private final ObjectMapper om = new ObjectMapper();

    @Override public String getName()           { return "mask-card"; }
    @Override public int getMinArguments()      { return 1; }
    @Override public int getMaxArguments()      { return 1; }

    @Override
    public JsonNode call(JsonNode input, JsonNode[] args) {
        String card = args[0].asText();
        if (card.length() < 4) return args[0];
        String masked = "*".repeat(card.length() - 4) + card.substring(card.length() - 4);
        return TextNode.valueOf(masked);
    }
}
```

### Registration

```java
List<Function> fns = List.of(new MaskCardFunction());
Expression expr = Parser.compileString(script, fns);
```

Or using the builder:

```java
Expression expr = new Parser()
    .withFunctions(fns)
    .compileString(script);
```

Inside JSLT:

```
{
  "card": mask-card(.paymentMethod.cardNumber)
}
```

### When to Use Custom Functions

- Logic genuinely requires Java libraries (regex engine, cryptography, proprietary SDK).
- Reuse of existing well-tested Java utilities (e.g., a `MaskingHelper` already in the codebase).

### When Not To

Do not push business rules into custom functions. A function that calls a database, publishes an event, or has side effects defeats JSLT's declarative, testable model.

---

## Section 13 — Error Handling and Debugging

### Compile-Time Errors

```java
try {
    Expression expr = Parser.compileString(script);
} catch (JsltException e) {
    // e.getMessage() includes line:column and description
    log.error("JSLT compile error in script [{}]: {}", scriptName, e.getMessage());
}
```

### Runtime Errors

```java
try {
    JsonNode result = expr.apply(input);
} catch (JsltException e) {
    log.warn("JSLT apply failed: {}", e.getMessage());
    // fallback to original body
}
```

### Deliberate Failure: `error()`

Use inside a script to validate preconditions:

```
if (not(is-string(.accountId)))
  error("accountId must be a string, got: " + string(.)  )
else
  { "id": .accountId }
```

This causes `Expression.apply` to throw a `JsltException` with the given message.

### Logging Best Practices

Log the script name (not the full text) and the first 200 bytes of the input — not the full payload, to avoid flooding logs with PII.

```java
catch (Exception e) {
    String inputHead = rawJson.length() > 200 ? rawJson.substring(0, 200) + "…" : rawJson;
    log.warn("JSLT mapping failed [script={}] input=[{}]: {}", scriptName, inputHead, e.getMessage());
    return null;
}
```

The project pattern in `JsltResponseMapper` swallows failures to `null` and lets the caller decide whether to use the original body — this is appropriate for a gateway where a mapping failure should not crash the request.

### Debugging Workflow

1. Copy the failing script and the input JSON.
2. Write a 10-line test: `Parser.compileString(script).apply(om.readTree(input))`.
3. Assert against the expected output with AssertJ JSON comparisons.
4. Narrow down which sub-expression produces the unexpected value by temporarily replacing the whole script with just that expression.

---

## Section 14 — Advanced Patterns

### Composing Multiple Transforms

Apply a chain of JSLT scripts sequentially when each stage has a single, clear responsibility:

```java
// Stage 1: normalise upstream response envelope
JsonNode stage1 = normalise.apply(input);
// Stage 2: apply per-client field projection
JsonNode stage2 = project.apply(stage1);
// Stage 3: inject trace context
JsonNode result = enrich.apply(vars, stage2);
```

This keeps each script small and testable. Avoid one 300-line mega-script.

### Nested JSON String Transforms

Upstream sometimes returns a field whose value is a JSON-encoded string (double-serialised). Decode, transform, re-encode:

```java
// Decode the inner string to a JsonNode, transform, re-encode to string
JsonNode inner = om.readTree(outerNode.get("payload").asText());
JsonNode transformed = innerExpr.apply(inner);
// Inject back as a string field:
((ObjectNode) outerNode).put("payload", om.writeValueAsString(transformed));
```

This cannot be done inside JSLT alone — the decode/encode boundary requires Java.

### Performance

| Practice | Impact |
|---|---|
| Compile once, cache `Expression` | Eliminates parse cost on every request |
| Key cache on script text | Changing the script text auto-busts the cache |
| Avoid `string()` on large arrays | Generates a full JSON string; prefer structural output |
| Keep scripts under ~100 lines | Readability and compile time |

The Caffeine cache in `JsltResponseMapper` (max 200 entries) covers typical gateway scenarios where scripts are stored per `OutboundResponseMapperConfiguration` row and change rarely.

### Hot-Reloading Scripts

When an `OutboundResponseMapperConfiguration` is updated via the admin API, the old compiled `Expression` sitting in the Caffeine cache will continue to be served until evicted. Options:

1. **Key invalidation:** call `expressionCache.invalidate(oldScriptText)` after a configuration update.
2. **Short TTL:** add `.expireAfterWrite(10, TimeUnit.MINUTES)` to the Caffeine builder — stale scripts expire automatically.
3. **Key on configuration ID + version** rather than script text, and invalidate by ID.

### A Word on Streaming

JSLT 0.1.14 has **no streaming mode**. It requires the complete `JsonNode` tree in memory before the script can execute. For typical API response payloads (< 1 MB) this is fine. For bulk exports or event logs measured in megabytes, use Jackson's `JsonParser` streaming API directly and skip JSLT entirely.

### Testing Transforms in Isolation

Write tests directly against the JSLT script, without Spring context:

```java
class PaymentTransformTest {
    private static Expression expr;

    @BeforeAll
    static void compile() {
        expr = Parser.compileString(
            Files.readString(Path.of("src/main/resources/jslt/payment.jslt"))
        );
    }

    @Test
    void mapsAmountToPayableField() throws Exception {
        JsonNode input  = om.readTree("{\"amount\": 5000, \"currency\": \"IDR\"}");
        JsonNode output = expr.apply(input);
        assertThat(output.get("payable").asInt()).isEqualTo(5000);
    }
}
```

Fast, zero-infrastructure tests. Run them in CI before deploying updated scripts.

---

## Section 15 — Real-World Gateway Recipes

These five recipes are grounded in this project's architecture. Each shows: input → JSLT script → output, plus where it fits in the gateway flow (`GatewayServiceImpl` → `JsltResponseMapper` → `GatewayExecuteResponse`).

---

### Recipe 1: Response Envelope Normalisation

**Problem:** Upstream returns `{data: {...}, meta: {...}}`. Gateway consumers expect `{status, payload, traceId}`.

**Input:**
```json
{"data": {"orderId": "ORD-1"}, "meta": {"page": 1, "total": 50}}
```

**Script:**
```
{
  "status":  "SUCCESS",
  "payload": .data,
  "page":    .meta.page,
  "total":   .meta.total
}
```

**Output:**
```json
{"status": "SUCCESS", "payload": {"orderId": "ORD-1"}, "page": 1, "total": 50}
```

---

### Recipe 2: PII Field Masking

**Problem:** Upstream returns raw PII. The gateway must mask before returning to the client. Best stored as a shared JSLT module (`jslt/utils/masking.jslt`).

**`jslt/utils/masking.jslt`:**
```
def mask-email(addr)
  let parts = split(addr, "@")
  if (size(parts) == 2)
    substring(parts[0], 0, 2) + "***@" + parts[1]
  else addr

def mask-card(num)
  if (size(num) >= 4)
    "****-****-****-" + substring(num, size(num) - 4, size(num))
  else num
```

**Transform script:**
```
import "jslt/utils/masking.jslt" as mask

{
  "customerId": .id,
  "email":      mask:mask-email(.email),
  "card":       mask:mask-card(.paymentMethod.cardNumber),
  * - email - paymentMethod : .
}
```

> **Note:** `substring(s, from, to)` is available in 0.1.14.

---

### Recipe 3: Enrichment with Injected Variable

**Problem:** Inject the gateway's `requestId` into every transformed response for end-to-end traceability.

**Java side (in `JsltResponseMapper` or `GatewayServiceImpl`):**
```java
Map<String, JsonNode> vars = Map.of(
    "requestId", TextNode.valueOf(requestId)
);
JsonNode output = expression.apply(vars, input);
```

**Script:**
```
{
  "traceId": $requestId,
  "data":    .
}
```

**Output:**
```json
{"traceId": "abc-123", "data": { ... original response ... }}
```

---

### Recipe 4: Per-Consumer Field Projection

**Problem:** Same upstream endpoint, different consumers want different fields. The `OutboundResponseMapperConfiguration` entity stores a separate `mappingScript` per `outboundSystemId`.

**Consumer A script** (mobile — minimal, bandwidth-sensitive):
```
{"id": .orderId, "status": .orderStatus, "total": .totalAmount}
```

**Consumer B script** (back-office — full detail):
```
{
  "orderId":     .orderId,
  "status":      .orderStatus,
  "customer":    .customer,
  "lines":       [for (.lineItems) {"sku": .sku, "qty": .quantity, "unit": .unitPrice}],
  "total":       .totalAmount,
  "createdAt":   format-time(.createdTimestamp, "yyyy-MM-dd'T'HH:mm:ssXXX", "Asia/Jakarta")
}
```

Each script lives in `OutboundResponseMapperConfiguration.mappingScript` and is applied by `JsltResponseMapper.map(body, script)`. No Java change required to add a new consumer mapping.

---

### Recipe 5: Error Response Normalisation

**Problem:** Different upstreams return different error shapes. Map all of them to the gateway's canonical error envelope (`GatewayExecuteResponse.convertedResponse`).

**Upstream A error:**
```json
{"errorCode": "NOT_FOUND", "errorMessage": "User does not exist"}
```

**Upstream B error:**
```json
{"status": 404, "error": "Not Found", "message": "No user with id 99"}
```

**Unified normalisation script (applied conditionally based on `GatewayExecuteResponse.status`):**
```
{
  "code":    fallback(.errorCode, string(.status), "UNKNOWN"),
  "message": fallback(.errorMessage, .message, .error, "An error occurred"),
  "source":  "upstream"
}
```

`fallback` picks the first non-null value — making the script tolerant of both upstream shapes without branching logic.

---

## Appendix A — Syntax Cheat Sheet

```
.                          identity (whole input)
.field                     field access
.field.nested              nested access
.array[0]                  array index
.["key"]                   key with special chars

{ "k": expr }              object construction
{ * : . }                  copy all fields
{ * - f1 - f2 : . }        copy all except f1, f2
{ "extra": v, * : . }      add field, copy rest

[expr, expr]               array literal
[for (arr) expr]           array map
[for (arr) expr if (cond)] array map + filter
{for (obj) k : v}          object map from object

if (c) t else f            conditional expression

let x = expr               variable binding
$varName                   externally injected variable

def name(a, b) expr        user-defined function
import "path" as ns        module import
ns:funcName(args)          call imported function
```

---

## Appendix B — Pitfalls

1. **Missing keys are silently `null`.** `.does.not.exist` returns `null`, not an error. Add explicit `if` guards when you need to fail fast.
2. **`for` over an object yields `{key, value}` elements.** The keys of each entry are literally `"key"` and `"value"` — not the actual key name.
3. **Truthiness surprises.** `0`, `""`, `[]`, `{}` are all falsy. Guard numeric zero values explicitly: `if (.count != null) .count else 0`.
4. **No mutation.** Every expression returns a new value. You cannot modify the input document in place.
5. **No iteration other than `for` and recursion.** There is no `while`, no `reduce`, no `forEach`. For accumulation, write a recursive `def`.
6. **Compiled `Expression` is not a singleton by default.** Two calls to `Parser.compileString(sameScript)` produce two objects. Cache the result yourself (Caffeine, Guava, or a `ConcurrentHashMap`).
7. **`error()` in a sub-expression aborts the whole transform.** There is no `try/catch` inside JSLT; exceptions must be caught in Java.
8. **Module paths are classpath-absolute.** `import "utils/masking.jslt"` resolves from the classpath root, not relative to the importing script.

---

## Appendix C — Links

| Resource | URL |
|---|---|
| JSLT GitHub (source, changelog) | https://github.com/schibsted/jslt |
| JSLT built-in function reference | https://github.com/schibsted/jslt/blob/master/functions.md |
| JSLT tutorial | https://github.com/schibsted/jslt/blob/master/tutorial.md |
| This project's `JsltResponseMapper` | `src/main/java/com/nantaaditya/example/helper/JsltResponseMapper.java` |
| Response mapper config entity | `src/main/java/com/nantaaditya/example/entity/OutboundResponseMapperConfiguration.java` |
| Converter type enum | `src/main/java/com/nantaaditya/example/model/constant/ConverterType.java` |

---

## Appendix D — Glossary

| Term | Meaning |
|---|---|
| **Expression** | Any JSLT construct that evaluates to a JSON value |
| **Identity transform** | The script `.` — returns input unchanged |
| **Matcher** | The `*` pattern in object construction; matches remaining keys |
| **Module** | A `.jslt` file that exports `def` functions via `import` |
| **Compiled expression** | The result of `Parser.compileString` — an immutable, thread-safe `Expression` object |
| **Pass-through** | A mapping configuration with `ConverterType.NONE` — no transform applied (`OutboundResponseMapperConfiguration.isPassThrough()`) |
