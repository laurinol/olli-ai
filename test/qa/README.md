# Olli AI Manual Test Suite

This directory contains the manual test suite for Olli AI in a format that can easily be imported into Google Sheets for external QA testers.

## How to Use

### Importing to Google Sheets
1. Open Google Sheets
2. Create a new sheet
3. Select File > Import
4. Upload the TestSuite.csv file
5. Select "Replace current sheet" and "Yes" for headers
6. Click Import data

### Test Suite Structure
- **Test ID**: Unique identifier for the test
- **Description**: Brief description of what's being tested
- **Preconditions**: Setup required before executing the test
- **Steps**: Numbered steps to execute the test
- **Expected Result**: What should happen if the system works correctly
- **Actual Result**: What actually happened (to be filled by tester)
- **Status**: Pass/Fail/Not Tested
- **Notes**: Additional information, bug IDs, test date, etc.

### Workflow for QA Testers
1. Import the test suite into Google Sheets
2. Execute each test following the steps
3. Record actual results
4. Mark status as Pass or Fail
5. Add relevant notes, especially for failures
6. Share the updated sheet with the development team

### Exporting Updated Results
After completing testing:
1. In Google Sheets, select File > Download > Comma-separated values (.csv)
2. Send the updated CSV to the development team or commit to the repository

## Test Categories
The test suite covers:
- User management and authentication
- Business and team management
- Document management and search
- Storage provider integration
- Chat and RAG functionality
- Billing and subscription management
- System performance and resilience
- Security and access control
- API functionality
- Error handling
- Monitoring and tracing

## Adding New Tests
To add new tests:
1. Add rows to the CSV with new test IDs (following the Txxx pattern)
2. Be sure to include clear steps and expected results
3. Commit the updated CSV to the repository