#!/bin/bash
# Test script for Samba server connection

echo "Testing Samba connection..."

# Check if smbclient is installed
if ! command -v smbclient &> /dev/null
then
    echo "smbclient not found. Please install it first:"
    echo "On macOS: brew install samba"
    echo "On Ubuntu: sudo apt install smbclient"
    exit 1
fi

# Ensure Samba container is running
if ! docker ps | grep -q "samba"; then
    echo "Samba container not running. Starting it now..."
    docker-compose up -d samba
    echo "Waiting 5 seconds for Samba to initialize..."
    sleep 5
fi

# Copy some test files to ensure there's content in the share
echo "Copying test files to shared directory..."
cp -r ./../test-files/* ./test-files/ 2>/dev/null || true
echo "Files in ./test-files directory:"
ls -la ./test-files/

# Test connection to the share
echo ""
echo "Trying to connect to the 'share' folder with credentials..."
smbclient //localhost/share -U testuser%testpass -c "ls"
SHARE_RESULT=$?

echo ""
echo "Trying to connect to the 'public' folder anonymously..."
smbclient //localhost/public -N -c "ls"
PUBLIC_RESULT=$?

echo ""
if [ $SHARE_RESULT -eq 0 ]; then
    echo "✅ Share connection SUCCESSFUL"
else
    echo "❌ Share connection FAILED"
fi

if [ $PUBLIC_RESULT -eq 0 ]; then
    echo "✅ Public connection SUCCESSFUL"
else
    echo "❌ Public connection FAILED"
fi

echo ""
echo "Connection information:"
echo "Server: localhost (127.0.0.1)"
echo "Ports: 139, 445"
echo "Shares: 'share' (protected), 'public' (anonymous)"
echo "Username: testuser"
echo "Password: testpass"
echo ""
echo "SMB URL format for NetworkConnector: smb://testuser:testpass@localhost/share/path/to/file"

# Run the NetworkConnectorTest
echo ""
echo "Running NetworkConnectorTest with appropriate settings..."
cd ..
mvn test -Dtest=NetworkConnectorTest \
  -Dsamba.host=localhost \
  -Dsamba.share=share \
  -Dsamba.username=testuser \
  -Dsamba.password=testpass