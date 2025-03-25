#!/bin/bash
# Script to run connector tests

# Set default values
RUN_NETWORK=false
RUN_SFTP=false
RUN_LOCAL=true
HELP=false
VERBOSE=false

# Parse command line arguments
while [[ "$#" -gt 0 ]]; do
    case $1 in
        -n|--network) RUN_NETWORK=true ;;
        -s|--sftp) RUN_SFTP=true ;;
        -a|--all) RUN_NETWORK=true; RUN_SFTP=true; RUN_LOCAL=true ;;
        --no-local) RUN_LOCAL=false ;;
        -v|--verbose) VERBOSE=true ;;
        -h|--help) 
            HELP=true 
            break
            ;;
        *) echo "Unknown parameter: $1"; exit 1 ;;
    esac
    shift
done

# Show help
if [ "$HELP" = true ]; then
    echo "Usage: $0 [options]"
    echo ""
    echo "Options:"
    echo "  -n, --network       Run NetworkConnector tests"
    echo "  -s, --sftp          Run SftpConnector tests"
    echo "  --no-local          Skip LocalConnector tests"
    echo "  -a, --all           Run all connector tests"
    echo "  -v, --verbose       Show verbose output"
    echo "  -h, --help          Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0                  Run LocalConnector tests only"
    echo "  $0 -n -s            Run Network and SFTP connector tests"
    echo "  $0 -a               Run all connector tests"
    exit 0
fi

# Ensure Docker is running
if [ "$RUN_NETWORK" = true ] || [ "$RUN_SFTP" = true ]; then
    echo "Checking if Docker is running..."
    docker info > /dev/null 2>&1
    if [ $? -ne 0 ]; then
        echo "Docker is not running. Please start Docker and try again."
        exit 1
    fi
fi

# Start containers if needed
if [ "$RUN_NETWORK" = true ]; then
    echo "Starting Samba container..."
    docker-compose up -d samba
fi

if [ "$RUN_SFTP" = true ]; then
    echo "Starting SFTP container..."
    docker-compose up -d sftp
fi

# Wait for containers to be ready
if [ "$RUN_NETWORK" = true ] || [ "$RUN_SFTP" = true ]; then
    echo "Waiting for containers to be ready..."
    sleep 5
fi

# Build test command
TEST_CLASSES=""
TEST_ARGS=""

if [ "$RUN_LOCAL" = true ]; then
    TEST_CLASSES="${TEST_CLASSES},LocalConnectorTest"
fi

if [ "$RUN_NETWORK" = true ]; then
    TEST_CLASSES="${TEST_CLASSES},NetworkConnectorTest"
    TEST_ARGS="${TEST_ARGS} -Dsamba.host=localhost -Dsamba.share=share -Dsamba.username=testuser -Dsamba.password=testpass"
fi

if [ "$RUN_SFTP" = true ]; then
    TEST_CLASSES="${TEST_CLASSES},SftpConnectorTest"
    TEST_ARGS="${TEST_ARGS} -Dtest.sftp.real=true -Dsftp.username=testuser -Dsftp.password=testpass -Dsftp.host=localhost -Dsftp.port=2222"
fi

# Remove leading comma
TEST_CLASSES=${TEST_CLASSES#,}

if [ -z "$TEST_CLASSES" ]; then
    echo "No tests selected. Use -n, -s, or -a to select tests to run."
    exit 1
fi

# Run tests
echo "Running tests: $TEST_CLASSES"
cd ..

if [ "$VERBOSE" = true ]; then
    # Verbose output
    mvn test -Dtest="$TEST_CLASSES" $TEST_ARGS
else
    # Minimal output
    mvn test -Dtest="$TEST_CLASSES" $TEST_ARGS | grep -E "Results|Failures|Errors|BUILD"
fi