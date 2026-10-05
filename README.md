# Olli AI

A multi-tenant backend that turns a business's scattered documents into a
queryable knowledge base. It syncs files from the storage systems a business
already uses, indexes them, and answers questions about them conversationally.

Built with Quarkus 3.8.3 on Java 17.

> **Status: proof of concept.** This was built to work through the design of a
> multi-tenant RAG service end to end — connector abstraction, embedding
> strategy, retrieval quality, and the operational concerns around them. It is
> not deployed and has no production users. See [Known gaps](#known-gaps).

---

## What it does

1. A business registers and its employees sign in with Google.
2. The business connects one or more storage sources: Google Drive, Dropbox,
   S3, SFTP, a Samba/network share, or a local directory.
3. Documents are pulled on demand, chunked, and embedded.
4. Users ask questions in natural language and get answers grounded in the
   retrieved content.

## Architecture

The core design decision is that every external dependency sits behind an
interface with interchangeable implementations. Connectors, repositories, task
queues, and AI providers are all selected by configuration rather than
hardcoded, so a deployment can run entirely against local emulators or against
Google Cloud without changing application code.

```
SyncResource ─┐
              ├─► SyncService ─► ConnectorFactory ─► Drive │ Dropbox │ S3 │ SFTP │ Samba │ Local
ChatResource ─┘         │
                        ├─► TaskQueueFactory ──► Cloud Tasks │ RabbitMQ
                        │
                        └─► SyncWorker ─► EmbeddingService ─► Vertex AI │ Ollama │ ONNX
                                               │
                                               └─► Repository ─► Firestore │ MongoDB │ in-memory
```

| Concern | Abstraction | Implementations |
|---|---|---|
| Document sources | `Connector` / `ConnectorFactory` | Google Drive, Dropbox, S3, SFTP, Samba, local |
| Persistence | `*Repository` / `RepositoryProducer` | Firestore, MongoDB, in-memory |
| Async work | `TaskQueueService` / `TaskQueueFactory` | Google Cloud Tasks, RabbitMQ |
| AI providers | `AiProducer` / `AiImplementationType` | Vertex AI, Ollama, ONNX |

### Multi-tenancy

Every record is scoped to a `businessId`, and users are individual `userId`s
within a business. Sync history and connector credentials are tracked per user,
not per business, so one employee's Google Drive connection is not visible to
another.

### Billing and team management

Subscription state and payment events go through a `PaymentProvider` interface
with a Stripe implementation, including webhook handling for subscription
lifecycle events. Team invitations and email verification are handled in
`InvitationController` and `auth/`.

## AI and retrieval design

This is where most of the work went.

**Embedding is pluggable, including a fully local option.** `EmbeddingService`
has three implementations: Vertex AI (hosted), Ollama (local server), and ONNX
(in-process). The ONNX path runs a quantised `qint8` model directly inside the
JVM, which means embeddings cost nothing per document and need no network call.

**Tokenisation is implemented rather than delegated.** `BytePairEncodingTokenizer`
loads `tokenizer.json` and produces token boundaries directly, which is what
makes chunking respect real token limits instead of approximating with character
counts.

**Four retrieval strategies coexist**, so their output quality can be compared
on the same corpus rather than argued about:

| Strategy | Approach |
|---|---|
| `DirectOllamaQueryService` | No retrieval — direct prompt, for baseline comparison |
| `SimpleRAGOllamaQueryService` | Naive top-k retrieval |
| `RAGOllamaQueryService` | Chunked retrieval with context assembly |
| `EnhancedRAGOllamaQueryService` | Retrieval plus query refinement |

Keeping the naive versions alongside the improved ones was deliberate: without a
baseline on the same data, "this retrieval approach is better" is a guess.

## Tech stack

- **Framework:** Quarkus 3.8.3, RESTEasy Reactive, Mutiny
- **Language:** Java 17
- **AI:** Vertex AI, Ollama, ONNX Runtime
- **Storage:** Firestore, MongoDB, in-memory
- **Async:** Google Cloud Tasks, RabbitMQ
- **Auth:** OIDC via Google
- **Payments:** Stripe
- **Observability:** structured logging, tracing, performance and circuit-breaker
  metrics, health checks

## Getting started

**Prerequisites:** JDK 17+, Maven 3.8+, Docker (for emulators). A GCP project is
only needed if you want the Vertex AI path.

```bash
git clone https://github.com/laurinol/olli-ai.git
cd olli-ai
```

Create `.env` from the provided template and fill in your values:

```bash
cp sample.env .env
```

Run the local emulators (Firestore, MongoDB, and others):

```bash
docker-compose -f test/docker-compose.yml up -d
```

Start in dev mode:

```bash
mvn quarkus:dev
```

Dev mode disables OIDC so you can exercise the API without a Google round trip,
and the emulator-backed repositories mean **no cloud account is required to run
the application locally**.

## Configuration

Key settings in `src/main/resources/application.properties`:

| Property | Purpose |
|---|---|
| `gcp.project-id` | GCP project for Firestore, Cloud Tasks and Vertex AI |
| `quarkus.oidc.auth-server-url` | `https://accounts.google.com` |
| `quarkus.oidc.application-type` | `web-app` |
| `quarkus.oidc.redirect-path` | OAuth callback path |
| `quarkus.http.auth.permission.*` | Which endpoints require authentication |

Secrets belong in `.env`, which is gitignored. `sample.env` documents every
variable the application reads.

## Testing

```bash
mvn test                                    # all tests
mvn test -Dtest=SyncResourceTest            # a single class
mvn test -Dtest=SyncResourceTest#testSyncFolderLocalNoAuthSuccess
```

The suite covers connectors (`LocalConnectorTest`, `NetworkConnectorTest`,
`SftpConnectorTest`), services (`SyncServiceTest`), the REST layer
(`SyncResourceTest`), Stripe webhook handling, and end-to-end flows with
dedicated test profiles so integration tests run without cloud credentials.

There is also a Postman collection (`test/olli-ai.postman_collection.json`) and
an OpenAPI spec (`test/openapi.yml`) for exercising the API directly.

## Project layout

```
src/main/java/pro/olli/
  ai/          embedding and query services, tokenizer, RAG strategies
  auth/        OIDC and JWT handling
  connector/   document sources behind one interface
  core/        logging, metrics, tracing, resilience
  models/      domain types
  queue/       task queue abstraction
  repository/  persistence abstraction and implementations
  rest/        HTTP resources
  service/     sync orchestration, billing, payment providers
  worker/      asynchronous sync execution
docs/          Stripe, email verification, invitations, architecture diagrams
docs/internal/ working notes from the build process
test/          emulator config, test scripts, Postman collection, OpenAPI spec
k8s/           deployment manifest
```

## Design decisions worth calling out

**Local-first development.** Being able to run the whole system with no cloud
account shortened the feedback loop more than any other decision. The emulator
and in-memory implementations exist to make that possible.

**Strategy objects over configuration flags.** Adding a retrieval approach means
adding a class, not threading another `if` through the query path.

**Quantised in-process embeddings.** Committing to a bundled ONNX model trades a
little accuracy for zero per-document cost and no network dependency, which is
the right trade for a service whose whole premise is indexing a lot of
documents.

**Repository-per-store rather than an ORM.** Firestore and MongoDB have
different query models; forcing them through a common ORM would have leaked
abstractions rather than hidden them.

## Known gaps

Stated plainly, because they define where this would go next:

- **OneDrive ingestion is not implemented.** The class exists in the tree but is
  disabled pending an API review.
- **No production deployment.** The CI workflow's deploy jobs reference
  `app.yaml` manifests that were never written, and they require GCP credentials
  that only exist in a private environment.
- **Retrieval quality is not measured.** The four strategies exist to make
  comparison possible; the eval harness comparing them does not.
- **Single-region assumptions.** Tenant data isolation is enforced in code, not
  by any infrastructure boundary.

## License

All rights reserved. This is a demonstration project, not open-source software.
