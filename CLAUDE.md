# Olli AI Backend - Commands and Style Guide

## Build Commands
- Build: `mvn clean install`
- Run locally: `sh test/setup-local.sh` or `mvn quarkus:dev`
- Run with emulators: `docker-compose -f test/docker-compose.yml up -d`
- Run tests: `mvn test`
- Run single test: `mvn test -Dtest=SyncResourceTest`
- Run specific test method: `mvn test -Dtest=SyncResourceTest#testSyncFolderLocalNoAuthSuccess`
- Build for production: `mvn package -Pprod`

## Code Style
- Copyright header on all files: `Copyright (c) 2025 Olli Solutions LLC. All rights reserved.`
- Package structure: `pro.olli.<module>`, modules: ai, auth, core, models, repository, rest, service
- Imports ordering: Jakarta/Java imports first, third-party libs next, project imports last
- Naming: PascalCase for classes, camelCase for methods/variables, UPPER_SNAKE_CASE for constants
- Use constructor-based dependency injection, mark fields as final when possible
- Use Mutiny reactive types (Uni) for async operations
- Error handling: Use AppException with appropriate ErrorType, implement fault tolerance patterns
- Documentation: JavaDoc for public APIs, inline comments for complex logic
- Testing: Use JUnit 5 with Mockito, clear test method naming (testMethodNameScenario)