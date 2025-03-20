# Olli AI Application Documentation

## System Architecture Diagrams

This directory contains various UML diagrams describing the Olli AI system architecture and user flows:

### Component Diagram
[`app-component-diagram.puml`](app-component-diagram.puml) - Provides a high-level overview of all system components and their relationships.

This diagram shows:
- Frontend components (Customer web app and Admin web app)
- Backend services (Auth, Team Management, Sync, Search, Billing, Admin)
- Connector layers (User DB, Sync DB, Search Index, Billing DB)
- External systems integration (S3, Google Drive, Dropbox, etc.)

### Use Case Diagram
[`app-use-case-diagram.puml`](app-use-case-diagram.puml) - Displays all system functionalities from the perspective of different user roles.

This diagram shows the actions that can be performed by:
- Business Owners
- Team Admins
- Regular Users
- System Admins
- Automated processes

### Flow Diagram
[`app-flow-diagram.puml`](app-flow-diagram.puml) - Illustrates the main user journey through the system.

This diagram shows the step-by-step process flow from:
- Registration and login
- Setting up a business and subscription
- Managing team, connectors, and synchronization
- Using search and AI features
- Administrative operations

### Invitation System Sequence Diagram
[`invitation-sequence-diagram.puml`](invitation-sequence-diagram.puml) - Details the interaction flow for the team invitation subsystem.

This diagram shows the sequence of operations for:
- Creating and sending invitations
- Accepting invitations
- Cancelling invitations
- Resending invitations

## Feature Documentation

### Email Verification
[`EMAIL_VERIFICATION.md`](EMAIL_VERIFICATION.md) - Documentation about the email verification system used during user registration.

### Team Invitations
[`TEAM_INVITATIONS.md`](TEAM_INVITATIONS.md) - Documentation about the team invitation system for adding members to businesses.

## Generating Diagrams

To generate PNG/SVG files from the PlantUML files, you can use one of these methods:

1. **Online PlantUML Server**: Visit [PlantUML Online Server](https://www.plantuml.com/plantuml/uml/), paste the content of any .puml file, and download the generated image.

2. **IntelliJ IDEA with PlantUML Plugin**: Open the .puml files in IntelliJ with the PlantUML plugin installed, and use the export feature.

3. **Command Line with PlantUML JAR**:
   ```bash
   java -jar plantuml.jar *.puml
   ```

4. **VS Code with PlantUML Extension**: Open the .puml files in VS Code with the PlantUML extension installed, and use the preview/export features.

## Development Guidelines

Please refer to the main project README for development setup, coding standards, and contribution guidelines.

For specific implementation details of system components, check the corresponding source code directories.
