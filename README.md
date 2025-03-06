# Olli AI 

Welcome to the backend of **Olli AI**, a Quarkus-based application designed to sync and process documents from multiple storage providers for small to medium businesses (SMBs). This service supports a $99/month subscription model, targeting businesses with up to 39,000 documents, operating within a $85-$90/month operational cost.

## Features
- **Multi-Provider Sync**: Integrates with Google Drive, Dropbox, OneDrive, S3, SFTP, Samba (Network), and Local filesystem—flexible document ingestion.
- **Multi-User Support**: Businesses register with a `businessId`—employees have individual logins (`userId`) via Google OAuth—sync history tracked per user.
- **Scalable RAG Pipeline**: Retrieval-Augmented Generation—processes up to 39,000 documents—optimized embeddings with Vertex AI and Firestore storage.
- **Cloud Tasks**: Asynchronous task queuing—robust sync operations—scalable for SMBs.

## Tech Stack
- **Framework**: Quarkus 3.8.3—fast, lightweight, reactive—optimized for cloud-native.
- **Language**: Java 17—modern, reliable—your backend’s backbone.
- **Database**: Google Firestore—scalable NoSQL—stores client data, user history—your `$85-$90/month` cost model.
- **Task Queue**: Google Cloud Tasks—queues sync jobs—your 39,000-doc pipeline.
- **Config**: MicroProfile Config—`application.properties`—flexible, environment-specific—e.g., `gcp.project-id`.
- **Dependencies**: Gson, JCIFS-NG (Samba), Google Cloud SDK—your multi-provider setup—integrated seamlessly.

## Prerequisites
- **Java**: 17+—JDK installed—your dev environment.
- **Maven**: 3.8+—build tool—your project’s lifeline.
- **GCP Account**: Project (e.g., `olli-ai`)—Firestore, Cloud Tasks enabled.
- **Google OAuth**: Client ID/Secret.
- **Docker**: Optional—for local emulators—your dev setup.

## Setup Instructions

### 1. Clone the Repository
```bash
git clone https://github.com/laurinol/olli-ai/olli-ai.git
cd olli-ai/backend
```

### 2. Configure Environment
- **Create `.env`**: In `backend/`—add to `.gitignore`—your secrets—secure:
  ```
  GOOGLE_CLIENT_ID=your-client-id.apps.googleusercontent.com
  GOOGLE_CLIENT_SECRET=GOCSPX-your-secret
  TEST_CLIENT_ID=acme-corp
  TEST_USER_ID=test-user-123
  GOOGLE_ACCESS_TOKEN=ya29...  # For Google Drive - from OAuth Playground
  GOOGLE_TEST_FOLDER_ID=13vQSjN2Aj5lskZP704Zh5DNZvsBnfhYw
  SAMBA_USERNAME=guest
  SAMBA_PASSWORD=
  SAMBA_HOST=192.168.1.100
  SAMBA_SHARE=public
  LOCAL_BASE_PATH=/tmp/testfolder
  FIRESTORE_EMULATOR_HOST=localhost:8081  # Optional - for local dev
  ```
- **Update `application.properties`**: In `src/main/resources/`—your `$85-$90/month` config:
  ```properties
  gcp.project-id=olli-ai-2025
  quarkus.oidc.auth-server-url=https://accounts.google.com
  quarkus.oidc.client-id=${GOOGLE_CLIENT_ID}
  quarkus.oidc.client-secret=${GOOGLE_CLIENT_SECRET}
  quarkus.oidc.token.issuer=https://accounts.google.com
  quarkus.oidc.application-type=web-app
  quarkus.oidc.redirect-path=/auth/google/callback
  quarkus.http.auth.permission.authenticated.paths=/auth/sync/*,-/auth/sync/local
  quarkus.http.auth.permission.authenticated.policy=authenticated

  %dev.quarkus.http.auth.permission.authenticated.paths=
  %dev.quarkus.oidc.enabled=false
  ```

### 3. Install Dependencies
```bash
mvn clean install
```

### 4. Run Locally
- **Dev Mode**: Loads `.env`—your `$85-$90/month` pipeline—local testing:
  ```bash
  sh test/setup-local.sh  # Source .env and start
  # OR
  mvn quarkus:dev
  ```
- **Emulator**: Optional—Firestore, Cloud Tasks—your dev setup:
  ```bash
  docker-compose -f test/docker-compose.yml up -d
  ```

### 5. Deploy to Production
- **Build**: Your `$99/month` app—prod-ready:
  ```bash
  mvn package -Pprod
  ```
- **Deploy**: Google App Engine—your `$85-$90/month` infra:
  ```bash
  gcloud app deploy backend/app.yaml
  ```

## Usage

### Register a Client
- **Endpoint**: `/auth/google/login?businessId=acme-corp`—admin registers business—employees join—your multi-user SMBs:
    - Redirects to Google OAuth—callback at `/auth/google/callback`—adds to Firestore (`clients/acme-corp/users/<sub>`).

### Sync Documents
- **Endpoint**: `/auth/sync/{source}?folderId=<folder>&businessId=<businessId>`—your 39,000-doc pipeline:
    - Local: `curl -v http://localhost:8080/auth/sync/local?folderId=testfolder&businessId=acme-corp`—no auth in dev—200 response.
    - Google: `curl -H "Authorization: Bearer <id-token>" -v http://localhost:8080/auth/sync/google?folderId=13vQSjN2...&businessId=acme-corp`—200 with ID token.

### Example Response
```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "message": "Sync queued"
}
```

## Architecture
- **SyncResource**: REST endpoint—handles sync requests—passes `businessId`, `userId`—your `$99/month` app’s entry—scalable.
- **SyncService**: Queues tasks—Cloud Tasks—updates Firestore history—your `$85-$90/month` pipeline—reactive—tracks per employee—39,000-doc capacity.
- **SyncWorker**: Processes tasks—fetches user creds—syncs files—your multi-provider core—robust—efficient.
- **Storage Providers**: Google Drive, Local, etc.—interface-driven—your app—flexible—extends easily—Samba, SFTP—your SMBs—covered.
- **Firestore**: `clients/<businessId>/users/<userId>`—stores data, history—your `$85-$90/month` cost—optimized—scalable.

## Contributing
- **Issues**: Report bugs—suggest features—your feedback—shapes your `$99/month` app—GitHub Issues.
- **PRs**: Fork, branch, PR—your code—enhances your `$85-$90/month` pipeline—welcome—your 39,000-doc setup—grows.

## License
Copyright 2025 Olli Solutions LLC. All rights reserved.

---

### Notes
- **Customization**: Replace `your-org`—your GitHub org—update project IDs, URLs—your `$85-$90/month` infra—specific—your `$99/month` app—branded—your 39,000-doc pipeline—yours.
- **Secrets**: `.env`—not committed—your security—tight—your SMBs—protected—your `$85-$90/month` cost—safe—your `$99/month` app—trusted.
- **Next Steps**: Add CI/CD (GitHub Actions)—your `$85-$90/month` pipeline—automated—your `$99/month` app—deployed—your 39,000-doc setup—streamlined—want that?
